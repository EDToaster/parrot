# Feasibility Report: WebSocket Server Inside a Minecraft Mod

**Explorer:** explorer-websocket
**Date:** 2026-03-10
**Verdict:** HIGHLY FEASIBLE

---

## Executive Summary

Embedding a WebSocket server inside a Minecraft mod to enable a call-and-response MCP protocol is **highly feasible** on both Fabric and NeoForge. The recommended approach is to reuse Minecraft's built-in Netty library (already on the classpath) to stand up a WebSocket server, bridging async I/O to the game's single-threaded tick loop via a concurrent command queue. No additional dependencies are strictly required.

---

## 1. Library Evaluation

### 1.1 Netty (RECOMMENDED)

**Availability:** Already bundled with Minecraft (used for its own networking). Minecraft 1.21.x ships Netty 4.1.x.

**WebSocket Support:** Full support via pipeline: `HttpServerCodec` -> `HttpObjectAggregator` -> `WebSocketServerProtocolHandler` -> custom `SimpleChannelInboundHandler<WebSocketFrame>`. This has been stable since Netty 4.0.

**Pros:**
- Zero additional dependencies — nothing to shade or bundle
- Battle-tested in the Minecraft runtime environment
- High-performance, non-blocking I/O
- Full control over the pipeline (can add compression, SSL, custom codecs)

**Cons:**
- More boilerplate than higher-level libraries
- Must create a **separate** `NioEventLoopGroup` to avoid interfering with Minecraft's own Netty networking
- API is lower-level (channels, pipelines, handlers)

**Risk:** LOW. The only concern is Netty version pinning — the mod must use only APIs available in the version Minecraft bundles. WebSocket APIs have been stable for years.

### 1.2 Java-WebSocket (TooTallNate)

**What it is:** Pure Java WebSocket client/server using `java.nio`. Minimal dependency (only SLF4J for logging).

**Pros:**
- Very simple API — extend `WebSocketServer`, override callbacks
- Lightweight JAR, easy to bundle
- No classloading conflicts (uses only JDK APIs)

**Cons:**
- Requires bundling via Jar-in-Jar (Fabric `include` or NeoForge JiJ)
- Adds an unnecessary dependency when Netty is already available
- Less control over the networking pipeline

**Verdict:** Good fallback if Netty reuse proves problematic, but unnecessary in practice.

### 1.3 Ktor Embedded Server

**What it is:** Kotlin async framework with WebSocket support. Can use CIO or Netty engine.

**Pros:**
- Elegant Kotlin DSL for WebSocket routing
- Built-in features (serialization, content negotiation)

**Cons:**
- **Massive dependency chain**: Kotlin stdlib, kotlinx-coroutines, Ktor core, engine, plugins
- Ktor's Netty engine would conflict with Minecraft's Netty classloading
- Significant JAR size bloat (tens of MB)
- Overkill for a simple WebSocket endpoint

**Verdict:** NOT RECOMMENDED. Too heavy for mod embedding.

---

## 2. Thread Safety: Bridging WebSocket I/O to the Game Loop

### 2.1 The Problem

Minecraft runs on a single-threaded game loop (one tick = 50ms at 20 TPS). WebSocket messages arrive on Netty's I/O threads. Directly accessing game state from a Netty handler would cause race conditions and crashes.

### 2.2 The Solution: Command Queue Pattern

This is a well-established game development pattern:

```
[MCP Client] --WebSocket--> [Netty I/O Thread] --enqueue--> [ConcurrentLinkedQueue]
                                                                      |
                                                               [Server Tick]
                                                                      |
                                                              drain & execute
                                                                      |
                                                              [Result Map]
                                                                      |
[MCP Client] <--WebSocket-- [Netty I/O Thread] <--poll response------+
```

1. **WebSocket handler** (Netty thread): Parse incoming JSON command, assign a request ID, enqueue to `ConcurrentLinkedQueue<Command>`
2. **Server tick handler** (main thread): Drain the queue, execute each command against the game state, place results in a `ConcurrentHashMap<String, Response>` keyed by request ID
3. **WebSocket handler** (Netty thread): Poll for completed responses, send JSON back to client

### 2.3 Mod Loader Support

**NeoForge:**
- `NeoForge.EVENT_BUS` fires tick events on the main thread
- Built-in `context.enqueueWork()` pattern for network->main thread marshaling (validates the approach)
- `ServerTickEvent` for draining the command queue

**Fabric:**
- `ServerTickEvents.END_SERVER_TICK` callback runs on the main thread
- `ServerLifecycleEvents.SERVER_STARTED` / `SERVER_STOPPING` for lifecycle management
- Same queue-drain pattern works identically

### 2.4 Completable Futures for Request-Response

For the call-and-response pattern, each command can carry a `CompletableFuture<Response>`:

1. WebSocket handler creates `CompletableFuture`, enqueues command with it
2. Tick handler completes the future on the main thread
3. WebSocket handler uses `.thenAccept()` to send the response back on the Netty thread

This avoids polling and provides clean async flow.

---

## 3. Classloading and Dependency Bundling

### 3.1 Using Built-in Netty (Recommended Path)

- **No bundling required** — Netty classes are already on the classpath
- **No shading required** — using the same Netty instance as Minecraft
- **No classloading conflicts** — same ClassLoader, same classes
- Must create a dedicated `NioEventLoopGroup` with daemon threads (separate from Minecraft's)

### 3.2 Bundling Third-Party Libraries (If Needed)

**Fabric Loom:**
- `include` configuration in `build.gradle` for Jar-in-Jar
- Fabric Loader handles runtime extraction and classpath injection
- No package relocation (not a shadow plugin) — namespace conflicts possible
- Auto-generates wrapper mod metadata for non-mod JARs

**NeoForge:**
- Similar Jar-in-Jar support via NeoGradle
- Gradle Shadow plugin available for package relocation if needed

### 3.3 Risk Assessment

| Scenario | Risk Level | Notes |
|----------|-----------|-------|
| Reusing built-in Netty for WebSocket | LOW | Stable APIs, same runtime |
| Bundling Java-WebSocket via JiJ | LOW | Pure Java, no conflicts |
| Bundling Ktor | HIGH | Netty conflicts, Kotlin deps |
| Netty version mismatch | NEGLIGIBLE | WebSocket APIs stable since 4.0 |

---

## 4. Port Management

- **Default port:** Configurable, suggest `25566` (one above default MC port)
- **Bind address:** `127.0.0.1` (localhost only) by default
- **Error handling:** Catch `BindException` gracefully — log warning and disable WebSocket if port is taken
- **Configuration:** Expose via mod config file (both loaders have config APIs)
- **Multi-instance:** Each Minecraft instance should use a different port; config or auto-discovery needed

---

## 5. Security Considerations

| Concern | Mitigation |
|---------|------------|
| Remote access | Bind to `127.0.0.1` by default; external access opt-in |
| Authentication | Auto-generate a shared secret token on server start, write to known file path for MCP client to read |
| Command injection | Validate and whitelist all commands; never pass raw input to Minecraft's command dispatcher |
| Cross-site WebSocket hijacking | Check `Origin` header on WebSocket upgrade; reject non-local origins |
| Resource exhaustion | Limit concurrent connections (1-2 for MCP use case); limit message size |
| JVM shutdown | Use daemon threads for Netty EventLoopGroup; shut down on server stop event |

---

## 6. Architecture Recommendation

```
┌─────────────────────────────────────────────┐
│              Minecraft Mod                   │
│                                              │
│  ┌──────────────┐    ┌───────────────────┐  │
│  │  WebSocket    │    │  Command Queue    │  │
│  │  Server       │───>│  (Concurrent)     │  │
│  │  (Netty)      │    └───────┬───────────┘  │
│  │  Port 25566   │            │              │
│  └──────┬───────┘    ┌───────▼───────────┐  │
│         │            │  Tick Handler      │  │
│         │            │  (Main Thread)     │  │
│         │            │  - Execute cmds    │  │
│         │            │  - Read game state │  │
│         │            │  - Complete futures│  │
│         │            └───────────────────┘  │
│         │                                    │
│  ┌──────▼───────┐                           │
│  │  Response     │                           │
│  │  Handler      │                           │
│  │  (Send JSON)  │                           │
│  └──────────────┘                           │
└─────────────────────────────────────────────┘
         ▲ WebSocket (JSON)
         │
         ▼
┌─────────────────┐
│  MCP Server     │
│  (Rust/Node)    │
│  - Translates   │
│    MCP protocol │
│    to WS cmds   │
└─────────────────┘
```

### Shared Code Strategy (Fabric + NeoForge)

The WebSocket server, command queue, JSON protocol, and command execution logic can be written as **platform-independent Java code**. Only the mod loader integration layer differs:

- **Common module:** WebSocket server, command types, JSON serialization, command executor
- **Fabric module:** `ModInitializer`, `ServerLifecycleEvents`, `ServerTickEvents` hooks
- **NeoForge module:** `@Mod` class, `@SubscribeEvent` for lifecycle and tick events

This enables a multi-loader project (e.g., using Architectury or manual multi-module Gradle).

---

## 7. Feasibility Summary

| Aspect | Feasibility | Confidence |
|--------|-------------|------------|
| WebSocket server via Netty | Fully feasible | HIGH |
| Thread-safe command bridging | Well-established pattern | HIGH |
| Fabric support | Fully feasible | HIGH |
| NeoForge support | Fully feasible | HIGH |
| Cross-loader shared code | Feasible with multi-module | HIGH |
| No extra dependencies needed | Confirmed | HIGH |
| Security (localhost + token) | Straightforward | HIGH |
| Port management | Straightforward | HIGH |

**Overall: GREEN LIGHT.** The approach is sound, uses proven patterns, requires no exotic dependencies, and works identically on both mod loaders.

---

## 8. Risks and Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Netty EventLoopGroup interferes with MC networking | Low | High | Dedicated group with daemon threads |
| Port conflict with other mods/services | Medium | Low | Configurable port, graceful fallback |
| Future Minecraft Netty version change breaks WebSocket API | Very Low | Medium | WebSocket APIs are stable; pin to stable subset |
| Performance impact of tick-drain on heavy command load | Low | Low | Batch limit per tick, async where possible |
| Classloading issues on specific mod loader versions | Low | Medium | Test on target versions; use only public Netty APIs |

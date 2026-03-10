# Minecraft MCP Server — Project Specification

**Version:** 1.0 Draft
**Date:** 2026-03-10
**Status:** Ready for Review

---

## 1. Overview

### What This Is

**Playwright for Minecraft** — an MCP server and companion mod that lets Claude Code (or any MCP client) interact with a running Minecraft instance. The system enables AI-assisted debugging of Minecraft mods by providing structured, programmatic access to game state, GUI observation, block/entity inspection, and command execution.

### Two Components

| Component | Language | Role |
|-----------|----------|------|
| **Kotlin MCP Server** | Kotlin/JVM | Separate process launched by Claude Code. Speaks MCP protocol (stdio JSON-RPC) to the AI client and WebSocket JSON to the mod. |
| **Java Minecraft Mod** | Java 21 | Multi-loader mod (Fabric + NeoForge) running inside Minecraft. Embeds a Netty WebSocket server exposing game state and accepting commands. |

### Target

- **Minecraft:** Java Edition, latest version (1.21.x)
- **Mod Loaders:** Fabric and NeoForge (from a single codebase)
- **Primary Use Case:** Singleplayer mod debugging (multiplayer supported with degraded GUI observation)

### Why a Mod Is Necessary

Adversarial exploration confirmed that external approaches (protocol proxies, RCON, bots, OS-level automation) cannot access mod internals, custom GUIs, or mod registries. External automation captures only 5-10% of needed data at 20-100x worse latency. A mod is not optional for the mod-debugging use case.

### No Direct Competitor Exists

A survey of 15+ existing projects (Mineflayer, KubeJS, GDMC HTTP, cuspymd/mcp-server-mod, etc.) found that no project combines in-game mod interaction, GUI reading, and MCP protocol support. GDMC HTTP Interface validates the architecture (in-game server, dual loader, REST API). cuspymd/mcp-server-mod validates MCP integration (Fabric + HTTP + MCP). Neither provides GUI reading or comprehensive tooling.

---

## 2. Architecture

### System Diagram

```
Claude Code ←── stdio (JSON-RPC) ──→ Kotlin MCP Server ←── WebSocket (JSON) ──→ Minecraft Mod (Netty)
                                      (separate JVM)                              (inside MC process)
```

### Kotlin MCP Server

- **SDK:** `io.modelcontextprotocol:kotlin-sdk-server` (official Kotlin MCP SDK)
- **Transport to Claude Code:** stdio (subprocess, newline-delimited JSON-RPC)
- **Transport to Mod:** Ktor Client CIO engine (WebSocket client) — CIO avoids Netty classpath conflicts
- **Distribution:** Shadow JAR (`java -jar minecraft-mcp-server.jar`)
- **Size estimate:** ~500-800 lines of Kotlin for MVP

### Minecraft Mod

- **Project Template:** MultiLoader Template (jaredlll08/MultiLoader-Template)
- **Structure:** `common/` (vanilla APIs only, ~85-90% of code), `fabric/`, `neoforge/`
- **WebSocket Server:** Netty (bundled with Minecraft, zero additional dependencies)
- **JSON Serialization:** Gson (bundled with Minecraft)
- **Platform Abstraction:** Interfaces in common (`PlatformBridge`, `ScreenReader`, `EventSubscriber`), implemented per-loader

### Connection Discovery

The mod writes a connection file on world load; the MCP server reads it on startup:

```
~/.minecraft-mcp/connection.json
{
  "port": 25566,
  "token": "a1b2c3d4e5f6..."
}
```

**Discovery sequence (MCP server):**
1. `MINECRAFT_MCP_PORT` environment variable
2. `--port` CLI argument
3. `~/.minecraft-mcp/connection.json`
4. Default: `127.0.0.1:25566`

The token is regenerated on each server start. The file is deleted on server stop.

### Thread Safety

```
MCP Server ──WebSocket──→ Netty I/O Thread ──enqueue──→ ConcurrentLinkedQueue<Command>
                                                                    |
                                                             Server Tick Handler
                                                             (main game thread)
                                                                    |
                                                         drain queue, execute,
                                                         complete CompletableFuture
                                                                    |
MCP Server ←─WebSocket──← Netty I/O Thread ←── response ──────────+
```

- Dedicated `NioEventLoopGroup` with daemon threads (separate from Minecraft's)
- All game state access happens on the server thread only
- Batch limit per tick to prevent lag
- `CompletableFuture<Response>` for clean async request-response flow

---

## 3. Client/Server Side Split

### The Problem

Minecraft has both logical sides. Screen/GUI observation is client-only. World state and commands are server-only. The mod must handle both.

### Side Classification

| Capability | Side | Notes |
|-----------|------|-------|
| Screen/GUI observation | **Client** | `ScreenEvents` (Fabric), `ScreenEvent.*` (NeoForge) |
| Container slot reading | **Both** | Synced from server to client within 1 tick |
| World state queries | **Server** | Blocks, entities, biomes — server is authoritative |
| Command execution | **Server** | `MinecraftServer.getCommands()` |
| WebSocket server | **Server** | Lives on server thread, authoritative game state |

### Deployment Scenarios

| Scenario | GUI Observation | World State | Communication |
|----------|----------------|-------------|---------------|
| **Singleplayer** (primary target) | Full | Full | Shared in-memory `ConcurrentLinkedQueue` — no packets |
| **Dedicated, mod on both sides** | Full | Full | Custom packets relay client observations to server |
| **Dedicated, server-only** | None (~70% capability) | Full | Graceful degradation |

### Side Safety

- **Fabric:** `splitEnvironmentSourceSets()` enforces at compile time that server code cannot import client classes
- **NeoForge:** `@Mod(dist = Dist.CLIENT)` for client-only entry points; indirection layer prevents classloading crashes

### Capability Advertisement

On WebSocket connect, the mod advertises available capabilities:

```json
{
  "capabilities": ["actions", "queries", "events", "gui_observation"],
  "server_type": "integrated",
  "minecraft_version": "1.21.5",
  "mod_loader": "fabric"
}
```

The MCP server adapts tool availability based on these capabilities. If `gui_observation` is absent, screen-related tools return informative errors.

---

## 4. WebSocket Protocol

### Connection Lifecycle

```
MCP Server                           Minecraft Mod
    |── HTTP Upgrade (WebSocket) ──→|
    |←── 101 Switching Protocols ──|
    |── hello (auth token) ────────→|
    |←── hello_ack (capabilities) ─|
    |                                |  [connection active]
    |── ping ──────────────────────→|
    |←── pong ─────────────────────|
    |── goodbye ───────────────────→|
    |←── goodbye_ack ──────────────|
```

- Client must send `hello` within 5 seconds or connection is closed
- Mod sends `ping` every 30 seconds; 10-second `pong` timeout
- Auth token validated during `hello` handshake

### Message Types

| Type | Direction | Purpose |
|------|-----------|---------|
| `hello` / `hello_ack` | C->S / S->C | Authentication + capability exchange |
| `ping` / `pong` | Bidirectional | Keepalive |
| `action` / `action_result` | C->S / S->C | Perform action, return consequences |
| `query` / `query_result` | C->S / S->C | Read-only state query (immediate) |
| `command` / `command_result` | C->S / S->C | Execute `/command` with optional consequences |
| `subscribe` / `subscribe_ack` | C->S / S->C | Start push event stream |
| `unsubscribe` / `unsubscribe_ack` | C->S / S->C | Stop push event stream |
| `event` | S->C | Push notification for subscribed events |
| `error` | S->C | Error response to any request |

### Consequence Collection (Core Innovation)

Inspired by Playwright's action-consequence model. When an action is performed, the mod waits a configurable tick window (default: 5 ticks = 250ms) and collects all observable game events that result.

**Why tick-based, not event-driven completion:** Minecraft actions have unpredictable consequences. Right-clicking a block might open a chest, trigger redstone, invoke a mod handler, or do nothing. A fixed tick window collects *whatever happens*.

**Action serialization:** Actions execute one at a time so consequences can be attributed to the correct action. Queries (read-only) can execute in parallel between action windows.

### Canonical Example: Open Chest Flow

**Request:**
```json
{
  "type": "action",
  "id": "101",
  "method": "interact_block",
  "params": { "position": { "x": 100, "y": 64, "z": -200 }, "hand": "main_hand" },
  "consequence_wait": 5
}
```

**Response (after 5 ticks):**
```json
{
  "type": "action_result",
  "id": "101",
  "success": true,
  "tick": 50000,
  "result": { "interaction_result": "success" },
  "consequences": [
    {
      "type": "screen_opened",
      "tick": 50001,
      "screen_type": "generic_container",
      "title": "Chest",
      "menu_type": "minecraft:generic_9x3",
      "slot_count": 27,
      "slots": [
        { "index": 0, "item": "minecraft:diamond_sword", "count": 1, "nbt": { "Enchantments": [{"id": "minecraft:sharpness", "lvl": 5}] } },
        { "index": 1, "item": "minecraft:golden_apple", "count": 12 }
      ]
    }
  ]
}
```

### Consequence Event Types

| Type | Description |
|------|-------------|
| `screen_opened` | GUI screen opened (with slot contents) |
| `screen_closed` | GUI screen closed |
| `block_changed` | Block state changed near the action |
| `block_entity_changed` | Block entity data changed |
| `entity_spawned` | Entity appeared |
| `entity_removed` | Entity removed |
| `sound_played` | Sound effect played |
| `chat_message` | Chat/system message appeared |
| `inventory_changed` | Player inventory changed |
| `status_effect_added` | Player gained status effect |
| `redstone_update` | Redstone signal changed |
| `particle_spawned` | Particles appeared |

Consequences are spatially filtered (default 8-block radius around action target). Clients can also specify `consequence_filter` to collect only specific types.

### Error Codes

| Code | Description |
|------|-------------|
| `INVALID_REQUEST` | Malformed JSON or missing fields |
| `UNKNOWN_METHOD` | Unrecognized action/query method |
| `AUTH_FAILED` | Invalid token |
| `BLOCK_OUT_OF_RANGE` | Target not in loaded chunk |
| `ENTITY_NOT_FOUND` | Entity ID does not exist |
| `NO_SCREEN_OPEN` | Action requires open screen |
| `COMMAND_FAILED` | Minecraft command execution failed |
| `TIMEOUT` | Consequence collection timed out |
| `INTERNAL_ERROR` | Unexpected mod-side error |
| `RATE_LIMITED` | Too many requests |

---

## 5. MCP Tool Vocabulary

### Naming Convention

| Prefix | Meaning | Side Effects |
|--------|---------|-------------|
| `get_*` | Read/query game state | None |
| `do_*` | Perform an in-game action | Yes (consequence collection) |
| `run_*` | Execute commands or scripts | Yes |
| `subscribe_*` | Start push event stream | Creates subscription |
| `unsubscribe_*` | Stop push event stream | Removes subscription |

### Critical MVP Feature: Smart High-Level Tools

To minimize context window consumption, tools perform compound operations in a single call:

- `get_blocks_area` — scan a rectangular region (up to 32x32x32), with optional block type filter
- `get_entities` — find entities in a radius with type filter, returns health/position/equipment
- `get_screen` — returns complete screen state (type, all slots, all widgets) in one call

### Critical MVP Feature: Batch Command Support

A `batch` tool accepts an array of read-only commands and returns all results in a single MCP round-trip:

```json
{
  "tool": "batch",
  "commands": [
    { "method": "get_block", "params": { "x": 1, "y": 2, "z": 3 } },
    { "method": "get_block", "params": { "x": 4, "y": 5, "z": 6 } },
    { "method": "get_entities", "params": { "radius": 10 } }
  ]
}
```

### Tool Catalog by Category

#### World Inspection (`get_*`)

| Tool | Description |
|------|-------------|
| `get_block` | Block state, properties, block entity data, light level, biome at position |
| `get_blocks_area` | Scan rectangular region with optional filter (max 32x32x32) |
| `get_world_info` | Time, weather, difficulty, game rules, TPS, online players |

#### Player Inspection (`get_*`)

| Tool | Description |
|------|-------------|
| `get_player` | Position, health, hunger, XP, effects, equipment, game mode |
| `get_inventory` | Player inventory, block container, or open screen contents |

#### Entity Inspection (`get_*`)

| Tool | Description |
|------|-------------|
| `get_entities` | Find entities by radius + type filter; returns type, position, health, equipment |
| `get_entity` | Detailed single entity: NBT, velocity, attributes, AI goals, passengers |

#### Screen/GUI (`get_*`)

| Tool | Description |
|------|-------------|
| `get_screen` | Current open screen: type, title, menu type, all slots with items, widgets |

#### Actions (`do_*`)

| Tool | Description |
|------|-------------|
| `do_interact_block` | Right-click a block (open chest, press button, etc.) with consequence collection |
| `do_attack_block` | Left-click a block (start breaking) |
| `do_interact_entity` | Right-click an entity |
| `do_attack_entity` | Attack an entity |
| `do_click_slot` | Click a slot in open container (move/swap items) |
| `do_close_screen` | Close the current screen |
| `do_set_held_slot` | Change hotbar selection (0-8) |
| `do_send_chat` | Send a chat message |

#### Commands (`run_*`)

| Tool | Description |
|------|-------------|
| `run_command` | Execute any Minecraft `/command` with consequence collection |

#### Batch

| Tool | Description |
|------|-------------|
| `batch` | Execute multiple read-only queries in a single round-trip |

#### Event Subscriptions

| Tool | Description |
|------|-------------|
| `subscribe_events` | Start receiving push events for specified types with spatial filter |
| `unsubscribe_events` | Stop a subscription by ID |

### MCP Resources

| URI | Description |
|-----|-------------|
| `minecraft://world/info` | World time, weather, dimension |
| `minecraft://player/info` | Player position, health, effects |
| `minecraft://player/inventory` | Full player inventory |
| `minecraft://screen/current` | Currently open screen/GUI |
| `minecraft://logs/recent` | Recent game log entries |

Resource subscriptions trigger `notifications/resources/updated` when game state changes, enabling reactive debugging.

### Subscribable Event Types (14 types)

`screen_opened`, `screen_closed`, `block_changed`, `entity_spawned`, `entity_removed`, `chat_message`, `inventory_changed`, `player_health_changed`, `player_moved`, `dimension_changed`, `death`, `respawn`, `advancement`, `block_break_progress`

Full tool JSON schemas are defined in [docs/explore/13-tool-vocabulary.md](../explore/13-tool-vocabulary.md).

---

## 6. Build & Distribution

### Mono-Repo Gradle Multi-Module

```
minecraft-mcp/
├── settings.gradle.kts
├── gradle.properties              # Shared versions: MC, Fabric, NeoForge, mod
├── mod/
│   ├── common/                    # ~85-90% of mod code (vanilla APIs only)
│   │   └── src/main/java/         # WebSocket server, protocol, command framework
│   ├── fabric/                    # Fabric entrypoint, events, client source set
│   │   └── src/{main,client}/java/
│   └── neoforge/                  # NeoForge @Mod, events, @Mod(dist=CLIENT)
│       └── src/main/java/
├── mcp-server/                    # Kotlin MCP server (Shadow JAR)
│   └── src/main/kotlin/
└── .github/workflows/             # CI build + release
```

### Mod Distribution

| Channel | Artifact | Notes |
|---------|----------|-------|
| Modrinth | `minecraft-mcp-fabric-{version}.jar` | Preferred by Fabric community |
| Modrinth | `minecraft-mcp-neoforge-{version}.jar` | |
| CurseForge | Both JARs | Larger user base |

User installation: drop JAR in `.minecraft/mods/`, launch Minecraft. WebSocket server starts automatically on world load.

### MCP Server Distribution

| Channel | Artifact | Usage |
|---------|----------|-------|
| GitHub Releases (primary) | `minecraft-mcp-server.jar` | `java -jar minecraft-mcp-server.jar` |
| npm wrapper (optional) | `@minecraftmcp/server` | `npx @minecraftmcp/server` |

Anyone running Minecraft already has Java 21 installed, so the Shadow JAR requires zero additional runtime dependencies.

### Claude Code Configuration (`.mcp.json`)

```json
{
  "mcpServers": {
    "minecraft": {
      "command": "java",
      "args": ["-jar", "/path/to/minecraft-mcp-server.jar"]
    }
  }
}
```

### CI/CD

- **GitHub Actions** with Java 21
- **Build workflow:** On every push/PR — builds mod (both loaders) + MCP server, runs tests, uploads artifacts
- **Release workflow:** On version tag (`v*`) — builds all artifacts, creates GitHub Release, publishes to Modrinth
- **Test strategy:** Unit tests for protocol serialization (no MC needed), integration tests with mock WebSocket, game tests via `fabric-gametest-api-v1` / NeoForge game test framework

### Versioning

Mod and MCP server share the same version number (released together). Semantic versioning. Protocol version validated on WebSocket connect.

---

## 7. MVP Scope & Phases

### Phase 1: Core Infrastructure

- MultiLoader Template project setup (common/fabric/neoforge)
- Embedded Netty WebSocket server with token authentication
- JSON message protocol (hello/auth, request/response with IDs, error handling)
- `ConcurrentLinkedQueue` + `CompletableFuture` + tick handler threading
- Mod lifecycle: start WebSocket on `SERVER_STARTED`, stop on `SERVER_STOPPING`
- Connection file write/delete (`~/.minecraft-mcp/connection.json`)

### Phase 2: Read-Only State Queries

- `get_block` — block state + block entity data at coordinates
- `get_blocks_area` — rectangular region scan with filter (max 32x32x32)
- `get_entities` — entities in radius with type filter
- `get_player` — position, health, food, effects, equipment, game mode
- `get_world_info` — time, weather, difficulty, game rules, TPS
- `get_screen` — currently open screen type, title, slots, widgets (client-side observer)
- `get_inventory` — player, block, or screen inventory
- `batch` — multiple read queries in one request

### Phase 3: Interaction Tools + Commands

- `do_interact_block` — right-click block with consequence collection
- `do_click_slot` — interact with open container slots
- `do_close_screen` — close current screen
- `do_set_held_slot` — change hotbar selection
- `run_command` — execute any Minecraft command with consequence collection
- Consequence collection system (temporary event listeners, tick-based window, spatial filtering)

### Phase 4: Event Subscriptions

- `subscribe_events` — register for push notifications by event type + spatial filter
- `unsubscribe_events` — remove subscription
- Push event delivery over WebSocket
- All 14 subscribable event types

### Phase 5: MCP Server Wrapper

- Kotlin MCP server process with stdio transport
- Tool registration (all tools from Phases 2-4)
- Resource registration (5 resource URIs + templates)
- WebSocket client bridge with auto-reconnect and exponential backoff
- Graceful degradation when mod is disconnected
- Dynamic tool registration from mod command catalog

---

## 8. Post-MVP Roadmap

| Feature | Description | Priority |
|---------|-------------|----------|
| **Rhino/JS scripting** | Execute arbitrary JavaScript in-game for inspection of unknown mod internals. Sandboxed with class allowlist, timeout enforcement, memory limits. (Layer 3 from context optimization.) | High |
| **Mod-specific GUI adapters** | Pluggable screen readers for popular mods' custom GUIs beyond vanilla container types. | Medium |
| **Screenshot capture** | Render game frame as PNG, return as `ImageContent` in MCP responses. | Medium |
| **Multi-instance support** | Instance-keyed connection files in `~/.minecraft-mcp/instance-{uuid}/` for multiple MC sessions. | Medium |
| **Dedicated server GUI relay** | Custom packet system to relay client screen observations to server in multiplayer. | Medium |
| **MCDxAI integration** | Complementary static analysis (decompilation, mappings, mixin validation) alongside runtime inspection. | Low |
| **GraalVM native image** | Native binary for MCP server — instant startup, no JVM requirement. | Low |

---

## 9. Risks & Mitigations

| # | Risk | Severity | Mitigation |
|---|------|----------|------------|
| 1 | **Client/server side split** — Screen events are client-only; world state is server-only. Dedicated servers need packet relay for GUI observation. | Medium | Singleplayer is primary target (shared JVM, in-memory bridge). Server-only mode degrades gracefully (~70% capability). Packet relay is post-MVP. |
| 2 | **Mod GUI diversity** — Each mod implements GUIs differently. Reading arbitrary mod screens is the hardest unsolved problem. | Medium | Start with vanilla container types (chests, furnaces, crafting). Expose raw `screen_class` for identification. Scripting escape hatch (post-MVP) bridges the gap. |
| 3 | **Minecraft version churn** — MC updates break mods. Both loaders update at different rates. | Medium | Minimize Mixin use. Depend on stable public APIs (Fabric API events, NeoForge event bus). Target only latest MC version. |
| 4 | **Thread safety bugs** — Accessing game state from Netty I/O threads causes crashes. | Medium | Strict queue-drain pattern. All game state access confined to tick handler. Code review discipline. |
| 5 | **MCP protocol evolution** — The Model Context Protocol is still maturing. | Low | Thin MCP adapter layer in the Kotlin server can be updated independently of the mod. |
| 6 | **Netty EventLoopGroup interference** — Mod's Netty could conflict with Minecraft's networking. | Low | Dedicated `NioEventLoopGroup` with daemon threads, completely separate from MC's. Precedent: Dynmap, BlueMap do this successfully. |
| 7 | **Context window consumption** — Verbose tool responses exhaust Claude's context. | Medium | Smart high-level tools, batch command support, air-block filtering, result truncation with `truncated` flag. |

---

## 10. References

All exploration and refinement documents that informed this specification:

| Doc | Title | Key Contribution |
|-----|-------|------------------|
| [01](../explore/01-fabric-feasibility.md) | Fabric Feasibility | Comprehensive API mapping: ScreenEvents, interaction callbacks, Mixin escape hatch |
| [02](../explore/02-neoforge-feasibility.md) | NeoForge Feasibility | Event bus system, ScreenEvent, Container interface, command execution |
| [03](../explore/03-crossloader-architecture.md) | Cross-Loader Architecture | MultiLoader Template recommendation, 85-90% code sharing estimate |
| [04](../explore/04-websocket-server.md) | WebSocket Server | Netty reuse (zero deps), thread safety architecture, security model |
| [05](../explore/05-adversarial-no-mod.md) | No-Mod Approach | RCON + proxy covers vanilla but not mod internals; confirms mod is necessary |
| [06](../explore/06-adversarial-existing-solutions.md) | Existing Solutions Survey | 15+ projects surveyed; GDMC validates architecture; no competitor fills this gap |
| [07](../explore/07-adversarial-scripting-engine.md) | Scripting Engine | Rhino/JS hybrid architecture; post-MVP escape hatch for unknown mod inspection |
| [08](../explore/08-adversarial-client-automation.md) | Client Automation | OS-level automation fundamentally insufficient (5-10% data, 20-100x latency) |
| [09](../explore/09-evaluation-report.md) | Evaluation Report | Consolidated verdict: BUILD IT. Architecture validated, gap confirmed |
| [10](../explore/10-mcp-server-design.md) | MCP Server Design | Kotlin SDK, stdio transport, Ktor Client CIO, connection management |
| [11](../explore/11-client-server-design.md) | Client/Server Design | Dual-side mod, singleplayer in-memory bridge, capability advertisement |
| [12](../explore/12-protocol-design.md) | Protocol Design | Consequence collection model, message types, tick-based windows |
| [13](../explore/13-tool-vocabulary.md) | Tool Vocabulary | Full tool catalog with JSON schemas, naming conventions, response formats |
| [14](../explore/14-build-distribution-design.md) | Build & Distribution | Mono-repo Gradle, Shadow JAR, Modrinth/CurseForge, CI/CD |
| [15](../explore/15-context-optimization-decision.md) | Context Optimization | Batch commands, smart tools, scripting layers for minimal context usage |

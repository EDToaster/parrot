# MCP Server Design: Minecraft Mod Bridge

**Explorer:** explorer-mcp-server
**Date:** 2026-03-10

---

## 1. Overview

This document designs the **Kotlin MCP server** that bridges Claude Code (or any MCP client) to the Minecraft mod's WebSocket server. The MCP server is a **separate process** that:

1. Speaks the MCP protocol over **stdio** (JSON-RPC) to Claude Code
2. Connects via **WebSocket** to the Minecraft mod on `localhost:25566`
3. Translates MCP tool calls into mod commands and mod responses into MCP results

```
┌──────────────┐   stdio (JSON-RPC)   ┌──────────────────┐   WebSocket (JSON)   ┌──────────────┐
│  Claude Code │◄─────────────────────►│  Kotlin MCP      │◄───────────────────►│  Minecraft   │
│  (MCP Client)│                       │  Server (JVM)    │                      │  Mod (Netty) │
└──────────────┘                       └──────────────────┘                      └──────────────┘
```

---

## 2. Technology Choices

### 2.1 Language: Kotlin (JVM)

**Rationale:**
- Official MCP SDK exists: `io.modelcontextprotocol:kotlin-sdk-server:0.8.3`
- Kotlin coroutines provide clean async WebSocket handling
- JVM interop allows potential future embedding in the mod itself
- kotlinx.serialization for type-safe JSON handling

### 2.2 MCP SDK: Official Kotlin SDK

**Package:** `io.modelcontextprotocol:kotlin-sdk-server:0.8.3`

The SDK provides:
- `Server` class with tool/resource registration
- `StdioServerTransport` for stdin/stdout JSON-RPC communication
- `ServerCapabilities` for declaring tools, resources, prompts
- `CallToolResult` for structured responses
- `ToolSchema` with JSON Schema input validation

**Key imports:**
```kotlin
io.modelcontextprotocol.kotlin.sdk.server.Server
io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
io.modelcontextprotocol.kotlin.sdk.types.*
```

### 2.3 Transport: stdio

Claude Code launches MCP servers as **subprocesses** and communicates over stdin/stdout using newline-delimited JSON-RPC messages. This is the standard MCP transport for local tools.

**Why not SSE or StreamableHTTP?** Those are for remote/web deployments. Since this server runs on the same machine as Claude Code, stdio is simpler, requires no port management, and is the default expectation.

### 2.4 WebSocket Client: Ktor Client

**Package:** `io.ktor:ktor-client-cio` (CIO engine, no Netty conflicts)

The MCP server acts as a **WebSocket client** connecting to the mod's Netty WebSocket server. Ktor Client is already a transitive dependency of the Kotlin MCP SDK's server module, so it adds no new dependencies.

**Why Ktor Client (not raw Netty client)?**
- Ktor Client is lightweight and already on the classpath via the SDK
- Clean coroutine-based WebSocket API
- CIO engine avoids Netty — no confusion with the mod's Netty server
- Built-in reconnection primitives

---

## 3. Claude Code Configuration

Users configure MCP servers in Claude Code via `.mcp.json` (project-level) or `~/.claude/settings.json` (global). The server is launched as a subprocess.

### 3.1 Configuration File (`.mcp.json`)

```json
{
  "mcpServers": {
    "minecraft": {
      "command": "java",
      "args": ["-jar", "/path/to/minecraft-mcp-server.jar"],
      "env": {
        "MINECRAFT_WS_PORT": "25566"
      }
    }
  }
}
```

**Alternative (with gradle wrapper for development):**
```json
{
  "mcpServers": {
    "minecraft": {
      "command": "./gradlew",
      "args": ["run", "--quiet"],
      "cwd": "/path/to/minecraft-mcp-server"
    }
  }
}
```

### 3.2 Connection Discovery

The MCP server needs to find the mod's WebSocket port. Options (in priority order):

1. **Environment variable:** `MINECRAFT_WS_PORT` (set in `.mcp.json` env)
2. **Port file:** Mod writes port to `~/.minecraft-mcp/port` on startup; server reads it
3. **Default:** `127.0.0.1:25566`

The **port file approach** is recommended for automatic discovery: the mod writes `{"port": 25566, "token": "abc123"}` to a known path, and the MCP server reads it on startup. This also solves authentication token sharing.

---

## 4. Server Architecture

### 4.1 Component Overview

```
┌─────────────────────────────────────────────────┐
│              Kotlin MCP Server                   │
│                                                  │
│  ┌──────────────┐    ┌─────────────────────┐    │
│  │ StdioTransport│    │ MinecraftBridge     │    │
│  │ (MCP JSON-RPC)│    │ (WebSocket Client)  │    │
│  └──────┬───────┘    └──────────┬──────────┘    │
│         │                       │                │
│  ┌──────▼───────────────────────▼──────────┐    │
│  │           MCP Server Core                │    │
│  │  - Tool Registration                     │    │
│  │  - Resource Registration                 │    │
│  │  - Request Routing                       │    │
│  └──────────────────────────────────────────┘    │
│                                                  │
│  ┌──────────────────────────────────────────┐    │
│  │         Connection Manager               │    │
│  │  - Auto-reconnect with backoff           │    │
│  │  - Health monitoring                     │    │
│  │  - Request/response correlation          │    │
│  └──────────────────────────────────────────┘    │
└─────────────────────────────────────────────────┘
```

### 4.2 Startup Sequence

1. Parse configuration (env vars, port file)
2. Create `Server` instance with capabilities (tools + resources)
3. Register all tools (see Section 5)
4. Register all resources (see Section 6)
5. Attempt WebSocket connection to mod (non-blocking)
6. Create `StdioServerTransport` and start listening
7. If WebSocket not yet connected, tools return helpful error messages

### 4.3 Main Entry Point (Conceptual)

```kotlin
fun main() = runBlocking {
    val config = loadConfig()  // port file, env vars, defaults
    val bridge = MinecraftBridge(config)

    val server = Server(
        serverInfo = Implementation("minecraft-mcp", "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
                resources = ServerCapabilities.Resources(
                    subscribe = true,
                    listChanged = true
                )
            )
        )
    )

    registerTools(server, bridge)
    registerResources(server, bridge)

    // Connect to mod (async, with reconnection)
    launch { bridge.connectWithRetry() }

    // Start MCP stdio transport
    val transport = StdioServerTransport(
        System.`in`.asSource().buffered(),
        System.out.asSink().buffered()
    )
    server.createSession(transport)

    val done = Job()
    server.onClose { done.complete() }
    done.join()
}
```

---

## 5. Tool Registration

Tools are the primary interface. Each tool maps to a mod WebSocket command.

### 5.1 Tool Catalog

| MCP Tool Name | Mod Command | Description | Input Schema |
|---------------|-------------|-------------|--------------|
| `get_block` | `get_block` | Get block state and data at coordinates | `{x: int, y: int, z: int}` |
| `get_entities` | `get_entities` | List entities near a position | `{x: int, y: int, z: int, radius: float}` |
| `get_player` | `get_player_info` | Get player position, health, inventory | `{}` |
| `get_world` | `get_world_info` | Get time, weather, dimension, game rules | `{}` |
| `get_screen` | `get_screen` | Get currently open GUI/screen contents | `{}` |
| `get_inventory` | `get_inventory` | Get player or container inventory | `{target?: string}` |
| `run_command` | `run_command` | Execute a Minecraft command | `{command: string}` |
| `interact_block` | `interact_block` | Simulate right-click on a block | `{x: int, y: int, z: int}` |
| `click_slot` | `click_slot` | Click a slot in open container | `{slot: int, button?: int}` |
| `subscribe_events` | `subscribe` | Subscribe to game events | `{events: string[]}` |
| `get_registries` | `get_registries` | List mod registries and entries | `{registry?: string}` |
| `get_logs` | `get_logs` | Get recent game/mod log entries | `{lines?: int, filter?: string}` |

### 5.2 Tool Registration Pattern

Each tool follows this pattern:

```kotlin
server.addTool(
    name = "get_block",
    description = "Get block state, properties, and block entity data at the given coordinates",
    inputSchema = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("x") { put("type", "integer"); put("description", "X coordinate") }
            putJsonObject("y") { put("type", "integer"); put("description", "Y coordinate") }
            putJsonObject("z") { put("type", "integer"); put("description", "Z coordinate") }
        },
        required = listOf("x", "y", "z")
    )
) { request ->
    bridge.sendCommand("get_block", request.arguments)
}
```

### 5.3 Bridge Command Translation

The bridge translates MCP tool calls to WebSocket messages:

```
MCP tool call:
  tools/call { name: "get_block", arguments: { x: 10, y: 64, z: -20 } }

Sent to mod via WebSocket:
  { "id": "req-001", "command": "get_block", "args": { "x": 10, "y": 64, "z": -20 } }

Received from mod:
  { "id": "req-001", "status": "ok", "data": { "block": "minecraft:chest", "properties": { "facing": "north" }, "blockEntity": { ... } } }

Returned to Claude Code:
  CallToolResult(content = [TextContent(json_string)])
```

### 5.4 Dynamic Tool Registration

The mod may support custom commands added by other mods. The MCP server can query the mod for its command catalog at connection time:

1. On WebSocket connect, send `{ "command": "list_commands" }`
2. Mod responds with available commands and their schemas
3. MCP server dynamically registers tools and sends `notifications/tools/list_changed`

This allows the tool list to grow as mods add commands, without updating the MCP server.

---

## 6. Resource Registration

MCP resources expose **read-only game state** that Claude Code can reference as context.

### 6.1 Resource Catalog

| Resource URI | Description | MIME Type |
|-------------|-------------|-----------|
| `minecraft://world/info` | World time, weather, dimension | `application/json` |
| `minecraft://player/info` | Player position, health, effects | `application/json` |
| `minecraft://player/inventory` | Full player inventory | `application/json` |
| `minecraft://screen/current` | Currently open screen/GUI | `application/json` |
| `minecraft://logs/recent` | Recent game log entries | `text/plain` |

### 6.2 Resource Templates

```kotlin
// Template for block lookup by coordinates
ResourceTemplate(
    uriTemplate = "minecraft://world/block/{x}/{y}/{z}",
    name = "Block State",
    description = "Get block state at specific coordinates",
    mimeType = "application/json"
)

// Template for entity lookup by ID
ResourceTemplate(
    uriTemplate = "minecraft://entity/{id}",
    name = "Entity Data",
    description = "Get full entity data by entity ID",
    mimeType = "application/json"
)

// Template for registry browsing
ResourceTemplate(
    uriTemplate = "minecraft://registry/{name}",
    name = "Registry",
    description = "Browse a Minecraft registry (blocks, items, entities, etc.)",
    mimeType = "application/json"
)
```

### 6.3 Resource Subscriptions

When the MCP client subscribes to a resource (e.g., `minecraft://screen/current`), the server:
1. Subscribes to the corresponding mod event via WebSocket
2. When the mod pushes an event (screen changed), sends `notifications/resources/updated` to Claude Code
3. Claude Code can then `resources/read` to get the updated state

This enables reactive debugging — Claude Code is notified when game state changes.

---

## 7. WebSocket Client Bridge

### 7.1 Connection Management

```kotlin
class MinecraftBridge(private val config: Config) {
    private var session: WebSocketSession? = null
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private var requestCounter = AtomicLong(0)

    suspend fun connectWithRetry() {
        // Exponential backoff: 1s, 2s, 4s, 8s, max 30s
        // Log connection attempts to stderr (visible in Claude Code logs)
    }

    suspend fun sendCommand(command: String, args: JsonObject?): CallToolResult {
        val session = this.session
            ?: return CallToolResult.error("Not connected to Minecraft. Is the mod running?")

        val id = "req-${requestCounter.incrementAndGet()}"
        val deferred = CompletableDeferred<JsonObject>()
        pendingRequests[id] = deferred

        session.send(buildJsonObject {
            put("id", id)
            put("command", command)
            args?.let { put("args", it) }
        }.toString())

        return try {
            val response = withTimeout(10.seconds) { deferred.await() }
            CallToolResult(content = listOf(TextContent(response.toString())))
        } catch (e: TimeoutCancellationException) {
            CallToolResult.error("Command timed out after 10 seconds")
        } finally {
            pendingRequests.remove(id)
        }
    }
}
```

### 7.2 Message Routing

Incoming WebSocket messages from the mod fall into two categories:

1. **Responses** (have an `id` field): Routed to the matching `CompletableDeferred` in `pendingRequests`
2. **Events** (have an `event` field): Routed to the event subscription handler, which triggers MCP resource update notifications

```kotlin
// In WebSocket receive loop
when {
    message.containsKey("id") -> {
        val deferred = pendingRequests[message["id"]!!.jsonPrimitive.content]
        deferred?.complete(message)
    }
    message.containsKey("event") -> {
        handleEvent(message)
    }
}
```

### 7.3 Authentication

The mod writes a connection file on startup:

```json
// ~/.minecraft-mcp/connection.json
{
    "port": 25566,
    "token": "a1b2c3d4e5f6..."
}
```

The MCP server reads this file and includes the token in the WebSocket handshake:
- As a query parameter: `ws://127.0.0.1:25566/?token=a1b2c3d4e5f6`
- Or as a custom header during the WebSocket upgrade

---

## 8. Error Handling

### 8.1 Connection States

| State | MCP Behavior |
|-------|-------------|
| **Connected** | Tools work normally |
| **Disconnected (reconnecting)** | Tools return error: "Reconnecting to Minecraft... (attempt 3/10)" |
| **Disconnected (gave up)** | Tools return error: "Cannot connect to Minecraft mod. Ensure the mod is loaded and Minecraft is running." |
| **Mod not found** | Connection file missing → "Minecraft mod not detected. Install the mod and launch Minecraft." |

### 8.2 Per-Tool Error Handling

```kotlin
suspend fun sendCommand(command: String, args: JsonObject?): CallToolResult {
    // 1. Check connection
    if (session == null) return connectionError()

    // 2. Send and await with timeout
    // 3. Handle mod-side errors
    val response = ...
    return when (response["status"]?.jsonPrimitive?.content) {
        "ok" -> CallToolResult(content = listOf(TextContent(
            response["data"].toString()
        )))
        "error" -> CallToolResult(
            content = listOf(TextContent(
                "Minecraft error: ${response["error"]?.jsonPrimitive?.content}"
            )),
            isError = true
        )
        else -> CallToolResult.error("Unexpected response from mod")
    }
}
```

### 8.3 Graceful Degradation

When the mod disconnects mid-session:
1. All pending requests receive a timeout/disconnect error
2. The MCP server remains running (Claude Code doesn't need to restart it)
3. Reconnection attempts begin automatically
4. When reconnected, tools become available again
5. Send `notifications/tools/list_changed` to signal Claude Code to refresh

### 8.4 Logging

All diagnostic output goes to **stderr** (never stdout, which is the MCP transport):
- Connection status changes
- WebSocket errors
- Command timeouts
- Reconnection attempts

Claude Code captures stderr and can display it to the user.

---

## 9. Build and Distribution

### 9.1 Gradle Build

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

dependencies {
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.8.3")
    implementation("io.ktor:ktor-client-cio:3.2.3")
    implementation("io.ktor:ktor-client-websockets:3.2.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

application {
    mainClass.set("com.minecraftmcp.server.MainKt")
}

tasks.shadowJar {
    archiveBaseName.set("minecraft-mcp-server")
    archiveClassifier.set("")
}
```

### 9.2 Distribution Options

1. **Fat JAR** (recommended): Single `minecraft-mcp-server.jar` via Shadow plugin. User runs `java -jar minecraft-mcp-server.jar`.
2. **Native binary** (future): GraalVM native-image for instant startup and no JVM requirement. The Kotlin MCP SDK targets Kotlin Multiplatform, so native compilation may be feasible.
3. **Package managers**: Homebrew tap, npm wrapper (runs the JAR), or direct download.

### 9.3 JVM Requirement

- Requires Java 17+ (Kotlin 2.1 target)
- Minecraft 1.21.x requires Java 21 — users will already have it installed
- The mod could bundle the MCP server JAR and offer a "launch MCP server" command

---

## 10. Protocol: Mod WebSocket Message Format

The MCP server and mod communicate using a simple JSON protocol over WebSocket.

### 10.1 Request (MCP Server → Mod)

```json
{
    "id": "req-001",
    "command": "get_block",
    "args": {
        "x": 10,
        "y": 64,
        "z": -20
    }
}
```

### 10.2 Response (Mod → MCP Server)

```json
{
    "id": "req-001",
    "status": "ok",
    "data": {
        "block": "minecraft:chest",
        "properties": {
            "facing": "north",
            "type": "single",
            "waterlogged": "false"
        },
        "blockEntity": {
            "type": "minecraft:chest",
            "items": [
                { "slot": 0, "id": "minecraft:diamond", "count": 5 },
                { "slot": 3, "id": "minecraft:iron_ingot", "count": 32 }
            ]
        }
    }
}
```

### 10.3 Event (Mod → MCP Server, unsolicited)

```json
{
    "event": "screen_opened",
    "data": {
        "screenType": "minecraft:generic_9x3",
        "title": "Chest",
        "slots": [
            { "index": 0, "id": "minecraft:diamond", "count": 5 },
            { "index": 3, "id": "minecraft:iron_ingot", "count": 32 }
        ]
    }
}
```

### 10.4 Command Discovery (MCP Server → Mod)

```json
{
    "id": "req-000",
    "command": "list_commands"
}
```

Response:
```json
{
    "id": "req-000",
    "status": "ok",
    "data": {
        "commands": [
            {
                "name": "get_block",
                "description": "Get block state at coordinates",
                "args": {
                    "x": { "type": "integer", "required": true },
                    "y": { "type": "integer", "required": true },
                    "z": { "type": "integer", "required": true }
                }
            }
        ]
    }
}
```

---

## 11. Alternatives Considered

### 11.1 Rust MCP Server (Rejected for now)

The evaluation report suggested Rust or TypeScript. Kotlin was chosen because:
- Official MCP SDK with full tool/resource support
- Coroutines are a natural fit for the async bridge pattern
- JVM ecosystem aligns with Minecraft (both Java-based)
- Potential future embedding in the mod itself
- Type-safe JSON with kotlinx.serialization

Rust would offer faster startup and lower memory, but requires a separate ecosystem and has no official MCP SDK (only community crates).

### 11.2 TypeScript MCP Server (Viable alternative)

TypeScript has the most mature MCP SDK (`@modelcontextprotocol/sdk`). If the team prefers Node.js, this is a solid alternative. The design in this document would translate directly — the architecture is language-agnostic.

### 11.3 Embedding MCP in the Mod (Rejected for MVP)

Running the MCP server inside the Minecraft JVM would eliminate the WebSocket bridge but:
- stdio transport conflicts with Minecraft's own console I/O
- Mod updates would require Minecraft restart for MCP changes
- Heavier mod footprint (SDK + Ktor dependencies)
- The Ktor dependency was explicitly flagged as problematic for mod embedding (see websocket report)

A separate process keeps concerns cleanly separated.

### 11.4 SSE/HTTP Transport Instead of stdio (Rejected)

Claude Code supports SSE-based MCP servers, but stdio is:
- Simpler (no port management for the MCP server itself)
- The standard for local tools
- How most MCP servers are configured in Claude Code

---

## 12. Open Questions

1. **Response formatting**: Should tool results return raw JSON or human-readable formatted text? Recommendation: JSON by default with a `format` parameter option.

2. **Batch operations**: Should there be a `batch_commands` tool for executing multiple commands in one tick? Useful for atomic operations like "open chest then read contents."

3. **Screenshot/render capture**: The mod could capture screenshots and return them as base64 images via MCP. The SDK supports `ImageContent` in tool results. Worth adding post-MVP.

4. **Multi-instance**: If multiple Minecraft instances are running, the port file approach needs instance discrimination. Could use the Minecraft instance directory as a key.

5. **Kotlin Multiplatform native**: The Kotlin MCP SDK supports Native targets. A GraalVM or Kotlin/Native build could eliminate the JVM requirement for faster startup. Worth exploring post-MVP.

---

## 13. Summary

| Aspect | Decision |
|--------|----------|
| Language | Kotlin (JVM) |
| MCP SDK | `io.modelcontextprotocol:kotlin-sdk-server:0.8.3` |
| MCP Transport | stdio (subprocess of Claude Code) |
| WebSocket Client | Ktor Client CIO engine |
| Mod Communication | JSON over WebSocket to `localhost:25566` |
| Authentication | Shared token via connection file |
| Distribution | Shadow JAR (`java -jar`) |
| Tool Registration | Static tools + dynamic discovery from mod |
| Resources | Game state as MCP resources with subscriptions |
| Error Handling | Graceful degradation, auto-reconnect, helpful error messages |

The architecture is straightforward: a thin translation layer between two well-defined protocols (MCP JSON-RPC over stdio ↔ custom JSON over WebSocket). The Kotlin MCP SDK handles all MCP protocol complexity, and Ktor Client handles WebSocket communication. The MCP server itself is ~500-800 lines of Kotlin code for an MVP.

# Parrot

A Playwright-style [MCP server](https://modelcontextprotocol.io/) for Minecraft that lets AI assistants like Claude observe and interact with running Minecraft instances in real time.

Parrot has two components: a **Minecraft mod** (Fabric + NeoForge) that embeds a WebSocket server exposing game state and accepting commands, and a standalone **MCP server** that bridges Claude Code's stdio JSON-RPC to the mod's WebSocket.

## Requirements

- Java 21+
- Minecraft 1.21.10
- One of:
  - [Fabric Loader](https://fabricmc.net/) 0.18.4+ with [Fabric API](https://modrinth.com/mod/fabric-api) and [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin)
  - [NeoForge](https://neoforged.net/) 21.10.64+

## Quick Start

### 1. Build

```bash
git clone <repo-url> && cd minecraft-mcp
./gradlew build
```

This produces:
| Artifact | Location |
|----------|----------|
| Fabric mod | `mod/fabric/build/libs/` |
| NeoForge mod | `mod/neoforge/build/libs/` |
| MCP server JAR | `mcp-server/build/libs/parrot-mcp-server-0.1.0.jar` |

### 2. Run Minecraft with the mod

**Option A: Via Gradle** (easiest for development)

```bash
# Fabric
./gradlew :mod:fabric:runClient

# NeoForge
./gradlew :mod:neoforge:runClient
```

**Option B: Manual install**

Copy the mod JAR into your Minecraft instance's `mods/` folder alongside the required loader and dependencies.

### 3. Start a world

Create or load a **Creative mode singleplayer** world. When the world loads, Parrot will:

1. Start a WebSocket server on `127.0.0.1:25566`
2. Write connection info to `~/.parrot/connection.json`

Verify with:
```bash
cat ~/.parrot/connection.json
# {"port":25566,"token":"<random-hex>","pid":12345}
```

### 4. Configure Claude Code

Add the MCP server to your Claude Code config. Create or edit `.mcp.json` in your project root:

```json
{
  "mcpServers": {
    "minecraft": {
      "command": "java",
      "args": ["-jar", "<path-to-repo>/mcp-server/build/libs/parrot-mcp-server-0.1.0.jar"]
    }
  }
}
```

The MCP server auto-discovers the connection via `~/.parrot/connection.json`. You can also set `PARROT_PORT` and `PARROT_HOST` environment variables to override.

### 5. Talk to Minecraft

Ask Claude things like:

- *"What block am I standing on?"*
- *"Build a 5x5 stone platform at my feet"*
- *"What entities are near me?"*
- *"Open the chest at 100, 64, -200 and tell me what's inside"*
- *"Set the time to noon"*

---

## Available Tools

### Queries (read-only)

| Tool | Description | Key Parameters |
|------|-------------|----------------|
| `get_block` | Block state at a position | `x`, `y`, `z` |
| `get_blocks_area` | Scan a rectangular region (max 32^3) | `x1,y1,z1`, `x2,y2,z2` |
| `get_world_info` | Time, weather, TPS, game rules | — |
| `get_player` | Position, health, equipment, game mode | `name` (optional) |
| `get_inventory` | Player or screen inventory slots | `target`: `"player"` or `"screen"` |
| `get_entities` | Entities near a position | `x,y,z`, `radius` (max 64) |
| `get_entity` | Detailed info for one entity | `uuid` |
| `get_screen` | Currently open GUI screen | — |

### Actions

| Tool | Description | Key Parameters |
|------|-------------|----------------|
| `do_interact_block` | Right-click a block | `x,y,z`, `face`, `hand` |
| `do_attack_block` | Break a block (creative) | `x,y,z`, `face` |
| `do_interact_entity` | Right-click an entity | `uuid`, `hand` |
| `do_attack_entity` | Attack an entity | `uuid` |
| `do_click_slot` | Click an inventory slot | `slot`, `button`, `clickType` |
| `do_close_screen` | Close the open GUI | — |
| `do_set_held_slot` | Switch hotbar slot | `slot` (0-8) |
| `do_send_chat` | Send a chat message | `message` |

### Utility

| Tool | Description | Key Parameters |
|------|-------------|----------------|
| `run_command` | Execute a server command (op-level) | `command` |
| `batch` | Multiple read-only queries in one call | `commands[]` |
| `subscribe` | Subscribe to game events | `eventTypes[]` |
| `unsubscribe` | Cancel a subscription | `subscriptionId` |
| `poll_events` | Drain buffered events | `subscriptionId` (optional) |
| `list_methods` | List all available methods | — |

### Resources

| URI | Description |
|-----|-------------|
| `minecraft://world/info` | World time, weather, TPS |
| `minecraft://player/info` | Player position, health, equipment |
| `minecraft://player/inventory` | Full inventory contents |
| `minecraft://screen/current` | Current GUI screen state |
| `minecraft://logs/recent` | Recent game log entries |

---

## Event Types

Subscribe to real-time game events with the `subscribe` tool:

`screen_opened`, `screen_closed`, `block_changed`, `entity_spawned`, `entity_removed`, `chat_message`, `inventory_changed`, `player_health_changed`, `player_moved`, `dimension_changed`, `death`, `respawn`, `advancement`, `block_break_progress`

Events support spatial filtering — subscribe only to events within a radius of a position.

---

## Architecture

```
Claude Code  <--stdio/JSON-RPC-->  MCP Server  <--WebSocket-->  Minecraft Mod
                                   (Kotlin)                     (Fabric/NeoForge)
                                   :mcp-server                  :mod:common
                                                                :mod:fabric
                                                                :mod:neoforge
```

### Module Structure

```
parrot/
├── protocol/          # Shared message types (@Serializable sealed class hierarchy)
├── mod/
│   ├── common/        # ~90% of mod code — handlers, engine, WebSocket server
│   ├── fabric/        # Fabric entry point + client screen observation
│   └── neoforge/      # NeoForge entry point + client screen observation
├── mcp-server/        # Standalone MCP server (Shadow JAR)
└── buildSrc/          # Gradle convention plugins
```

### Key Design Decisions

- **Thread safety**: Commands from WebSocket (Netty I/O thread) are queued and executed on the server tick thread via `ConcurrentLinkedQueue` + `CompletableFuture`.
- **Single connection**: Only one MCP client can connect at a time. New connections disconnect the previous client.
- **Auth**: Each session requires a token (generated at startup, written to `~/.parrot/connection.json`).
- **Creative mode only**: The MVP assumes Creative mode. No survival mechanics are handled.
- **Loaded chunks only**: Queries return `BLOCK_OUT_OF_RANGE` for unloaded chunks — no force-loading.
- **Consequences**: Action results include a `consequences` array — observed side effects (block changes, screen opens, etc.) within a short tick window after the action.

---

## Development

### Building individual modules

```bash
./gradlew :protocol:build          # Protocol types + serialization tests
./gradlew :mod:common:build        # Common mod code
./gradlew :mod:fabric:build        # Fabric mod JAR
./gradlew :mod:neoforge:build      # NeoForge mod JAR
./gradlew :mcp-server:shadowJar    # MCP server fat JAR
```

### Running tests

```bash
./gradlew :protocol:test           # Protocol serialization tests (~30 tests)
./gradlew :mcp-server:test         # MCP server unit + integration tests (~45 tests)
./gradlew :mod:fabric:runGametest  # In-game tests via Fabric GameTest API (~7 tests)
./gradlew test                     # All unit/integration tests (excludes game tests)
```

### Running Minecraft for development

```bash
./gradlew :mod:fabric:runClient    # Launch Fabric client with mod
./gradlew :mod:fabric:runServer    # Launch Fabric dedicated server with mod
./gradlew :mod:neoforge:runClient  # Launch NeoForge client with mod
```

The first run downloads Minecraft assets and takes a few minutes. Subsequent launches are fast. Game files are stored in `mod/fabric/runs/` or `mod/neoforge/runs/`.

### Connection discovery

The MCP server finds the mod using this priority:

1. `PARROT_PORT` / `PARROT_HOST` environment variables
2. `~/.parrot/connection.json` (written by the mod at startup)
3. Default: `127.0.0.1:25566`

---

## Tech Stack

| Component | Version |
|-----------|---------|
| Kotlin | 2.3.10 |
| Minecraft | 1.21.10 |
| Fabric Loader | 0.18.4 |
| Fabric API | 0.138.4+1.21.10 |
| NeoForge | 21.10.64 |
| MCP Kotlin SDK | 0.9.0 |
| Ktor | 3.4.1 |
| kotlinx.serialization | 1.10.0 |
| Netty | (bundled with Minecraft) |

## License

MIT

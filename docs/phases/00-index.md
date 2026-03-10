# Parrot Implementation Plan

**Goal:** Build "Parrot" — a Playwright-style MCP server + Minecraft mod that lets Claude Code interact with running Minecraft instances for AI-assisted mod debugging.

**Architecture:** A Kotlin mono-repo with two components: (1) a MultiLoader Minecraft mod (Fabric + NeoForge) embedding a Netty WebSocket server that exposes game state and accepts commands, and (2) a standalone Kotlin MCP server process that bridges Claude Code's stdio JSON-RPC to the mod's WebSocket. A shared `:protocol` module provides compile-time-safe message types used by both.

**Tech Stack:** Kotlin 2.3.10, Minecraft 1.21.10, Fabric Loader 0.18.4, NeoForge 21.10.64, Official Mojang Mappings + Parchment, MCP Kotlin SDK 0.9.0, Ktor 3.4.1, kotlinx.serialization 1.10.0, Netty (MC-bundled), Gson (MC-bundled)

---

## Key Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Language | Kotlin for both mod and MCP server | Shared `:protocol` module, single source of truth for message types |
| Mappings | Official Mojang + Parchment | Identical between Fabric and NeoForge, required for MultiLoader |
| Game mode | Creative only (MVP) | Simplifies action handlers, no survival constraints |
| Range limits | Loaded chunks only | No force-loading, `BLOCK_OUT_OF_RANGE` for unloaded |
| Permissions | Queries unrestricted, actions as-player, commands as-op | See spec section 4 |
| Serialization | kotlinx.serialization everywhere | Consistency between mod and MCP server via shared protocol |

## Version Catalog

| Library | Version |
|---------|---------|
| Minecraft | 1.21.10 |
| Fabric Loader | 0.18.4 |
| Fabric API | 0.138.4+1.21.10 |
| fabric-language-kotlin | 1.13.9+kotlin.2.3.10 |
| NeoForge | 21.10.64 |
| Kotlin | 2.3.10 |
| MCP Kotlin SDK | 0.9.0 |
| Ktor | 3.4.1 |
| kotlinx-serialization | 1.10.0 |
| kotlinx-coroutines | 1.10.2 |
| Shadow plugin | 9.3.2 |

## Directory Structure

```
parrot/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml
├── buildSrc/                        # Convention plugins (parrot-common, parrot-loader)
├── protocol/                        # Shared Kotlin data classes (@Serializable)
│   ├── build.gradle.kts
│   └── src/main/kotlin/dev/parrot/protocol/
├── mod/
│   ├── common/                      # ~85-90% of mod code, vanilla MC APIs only
│   │   ├── build.gradle.kts         # NeoForm-only mode (no loader APIs)
│   │   └── src/main/kotlin/dev/parrot/mod/
│   │       ├── engine/              # ParrotEngine, CommandQueue, ConsequenceCollector
│   │       ├── server/              # WebSocket server, ConnectionFileManager
│   │       ├── commands/            # CommandHandler framework + all handlers
│   │       └── events/              # SubscriptionManager, EventBridge
│   ├── fabric/
│   │   ├── build.gradle.kts         # Fabric Loom + fabric-language-kotlin
│   │   ├── src/main/kotlin/         # ParrotFabric, FabricPlatformBridge
│   │   └── src/client/kotlin/       # ParrotFabricClient, FabricScreenReader
│   └── neoforge/
│       ├── build.gradle.kts         # NeoForge ModDevGradle + jarJar kotlin-stdlib
│       └── src/main/kotlin/         # ParrotNeoForge, NeoForgePlatformBridge, client
├── mcp-server/
│   ├── build.gradle.kts             # Shadow JAR
│   └── src/main/kotlin/dev/parrot/mcp/
│       ├── Main.kt                  # Entry point (stdio transport)
│       ├── MinecraftBridge.kt       # Ktor WebSocket client to mod
│       ├── ToolRegistrar.kt         # 21 MCP tools
│       ├── ResourceRegistrar.kt     # 5 MCP resources
│       └── Config.kt                # Connection discovery
└── .github/workflows/build.yml
```

## Phase Documents

| Phase | Document | Description |
|-------|----------|-------------|
| 1 | [01-scaffolding.md](01-scaffolding.md) | Project scaffolding: Gradle multi-module, version catalog, buildSrc conventions, CI |
| 2 | [02-protocol-websocket.md](02-protocol-websocket.md) | Shared protocol types, Netty WebSocket server, thread safety layer, connection file |
| 3 | [03-commands.md](03-commands.md) | Command framework, 8 query handlers, 8 action handlers, batch, run_command, events |
| 4 | [04-platform-mcp.md](04-platform-mcp.md) | Fabric/NeoForge loader modules, client-side observer, Kotlin MCP server |
| 5 | [05-testing.md](05-testing.md) | Unit tests, integration tests (mock WebSocket), game tests (fabric-gametest-api) |

## Implementation Order

```
Phase 1: Scaffolding
  └─ Task 1.1: Root Gradle config (settings, build, properties, version catalog)
  └─ Task 1.2: buildSrc convention plugins (parrot-common, parrot-loader)
  └─ Task 1.3: Protocol module build
  └─ Task 1.4: Common module build (NeoForm-only mode)
  └─ Task 1.5: Fabric module build (Loom + kotlin adapter)
  └─ Task 1.6: NeoForge module build (ModDevGradle + jarJar)
  └─ Task 1.7: MCP server module build (Shadow JAR)
  └─ Task 1.8: GitHub Actions CI

Phase 2: Protocol + WebSocket (depends on Phase 1)
  └─ Task 2.1: Protocol message types (sealed class hierarchy)
  └─ Task 2.2: ParrotJson singleton + serialization config
  └─ Task 2.3: WebSocket server (Netty pipeline, auth, single connection)
  └─ Task 2.4: Thread safety layer (CommandQueue, ConsequenceCollector)
  └─ Task 2.5: Connection file manager (~/.parrot/connection.json)

Phase 3: Commands (depends on Phase 2)
  └─ Task 3.1: Command framework (CommandHandler, CommandContext, CommandRegistry)
  └─ Task 3.2: JSON helper utilities (BlockState/ItemStack/Entity -> JsonObject)
  └─ Task 3.3: Query handlers (get_block, get_blocks_area, get_world_info, get_player,
                                get_inventory, get_entities, get_entity, get_screen)
  └─ Task 3.4: Action handlers (interact_block, attack_block, interact_entity,
                                 attack_entity, click_slot, close_screen, set_held_slot, send_chat)
  └─ Task 3.5: RunCommandHandler (run_command)
  └─ Task 3.6: BatchHandler (batch)
  └─ Task 3.7: Event subscription system (SubscriptionManager, EventBridge, 14 event types)

Phase 4: Platform + MCP Server (depends on Phase 2, partially parallel with Phase 3)
  └─ Task 4.1: ParrotEngine (central coordinator in mod/common)
  └─ Task 4.2: PlatformBridge + ScreenReader interfaces
  └─ Task 4.3: Fabric module (ParrotFabric, FabricPlatformBridge, FabricScreenReader)
  └─ Task 4.4: NeoForge module (ParrotNeoForge, NeoForgePlatformBridge, NeoForgeScreenReader)
  └─ Task 4.5: MCP server (Main, Config, MinecraftBridge, ToolRegistrar, ResourceRegistrar)

Phase 5: Testing (depends on Phases 3 + 4)
  └─ Task 5.1: Protocol serialization tests
  └─ Task 5.2: MCP server unit tests (config, validation, backoff, degradation)
  └─ Task 5.3: Integration tests (MockMinecraftServer + bridge + E2E MCP tools)
  └─ Task 5.4: Game tests (fabric-gametest-api: lifecycle, get_block, interact_block, events)

## References

- [Project Specification](../plans/2026-03-10-minecraft-mcp-spec.md)
- [Exploration Documents](../explore/)
```

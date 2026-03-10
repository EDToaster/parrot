# Phase 4: Platform Modules + MCP Server

**Goal:** Implement the Fabric and NeoForge loader modules (lifecycle, event registration, client-side screen observation), the shared ParrotEngine coordinator, and the standalone Kotlin MCP server process.

**Architecture:** Each loader module provides a thin entry point that delegates to ParrotEngine (in mod/common) via a PlatformBridge interface. Client-side screen observation uses ScreenEvents (Fabric) / ScreenEvent (NeoForge). In singleplayer, screen data flows via in-memory ObservationBus (no network packets). The MCP server uses Ktor CIO WebSocket client and the official MCP Kotlin SDK for stdio transport.

---

## Task 4.1: ParrotEngine (mod/common)

**File:** `mod/common/src/main/kotlin/dev/parrot/mod/engine/ParrotEngine.kt`

Central coordinator singleton. Owns WebSocket server, command queue, registry, subscription manager. Receives a PlatformBridge from each loader.

```kotlin
package dev.parrot.mod.engine

import dev.parrot.mod.commands.CommandContext
import dev.parrot.mod.commands.CommandRegistry
import dev.parrot.mod.commands.createCommandRegistry
import dev.parrot.mod.engine.bridge.PlatformBridge
import dev.parrot.mod.engine.bridge.ScreenObservation
import dev.parrot.mod.events.SubscriptionManager
import dev.parrot.mod.server.ConnectionFileManager
import dev.parrot.mod.server.ParrotWebSocketServer
import net.minecraft.server.MinecraftServer
import java.util.concurrent.atomic.AtomicReference

object ParrotEngine {
    private val webSocketServer = AtomicReference<ParrotWebSocketServer?>(null)
    private val commandQueue = AtomicReference<CommandQueue?>(null)
    private val subscriptionManager = AtomicReference<SubscriptionManager?>(null)
    private val platformBridge = AtomicReference<PlatformBridge?>(null)

    fun start(server: MinecraftServer, bridge: PlatformBridge) {
        val subMgr = SubscriptionManager()
        val registry = createCommandRegistry(subMgr)
        val queue = CommandQueue(registry)

        subscriptionManager.set(subMgr)
        commandQueue.set(queue)
        platformBridge.set(bridge)
        bridge.registerEventListeners(subMgr)

        val wsServer = ParrotWebSocketServer(queue, subMgr)
        wsServer.start()
        webSocketServer.set(wsServer)
        ConnectionFileManager.write(wsServer.port, wsServer.token)
    }

    fun stop() {
        webSocketServer.getAndSet(null)?.stop()
        commandQueue.set(null)
        subscriptionManager.set(null)
        platformBridge.set(null)
        ConnectionFileManager.delete()
    }

    fun tick(server: MinecraftServer) {
        commandQueue.get()?.drainAndExecute(server, server.tickCount)
    }

    fun onScreenObservation(observation: ScreenObservation) {
        subscriptionManager.get()?.handleScreenObservation(observation)
    }
}
```

### Commit

```bash
git add mod/common/src/main/kotlin/dev/parrot/mod/engine/ParrotEngine.kt
git commit -m "feat: add ParrotEngine central coordinator"
```

---

## Task 4.2: PlatformBridge + ScreenReader Interfaces

**Files:**
- Create: `mod/common/src/main/kotlin/dev/parrot/mod/engine/bridge/PlatformBridge.kt`
- Create: `mod/common/src/main/kotlin/dev/parrot/mod/engine/bridge/ScreenReader.kt`
- Create: `mod/common/src/main/kotlin/dev/parrot/mod/engine/bridge/ScreenState.kt`

```kotlin
// PlatformBridge.kt
package dev.parrot.mod.engine.bridge
import dev.parrot.mod.events.SubscriptionManager

interface PlatformBridge {
    fun registerEventListeners(subscriptionManager: SubscriptionManager)
    fun getScreenReader(): ScreenReader?
}
```

```kotlin
// ScreenReader.kt
package dev.parrot.mod.engine.bridge

interface ScreenReader {
    fun getCurrentScreen(): ScreenState?
}
```

```kotlin
// ScreenState.kt
package dev.parrot.mod.engine.bridge

data class ScreenState(val isOpen: Boolean, val screenClass: String, val screenType: String?, val title: String?, val menuType: String?, val slots: List<SlotState>, val widgets: List<WidgetState>)
data class SlotState(val index: Int, val item: String, val count: Int)
data class WidgetState(val type: String, val x: Int, val y: Int, val width: Int, val height: Int, val message: String?, val active: Boolean)
data class ScreenObservation(val type: ScreenObservationType, val screenState: ScreenState?, val tick: Long)
enum class ScreenObservationType { OPENED, CLOSED, CONTENTS_CHANGED }
```

### Commit

```bash
git add mod/common/src/main/kotlin/dev/parrot/mod/engine/bridge/
git commit -m "feat: add PlatformBridge and ScreenReader interfaces"
```

---

## Task 4.3: Fabric Module

**Files:**
- Modify: `mod/fabric/src/main/kotlin/dev/parrot/mod/fabric/ParrotFabric.kt`
- Create: `mod/fabric/src/main/kotlin/dev/parrot/mod/fabric/FabricPlatformBridge.kt`
- Modify: `mod/fabric/src/client/kotlin/dev/parrot/mod/fabric/client/ParrotFabricClient.kt`
- Create: `mod/fabric/src/client/kotlin/dev/parrot/mod/fabric/client/FabricScreenReader.kt`

### ParrotFabric.kt (server-side)

```kotlin
package dev.parrot.mod.fabric
import dev.parrot.mod.engine.ParrotEngine
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents

object ParrotFabric : ModInitializer {
    override fun onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register { server -> ParrotEngine.start(server, FabricPlatformBridge()) }
        ServerLifecycleEvents.SERVER_STOPPING.register { _ -> ParrotEngine.stop() }
        ServerTickEvents.END_SERVER_TICK.register { server -> ParrotEngine.tick(server) }
    }
}
```

### FabricPlatformBridge.kt

Uses `companion object` with `@Volatile var clientScreenReader` for singleplayer in-memory bridge.

### ParrotFabricClient.kt (client source set)

Registers `ScreenEvents.AFTER_INIT` and `ScreenEvents.remove(screen)`. In singleplayer (`hasSingleplayerServer()`), calls `ParrotEngine.onScreenObservation()` directly.

### FabricScreenReader.kt

Reads `Minecraft.getInstance().screen`, extracts slots from `AbstractContainerScreen.menu`, widgets from `screen.renderables`.

### Commit

```bash
git add mod/fabric/
git commit -m "feat: implement Fabric module with lifecycle events and screen observation"
```

---

## Task 4.4: NeoForge Module

**Files:**
- Modify: `mod/neoforge/src/main/kotlin/dev/parrot/mod/neoforge/ParrotNeoForge.kt`
- Create: `mod/neoforge/src/main/kotlin/dev/parrot/mod/neoforge/NeoForgePlatformBridge.kt`
- Create: `mod/neoforge/src/main/kotlin/dev/parrot/mod/neoforge/client/ParrotNeoForgeClient.kt`
- Create: `mod/neoforge/src/main/kotlin/dev/parrot/mod/neoforge/client/NeoForgeScreenReader.kt`

### ParrotNeoForge.kt

```kotlin
@Mod("parrot")
class ParrotNeoForge(modBus: IEventBus) {
    init {
        val gameBus = NeoForge.EVENT_BUS
        gameBus.addListener(::onServerStarted)
        gameBus.addListener(::onServerStopping)
        gameBus.addListener(::onServerTick)
    }
    private fun onServerStarted(event: ServerStartedEvent) { ParrotEngine.start(event.server, NeoForgePlatformBridge()) }
    private fun onServerStopping(event: ServerStoppingEvent) { ParrotEngine.stop() }
    private fun onServerTick(event: ServerTickEvent.Post) { ParrotEngine.tick(event.server) }
}
```

### ParrotNeoForgeClient.kt

```kotlin
@Mod(value = "parrot", dist = [Dist.CLIENT])
class ParrotNeoForgeClient(modBus: IEventBus) {
    init {
        NeoForgePlatformBridge.clientScreenReader = NeoForgeScreenReader()
        NeoForge.EVENT_BUS.addListener(::onScreenOpen)
        NeoForge.EVENT_BUS.addListener(::onScreenClose)
    }
    // ScreenEvent.Opening / ScreenEvent.Closing handlers
}
```

### Commit

```bash
git add mod/neoforge/
git commit -m "feat: implement NeoForge module with lifecycle events and screen observation"
```

---

## Task 4.5: Kotlin MCP Server

**Files:**
- Modify: `mcp-server/src/main/kotlin/dev/parrot/mcp/Main.kt`
- Create: `mcp-server/src/main/kotlin/dev/parrot/mcp/Config.kt`
- Create: `mcp-server/src/main/kotlin/dev/parrot/mcp/MinecraftBridge.kt`
- Create: `mcp-server/src/main/kotlin/dev/parrot/mcp/ToolRegistrar.kt`
- Create: `mcp-server/src/main/kotlin/dev/parrot/mcp/ResourceRegistrar.kt`

### Main.kt

```kotlin
fun main() = runBlocking {
    System.err.println("[parrot-mcp] Starting Parrot MCP Server...")
    val config = Config.discover()
    val bridge = MinecraftBridge(config)
    val server = Server(
        serverInfo = Implementation(name = "parrot-mcp", version = "0.1.0"),
        options = ServerOptions(capabilities = ServerCapabilities(
            tools = ServerCapabilities.Tools(listChanged = true),
            resources = ServerCapabilities.Resources(subscribe = true, listChanged = true)
        ))
    )
    ToolRegistrar.registerAll(server, bridge)
    ResourceRegistrar.registerAll(server, bridge)
    launch { bridge.connectWithRetry() }
    val transport = StdioServerTransport(System.`in`.asSource().buffered(), System.out.asSink().buffered())
    server.connect(transport)
    val done = Job()
    server.onClose { done.complete() }
    done.join()
}
```

### Config.kt

Discovery priority: `PARROT_PORT` env var > `~/.parrot/connection.json` > default 25566.

### MinecraftBridge.kt

Ktor CIO WebSocket client with:
- Auto-reconnect with exponential backoff (1s, 2s, 4s, 8s, max 30s)
- Hello/hello_ack handshake
- Pending requests via `ConcurrentHashMap<String, CompletableDeferred<JsonObject>>`
- Ping/pong handling
- All pending requests failed on disconnect

### ToolRegistrar.kt

Registers 21 MCP tools:
- 8 query tools (get_*) -> message type "query"
- 8 action tools (do_*) -> message type "action" (strips `do_` prefix for method name)
- 1 command tool (run_command) -> message type "command"
- 1 batch tool -> message type "query"
- 2 event tools (subscribe/unsubscribe)

Each tool: defines JSON Schema inputSchema, sends via bridge, returns CallToolResult.

### ResourceRegistrar.kt

5 resources: `minecraft://world/info`, `minecraft://player/info`, `minecraft://player/inventory`, `minecraft://screen/current`, `minecraft://logs/recent`. Each delegates to the corresponding query command.

### Commit

```bash
git add mcp-server/
git commit -m "feat: implement Kotlin MCP server with tool/resource registration and WebSocket bridge"
```

---

## Risks

| Risk | Mitigation |
|------|------------|
| MCP Kotlin SDK API differences | Verify against SDK 0.9.0 source; adjust Server.addTool() signatures |
| NeoForge @Mod(dist) Kotlin syntax | Use `dist = [Dist.CLIENT]` array syntax |
| Screen.renderables access | Fallback to Fabric's Screens.getButtons() or @Accessor Mixin |
| Ktor version conflicts with MCP SDK | Align Ktor dependency version with SDK's transitive deps |

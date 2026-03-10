# Phase 2: Protocol Types + WebSocket Server + Thread Safety + Connection File

**Goal:** Build the foundational communication layer: shared protocol data classes, embedded Netty WebSocket server, thread-safe command queue, and connection file manager.

**Architecture:** The `:protocol` module defines all message types as `@Serializable` sealed classes using kotlinx.serialization with `classDiscriminator = "type"`. The WebSocket server in `mod/common` uses MC-bundled Netty with daemon threads, bound to 127.0.0.1. The command queue bridges Netty I/O threads to the main game thread via `ConcurrentLinkedQueue` + `CompletableFuture`.

---

## Task 2.1: Protocol Message Types

**Files:**
- Create: `protocol/src/main/kotlin/dev/parrot/protocol/ParrotMessage.kt`
- Create: `protocol/src/main/kotlin/dev/parrot/protocol/SupportingTypes.kt`
- Create: `protocol/src/main/kotlin/dev/parrot/protocol/ParrotJson.kt`
- Test: `protocol/src/test/kotlin/dev/parrot/protocol/SerializationTest.kt`

### Step 1: Create ParrotMessage.kt

All messages use a sealed class hierarchy with `@SerialName` mapping to the `type` discriminator.

```kotlin
package dev.parrot.protocol

import kotlinx.serialization.*
import kotlinx.serialization.json.JsonObject

@Serializable
sealed class ParrotMessage {
    abstract val id: String
}

// --- Client -> Server ---

@Serializable @SerialName("hello")
data class Hello(
    override val id: String,
    val protocolVersion: Int = 1,
    val authToken: String
) : ParrotMessage()

@Serializable @SerialName("ping")
data class Ping(override val id: String, val timestamp: Long) : ParrotMessage()

@Serializable @SerialName("pong")
data class Pong(override val id: String, val timestamp: Long) : ParrotMessage()

@Serializable @SerialName("goodbye")
data class Goodbye(override val id: String) : ParrotMessage()

@Serializable @SerialName("action")
data class ActionRequest(
    override val id: String,
    val method: String,
    val params: JsonObject = JsonObject(emptyMap()),
    val consequenceWait: Int = 5,
    val consequenceFilter: List<String>? = null
) : ParrotMessage()

@Serializable @SerialName("query")
data class QueryRequest(
    override val id: String,
    val method: String,
    val params: JsonObject = JsonObject(emptyMap())
) : ParrotMessage()

@Serializable @SerialName("command")
data class CommandRequest(
    override val id: String,
    val command: String,
    val consequenceWait: Int = 3
) : ParrotMessage()

@Serializable @SerialName("subscribe")
data class SubscribeRequest(
    override val id: String,
    val eventTypes: List<String>,
    val filter: JsonObject? = null
) : ParrotMessage()

@Serializable @SerialName("unsubscribe")
data class UnsubscribeRequest(
    override val id: String,
    val subscriptionId: String
) : ParrotMessage()

@Serializable @SerialName("batch")
data class BatchRequest(
    override val id: String,
    val commands: List<BatchCommand>
) : ParrotMessage()

// --- Server -> Client ---

@Serializable @SerialName("hello_ack")
data class HelloAck(
    override val id: String,
    val protocolVersion: Int = 1,
    val minecraftVersion: String,
    val modLoader: String,
    val modVersion: String,
    val capabilities: List<String>,
    val tickRate: Int = 20,
    val serverType: String
) : ParrotMessage()

@Serializable @SerialName("goodbye_ack")
data class GoodbyeAck(override val id: String) : ParrotMessage()

@Serializable @SerialName("action_result")
data class ActionResult(
    override val id: String,
    val success: Boolean,
    val tick: Long,
    val result: JsonObject = JsonObject(emptyMap()),
    val consequences: List<Consequence> = emptyList()
) : ParrotMessage()

@Serializable @SerialName("query_result")
data class QueryResult(
    override val id: String,
    val tick: Long,
    val result: JsonObject
) : ParrotMessage()

@Serializable @SerialName("command_result")
data class CommandResult(
    override val id: String,
    val success: Boolean,
    val tick: Long,
    val output: String,
    val returnValue: Int? = null,
    val consequences: List<Consequence> = emptyList()
) : ParrotMessage()

@Serializable @SerialName("subscribe_ack")
data class SubscribeAck(
    override val id: String,
    val subscriptionId: String,
    val subscribedEvents: List<String>
) : ParrotMessage()

@Serializable @SerialName("unsubscribe_ack")
data class UnsubscribeAck(
    override val id: String,
    val success: Boolean
) : ParrotMessage()

@Serializable @SerialName("event")
data class PushEvent(
    override val id: String = "",
    val subscriptionId: String,
    val tick: Long,
    val eventType: String,
    val data: JsonObject
) : ParrotMessage()

@Serializable @SerialName("error")
data class ErrorResponse(
    override val id: String,
    val code: String,
    val message: String,
    val details: JsonObject? = null
) : ParrotMessage()

@Serializable @SerialName("batch_result")
data class BatchResult(
    override val id: String,
    val results: List<JsonObject>
) : ParrotMessage()
```

### Step 2: Create SupportingTypes.kt

```kotlin
package dev.parrot.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Consequence(
    val type: String,
    val tick: Long,
    val data: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class BatchCommand(
    val method: String,
    val params: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class ConnectionInfo(
    val port: Int,
    val token: String,
    val pid: Long? = null
)

enum class ErrorCode(val code: String) {
    INVALID_REQUEST("INVALID_REQUEST"),
    UNKNOWN_METHOD("UNKNOWN_METHOD"),
    INVALID_PARAMS("INVALID_PARAMS"),
    AUTH_FAILED("AUTH_FAILED"),
    NOT_AUTHENTICATED("NOT_AUTHENTICATED"),
    BLOCK_OUT_OF_RANGE("BLOCK_OUT_OF_RANGE"),
    ENTITY_NOT_FOUND("ENTITY_NOT_FOUND"),
    NO_SCREEN_OPEN("NO_SCREEN_OPEN"),
    INVALID_SLOT("INVALID_SLOT"),
    COMMAND_FAILED("COMMAND_FAILED"),
    TIMEOUT("TIMEOUT"),
    INTERNAL_ERROR("INTERNAL_ERROR"),
    RATE_LIMITED("RATE_LIMITED")
}
```

### Step 3: Create ParrotJson.kt

```kotlin
package dev.parrot.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

val ParrotJson = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
    serializersModule = SerializersModule {
        polymorphic(ParrotMessage::class) {
            subclass(Hello::class)
            subclass(Ping::class)
            subclass(Pong::class)
            subclass(Goodbye::class)
            subclass(ActionRequest::class)
            subclass(QueryRequest::class)
            subclass(CommandRequest::class)
            subclass(SubscribeRequest::class)
            subclass(UnsubscribeRequest::class)
            subclass(BatchRequest::class)
            subclass(HelloAck::class)
            subclass(GoodbyeAck::class)
            subclass(ActionResult::class)
            subclass(QueryResult::class)
            subclass(CommandResult::class)
            subclass(SubscribeAck::class)
            subclass(UnsubscribeAck::class)
            subclass(PushEvent::class)
            subclass(ErrorResponse::class)
            subclass(BatchResult::class)
        }
    }
}
```

### Step 4: Write serialization tests

```kotlin
package dev.parrot.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SerializationTest {
    @Test
    fun `Hello round-trips through JSON`() {
        val msg = Hello(id = "1", protocolVersion = 1, authToken = "abc123")
        val json = ParrotJson.encodeToString(ParrotMessage.serializer(), msg)
        val decoded = ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
        assertEquals(msg, decoded)
    }

    @Test
    fun `polymorphic deserialization resolves correct type`() {
        val json = """{"type":"hello","id":"1","protocolVersion":1,"authToken":"test"}"""
        val msg = ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
        assertIs<Hello>(msg)
        assertEquals("test", msg.authToken)
    }

    @Test
    fun `ActionRequest with defaults round-trips`() {
        val msg = ActionRequest(id = "7", method = "interact_block")
        val json = ParrotJson.encodeToString(ParrotMessage.serializer(), msg)
        val decoded = ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
        assertIs<ActionRequest>(decoded)
        assertEquals(5, decoded.consequenceWait)
        assertEquals(null, decoded.consequenceFilter)
    }

    @Test
    fun `ErrorResponse round-trips with details`() {
        val details = buildJsonObject { put("position", "100,64,-200") }
        val msg = ErrorResponse(id = "7", code = "BLOCK_OUT_OF_RANGE", message = "Not loaded", details = details)
        val json = ParrotJson.encodeToString(ParrotMessage.serializer(), msg)
        val decoded = ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
        assertEquals(msg, decoded)
    }

    @Test
    fun `ConnectionInfo serializes correctly`() {
        val info = ConnectionInfo(port = 25566, token = "abc", pid = 12345)
        val json = ParrotJson.encodeToString(ConnectionInfo.serializer(), info)
        val decoded = ParrotJson.decodeFromString(ConnectionInfo.serializer(), json)
        assertEquals(info, decoded)
    }

    @Test
    fun `BatchRequest round-trips`() {
        val msg = BatchRequest(id = "1", commands = listOf(
            BatchCommand(method = "get_block", params = buildJsonObject { put("x", 1); put("y", 2); put("z", 3) }),
            BatchCommand(method = "get_player")
        ))
        val json = ParrotJson.encodeToString(ParrotMessage.serializer(), msg)
        val decoded = ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
        assertIs<BatchRequest>(decoded)
        assertEquals(2, decoded.commands.size)
    }

    @Test
    fun `Consequence serializes with data`() {
        val c = Consequence(type = "screen_opened", tick = 50001, data = buildJsonObject { put("title", "Chest") })
        val json = ParrotJson.encodeToString(Consequence.serializer(), c)
        val decoded = ParrotJson.decodeFromString(Consequence.serializer(), json)
        assertEquals(c, decoded)
    }
}
```

### Step 5: Verify

Run: `./gradlew :protocol:test`
Expected: All tests pass

### Step 6: Commit

```bash
git add protocol/
git commit -m "feat: add protocol message types with sealed class hierarchy and serialization tests"
```

---

## Task 2.2: WebSocket Server

**Files:**
- Create: `mod/common/src/main/kotlin/dev/parrot/mod/server/ParrotWebSocketServer.kt`
- Create: `mod/common/src/main/kotlin/dev/parrot/mod/server/WebSocketServerInitializer.kt`
- Create: `mod/common/src/main/kotlin/dev/parrot/mod/server/ParrotMessageHandler.kt`
- Create: `mod/common/src/main/kotlin/dev/parrot/mod/server/ClientSession.kt`

### Step 1: Create ParrotWebSocketServer.kt

```kotlin
package dev.parrot.mod.server

import dev.parrot.mod.engine.CommandQueue
import dev.parrot.mod.events.SubscriptionManager
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.util.concurrent.DefaultThreadFactory
import java.net.InetSocketAddress
import java.util.UUID

class ParrotWebSocketServer(
    private val commandQueue: CommandQueue,
    private val subscriptionManager: SubscriptionManager
) {
    private val bossGroup = NioEventLoopGroup(1, DefaultThreadFactory("parrot-boss", true))
    private val workerGroup = NioEventLoopGroup(1, DefaultThreadFactory("parrot-worker", true))
    private var channel: Channel? = null

    val token: String = UUID.randomUUID().toString().replace("-", "")
    var port: Int = 0; private set
    var currentSession: ClientSession? = null

    fun start(requestedPort: Int = 25566) {
        val bootstrap = ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(WebSocketServerInitializer(this, commandQueue, subscriptionManager))

        val bindFuture = bootstrap.bind(InetSocketAddress("127.0.0.1", requestedPort)).sync()
        channel = bindFuture.channel()
        port = (channel!!.localAddress() as InetSocketAddress).port
    }

    fun stop() {
        currentSession?.channel?.close()
        channel?.close()?.sync()
        bossGroup.shutdownGracefully(0, 2, java.util.concurrent.TimeUnit.SECONDS)
        workerGroup.shutdownGracefully(0, 2, java.util.concurrent.TimeUnit.SECONDS)
    }

    fun onNewSession(session: ClientSession) {
        currentSession?.channel?.close()
        currentSession = session
    }

    fun onSessionClosed(session: ClientSession) {
        if (currentSession == session) {
            currentSession = null
            subscriptionManager.cleanupChannel(session.channelId)
        }
    }
}
```

### Step 2: Create WebSocketServerInitializer.kt

```kotlin
package dev.parrot.mod.server

import dev.parrot.mod.engine.CommandQueue
import dev.parrot.mod.events.SubscriptionManager
import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler

class WebSocketServerInitializer(
    private val server: ParrotWebSocketServer,
    private val commandQueue: CommandQueue,
    private val subscriptionManager: SubscriptionManager
) : ChannelInitializer<SocketChannel>() {
    override fun initChannel(ch: SocketChannel) {
        ch.pipeline().addLast(
            HttpServerCodec(),
            HttpObjectAggregator(65536),
            WebSocketServerProtocolHandler("/"),
            ParrotMessageHandler(server, commandQueue, subscriptionManager)
        )
    }
}
```

### Step 3: Create ParrotMessageHandler.kt

```kotlin
package dev.parrot.mod.server

import dev.parrot.mod.engine.CommandQueue
import dev.parrot.mod.events.SubscriptionManager
import dev.parrot.protocol.*
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketFrame
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class ParrotMessageHandler(
    private val server: ParrotWebSocketServer,
    private val commandQueue: CommandQueue,
    private val subscriptionManager: SubscriptionManager
) : SimpleChannelInboundHandler<WebSocketFrame>() {

    private var session: ClientSession? = null
    private var authTimeout: ScheduledFuture<*>? = null

    override fun channelActive(ctx: ChannelHandlerContext) {
        val newSession = ClientSession(ctx.channel())
        session = newSession
        // 5-second auth timeout
        authTimeout = ctx.executor().schedule({
            if (session?.authenticated != true) {
                sendMessage(ctx, ErrorResponse(id = "0", code = ErrorCode.AUTH_FAILED.code, message = "Auth timeout"))
                ctx.close()
            }
        }, 5, TimeUnit.SECONDS)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        authTimeout?.cancel(false)
        session?.let { server.onSessionClosed(it) }
        session = null
    }

    override fun channelRead0(ctx: ChannelHandlerContext, frame: WebSocketFrame) {
        if (frame !is TextWebSocketFrame) return
        val text = frame.text()

        val message = try {
            ParrotJson.decodeFromString(ParrotMessage.serializer(), text)
        } catch (e: Exception) {
            sendMessage(ctx, ErrorResponse(id = "0", code = ErrorCode.INVALID_REQUEST.code, message = "Malformed JSON: ${e.message}"))
            return
        }

        val currentSession = session ?: return

        if (!currentSession.authenticated) {
            if (message is Hello) {
                if (message.authToken == server.token) {
                    currentSession.authenticated = true
                    authTimeout?.cancel(false)
                    server.onNewSession(currentSession)
                    sendMessage(ctx, HelloAck(
                        id = message.id,
                        minecraftVersion = "1.21.10",
                        modLoader = "unknown", // Set by platform bridge
                        modVersion = "0.1.0",
                        capabilities = listOf("actions", "queries", "events", "gui_observation"),
                        serverType = "integrated"
                    ))
                } else {
                    sendMessage(ctx, ErrorResponse(id = message.id, code = ErrorCode.AUTH_FAILED.code, message = "Invalid token"))
                    ctx.close()
                }
            } else {
                sendMessage(ctx, ErrorResponse(id = message.id, code = ErrorCode.NOT_AUTHENTICATED.code, message = "Send hello first"))
            }
            return
        }

        // Authenticated message routing
        when (message) {
            is Ping -> sendMessage(ctx, Pong(id = message.id, timestamp = message.timestamp))
            is Goodbye -> { sendMessage(ctx, GoodbyeAck(id = message.id)); ctx.close() }
            is ActionRequest, is QueryRequest, is CommandRequest,
            is BatchRequest, is SubscribeRequest, is UnsubscribeRequest -> {
                commandQueue.enqueue(message, ctx.channel()).thenAccept { response ->
                    sendMessage(ctx, response)
                }
            }
            else -> sendMessage(ctx, ErrorResponse(id = message.id, code = ErrorCode.INVALID_REQUEST.code, message = "Unexpected message type"))
        }
    }

    private fun sendMessage(ctx: ChannelHandlerContext, message: ParrotMessage) {
        if (ctx.channel().isActive) {
            val json = ParrotJson.encodeToString(ParrotMessage.serializer(), message)
            ctx.writeAndFlush(TextWebSocketFrame(json))
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        ctx.close()
    }
}
```

### Step 4: Create ClientSession.kt

```kotlin
package dev.parrot.mod.server

import io.netty.channel.Channel

class ClientSession(
    val channel: Channel,
    var authenticated: Boolean = false,
    val channelId: String = channel.id().asShortText(),
    val connectedAt: Long = System.currentTimeMillis()
)
```

### Step 5: Commit

```bash
git add mod/common/src/main/kotlin/dev/parrot/mod/server/
git commit -m "feat: add Netty WebSocket server with auth and single-connection enforcement"
```

---

## Task 2.3: Thread Safety Layer

**Files:**
- Create: `mod/common/src/main/kotlin/dev/parrot/mod/engine/CommandQueue.kt`
- Create: `mod/common/src/main/kotlin/dev/parrot/mod/engine/ConsequenceCollector.kt`
- Create: `mod/common/src/main/kotlin/dev/parrot/mod/engine/PendingCommand.kt`

### Step 1: Create PendingCommand.kt

```kotlin
package dev.parrot.mod.engine

import dev.parrot.protocol.ParrotMessage
import io.netty.channel.Channel
import java.util.concurrent.CompletableFuture

data class PendingCommand(
    val message: ParrotMessage,
    val channel: Channel,
    val future: CompletableFuture<ParrotMessage>,
    val submittedTime: Long = System.currentTimeMillis()
)
```

### Step 2: Create CommandQueue.kt

```kotlin
package dev.parrot.mod.engine

import dev.parrot.protocol.*
import io.netty.channel.Channel
import net.minecraft.server.MinecraftServer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue

class CommandQueue(
    private val registry: Any? = null, // CommandRegistry, typed as Any to avoid circular dep during scaffolding
    private val maxPerTick: Int = 20
) {
    private val queue = ConcurrentLinkedQueue<PendingCommand>()

    fun enqueue(message: ParrotMessage, channel: Channel): CompletableFuture<ParrotMessage> {
        val future = CompletableFuture<ParrotMessage>()
        queue.add(PendingCommand(message, channel, future))
        return future
    }

    fun drainAndExecute(server: MinecraftServer, tickCount: Long) {
        var executed = 0
        while (executed < maxPerTick) {
            val pending = queue.poll() ?: break
            try {
                // Dispatch to command registry (implemented in Phase 3)
                val response = dispatch(pending.message, server, tickCount)
                pending.future.complete(response)
            } catch (e: Exception) {
                pending.future.complete(ErrorResponse(
                    id = pending.message.id,
                    code = ErrorCode.INTERNAL_ERROR.code,
                    message = e.message ?: "Internal error"
                ))
            }
            executed++
        }
    }

    private fun dispatch(message: ParrotMessage, server: MinecraftServer, tickCount: Long): ParrotMessage {
        // Placeholder -- Phase 3 wires this to CommandRegistry
        return ErrorResponse(
            id = message.id,
            code = ErrorCode.UNKNOWN_METHOD.code,
            message = "Command dispatch not yet implemented"
        )
    }
}
```

### Step 3: Create ConsequenceCollector.kt

```kotlin
package dev.parrot.mod.engine

import dev.parrot.protocol.Consequence
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

class ConsequenceCollector {
    private val activeHandles = CopyOnWriteArrayList<CollectionHandle>()

    fun startCollecting(
        startTick: Long,
        tickWindow: Int,
        filter: List<String>?,
        originX: Int? = null, originY: Int? = null, originZ: Int? = null
    ): CollectionHandle {
        val handle = CollectionHandle(
            deadline = startTick + tickWindow,
            wallClockDeadline = System.currentTimeMillis() + (tickWindow * 100L),
            filter = filter,
            originX = originX, originY = originY, originZ = originZ
        )
        activeHandles.add(handle)
        return handle
    }

    fun onConsequence(type: String, tick: Long, data: JsonObject, x: Int? = null, y: Int? = null, z: Int? = null) {
        for (handle in activeHandles) {
            if (handle.future.isDone) continue
            if (handle.filter != null && type !in handle.filter) continue
            // Spatial filtering (8-block radius)
            if (handle.originX != null && x != null) {
                val dx = x - handle.originX
                val dy = (y ?: 0) - (handle.originY ?: 0)
                val dz = (z ?: 0) - (handle.originZ ?: 0)
                if (dx * dx + dy * dy + dz * dz > 64) continue // 8^2
            }
            handle.consequences.add(Consequence(type, tick, data))
        }
    }

    fun tick(currentTick: Long) {
        val now = System.currentTimeMillis()
        val iterator = activeHandles.iterator()
        while (iterator.hasNext()) {
            val handle = iterator.next()
            if (currentTick >= handle.deadline || now >= handle.wallClockDeadline) {
                handle.complete()
                activeHandles.remove(handle)
            }
        }
    }
}

class CollectionHandle(
    val deadline: Long,
    val wallClockDeadline: Long,
    val filter: List<String>?,
    val originX: Int?,
    val originY: Int?,
    val originZ: Int?,
    val spatialRadius: Int = 8
) {
    val consequences = mutableListOf<Consequence>()
    val future = CompletableFuture<List<Consequence>>()

    fun complete() {
        future.complete(consequences.toList())
    }
}
```

### Step 4: Commit

```bash
git add mod/common/src/main/kotlin/dev/parrot/mod/engine/
git commit -m "feat: add thread-safe command queue and consequence collector"
```

---

## Task 2.4: Connection File Manager

**Files:**
- Create: `mod/common/src/main/kotlin/dev/parrot/mod/server/ConnectionFileManager.kt`

### Step 1: Create ConnectionFileManager.kt

```kotlin
package dev.parrot.mod.server

import dev.parrot.protocol.ConnectionInfo
import dev.parrot.protocol.ParrotJson
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

object ConnectionFileManager {
    private val connectionDir: Path = Path.of(System.getProperty("user.home"), ".parrot")
    private val connectionFile: Path = connectionDir.resolve("connection.json")

    fun write(port: Int, token: String) {
        Files.createDirectories(connectionDir)
        val info = ConnectionInfo(port = port, token = token, pid = ProcessHandle.current().pid())
        val json = ParrotJson.encodeToString(ConnectionInfo.serializer(), info)
        val tempFile = Files.createTempFile(connectionDir, "connection", ".tmp")
        try {
            Files.writeString(tempFile, json, StandardCharsets.UTF_8)
            Files.move(tempFile, connectionFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tempFile, connectionFile, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Files.deleteIfExists(tempFile)
            throw e
        }
    }

    fun read(): ConnectionInfo? {
        if (!Files.exists(connectionFile)) return null
        val json = Files.readString(connectionFile, StandardCharsets.UTF_8)
        return ParrotJson.decodeFromString(ConnectionInfo.serializer(), json)
    }

    fun delete() {
        Files.deleteIfExists(connectionFile)
    }
}
```

### Step 2: Commit

```bash
git add mod/common/src/main/kotlin/dev/parrot/mod/server/ConnectionFileManager.kt
git commit -m "feat: add connection file manager with atomic write to ~/.parrot/"
```

---

## Dependency Order

1. **Task 2.1** (Protocol types) -- no dependencies, must be first
2. **Task 2.4** (Connection file) -- depends only on :protocol, can parallel with 2.2/2.3
3. **Task 2.3** (Thread safety) -- depends on :protocol
4. **Task 2.2** (WebSocket server) -- depends on :protocol and Task 2.3 (CommandQueue)

## Risks

| Risk | Mitigation |
|------|------------|
| kotlinx.serialization polymorphic dispatch with `classDiscriminator = "type"` | Explicit registration of every subclass in SerializersModule; test every type |
| Netty version compatibility with MC 1.21.x | MC ships Netty 4.1.x; WebSocketServerProtocolHandler API stable since 4.0 |
| Testing WebSocket server without MC | Server uses only Netty APIs; tests can run as pure JVM tests |
| Atomic file operations on Windows | Catch `AtomicMoveNotSupportedException`, fallback to non-atomic move |

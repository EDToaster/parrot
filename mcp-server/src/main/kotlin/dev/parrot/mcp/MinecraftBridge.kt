package dev.parrot.mcp

import dev.parrot.protocol.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean

data class GameInfo(
    val minecraftVersion: String,
    val modLoader: String,
    val modVersion: String,
    val serverType: String
)

class MinecraftBridge(@Volatile var config: ParrotConfig) {
    private val _connected = AtomicBoolean(false)
    val isConnected: Boolean get() = _connected.get()

    @Volatile var gameInfo: GameInfo? = null
        private set

    private var retryJob: Job? = null

    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private var session: WebSocketSession? = null

    /**
     * Buffered push events received from the mod, keyed by subscriptionId.
     * Each subscription holds at most [MAX_BUFFERED_EVENTS] events (oldest dropped on overflow).
     */
    private val eventBuffer = ConcurrentHashMap<String, ConcurrentLinkedDeque<JsonObject>>()

    companion object {
        private const val MAX_BUFFERED_EVENTS = 200
    }
    private val client = HttpClient(CIO) {
        install(WebSockets)
    }

    suspend fun connectWithRetry() {
        var delay = 1000L
        while (true) {
            try {
                System.err.println("[parrot-mcp] Connecting to ws://${config.host}:${config.port}/parrot...")
                client.webSocket(host = config.host, port = config.port, path = "/parrot") {
                    session = this
                    delay = 1000L

                    // Start receive loop first so handshake response can be processed
                    val receiveJob = launch {
                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    handleFrame(frame.readText())
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            System.err.println("[parrot-mcp] Receive error: ${e.message}")
                        }
                    }

                    // Hello handshake
                    val helloId = UUID.randomUUID().toString()
                    val helloDeferred = CompletableDeferred<JsonObject>()
                    pendingRequests[helloId] = helloDeferred
                    val hello = Hello(id = helloId, authToken = config.token ?: "")
                    send(Frame.Text(ParrotJson.encodeToString<ParrotMessage>(hello)))
                    withTimeout(30_000) { helloDeferred.await() }
                    _connected.set(true)
                    System.err.println("[parrot-mcp] Connected and authenticated")

                    // Wait for receive loop to finish (connection closed)
                    receiveJob.join()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("[parrot-mcp] Connection failed: ${e.message}")
            }

            // Disconnected
            onDisconnect()
            System.err.println("[parrot-mcp] Reconnecting in ${delay}ms...")
            kotlinx.coroutines.delay(delay)
            delay = (delay * 2).coerceAtMost(30_000)
            config = Config.discover()
        }
    }

    suspend fun sendRequest(message: ParrotMessage): JsonObject {
        if (!isConnected) {
            throw IllegalStateException("Not connected to Minecraft. Start the game with the Parrot mod, then use wait_for_instance to connect.")
        }
        val deferred = CompletableDeferred<JsonObject>()
        pendingRequests[message.id] = deferred
        try {
            session?.send(Frame.Text(ParrotJson.encodeToString<ParrotMessage>(message)))
            return withTimeout(30_000) { deferred.await() }
        } catch (e: Exception) {
            pendingRequests.remove(message.id)
            throw e
        }
    }

    /**
     * Drains all buffered events for the given [subscriptionId], or all subscriptions if null.
     * Returns the events and removes them from the buffer.
     */
    fun drainEvents(subscriptionId: String? = null): List<JsonObject> {
        if (subscriptionId != null) {
            val deque = eventBuffer.remove(subscriptionId) ?: return emptyList()
            return deque.toList()
        }
        val all = mutableListOf<JsonObject>()
        val keys = eventBuffer.keys().toList()
        for (key in keys) {
            eventBuffer.remove(key)?.let { all.addAll(it) }
        }
        return all.sortedBy { it["tick"]?.jsonPrimitive?.longOrNull ?: 0L }
    }

    fun reconnectTo(newConfig: ParrotConfig, scope: CoroutineScope) {
        config = newConfig
        retryJob?.cancel()
        retryJob = scope.launch { connectWithRetry() }
    }

    suspend fun connectOnce(targetConfig: ParrotConfig): Boolean {
        val probeClient = HttpClient(CIO) { install(WebSockets) }
        return try {
            var success = false
            probeClient.webSocket(host = targetConfig.host, port = targetConfig.port, path = "/parrot") {
                val helloId = UUID.randomUUID().toString()
                val hello = Hello(id = helloId, authToken = targetConfig.token ?: "")
                send(Frame.Text(ParrotJson.encodeToString<ParrotMessage>(hello)))

                success = withTimeout(10_000) {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val msg = ParrotJson.decodeFromString<ParrotMessage>(frame.readText())
                            if (msg is HelloAck && msg.id == helloId) {
                                return@withTimeout true
                            }
                        }
                    }
                    false
                }
            }
            success
        } catch (_: Exception) {
            false
        } finally {
            probeClient.close()
        }
    }

    fun disconnect() {
        session?.let {
            runBlocking { it.close(CloseReason(CloseReason.Codes.NORMAL, "Client disconnecting")) }
        }
        session = null
        onDisconnect()
        client.close()
    }

    private fun handleFrame(text: String) {
        val message = try {
            ParrotJson.decodeFromString<ParrotMessage>(text)
        } catch (e: Exception) {
            System.err.println("[parrot-mcp] Failed to parse message: ${e.message}")
            return
        }

        when (message) {
            is HelloAck -> {
                System.err.println("[parrot-mcp] HelloAck: MC ${message.minecraftVersion}, ${message.modLoader}")
                val result = buildJsonObject {
                    put("minecraftVersion", message.minecraftVersion)
                    put("modLoader", message.modLoader)
                    put("modVersion", message.modVersion)
                    put("serverType", message.serverType)
                }
                gameInfo = GameInfo(
                    minecraftVersion = message.minecraftVersion,
                    modLoader = message.modLoader,
                    modVersion = message.modVersion,
                    serverType = message.serverType
                )
                pendingRequests.remove(message.id)?.complete(result)
            }

            is QueryResult -> {
                pendingRequests.remove(message.id)?.complete(message.result)
            }

            is ActionResult -> {
                val result = buildJsonObject {
                    put("success", message.success)
                    put("tick", message.tick)
                    put("result", message.result)
                    putJsonArray("consequences") {
                        for (c in message.consequences) {
                            addJsonObject {
                                put("type", c.type)
                                put("tick", c.tick)
                                put("data", c.data)
                            }
                        }
                    }
                }
                pendingRequests.remove(message.id)?.complete(result)
            }

            is CommandResult -> {
                val result = buildJsonObject {
                    put("success", message.success)
                    put("output", message.output)
                    message.returnValue?.let { put("returnValue", it) }
                    putJsonArray("consequences") {
                        for (c in message.consequences) {
                            addJsonObject {
                                put("type", c.type)
                                put("tick", c.tick)
                                put("data", c.data)
                            }
                        }
                    }
                }
                pendingRequests.remove(message.id)?.complete(result)
            }

            is BatchResult -> {
                val result = buildJsonObject {
                    putJsonArray("results") {
                        for (r in message.results) {
                            add(r)
                        }
                    }
                }
                pendingRequests.remove(message.id)?.complete(result)
            }

            is SubscribeAck -> {
                val result = buildJsonObject {
                    put("subscriptionId", message.subscriptionId)
                    putJsonArray("subscribedEvents") {
                        for (e in message.subscribedEvents) add(e)
                    }
                }
                pendingRequests.remove(message.id)?.complete(result)
            }

            is UnsubscribeAck -> {
                val result = buildJsonObject {
                    put("success", message.success)
                }
                pendingRequests.remove(message.id)?.complete(result)
            }

            is ErrorResponse -> {
                val exception = RuntimeException("Minecraft error [${message.code}]: ${message.message}")
                pendingRequests.remove(message.id)?.completeExceptionally(exception)
            }

            is Ping -> {
                val pong = Pong(id = message.id, timestamp = message.timestamp)
                val session = session ?: return
                // Fire-and-forget pong response
                @OptIn(DelicateCoroutinesApi::class)
                GlobalScope.launch {
                    try {
                        session.send(Frame.Text(ParrotJson.encodeToString<ParrotMessage>(pong)))
                    } catch (_: Exception) {}
                }
            }

            is PushEvent -> {
                System.err.println("[parrot-mcp] Event: ${message.eventType} (sub=${message.subscriptionId})")
                val event = buildJsonObject {
                    put("subscriptionId", message.subscriptionId)
                    put("eventType", message.eventType)
                    put("tick", message.tick)
                    put("data", message.data)
                }
                val deque = eventBuffer.computeIfAbsent(message.subscriptionId) { ConcurrentLinkedDeque() }
                deque.addLast(event)
                // Evict oldest events if buffer is full
                while (deque.size > MAX_BUFFERED_EVENTS) {
                    deque.pollFirst()
                }
            }

            else -> {
                System.err.println("[parrot-mcp] Unhandled message type: ${message::class.simpleName}")
            }
        }
    }

    private fun onDisconnect() {
        _connected.set(false)
        session = null
        gameInfo = null
        eventBuffer.clear()
        val exception = RuntimeException("Disconnected from Minecraft")
        for ((id, deferred) in pendingRequests) {
            deferred.completeExceptionally(exception)
            pendingRequests.remove(id)
        }
    }
}

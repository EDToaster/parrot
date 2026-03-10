package dev.parrot.mcp.integration

import dev.parrot.protocol.*
import io.ktor.server.application.*
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

class MockMinecraftServer {
    private var server: EmbeddedServer<*, *>? = null
    var actualPort: Int = 0
        private set

    private val cannedResponses = mutableMapOf<String, JsonObject>()
    val receivedMessages = CopyOnWriteArrayList<ParrotMessage>()
    private var session: WebSocketSession? = null
    var requiredToken: String? = null

    fun registerResponse(method: String, response: JsonObject) {
        cannedResponses[method] = response
    }

    fun start() {
        actualPort = ServerSocket(0).use { it.localPort }

        server = embeddedServer(ServerCIO, port = actualPort) {
            install(ServerWebSockets)
            routing {
                webSocket("/parrot") {
                    session = this
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                handleMessage(frame.readText(), this)
                            }
                        }
                    } catch (_: ClosedReceiveChannelException) {
                        // Client disconnected
                    } finally {
                        session = null
                    }
                }
            }
        }
        server!!.start(wait = false)
        Thread.sleep(500)
    }

    suspend fun sendPing(id: String, timestamp: Long) {
        val ping = Ping(id = id, timestamp = timestamp)
        session?.send(Frame.Text(ParrotJson.encodeToString<ParrotMessage>(ping)))
    }

    fun stop() {
        server?.stop(0, 0)
        server = null
    }

    private suspend fun handleMessage(text: String, session: WebSocketSession) {
        val message = try {
            ParrotJson.decodeFromString<ParrotMessage>(text)
        } catch (e: Exception) {
            return
        }
        receivedMessages.add(message)

        when (message) {
            is Hello -> {
                val expected = requiredToken
                if (expected != null && message.authToken != expected) {
                    val error = ErrorResponse(
                        id = message.id,
                        code = "AUTH_FAILED",
                        message = "Invalid auth token"
                    )
                    session.send(Frame.Text(ParrotJson.encodeToString<ParrotMessage>(error)))
                    session.close(CloseReason(CloseReason.Codes.NORMAL, "Auth failed"))
                    return
                }
                val ack = HelloAck(
                    id = message.id,
                    capabilities = listOf("gui_observation"),
                    minecraftVersion = "1.21.10",
                    modLoader = "fabric",
                    modVersion = "0.1.0",
                    serverType = "integrated"
                )
                session.send(Frame.Text(ParrotJson.encodeToString<ParrotMessage>(ack)))
            }

            is QueryRequest -> {
                val data = cannedResponses[message.method] ?: buildJsonObject {}
                val result = QueryResult(
                    id = message.id,
                    tick = 100L,
                    result = data
                )
                session.send(Frame.Text(ParrotJson.encodeToString<ParrotMessage>(result)))
            }

            is ActionRequest -> {
                val data = cannedResponses[message.method] ?: buildJsonObject {}
                val result = ActionResult(
                    id = message.id,
                    success = true,
                    tick = 100L,
                    result = data,
                    consequences = emptyList()
                )
                session.send(Frame.Text(ParrotJson.encodeToString<ParrotMessage>(result)))
            }

            is CommandRequest -> {
                val result = CommandResult(
                    id = message.id,
                    success = true,
                    tick = 100L,
                    output = "Command executed",
                    returnValue = 0,
                    consequences = emptyList()
                )
                session.send(Frame.Text(ParrotJson.encodeToString<ParrotMessage>(result)))
            }

            is BatchRequest -> {
                val results = message.commands.map { cmd ->
                    cannedResponses[cmd.method] ?: buildJsonObject { put("method", cmd.method) }
                }
                val result = BatchResult(
                    id = message.id,
                    results = results
                )
                session.send(Frame.Text(ParrotJson.encodeToString<ParrotMessage>(result)))
            }

            is SubscribeRequest -> {
                val ack = SubscribeAck(
                    id = message.id,
                    subscriptionId = "sub-${message.id}",
                    subscribedEvents = message.eventTypes
                )
                session.send(Frame.Text(ParrotJson.encodeToString<ParrotMessage>(ack)))
            }

            is UnsubscribeRequest -> {
                val ack = UnsubscribeAck(
                    id = message.id,
                    success = true
                )
                session.send(Frame.Text(ParrotJson.encodeToString<ParrotMessage>(ack)))
            }

            is Pong -> {
                // Already recorded in receivedMessages
            }

            else -> {}
        }
    }

    suspend fun sendPushEvent(subscriptionId: String, eventType: String, data: JsonObject) {
        val event = PushEvent(
            subscriptionId = subscriptionId,
            tick = 100L,
            eventType = eventType,
            data = data
        )
        session?.send(Frame.Text(ParrotJson.encodeToString<ParrotMessage>(event)))
    }
}

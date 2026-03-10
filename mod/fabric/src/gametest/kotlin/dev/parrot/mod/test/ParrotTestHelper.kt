package dev.parrot.mod.test

import dev.parrot.protocol.*
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

class ParrotTestClient(
    private val ws: WebSocket,
    private val inbound: ConcurrentLinkedQueue<ParrotMessage>
) : AutoCloseable {

    fun sendMessage(message: ParrotMessage): String {
        val json = ParrotJson.encodeToString(ParrotMessage.serializer(), message)
        ws.sendText(json, true)
        return message.id
    }

    fun sendQuery(method: String, params: JsonObject = JsonObject(emptyMap())): String {
        return sendMessage(QueryRequest(id = newId(), method = method, params = params))
    }

    fun sendAction(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        consequenceWait: Int = 5
    ): String {
        return sendMessage(ActionRequest(id = newId(), method = method, params = params, consequenceWait = consequenceWait))
    }

    fun sendCommand(command: String): String {
        return sendMessage(CommandRequest(id = newId(), command = command))
    }

    fun sendSubscribe(eventTypes: List<String>): String {
        return sendMessage(SubscribeRequest(id = newId(), eventTypes = eventTypes))
    }

    fun waitForMessage(id: String, timeoutMs: Long = 5000): ParrotMessage? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val iter = inbound.iterator()
            while (iter.hasNext()) {
                val msg = iter.next()
                if (msg.id == id) {
                    iter.remove()
                    return msg
                }
            }
            Thread.sleep(50)
        }
        return null
    }

    fun waitForEvent(timeoutMs: Long = 5000): PushEvent? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val iter = inbound.iterator()
            while (iter.hasNext()) {
                val msg = iter.next()
                if (msg is PushEvent) {
                    iter.remove()
                    return msg
                }
            }
            Thread.sleep(50)
        }
        return null
    }

    override fun close() {
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "test done")
    }

    private fun newId(): String = UUID.randomUUID().toString()
}

object ParrotTestHelper {

    fun connectAndAuth(): ParrotTestClient {
        val connInfo = dev.parrot.mod.server.ConnectionFileManager.read()
            ?: throw AssertionError("Connection file not found — is ParrotEngine running?")

        val inbound = ConcurrentLinkedQueue<ParrotMessage>()
        val httpClient = HttpClient.newHttpClient()

        val listener = object : WebSocket.Listener {
            private val buffer = StringBuilder()

            override fun onOpen(webSocket: WebSocket) {
                webSocket.request(1)
            }

            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
                buffer.append(data)
                if (last) {
                    val text = buffer.toString()
                    buffer.setLength(0)
                    try {
                        inbound.add(ParrotJson.decodeFromString(ParrotMessage.serializer(), text))
                    } catch (_: Exception) {}
                }
                webSocket.request(1)
                return CompletableFuture.completedFuture(null)
            }
        }

        val ws = httpClient.newWebSocketBuilder()
            .buildAsync(URI.create("ws://127.0.0.1:${connInfo.port}/parrot"), listener)
            .get(5, TimeUnit.SECONDS)

        // Send Hello and wait for HelloAck
        val helloId = UUID.randomUUID().toString()
        val helloJson = ParrotJson.encodeToString(
            ParrotMessage.serializer(),
            Hello(id = helloId, authToken = connInfo.token)
        )
        ws.sendText(helloJson, true)

        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            val iter = inbound.iterator()
            while (iter.hasNext()) {
                val msg = iter.next()
                if (msg.id == helloId) {
                    iter.remove()
                    if (msg is HelloAck) return ParrotTestClient(ws, inbound)
                    if (msg is ErrorResponse) throw AssertionError("Auth failed: ${msg.code} — ${msg.message}")
                }
            }
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for HelloAck")
    }
}

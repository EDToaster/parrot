package dev.parrot.mcp.integration

import dev.parrot.mcp.MinecraftBridge
import dev.parrot.mcp.ParrotConfig
import dev.parrot.protocol.SubscribeRequest
import dev.parrot.protocol.UnsubscribeRequest
import kotlinx.coroutines.*
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubscriptionFlowTest {
    private lateinit var mockServer: MockMinecraftServer
    private lateinit var bridge: MinecraftBridge
    private val scope = CoroutineScope(Dispatchers.IO)
    private var connectJob: Job? = null

    @BeforeEach
    fun setup() {
        mockServer = MockMinecraftServer()
    }

    @AfterEach
    fun teardown() {
        connectJob?.cancel()
        bridge.disconnect()
        mockServer.stop()
        scope.cancel()
    }

    @Test
    fun `subscribe request returns subscription ack`() = runBlocking {
        withTimeout(10_000) {
            mockServer.start()
            val config = ParrotConfig(host = "127.0.0.1", port = mockServer.actualPort, token = null)
            bridge = MinecraftBridge(config)
            connectJob = scope.launch { bridge.connectWithRetry() }
            waitForConnection(bridge)

            val requestId = UUID.randomUUID().toString()
            val request = SubscribeRequest(
                id = requestId,
                eventTypes = listOf("block_changed", "entity_spawned")
            )
            val result = bridge.sendRequest(request)

            assertTrue(result.containsKey("subscriptionId"))
            assertEquals("sub-$requestId", result["subscriptionId"]?.jsonPrimitive?.content)

            val events = result["subscribedEvents"]?.jsonArray?.map { it.jsonPrimitive.content }
            assertEquals(listOf("block_changed", "entity_spawned"), events)

            val subMessages = mockServer.receivedMessages.filterIsInstance<SubscribeRequest>()
            assertEquals(1, subMessages.size)
            assertEquals(listOf("block_changed", "entity_spawned"), subMessages[0].eventTypes)
        }
    }

    @Test
    fun `push event is received after subscription`() = runBlocking {
        withTimeout(10_000) {
            mockServer.start()
            val config = ParrotConfig(host = "127.0.0.1", port = mockServer.actualPort, token = null)
            bridge = MinecraftBridge(config)
            connectJob = scope.launch { bridge.connectWithRetry() }
            waitForConnection(bridge)

            // Subscribe first
            val requestId = UUID.randomUUID().toString()
            val request = SubscribeRequest(
                id = requestId,
                eventTypes = listOf("block_changed")
            )
            val subResult = bridge.sendRequest(request)
            val subscriptionId = subResult["subscriptionId"]?.jsonPrimitive?.content
            assertEquals("sub-$requestId", subscriptionId)

            // Server pushes an event — the bridge logs it via PushEvent handler.
            // We verify the server can send a push event without error.
            val eventData = buildJsonObject {
                put("x", 10)
                put("y", 64)
                put("z", 20)
                put("newBlock", "minecraft:air")
            }
            mockServer.sendPushEvent(subscriptionId!!, "block_changed", eventData)

            // Allow time for the event to be received and processed
            delay(500)

            // The bridge is still connected (event didn't break anything)
            assertTrue(bridge.isConnected, "Bridge should remain connected after receiving push event")
        }
    }

    @Test
    fun `unsubscribe returns success ack`() = runBlocking {
        withTimeout(10_000) {
            mockServer.start()
            val config = ParrotConfig(host = "127.0.0.1", port = mockServer.actualPort, token = null)
            bridge = MinecraftBridge(config)
            connectJob = scope.launch { bridge.connectWithRetry() }
            waitForConnection(bridge)

            // Subscribe first
            val subRequestId = UUID.randomUUID().toString()
            val subRequest = SubscribeRequest(
                id = subRequestId,
                eventTypes = listOf("block_changed")
            )
            val subResult = bridge.sendRequest(subRequest)
            val subscriptionId = subResult["subscriptionId"]?.jsonPrimitive?.content!!

            // Now unsubscribe
            val unsubRequest = UnsubscribeRequest(
                id = UUID.randomUUID().toString(),
                subscriptionId = subscriptionId
            )
            val unsubResult = bridge.sendRequest(unsubRequest)

            assertTrue(unsubResult["success"]?.jsonPrimitive?.boolean == true,
                "Unsubscribe should return success=true")

            // Verify server received the unsubscribe message
            val unsubMessages = mockServer.receivedMessages.filterIsInstance<UnsubscribeRequest>()
            assertEquals(1, unsubMessages.size)
            assertEquals(subscriptionId, unsubMessages[0].subscriptionId)
        }
    }
}

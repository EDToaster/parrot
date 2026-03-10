package dev.parrot.mcp.integration

import dev.parrot.mcp.MinecraftBridge
import dev.parrot.mcp.ParrotConfig
import dev.parrot.protocol.SubscribeRequest
import kotlinx.coroutines.*
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
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
}

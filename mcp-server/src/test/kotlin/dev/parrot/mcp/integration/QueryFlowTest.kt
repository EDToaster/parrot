package dev.parrot.mcp.integration

import dev.parrot.mcp.MinecraftBridge
import dev.parrot.mcp.ParrotConfig
import dev.parrot.protocol.QueryRequest
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

class QueryFlowTest {
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
    fun `query request returns canned response`() = runBlocking {
        withTimeout(10_000) {
            val cannedBlock = buildJsonObject {
                put("id", "minecraft:stone")
                put("x", 0)
                put("y", 64)
                put("z", 0)
            }
            mockServer.registerResponse("get_block", cannedBlock)
            mockServer.start()

            val config = ParrotConfig(host = "127.0.0.1", port = mockServer.actualPort, token = null)
            bridge = MinecraftBridge(config)
            connectJob = scope.launch { bridge.connectWithRetry() }
            waitForConnection(bridge)

            val request = QueryRequest(
                id = UUID.randomUUID().toString(),
                method = "get_block",
                params = buildJsonObject {
                    put("x", 0)
                    put("y", 64)
                    put("z", 0)
                }
            )
            val result = bridge.sendRequest(request)

            assertEquals("minecraft:stone", result["id"]?.jsonPrimitive?.content)
            assertEquals(0, result["x"]?.jsonPrimitive?.int)
            assertEquals(64, result["y"]?.jsonPrimitive?.int)

            val queryMessages = mockServer.receivedMessages.filterIsInstance<QueryRequest>()
            assertEquals(1, queryMessages.size)
            assertEquals("get_block", queryMessages[0].method)
        }
    }
}

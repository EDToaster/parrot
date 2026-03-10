package dev.parrot.mcp.integration

import dev.parrot.mcp.MinecraftBridge
import dev.parrot.mcp.ParrotConfig
import dev.parrot.protocol.BatchCommand
import dev.parrot.protocol.BatchRequest
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

class BatchFlowTest {
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
    fun `batch request returns results for all commands`() = runBlocking {
        withTimeout(10_000) {
            val blockResponse = buildJsonObject { put("id", "minecraft:stone") }
            val playerResponse = buildJsonObject { put("name", "Steve") }
            val worldResponse = buildJsonObject { put("time", 6000) }
            mockServer.registerResponse("get_block", blockResponse)
            mockServer.registerResponse("get_player", playerResponse)
            mockServer.registerResponse("get_world_info", worldResponse)
            mockServer.start()

            val config = ParrotConfig(host = "127.0.0.1", port = mockServer.actualPort, token = null)
            bridge = MinecraftBridge(config)
            connectJob = scope.launch { bridge.connectWithRetry() }
            waitForConnection(bridge)

            val request = BatchRequest(
                id = UUID.randomUUID().toString(),
                commands = listOf(
                    BatchCommand(method = "get_block", params = buildJsonObject { put("x", 0) }),
                    BatchCommand(method = "get_player"),
                    BatchCommand(method = "get_world_info")
                )
            )
            val result = bridge.sendRequest(request)

            val results = result["results"]?.jsonArray
            assertEquals(3, results?.size)
            assertEquals("minecraft:stone", results?.get(0)?.jsonObject?.get("id")?.jsonPrimitive?.content)
            assertEquals("Steve", results?.get(1)?.jsonObject?.get("name")?.jsonPrimitive?.content)
            assertEquals(6000, results?.get(2)?.jsonObject?.get("time")?.jsonPrimitive?.int)

            val batchMessages = mockServer.receivedMessages.filterIsInstance<BatchRequest>()
            assertEquals(1, batchMessages.size)
            assertEquals(3, batchMessages[0].commands.size)
        }
    }
}

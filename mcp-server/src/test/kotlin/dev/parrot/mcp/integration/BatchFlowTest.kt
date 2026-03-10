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
            mockServer.registerResponse("get_block", blockResponse)
            mockServer.registerResponse("get_player", playerResponse)
            mockServer.start()

            val config = ParrotConfig(host = "127.0.0.1", port = mockServer.actualPort, token = null)
            bridge = MinecraftBridge(config)
            connectJob = scope.launch { bridge.connectWithRetry() }
            waitForConnection(bridge)

            val request = BatchRequest(
                id = UUID.randomUUID().toString(),
                commands = listOf(
                    BatchCommand(method = "get_block", params = buildJsonObject { put("x", 0) }),
                    BatchCommand(method = "get_player")
                )
            )
            val result = bridge.sendRequest(request)

            val results = result["results"]?.jsonArray
            assertEquals(2, results?.size)
            assertEquals("minecraft:stone", results?.get(0)?.jsonObject?.get("id")?.jsonPrimitive?.content)
            assertEquals("Steve", results?.get(1)?.jsonObject?.get("name")?.jsonPrimitive?.content)

            val batchMessages = mockServer.receivedMessages.filterIsInstance<BatchRequest>()
            assertEquals(1, batchMessages.size)
            assertEquals(2, batchMessages[0].commands.size)
        }
    }
}

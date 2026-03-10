package dev.parrot.mcp.integration

import dev.parrot.mcp.MinecraftBridge
import dev.parrot.mcp.ParrotConfig
import dev.parrot.protocol.CommandRequest
import kotlinx.coroutines.*
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandFlowTest {
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
    fun `command request returns result`() = runBlocking {
        withTimeout(10_000) {
            mockServer.start()
            val config = ParrotConfig(host = "127.0.0.1", port = mockServer.actualPort, token = null)
            bridge = MinecraftBridge(config)
            connectJob = scope.launch { bridge.connectWithRetry() }
            waitForConnection(bridge)

            val request = CommandRequest(
                id = UUID.randomUUID().toString(),
                command = "/gamemode creative",
                consequenceWait = 3
            )
            val result = bridge.sendRequest(request)

            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
            assertEquals("Command executed", result["output"]?.jsonPrimitive?.content)
            assertEquals(0, result["returnValue"]?.jsonPrimitive?.int)

            val cmdMessages = mockServer.receivedMessages.filterIsInstance<CommandRequest>()
            assertEquals(1, cmdMessages.size)
            assertEquals("/gamemode creative", cmdMessages[0].command)
        }
    }
}

package dev.parrot.mcp.integration

import dev.parrot.mcp.MinecraftBridge
import dev.parrot.mcp.ParrotConfig
import dev.parrot.protocol.ActionRequest
import kotlinx.coroutines.*
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActionFlowTest {
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
    fun `action request sends consequenceWait and receives result`() = runBlocking {
        withTimeout(10_000) {
            mockServer.start()
            val config = ParrotConfig(host = "127.0.0.1", port = mockServer.actualPort, token = null)
            bridge = MinecraftBridge(config)
            connectJob = scope.launch { bridge.connectWithRetry() }
            waitForConnection(bridge)

            val request = ActionRequest(
                id = UUID.randomUUID().toString(),
                method = "interact_block",
                params = buildJsonObject {
                    put("x", 10)
                    put("y", 65)
                    put("z", 20)
                },
                consequenceWait = 10
            )
            val result = bridge.sendRequest(request)

            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)

            val actionMessages = mockServer.receivedMessages.filterIsInstance<ActionRequest>()
            assertEquals(1, actionMessages.size)
            assertEquals("interact_block", actionMessages[0].method)
            assertEquals(10, actionMessages[0].consequenceWait)
        }
    }
}

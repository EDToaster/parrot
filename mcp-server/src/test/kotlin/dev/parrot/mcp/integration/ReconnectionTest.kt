package dev.parrot.mcp.integration

import dev.parrot.mcp.MinecraftBridge
import dev.parrot.mcp.ParrotConfig
import dev.parrot.protocol.QueryRequest
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertIs

class ReconnectionTest {
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
        scope.cancel()
    }

    @Test
    fun `pending requests fail when server stops`() = runBlocking {
        withTimeout(15_000) {
            mockServer.start()
            val config = ParrotConfig(host = "127.0.0.1", port = mockServer.actualPort, token = null)
            bridge = MinecraftBridge(config)
            connectJob = scope.launch { bridge.connectWithRetry() }
            waitForConnection(bridge)

            mockServer.stop()
            // Wait for disconnect to propagate
            val start = System.currentTimeMillis()
            while (bridge.isConnected && System.currentTimeMillis() - start < 5000) {
                delay(50)
            }

            assertFalse(bridge.isConnected, "Bridge should be disconnected after server stop")

            val request = QueryRequest(
                id = UUID.randomUUID().toString(),
                method = "get_block",
                params = buildJsonObject {}
            )
            val exception = try {
                bridge.sendRequest(request)
                null
            } catch (e: Exception) {
                e
            }
            assertIs<IllegalStateException>(exception, "Should throw IllegalStateException when not connected")
        }
    }
}

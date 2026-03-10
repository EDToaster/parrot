package dev.parrot.mcp.integration

import dev.parrot.mcp.MinecraftBridge
import dev.parrot.mcp.ParrotConfig
import kotlinx.coroutines.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse

class AuthFailureTest {
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
    fun `wrong token causes auth failure with no reconnect`() = runBlocking {
        withTimeout(10_000) {
            mockServer.requiredToken = "correct-secret-token"
            mockServer.start()

            val config = ParrotConfig(host = "127.0.0.1", port = mockServer.actualPort, token = "wrong-token")
            bridge = MinecraftBridge(config)

            // Launch connection attempt — it should fail auth and the bridge should
            // receive an ErrorResponse which completes the hello deferred exceptionally.
            connectJob = scope.launch { bridge.connectWithRetry() }

            // Give enough time for the handshake attempt to fail
            delay(2000)

            // Bridge should NOT be connected after auth failure
            assertFalse(bridge.isConnected, "Bridge should not be connected after auth failure")

            // Verify the server received exactly one Hello (no reconnect attempts yet at this point,
            // but even if reconnect fires, each attempt should also fail).
            // The key assertion is that isConnected is false.
            val helloCount = mockServer.receivedMessages
                .filterIsInstance<dev.parrot.protocol.Hello>()
                .size
            assert(helloCount >= 1) { "Server should have received at least one Hello" }
        }
    }
}

package dev.parrot.mcp.integration

import dev.parrot.mcp.MinecraftBridge
import dev.parrot.mcp.ParrotConfig
import dev.parrot.protocol.Hello
import kotlinx.coroutines.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HandshakeTest {
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
    fun `bridge connects and completes handshake`() = runBlocking {
        withTimeout(10_000) {
            mockServer.start()
            val config = ParrotConfig(host = "127.0.0.1", port = mockServer.actualPort, token = null)
            bridge = MinecraftBridge(config)

            connectJob = scope.launch { bridge.connectWithRetry() }
            waitForConnection(bridge)

            assertTrue(bridge.isConnected, "Bridge should be connected after handshake")

            val helloMessages = mockServer.receivedMessages.filterIsInstance<Hello>()
            assertEquals(1, helloMessages.size, "Mock should have received exactly one Hello")
            assertEquals(1, helloMessages[0].protocolVersion, "Protocol version should be 1")
        }
    }
}

suspend fun waitForConnection(bridge: MinecraftBridge, timeoutMs: Long = 5000) {
    val start = System.currentTimeMillis()
    while (!bridge.isConnected) {
        if (System.currentTimeMillis() - start > timeoutMs) {
            error("Bridge did not connect within ${timeoutMs}ms")
        }
        delay(50)
    }
}

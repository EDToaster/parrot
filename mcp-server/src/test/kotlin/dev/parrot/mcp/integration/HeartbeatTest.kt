package dev.parrot.mcp.integration

import dev.parrot.mcp.MinecraftBridge
import dev.parrot.mcp.ParrotConfig
import dev.parrot.protocol.Pong
import kotlinx.coroutines.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeartbeatTest {
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
    fun `bridge responds to ping with pong`() = runBlocking {
        withTimeout(10_000) {
            mockServer.start()
            val config = ParrotConfig(host = "127.0.0.1", port = mockServer.actualPort, token = null)
            bridge = MinecraftBridge(config)
            connectJob = scope.launch { bridge.connectWithRetry() }
            waitForConnection(bridge)

            assertTrue(bridge.isConnected)

            val pingId = "ping-test-1"
            val pingTimestamp = System.currentTimeMillis()
            mockServer.sendPing(pingId, pingTimestamp)

            // Wait for pong to arrive
            val start = System.currentTimeMillis()
            while (mockServer.receivedMessages.filterIsInstance<Pong>().isEmpty()
                && System.currentTimeMillis() - start < 3000) {
                delay(50)
            }

            val pongMessages = mockServer.receivedMessages.filterIsInstance<Pong>()
            assertTrue(pongMessages.isNotEmpty(), "Should have received at least one Pong")
            assertEquals(pingId, pongMessages[0].id)
            assertEquals(pingTimestamp, pongMessages[0].timestamp)
        }
    }
}

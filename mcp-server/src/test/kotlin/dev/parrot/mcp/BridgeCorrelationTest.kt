package dev.parrot.mcp

import dev.parrot.protocol.QueryRequest
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class BridgeCorrelationTest {

    @Test
    fun `sendRequest throws when not connected`() = runTest {
        val bridge = MinecraftBridge(ParrotConfig(host = "127.0.0.1", port = 99999, token = null))
        assertFalse(bridge.isConnected)

        val exception = assertFailsWith<IllegalStateException> {
            bridge.sendRequest(
                QueryRequest(id = "test-1", method = "get_player", params = kotlinx.serialization.json.JsonObject(emptyMap()))
            )
        }
        assertEquals("Not connected to Minecraft", exception.message)
    }

    @Test
    fun `new bridge is not connected`() {
        val bridge = MinecraftBridge(ParrotConfig(host = "127.0.0.1", port = 25566, token = null))
        assertFalse(bridge.isConnected)
    }

    @Test
    fun `disconnect on new bridge sets isConnected to false`() {
        val bridge = MinecraftBridge(ParrotConfig(host = "localhost", port = 25566, token = "test-token"))
        assertFalse(bridge.isConnected)
        bridge.disconnect()
        assertFalse(bridge.isConnected)
    }

    @Test
    fun `constructor creates bridge with given config`() {
        val config = ParrotConfig(host = "10.0.0.1", port = 12345, token = "my-token")
        val bridge = MinecraftBridge(config)
        assertFalse(bridge.isConnected)
    }
}

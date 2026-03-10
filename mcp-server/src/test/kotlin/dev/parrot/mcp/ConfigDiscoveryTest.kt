package dev.parrot.mcp

import dev.parrot.protocol.ConnectionInfo
import dev.parrot.protocol.ParrotJson
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConfigDiscoveryTest {

    private fun withTempHome(block: (File) -> Unit) {
        val originalHome = System.getProperty("user.home")
        val tempDir = kotlin.io.path.createTempDirectory("parrot-test").toFile()
        try {
            System.setProperty("user.home", tempDir.absolutePath)
            block(tempDir)
        } finally {
            System.setProperty("user.home", originalHome)
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `default values when no connection file exists`() {
        // This test only works when PARROT_PORT and PARROT_HOST env vars are not set.
        // Skip if they happen to be set in the test environment.
        if (System.getenv("PARROT_PORT") != null || System.getenv("PARROT_HOST") != null) return

        withTempHome { _ ->
            val config = Config.discover()
            assertEquals("127.0.0.1", config.host)
            assertEquals(25566, config.port)
            assertNull(config.token)
        }
    }

    @Test
    fun `connection file reading via temp file`() {
        if (System.getenv("PARROT_PORT") != null) return

        withTempHome { tempDir ->
            val parrotDir = File(tempDir, ".parrot")
            parrotDir.mkdirs()
            val connectionFile = File(parrotDir, "connection.json")
            val connectionInfo = ConnectionInfo(port = 31337, token = "secret-token", pid = 42)
            connectionFile.writeText(ParrotJson.encodeToString(connectionInfo))

            val config = Config.discover()
            assertEquals("127.0.0.1", config.host)
            assertEquals(31337, config.port)
            assertEquals("secret-token", config.token)
        }
    }

    @Test
    fun `malformed connection file falls back to defaults`() {
        if (System.getenv("PARROT_PORT") != null) return

        withTempHome { tempDir ->
            val parrotDir = File(tempDir, ".parrot")
            parrotDir.mkdirs()
            val connectionFile = File(parrotDir, "connection.json")
            connectionFile.writeText("this is not valid json!!!")

            val config = Config.discover()
            assertEquals("127.0.0.1", config.host)
            assertEquals(25566, config.port)
            assertNull(config.token)
        }
    }

    @Test
    fun `ParrotConfig data class holds correct values`() {
        val config = ParrotConfig(host = "10.0.0.1", port = 12345, token = "my-token")
        assertEquals("10.0.0.1", config.host)
        assertEquals(12345, config.port)
        assertEquals("my-token", config.token)
    }

    @Test
    fun `ParrotConfig with null token`() {
        val config = ParrotConfig(host = "127.0.0.1", port = 25566, token = null)
        assertNull(config.token)
    }
}

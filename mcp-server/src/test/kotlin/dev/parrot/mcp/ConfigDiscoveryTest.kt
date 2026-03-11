package dev.parrot.mcp

import dev.parrot.protocol.ConnectionInfo
import dev.parrot.protocol.ParrotJson
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
            val connectionInfo = ConnectionInfo(port = 31337, token = "secret-token", pid = ProcessHandle.current().pid())
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

    @Test
    fun `readConnectionFile returns ConnectionInfo for valid file`() {
        withTempHome { tempDir ->
            val parrotDir = File(tempDir, ".parrot")
            parrotDir.mkdirs()
            val connectionFile = File(parrotDir, "connection.json")
            val connectionInfo = ConnectionInfo(port = 9999, token = "test-token", pid = 123)
            connectionFile.writeText(ParrotJson.encodeToString(connectionInfo))

            val result = Config.readConnectionFile()
            assertNotNull(result)
            assertEquals(9999, result.port)
            assertEquals("test-token", result.token)
            assertEquals(123L, result.pid)
        }
    }

    @Test
    fun `readConnectionFile returns null for missing file`() {
        withTempHome { _ ->
            val result = Config.readConnectionFile()
            assertNull(result)
        }
    }

    @Test
    fun `readConnectionFile returns null for malformed file`() {
        withTempHome { tempDir ->
            val parrotDir = File(tempDir, ".parrot")
            parrotDir.mkdirs()
            File(parrotDir, "connection.json").writeText("not valid json {{{")

            val result = Config.readConnectionFile()
            assertNull(result)
        }
    }

    @Test
    fun `isPidAlive returns true for current process PID`() {
        val currentPid = ProcessHandle.current().pid()
        assertTrue(Config.isPidAlive(currentPid))
    }

    @Test
    fun `isPidAlive returns false for dead PID`() {
        assertFalse(Config.isPidAlive(Long.MAX_VALUE))
    }

    @Test
    fun `isPidAlive returns true for null PID`() {
        assertTrue(Config.isPidAlive(null))
    }

    @Test
    fun `discover skips stale connection file with dead PID`() {
        if (System.getenv("PARROT_PORT") != null) return

        withTempHome { tempDir ->
            val parrotDir = File(tempDir, ".parrot")
            parrotDir.mkdirs()
            val connectionFile = File(parrotDir, "connection.json")
            val connectionInfo = ConnectionInfo(port = 31337, token = "stale-token", pid = Long.MAX_VALUE)
            connectionFile.writeText(ParrotJson.encodeToString(connectionInfo))

            val config = Config.discover()
            assertEquals("127.0.0.1", config.host)
            assertEquals(25566, config.port)
            assertNull(config.token)
        }
    }
}

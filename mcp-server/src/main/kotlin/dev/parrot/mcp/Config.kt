package dev.parrot.mcp

import dev.parrot.protocol.ConnectionInfo
import dev.parrot.protocol.ParrotJson
import java.io.File

data class ParrotConfig(val host: String, val port: Int, val token: String?)

object Config {
    private const val DEFAULT_HOST = "127.0.0.1"
    private const val DEFAULT_PORT = 25566

    fun discover(): ParrotConfig {
        val host = System.getenv("PARROT_HOST") ?: DEFAULT_HOST

        val envPort = System.getenv("PARROT_PORT")
        if (envPort != null) {
            val port = envPort.toIntOrNull()
                ?: error("PARROT_PORT is not a valid integer: $envPort")
            System.err.println("[parrot-mcp] Using PARROT_PORT=$port, host=$host")
            return ParrotConfig(host = host, port = port, token = null)
        }

        val connectionFile = File(System.getProperty("user.home"), ".parrot/connection.json")
        if (connectionFile.exists()) {
            try {
                val info = ParrotJson.decodeFromString<ConnectionInfo>(connectionFile.readText())
                System.err.println("[parrot-mcp] Loaded connection.json: port=${info.port}, pid=${info.pid}")
                return ParrotConfig(host = host, port = info.port, token = info.token)
            } catch (e: Exception) {
                System.err.println("[parrot-mcp] Failed to parse connection.json: ${e.message}")
            }
        }

        System.err.println("[parrot-mcp] Using default port $DEFAULT_PORT, host=$host")
        return ParrotConfig(host = host, port = DEFAULT_PORT, token = null)
    }
}

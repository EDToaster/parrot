package dev.parrot.mod.server

import dev.parrot.protocol.ConnectionInfo
import dev.parrot.protocol.ParrotJson
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

object ConnectionFileManager {
    private val connectionDir: Path = Path.of(System.getProperty("user.home"), ".parrot")
    private val connectionFile: Path = connectionDir.resolve("connection.json")

    fun write(port: Int, token: String) {
        Files.createDirectories(connectionDir)
        val info = ConnectionInfo(port = port, token = token, pid = ProcessHandle.current().pid())
        val json = ParrotJson.encodeToString(ConnectionInfo.serializer(), info)
        val tempFile = Files.createTempFile(connectionDir, "connection", ".tmp")
        try {
            Files.writeString(tempFile, json, StandardCharsets.UTF_8)
            Files.move(tempFile, connectionFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tempFile, connectionFile, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Files.deleteIfExists(tempFile)
            throw e
        }
    }

    fun read(): ConnectionInfo? {
        if (!Files.exists(connectionFile)) return null
        val json = Files.readString(connectionFile, StandardCharsets.UTF_8)
        return ParrotJson.decodeFromString(ConnectionInfo.serializer(), json)
    }

    fun delete() {
        Files.deleteIfExists(connectionFile)
    }
}

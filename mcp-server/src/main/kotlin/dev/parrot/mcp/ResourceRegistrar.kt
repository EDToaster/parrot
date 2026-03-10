package dev.parrot.mcp

import dev.parrot.protocol.QueryRequest
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlinx.serialization.json.JsonObject

object ResourceRegistrar {

    private data class ResourceDef(
        val uri: String,
        val name: String,
        val description: String,
        val mimeType: String,
        val method: String
    )

    private val resources = listOf(
        ResourceDef(
            uri = "minecraft://world/info",
            name = "World Info",
            description = "Current world state including time, weather, and dimension",
            mimeType = "application/json",
            method = "get_world_info"
        ),
        ResourceDef(
            uri = "minecraft://player/info",
            name = "Player Info",
            description = "Current player state including position, health, and game mode",
            mimeType = "application/json",
            method = "get_player"
        ),
        ResourceDef(
            uri = "minecraft://player/inventory",
            name = "Player Inventory",
            description = "Current inventory contents",
            mimeType = "application/json",
            method = "get_inventory"
        ),
        ResourceDef(
            uri = "minecraft://screen/current",
            name = "Current Screen",
            description = "Currently open GUI screen",
            mimeType = "application/json",
            method = "get_screen"
        ),
        ResourceDef(
            uri = "minecraft://logs/recent",
            name = "Recent Logs",
            description = "Recent game log entries",
            mimeType = "text/plain",
            method = "get_recent_logs"
        )
    )

    fun registerAll(server: Server, bridge: MinecraftBridge) {
        for (res in resources) {
            server.addResource(
                uri = res.uri,
                name = res.name,
                description = res.description,
                mimeType = res.mimeType
            ) { request ->
                if (!bridge.isConnected) {
                    return@addResource ReadResourceResult(
                        contents = listOf(
                            TextResourceContents(
                                text = """{"error": "Not connected to Minecraft"}""",
                                uri = request.uri,
                                mimeType = res.mimeType
                            )
                        )
                    )
                }
                try {
                    val result = bridge.sendRequest(
                        QueryRequest(
                            id = java.util.UUID.randomUUID().toString(),
                            method = res.method,
                            params = JsonObject(emptyMap())
                        )
                    )
                    ReadResourceResult(
                        contents = listOf(
                            TextResourceContents(
                                text = result.toString(),
                                uri = request.uri,
                                mimeType = res.mimeType
                            )
                        )
                    )
                } catch (e: Exception) {
                    ReadResourceResult(
                        contents = listOf(
                            TextResourceContents(
                                text = """{"error": "${e.message}"}""",
                                uri = request.uri,
                                mimeType = res.mimeType
                            )
                        )
                    )
                }
            }
        }
    }
}

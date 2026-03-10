package dev.parrot.mcp

import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GracefulDegradationTest {

    private lateinit var server: Server
    private lateinit var bridge: MinecraftBridge
    private val mockConnection = mockk<ClientConnection>(relaxed = true)

    @BeforeEach
    fun setUp() {
        val config = ParrotConfig(host = "192.0.2.1", port = 1, token = null)
        bridge = MinecraftBridge(config)
        server = Server(
            Implementation(name = "parrot-test", version = "0.0.1"),
            ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))
            )
        )
        ToolRegistrar.registerAll(server, bridge)
    }

    private suspend fun callTool(name: String, arguments: JsonObject = JsonObject(emptyMap())): io.modelcontextprotocol.kotlin.sdk.types.CallToolResult {
        val handler = server.tools[name]?.handler
            ?: error("Tool '$name' not registered")
        val request = CallToolRequest(CallToolRequestParams(name = name, arguments = arguments))
        return handler.invoke(mockConnection, request)
    }

    @Test
    fun `bridge with dummy config is not connected`() {
        assertFalse(bridge.isConnected)
    }

    @Test
    fun `all tool categories return helpful error messages when disconnected`() = runTest {
        val allTools = listOf(
            // Queries
            "get_block" to buildJsonObject { put("x", 0); put("y", 64); put("z", 0) },
            "get_blocks_area" to buildJsonObject {
                put("x1", 0); put("y1", 0); put("z1", 0)
                put("x2", 1); put("y2", 1); put("z2", 1)
            },
            "get_world_info" to JsonObject(emptyMap()),
            "get_player" to JsonObject(emptyMap()),
            "get_inventory" to JsonObject(emptyMap()),
            "get_entities" to buildJsonObject { put("x", 0.0); put("y", 0.0); put("z", 0.0); put("radius", 10.0) },
            "get_entity" to buildJsonObject { put("uuid", "00000000-0000-0000-0000-000000000000") },
            "get_screen" to JsonObject(emptyMap()),
            // Actions
            "do_interact_block" to buildJsonObject { put("x", 0); put("y", 0); put("z", 0) },
            "do_attack_block" to buildJsonObject { put("x", 0); put("y", 0); put("z", 0) },
            "do_interact_entity" to buildJsonObject { put("uuid", "test") },
            "do_attack_entity" to buildJsonObject { put("uuid", "test") },
            "do_click_slot" to buildJsonObject { put("slot", 0) },
            "do_close_screen" to JsonObject(emptyMap()),
            "do_set_held_slot" to buildJsonObject { put("slot", 0) },
            "do_send_chat" to buildJsonObject { put("message", "test") },
            // Other
            "run_command" to buildJsonObject { put("command", "/help") },
            "batch" to buildJsonObject {
                put("commands", kotlinx.serialization.json.JsonArray(emptyList()))
            },
            "subscribe" to buildJsonObject {
                put("eventTypes", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("block_change"))))
            },
            "unsubscribe" to buildJsonObject { put("subscriptionId", "sub-1") },
            "list_methods" to JsonObject(emptyMap()),
        )

        for ((toolName, args) in allTools) {
            val result = callTool(toolName, args)
            assertTrue(result.isError == true, "$toolName should return error when disconnected")
            val text = (result.content.first() as TextContent).text
            assertTrue(
                text.contains("Not connected") && text.contains("Parrot mod"),
                "$toolName should mention 'Not connected' and 'Parrot mod', got: $text"
            )
        }
    }
}

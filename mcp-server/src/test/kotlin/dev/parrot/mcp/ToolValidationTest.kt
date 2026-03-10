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
import kotlin.test.assertTrue

class ToolValidationTest {

    private lateinit var server: Server
    private lateinit var bridge: MinecraftBridge
    private val mockConnection = mockk<ClientConnection>(relaxed = true)

    @BeforeEach
    fun setUp() {
        bridge = MinecraftBridge(ParrotConfig(host = "127.0.0.1", port = 99999, token = null))
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
    fun `run_command without command param returns error`() = runTest {
        val result = callTool("run_command", JsonObject(emptyMap()))
        assertTrue(result.isError == true)
        val text = (result.content.first() as TextContent).text
        assertTrue(text.contains("Not connected"), "Expected 'Not connected' error, got: $text")
    }

    @Test
    fun `batch without commands param returns error`() = runTest {
        val result = callTool("batch", JsonObject(emptyMap()))
        assertTrue(result.isError == true)
        val text = (result.content.first() as TextContent).text
        assertTrue(text.contains("Not connected"), "Expected 'Not connected' error, got: $text")
    }

    @Test
    fun `query tools return not connected when bridge is disconnected`() = runTest {
        val queryTools = listOf(
            "get_block" to buildJsonObject { put("x", 0); put("y", 0); put("z", 0) },
            "get_world_info" to JsonObject(emptyMap()),
            "get_player" to JsonObject(emptyMap()),
            "get_inventory" to JsonObject(emptyMap()),
            "get_screen" to JsonObject(emptyMap()),
            "list_methods" to JsonObject(emptyMap()),
        )

        for ((toolName, args) in queryTools) {
            val result = callTool(toolName, args)
            assertTrue(result.isError == true, "Expected $toolName to return error when disconnected")
            val text = (result.content.first() as TextContent).text
            assertTrue(text.contains("Not connected"), "Expected 'Not connected' for $toolName, got: $text")
        }
    }

    @Test
    fun `action tools return not connected when bridge is disconnected`() = runTest {
        val actionTools = listOf(
            "do_interact_block" to buildJsonObject { put("x", 0); put("y", 0); put("z", 0) },
            "do_attack_block" to buildJsonObject { put("x", 0); put("y", 0); put("z", 0) },
            "do_interact_entity" to buildJsonObject { put("uuid", "test") },
            "do_attack_entity" to buildJsonObject { put("uuid", "test") },
            "do_click_slot" to buildJsonObject { put("slot", 0) },
            "do_close_screen" to JsonObject(emptyMap()),
            "do_set_held_slot" to buildJsonObject { put("slot", 0) },
            "do_send_chat" to buildJsonObject { put("message", "hi") },
        )

        for ((toolName, args) in actionTools) {
            val result = callTool(toolName, args)
            assertTrue(result.isError == true, "Expected $toolName to return error when disconnected")
            val text = (result.content.first() as TextContent).text
            assertTrue(text.contains("Not connected"), "Expected 'Not connected' for $toolName, got: $text")
        }
    }

    @Test
    fun `other tools return not connected when bridge is disconnected`() = runTest {
        val otherTools = listOf(
            "run_command" to buildJsonObject { put("command", "/tp @s 0 0 0") },
            "subscribe" to buildJsonObject {
                put("eventTypes", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("block_change"))))
            },
            "unsubscribe" to buildJsonObject { put("subscriptionId", "sub-1") },
        )

        for ((toolName, args) in otherTools) {
            val result = callTool(toolName, args)
            assertTrue(result.isError == true, "Expected $toolName to return error when disconnected")
            val text = (result.content.first() as TextContent).text
            assertTrue(text.contains("Not connected"), "Expected 'Not connected' for $toolName, got: $text")
        }
    }
}

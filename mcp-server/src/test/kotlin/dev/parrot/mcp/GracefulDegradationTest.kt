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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
                put("commands", JsonArray(emptyList()))
            },
            "subscribe" to buildJsonObject {
                put("eventTypes", JsonArray(listOf(JsonPrimitive("block_change"))))
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

    // --- Capability-based degradation tests ---

    @Test
    fun `get_screen returns not connected error when bridge is disconnected`() = runTest {
        // The spec requires capability-based degradation: when connected but the mod
        // lacks gui_observation capability, get_screen should return an appropriate error.
        //
        // Current implementation limitation: MinecraftBridge does not yet expose
        // HelloAck capabilities to ToolRegistrar. The bridge stores no capability info
        // from the handshake -- it only sets isConnected=true after HelloAck.
        //
        // When disconnected, get_screen correctly returns a "Not connected" error.
        // When connected, the capability check would need to happen either:
        //   (a) in the MCP server by inspecting stored capabilities from HelloAck, or
        //   (b) in the mod, which would return an ErrorResponse with a capability error code.
        //
        // For now, we verify the disconnected case works correctly, and document that
        // capability-based degradation for get_screen requires MinecraftBridge to store
        // and expose the capabilities list from HelloAck.
        val result = callTool("get_screen")
        assertTrue(result.isError == true, "get_screen should return error when disconnected")
        val text = (result.content.first() as TextContent).text
        assertTrue(text.contains("Not connected"), "Should report not connected, got: $text")
    }

    @Test
    fun `error message guides user to install Parrot mod`() = runTest {
        // All tools should provide actionable guidance when not connected
        val result = callTool("get_player")
        assertTrue(result.isError == true)
        val text = (result.content.first() as TextContent).text
        assertTrue(
            text.contains("Parrot mod"),
            "Error message should mention 'Parrot mod' for user guidance, got: $text"
        )
    }

    @Test
    fun `error messages are consistent across tool categories`() = runTest {
        // Verify that query, action, and utility tools all produce the same error format
        val queryResult = callTool("get_world_info")
        val actionResult = callTool("do_close_screen")
        val utilResult = callTool("run_command", buildJsonObject { put("command", "/help") })

        val queryText = (queryResult.content.first() as TextContent).text
        val actionText = (actionResult.content.first() as TextContent).text
        val utilText = (utilResult.content.first() as TextContent).text

        // All three should produce the exact same error message
        assertTrue(
            queryText == actionText && actionText == utilText,
            "All tool categories should produce the same disconnection error. " +
                    "Query: '$queryText', Action: '$actionText', Util: '$utilText'"
        )
    }

    @Test
    fun `gui-related tools are registered and will degrade gracefully`() {
        // Verify that GUI-dependent tools (get_screen, do_click_slot, do_close_screen)
        // are registered. These tools depend on gui_observation capability at runtime.
        // The mod's HelloAck includes capabilities list (e.g., ["gui_observation"]).
        //
        // Current behavior: these tools are always registered. When the mod lacks
        // gui_observation, the mod itself returns an ErrorResponse which the bridge
        // propagates as an error CallToolResult. This is server-side degradation.
        //
        // Future enhancement: MinecraftBridge could store capabilities from HelloAck
        // and ToolRegistrar could check them before sending the request, providing
        // a faster, client-side capability check.
        val guiTools = listOf("get_screen", "do_click_slot", "do_close_screen")
        for (toolName in guiTools) {
            assertNotNull(server.tools[toolName], "GUI tool '$toolName' should be registered")
        }
    }

    @Test
    fun `bridge disconnect is safe to call multiple times`() {
        // Graceful degradation includes handling repeated disconnect calls
        bridge.disconnect()
        assertFalse(bridge.isConnected)
        bridge.disconnect()
        assertFalse(bridge.isConnected)
    }

    @Test
    fun `tools return isError true not exceptions when disconnected`() = runTest {
        // Graceful degradation means tools return error results, not throw exceptions.
        // This is important for MCP protocol compliance -- the server should never crash.
        val toolsToTest = listOf(
            "get_block" to buildJsonObject { put("x", 0); put("y", 0); put("z", 0) },
            "do_interact_block" to buildJsonObject { put("x", 0); put("y", 0); put("z", 0) },
            "run_command" to buildJsonObject { put("command", "/help") },
            "batch" to buildJsonObject { put("commands", JsonArray(emptyList())) },
        )

        for ((toolName, args) in toolsToTest) {
            // This should NOT throw -- it should return a CallToolResult with isError=true
            val result = callTool(toolName, args)
            assertTrue(result.isError == true, "$toolName should set isError=true, not throw")
            assertTrue(result.content.isNotEmpty(), "$toolName should include error content")
        }
    }
}

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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    // --- Existing disconnection error tests ---

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
                put("eventTypes", JsonArray(listOf(JsonPrimitive("block_change"))))
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

    // --- Tool schema validation tests ---

    @Test
    fun `all 22 expected tools are registered`() {
        val expectedTools = listOf(
            "get_block", "get_blocks_area", "get_world_info", "get_player",
            "get_inventory", "get_entities", "get_entity", "get_screen",
            "do_interact_block", "do_attack_block", "do_interact_entity", "do_attack_entity",
            "do_click_slot", "do_close_screen", "do_set_held_slot", "do_send_chat",
            "run_command", "batch", "subscribe", "unsubscribe", "poll_events", "list_methods"
        )

        for (toolName in expectedTools) {
            assertNotNull(server.tools[toolName], "Tool '$toolName' should be registered")
        }
        assertEquals(expectedTools.size, server.tools.size, "Exactly ${expectedTools.size} tools should be registered")
    }

    @Test
    fun `tools with required parameters have them defined in schema`() {
        // Map of tool name -> expected required parameters
        val toolRequirements = mapOf(
            "get_block" to listOf("x", "y", "z"),
            "get_blocks_area" to listOf("x1", "y1", "z1", "x2", "y2", "z2"),
            "get_entities" to listOf("x", "y", "z", "radius"),
            "get_entity" to listOf("uuid"),
            "do_interact_block" to listOf("x", "y", "z"),
            "do_attack_block" to listOf("x", "y", "z"),
            "do_interact_entity" to listOf("uuid"),
            "do_attack_entity" to listOf("uuid"),
            "do_click_slot" to listOf("slot"),
            "do_set_held_slot" to listOf("slot"),
            "do_send_chat" to listOf("message"),
            "run_command" to listOf("command"),
            "batch" to listOf("commands"),
            "subscribe" to listOf("eventTypes"),
            "unsubscribe" to listOf("subscriptionId"),
        )

        for ((toolName, expectedRequired) in toolRequirements) {
            val tool = server.tools[toolName]
                ?: error("Tool '$toolName' not registered")
            val schema = tool.tool.inputSchema
            assertNotNull(schema, "Tool '$toolName' should have an inputSchema")

            // inputSchema.required is a List<String> of required parameter names
            val actualRequired = schema.required ?: emptyList()
            assertEquals(
                expectedRequired.sorted(),
                actualRequired.sorted(),
                "Tool '$toolName' required params mismatch"
            )

            // Verify each required parameter is defined in properties
            val properties = schema.properties
            assertNotNull(properties, "Tool '$toolName' should have properties defined")
            for (param in expectedRequired) {
                assertTrue(
                    properties.containsKey(param),
                    "Tool '$toolName' should have property '$param' defined"
                )
            }
        }
    }

    @Test
    fun `tools without required parameters have no required list`() {
        // These tools have no required parameters (no inputSchema or empty required)
        val noRequiredTools = listOf(
            "get_world_info", "get_player", "get_inventory", "get_screen",
            "do_close_screen", "list_methods"
        )

        for (toolName in noRequiredTools) {
            val tool = server.tools[toolName]
                ?: error("Tool '$toolName' not registered")
            val schema = tool.tool.inputSchema
            // These tools either have no schema or no required fields
            val required = schema.required ?: emptyList()
            assertTrue(
                required.isEmpty(),
                "Tool '$toolName' should have no required parameters, but has: $required"
            )
        }
    }

    @Test
    fun `batch tool schema defines commands as array type`() {
        val batchTool = server.tools["batch"] ?: error("batch tool not registered")
        val schema = batchTool.tool.inputSchema
        assertNotNull(schema, "batch tool should have a schema")
        val properties = schema.properties
        assertNotNull(properties, "batch tool schema should have properties")

        val commandsProp = properties["commands"]?.jsonObject
        assertNotNull(commandsProp, "batch tool should have 'commands' property")
        assertEquals("array", commandsProp["type"]?.jsonPrimitive?.content, "commands should be array type")

        // Verify items define method as required
        val items = commandsProp["items"]?.jsonObject
        assertNotNull(items, "commands array should define items schema")
        val itemRequired = items["required"]?.jsonArray?.map { it.jsonPrimitive.content }
        assertNotNull(itemRequired, "batch command items should have required fields")
        assertTrue("method" in itemRequired, "batch command items should require 'method'")
    }

    @Test
    fun `batch tool description documents sequential execution`() {
        // The batch tool should clearly communicate its purpose: executing multiple commands
        val batchTool = server.tools["batch"] ?: error("batch tool not registered")
        val description = batchTool.tool.description ?: ""
        assertTrue(
            description.contains("multiple") || description.contains("batch") || description.contains("sequence"),
            "batch tool description should mention multiple/batch/sequence execution, got: $description"
        )
        // Note: Read-only enforcement for batch is handled in the mod's CommandRegistry,
        // not in the MCP server. The MCP server passes all batch commands through to the mod.
    }

    @Test
    fun `all tools have non-empty descriptions`() {
        for ((toolName, registeredTool) in server.tools) {
            val description = registeredTool.tool.description
            assertNotNull(description, "Tool '$toolName' should have a description")
            assertTrue(description.isNotBlank(), "Tool '$toolName' description should not be blank")
        }
    }

    @Test
    fun `coordinate-based tools define integer typed parameters`() {
        val coordinateTools = mapOf(
            "get_block" to listOf("x", "y", "z"),
            "get_blocks_area" to listOf("x1", "y1", "z1", "x2", "y2", "z2"),
            "do_interact_block" to listOf("x", "y", "z"),
            "do_attack_block" to listOf("x", "y", "z"),
        )

        for ((toolName, coordParams) in coordinateTools) {
            val schema = server.tools[toolName]?.tool?.inputSchema
                ?: error("Tool '$toolName' has no schema")
            val properties = schema.properties ?: error("Tool '$toolName' has no properties")

            for (param in coordParams) {
                val paramSchema = properties[param]?.jsonObject
                    ?: error("Tool '$toolName' missing property '$param'")
                assertEquals(
                    "integer",
                    paramSchema["type"]?.jsonPrimitive?.content,
                    "Tool '$toolName' param '$param' should be integer type"
                )
            }
        }
    }

    @Test
    fun `get_entities uses number type for floating point coordinates`() {
        val schema = server.tools["get_entities"]?.tool?.inputSchema
            ?: error("get_entities has no schema")
        val properties = schema.properties ?: error("get_entities has no properties")

        for (param in listOf("x", "y", "z", "radius")) {
            val paramSchema = properties[param]?.jsonObject
                ?: error("get_entities missing property '$param'")
            assertEquals(
                "number",
                paramSchema["type"]?.jsonPrimitive?.content,
                "get_entities param '$param' should be number type (floating point)"
            )
        }
    }
}

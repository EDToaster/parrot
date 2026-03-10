package dev.parrot.mcp.integration

import dev.parrot.mcp.MinecraftBridge
import dev.parrot.mcp.ParrotConfig
import dev.parrot.mcp.ToolRegistrar
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class E2EMcpToolTest {
    private lateinit var mockServer: MockMinecraftServer
    private lateinit var bridge: MinecraftBridge
    private lateinit var mcpServer: Server
    private val mockConnection = mockk<ClientConnection>(relaxed = true)
    private val scope = CoroutineScope(Dispatchers.IO)
    private var connectJob: Job? = null

    @BeforeEach
    fun setup() {
        mockServer = MockMinecraftServer()
        mcpServer = Server(
            Implementation(name = "parrot-test", version = "0.0.1"),
            ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))
            )
        )
    }

    @AfterEach
    fun teardown() {
        connectJob?.cancel()
        bridge.disconnect()
        mockServer.stop()
        scope.cancel()
    }

    private suspend fun callTool(name: String, arguments: JsonObject = JsonObject(emptyMap())): io.modelcontextprotocol.kotlin.sdk.types.CallToolResult {
        val handler = mcpServer.tools[name]?.handler
            ?: error("Tool '$name' not registered")
        val request = CallToolRequest(CallToolRequestParams(name = name, arguments = arguments))
        return handler.invoke(mockConnection, request)
    }

    @Test
    fun `get_block tool returns canned response through full MCP stack`() = runBlocking {
        withTimeout(10_000) {
            val cannedBlock = buildJsonObject {
                put("id", "minecraft:diamond_block")
                put("x", 5)
                put("y", 70)
                put("z", -3)
            }
            mockServer.registerResponse("get_block", cannedBlock)
            mockServer.start()

            val config = ParrotConfig(host = "127.0.0.1", port = mockServer.actualPort, token = null)
            bridge = MinecraftBridge(config)
            ToolRegistrar.registerAll(mcpServer, bridge)

            connectJob = scope.launch { bridge.connectWithRetry() }
            waitForConnection(bridge)

            val result = callTool("get_block", buildJsonObject {
                put("x", 5)
                put("y", 70)
                put("z", -3)
            })

            assertFalse(result.isError == true, "Tool call should not be an error")
            val text = (result.content.first() as TextContent).text
            assertTrue(text.contains("minecraft:diamond_block"), "Result should contain block id, got: $text")
            assertTrue(text.contains("70"), "Result should contain y coordinate, got: $text")
        }
    }

    @Test
    fun `run_command tool returns command result through full MCP stack`() = runBlocking {
        withTimeout(10_000) {
            mockServer.start()

            val config = ParrotConfig(host = "127.0.0.1", port = mockServer.actualPort, token = null)
            bridge = MinecraftBridge(config)
            ToolRegistrar.registerAll(mcpServer, bridge)

            connectJob = scope.launch { bridge.connectWithRetry() }
            waitForConnection(bridge)

            val result = callTool("run_command", buildJsonObject {
                put("command", "time set day")
            })

            assertFalse(result.isError == true, "Tool call should not be an error")
            val text = (result.content.first() as TextContent).text
            assertTrue(text.contains("Command executed"), "Result should contain command output, got: $text")
            assertTrue(text.contains("\"success\":true"), "Result should indicate success, got: $text")
        }
    }

    @Test
    fun `do_interact_block action tool returns result through full MCP stack`() = runBlocking {
        withTimeout(10_000) {
            mockServer.start()

            val config = ParrotConfig(host = "127.0.0.1", port = mockServer.actualPort, token = null)
            bridge = MinecraftBridge(config)
            ToolRegistrar.registerAll(mcpServer, bridge)

            connectJob = scope.launch { bridge.connectWithRetry() }
            waitForConnection(bridge)

            val result = callTool("do_interact_block", buildJsonObject {
                put("x", 10)
                put("y", 65)
                put("z", 20)
            })

            assertFalse(result.isError == true, "Tool call should not be an error")
            val text = (result.content.first() as TextContent).text
            assertTrue(text.contains("\"success\":true"), "Result should indicate success, got: $text")
        }
    }
}

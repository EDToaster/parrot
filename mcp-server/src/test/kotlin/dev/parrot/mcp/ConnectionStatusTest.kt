package dev.parrot.mcp

import dev.parrot.mcp.integration.MockMinecraftServer
import dev.parrot.mcp.integration.waitForConnection
import dev.parrot.protocol.ConnectionInfo
import dev.parrot.protocol.ParrotJson
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConnectionStatusTest {

    private lateinit var mockServer: MockMinecraftServer
    private lateinit var bridge: MinecraftBridge
    private lateinit var mcpServer: Server
    private val mockConnection = mockk<ClientConnection>(relaxed = true)
    private val scope = CoroutineScope(Dispatchers.IO)
    private var connectJob: Job? = null

    @BeforeEach
    fun setup() {
        mockServer = MockMinecraftServer()
    }

    @AfterEach
    fun teardown() {
        connectJob?.cancel()
        if (::bridge.isInitialized) bridge.disconnect()
        mockServer.stop()
        scope.cancel()
    }

    private fun createMcpServer(): Server {
        return Server(
            Implementation(name = "parrot-test", version = "0.0.1"),
            ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))
            )
        )
    }

    private suspend fun callTool(name: String, arguments: JsonObject = JsonObject(emptyMap())): io.modelcontextprotocol.kotlin.sdk.types.CallToolResult {
        val handler = mcpServer.tools[name]?.handler
            ?: error("Tool '$name' not registered")
        val request = CallToolRequest(CallToolRequestParams(name = name, arguments = arguments))
        return handler.invoke(mockConnection, request)
    }

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
    fun `connection_status returns connected=false when not connected`() = runBlocking {
        withTimeout(10_000) {
            // Create bridge with bad port (won't connect)
            val config = ParrotConfig(host = "127.0.0.1", port = 1, token = null)
            bridge = MinecraftBridge(config)
            mcpServer = createMcpServer()
            ToolRegistrar.registerAll(mcpServer, bridge)

            val result = callTool("connection_status")
            val text = (result.content.first() as TextContent).text!!
            val json = ParrotJson.decodeFromString<JsonObject>(text)

            assertFalse(json["connected"]!!.jsonPrimitive.boolean)
            assertNotNull(json["hint"])
            assertTrue(json["hint"]!!.jsonPrimitive.content.contains("Not connected"))
        }
    }

    @Test
    fun `connection_status returns connected=true with game_info when connected`() = runBlocking {
        withTimeout(10_000) {
            mockServer.start()
            val config = ParrotConfig(host = "127.0.0.1", port = mockServer.actualPort, token = null)
            bridge = MinecraftBridge(config)
            mcpServer = createMcpServer()
            ToolRegistrar.registerAll(mcpServer, bridge)
            connectJob = scope.launch { bridge.connectWithRetry() }
            waitForConnection(bridge)

            val result = callTool("connection_status")
            val text = (result.content.first() as TextContent).text!!
            val json = ParrotJson.decodeFromString<JsonObject>(text)

            assertTrue(json["connected"]!!.jsonPrimitive.boolean)
            val gameInfo = json["game_info"]!!.jsonObject
            assertEquals("1.21.10", gameInfo["minecraft_version"]!!.jsonPrimitive.content)
            assertEquals("fabric", gameInfo["mod_loader"]!!.jsonPrimitive.content)
            assertEquals("0.1.0", gameInfo["mod_version"]!!.jsonPrimitive.content)
            assertEquals("integrated", gameInfo["server_type"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `connection_status shows connection_file info`() = runBlocking {
        withTimeout(10_000) {
            withTempHome { tempDir ->
                // Write a connection.json file
                val parrotDir = File(tempDir, ".parrot")
                parrotDir.mkdirs()
                val connectionFile = File(parrotDir, "connection.json")
                val connectionInfo = ConnectionInfo(port = 31337, token = "test-token", pid = ProcessHandle.current().pid())
                connectionFile.writeText(ParrotJson.encodeToString(connectionInfo))

                // Create disconnected bridge
                val config = ParrotConfig(host = "127.0.0.1", port = 1, token = null)
                bridge = MinecraftBridge(config)
                mcpServer = createMcpServer()
                ToolRegistrar.registerAll(mcpServer, bridge)

                val result = callTool("connection_status")
                val text = (result.content.first() as TextContent).text!!
                val json = ParrotJson.decodeFromString<JsonObject>(text)

                assertFalse(json["connected"]!!.jsonPrimitive.boolean)
                val connFileInfo = json["connection_file"]!!.jsonObject
                assertTrue(connFileInfo["exists"]!!.jsonPrimitive.boolean)
                assertEquals(31337, connFileInfo["port"]!!.jsonPrimitive.content.toInt())
                assertTrue(connFileInfo["pid_alive"]!!.jsonPrimitive.boolean)
            }
        }
    }
}

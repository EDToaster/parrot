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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WaitForInstanceTest {

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
    fun `wait_for_instance returns immediately when already connected`() = runBlocking {
        withTimeout(15_000) {
            mockServer.start()
            val config = ParrotConfig(host = "127.0.0.1", port = mockServer.actualPort, token = null)
            bridge = MinecraftBridge(config)
            mcpServer = createMcpServer()
            ToolRegistrar.registerAll(mcpServer, bridge)
            connectJob = scope.launch { bridge.connectWithRetry() }
            waitForConnection(bridge)

            val result = callTool("wait_for_instance", buildJsonObject {
                put("timeout_seconds", 5)
            })

            val text = (result.content.first() as TextContent).text!!
            val json = ParrotJson.decodeFromString<JsonObject>(text)

            assertEquals("connected", json["status"]!!.jsonPrimitive.content)
            val elapsedMs = json["elapsed_ms"]!!.jsonPrimitive.long
            assertTrue(elapsedMs < 2000, "Should return quickly when already connected, got ${elapsedMs}ms")
        }
    }

    @Test
    fun `wait_for_instance times out when nothing to connect to`() = runBlocking {
        withTimeout(15_000) {
            withTempHome { _ ->
                val config = ParrotConfig(host = "127.0.0.1", port = 1, token = null)
                bridge = MinecraftBridge(config)
                mcpServer = createMcpServer()
                ToolRegistrar.registerAll(mcpServer, bridge)

                val result = callTool("wait_for_instance", buildJsonObject {
                    put("timeout_seconds", 2)
                    put("poll_interval_ms", 200)
                })

                val text = (result.content.first() as TextContent).text!!
                val json = ParrotJson.decodeFromString<JsonObject>(text)

                assertEquals("timeout", json["status"]!!.jsonPrimitive.content)
                assertTrue(json["message"]!!.jsonPrimitive.content.contains("Timed out"))
            }
        }
    }

    @Test
    fun `wait_for_instance detects new connection file`() = runBlocking {
        withTimeout(30_000) {
            withTempHome { tempDir ->
                mockServer.start()

                val config = ParrotConfig(host = "127.0.0.1", port = 1, token = null)
                bridge = MinecraftBridge(config)
                mcpServer = createMcpServer()
                ToolRegistrar.registerAll(mcpServer, bridge)

                // Launch wait_for_instance in a coroutine
                val resultDeferred = async {
                    callTool("wait_for_instance", buildJsonObject {
                        put("timeout_seconds", 15)
                        put("poll_interval_ms", 500)
                    })
                }

                // After a short delay, write connection.json pointing to mock server
                delay(1500)
                val parrotDir = File(tempDir, ".parrot")
                parrotDir.mkdirs()
                val connectionFile = File(parrotDir, "connection.json")
                val connectionInfo = ConnectionInfo(
                    port = mockServer.actualPort,
                    token = "",
                    pid = ProcessHandle.current().pid()
                )
                connectionFile.writeText(ParrotJson.encodeToString(connectionInfo))

                val result = resultDeferred.await()
                val text = (result.content.first() as TextContent).text!!
                val json = ParrotJson.decodeFromString<JsonObject>(text)

                assertEquals("connected", json["status"]!!.jsonPrimitive.content)
                assertTrue(json["message"]!!.jsonPrimitive.content.contains("Connected"))
            }
        }
    }
}

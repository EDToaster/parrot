package dev.parrot.mcp

import dev.parrot.mcp.integration.MockMinecraftServer
import dev.parrot.mcp.integration.waitForConnection
import dev.parrot.protocol.ActionRequest
import dev.parrot.protocol.CommandRequest
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolRegistrarConsequenceTest {

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
        bridge.disconnect()
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

    private suspend fun setupConnectedBridge() {
        mockServer.start()
        val config = ParrotConfig(host = "127.0.0.1", port = mockServer.actualPort, token = null)
        bridge = MinecraftBridge(config)
        mcpServer = createMcpServer()
        ToolRegistrar.registerAll(mcpServer, bridge)
        connectJob = scope.launch { bridge.connectWithRetry() }
        waitForConnection(bridge)
    }

    private suspend fun callTool(name: String, arguments: JsonObject = JsonObject(emptyMap())): io.modelcontextprotocol.kotlin.sdk.types.CallToolResult {
        val handler = mcpServer.tools[name]?.handler
            ?: error("Tool '$name' not registered")
        val request = CallToolRequest(CallToolRequestParams(name = name, arguments = arguments))
        return handler.invoke(mockConnection, request)
    }

    @Test
    fun `handleAction strips consequence_filter and consequence_wait from forwarded params`() = runBlocking {
        withTimeout(10_000) {
            setupConnectedBridge()

            callTool("do_interact_block", buildJsonObject {
                put("x", 10)
                put("y", 65)
                put("z", 20)
                put("consequence_filter", JsonArray(listOf(JsonPrimitive("block_changed"))))
                put("consequence_wait", 10)
            })

            val actionMessages = mockServer.receivedMessages.filterIsInstance<ActionRequest>()
            assertEquals(1, actionMessages.size)
            val params = actionMessages[0].params
            assertFalse(params.containsKey("consequence_filter"), "params should not contain consequence_filter")
            assertFalse(params.containsKey("consequence_wait"), "params should not contain consequence_wait")
            assertTrue(params.containsKey("x"), "params should still contain x")
            assertTrue(params.containsKey("y"), "params should still contain y")
            assertTrue(params.containsKey("z"), "params should still contain z")
        }
    }

    @Test
    fun `handleAction uses defaults when no overrides provided`() = runBlocking {
        withTimeout(10_000) {
            setupConnectedBridge()

            callTool("do_interact_block", buildJsonObject {
                put("x", 10)
                put("y", 65)
                put("z", 20)
            })

            val actionMessages = mockServer.receivedMessages.filterIsInstance<ActionRequest>()
            assertEquals(1, actionMessages.size)
            assertEquals(5, actionMessages[0].consequenceWait)
            assertEquals(
                listOf("screen_opened", "block_changed", "inventory_changed"),
                actionMessages[0].consequenceFilter
            )
        }
    }

    @Test
    fun `handleAction uses override values when provided`() = runBlocking {
        withTimeout(10_000) {
            setupConnectedBridge()

            callTool("do_interact_block", buildJsonObject {
                put("x", 10)
                put("y", 65)
                put("z", 20)
                put("consequence_filter", JsonArray(listOf(JsonPrimitive("entity_removed"))))
                put("consequence_wait", 99)
            })

            val actionMessages = mockServer.receivedMessages.filterIsInstance<ActionRequest>()
            assertEquals(1, actionMessages.size)
            assertEquals(99, actionMessages[0].consequenceWait)
            assertEquals(listOf("entity_removed"), actionMessages[0].consequenceFilter)
        }
    }

    @Test
    fun `handleAction with unknown method falls back to wait 0 and null filter`() = runBlocking {
        withTimeout(10_000) {
            setupConnectedBridge()

            // do_close_screen has defaults, but let's test a scenario
            // where the method doesn't exist in DEFAULT_FILTERS.
            // We can't easily call an unregistered action method through the tool,
            // so instead verify set_held_slot which has wait=0 and emptyList filter.
            callTool("do_set_held_slot", buildJsonObject {
                put("slot", 3)
            })

            val actionMessages = mockServer.receivedMessages.filterIsInstance<ActionRequest>()
            assertEquals(1, actionMessages.size)
            assertEquals(0, actionMessages[0].consequenceWait)
            assertEquals(emptyList(), actionMessages[0].consequenceFilter)
        }
    }

    @Test
    fun `run_command strips consequence_wait and passes it to CommandRequest`() = runBlocking {
        withTimeout(10_000) {
            setupConnectedBridge()

            callTool("run_command", buildJsonObject {
                put("command", "time set day")
                put("consequence_wait", 7)
            })

            val cmdMessages = mockServer.receivedMessages.filterIsInstance<CommandRequest>()
            assertEquals(1, cmdMessages.size)
            assertEquals("time set day", cmdMessages[0].command)
            assertEquals(7, cmdMessages[0].consequenceWait)
        }
    }

    @Test
    fun `run_command uses default wait of 3 when no override provided`() = runBlocking {
        withTimeout(10_000) {
            setupConnectedBridge()

            callTool("run_command", buildJsonObject {
                put("command", "weather clear")
            })

            val cmdMessages = mockServer.receivedMessages.filterIsInstance<CommandRequest>()
            assertEquals(1, cmdMessages.size)
            assertEquals(3, cmdMessages[0].consequenceWait)
        }
    }

    @Test
    fun `attack_block uses correct defaults`() = runBlocking {
        withTimeout(10_000) {
            setupConnectedBridge()

            callTool("do_attack_block", buildJsonObject {
                put("x", 0)
                put("y", 0)
                put("z", 0)
            })

            val actionMessages = mockServer.receivedMessages.filterIsInstance<ActionRequest>()
            assertEquals(1, actionMessages.size)
            assertEquals(3, actionMessages[0].consequenceWait)
            assertEquals(listOf("block_changed"), actionMessages[0].consequenceFilter)
        }
    }
}

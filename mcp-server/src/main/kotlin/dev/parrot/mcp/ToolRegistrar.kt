package dev.parrot.mcp

import dev.parrot.protocol.ActionRequest
import dev.parrot.protocol.BatchCommand
import dev.parrot.protocol.BatchRequest
import dev.parrot.protocol.CommandRequest
import dev.parrot.protocol.QueryRequest
import dev.parrot.protocol.SubscribeRequest
import dev.parrot.protocol.UnsubscribeRequest
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

object ToolRegistrar {

    private data class ConsequenceDefaults(
        val filter: List<String>?,  // null means all types
        val wait: Int
    )

    private val DEFAULT_FILTERS = mapOf(
        "interact_block" to ConsequenceDefaults(listOf("screen_opened", "block_changed", "inventory_changed"), 5),
        "attack_block" to ConsequenceDefaults(listOf("block_changed"), 3),
        "interact_entity" to ConsequenceDefaults(listOf("screen_opened", "inventory_changed"), 5),
        "attack_entity" to ConsequenceDefaults(listOf("entity_removed"), 5),
        "click_slot" to ConsequenceDefaults(listOf("inventory_changed"), 2),
        "close_screen" to ConsequenceDefaults(listOf("screen_closed", "inventory_changed"), 2),
        "set_held_slot" to ConsequenceDefaults(emptyList(), 0),
        "send_chat" to ConsequenceDefaults(listOf("chat_message"), 3),
    )

    fun registerAll(server: Server, bridge: MinecraftBridge) {
        registerQueryTools(server, bridge)
        registerActionTools(server, bridge)
        registerOtherTools(server, bridge)
    }

    private fun registerQueryTools(server: Server, bridge: MinecraftBridge) {
        // 1. get_block
        server.addTool(
            name = "get_block",
            description = "Get the block state at a specific position",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("x") { put("type", "integer"); put("description", "X coordinate") }
                    putJsonObject("y") { put("type", "integer"); put("description", "Y coordinate") }
                    putJsonObject("z") { put("type", "integer"); put("description", "Z coordinate") }
                },
                required = listOf("x", "y", "z")
            )
        ) { request ->
            handleQuery(bridge, "get_block", request.arguments)
        }

        // 2. get_blocks_area
        server.addTool(
            name = "get_blocks_area",
            description = "Get all blocks in a rectangular area",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("x1") { put("type", "integer"); put("description", "First corner X coordinate") }
                    putJsonObject("y1") { put("type", "integer"); put("description", "First corner Y coordinate") }
                    putJsonObject("z1") { put("type", "integer"); put("description", "First corner Z coordinate") }
                    putJsonObject("x2") { put("type", "integer"); put("description", "Second corner X coordinate") }
                    putJsonObject("y2") { put("type", "integer"); put("description", "Second corner Y coordinate") }
                    putJsonObject("z2") { put("type", "integer"); put("description", "Second corner Z coordinate") }
                },
                required = listOf("x1", "y1", "z1", "x2", "y2", "z2")
            )
        ) { request ->
            handleQuery(bridge, "get_blocks_area", request.arguments)
        }

        // 3. get_world_info
        server.addTool(
            name = "get_world_info",
            description = "Get world information including time, weather, and dimension"
        ) { request ->
            handleQuery(bridge, "get_world_info", request.arguments)
        }

        // 4. get_player
        server.addTool(
            name = "get_player",
            description = "Get player information including position, health, food level, and game mode"
        ) { request ->
            handleQuery(bridge, "get_player", request.arguments)
        }

        // 5. get_inventory
        server.addTool(
            name = "get_inventory",
            description = "Get the player's inventory contents"
        ) { request ->
            handleQuery(bridge, "get_inventory", request.arguments)
        }

        // 6. get_entities
        server.addTool(
            name = "get_entities",
            description = "Get entities near a position",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("x") { put("type", "number"); put("description", "Center X coordinate") }
                    putJsonObject("y") { put("type", "number"); put("description", "Center Y coordinate") }
                    putJsonObject("z") { put("type", "number"); put("description", "Center Z coordinate") }
                    putJsonObject("radius") { put("type", "number"); put("description", "Search radius") }
                },
                required = listOf("x", "y", "z", "radius")
            )
        ) { request ->
            handleQuery(bridge, "get_entities", request.arguments)
        }

        // 7. get_entity
        server.addTool(
            name = "get_entity",
            description = "Get detailed information about a specific entity",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("uuid") { put("type", "string"); put("description", "Entity UUID") }
                },
                required = listOf("uuid")
            )
        ) { request ->
            handleQuery(bridge, "get_entity", request.arguments)
        }

        // 8. get_screen
        server.addTool(
            name = "get_screen",
            description = "Get the currently open GUI screen"
        ) { request ->
            handleQuery(bridge, "get_screen", request.arguments)
        }
    }

    private fun registerActionTools(server: Server, bridge: MinecraftBridge) {
        // 9. do_interact_block
        server.addTool(
            name = "do_interact_block",
            description = "Right-click/interact with a block. Waits 5 ticks for screen_opened, block_changed, inventory_changed within 8 blocks. Override with consequence_filter/consequence_wait.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("x") { put("type", "integer"); put("description", "Block X coordinate") }
                    putJsonObject("y") { put("type", "integer"); put("description", "Block Y coordinate") }
                    putJsonObject("z") { put("type", "integer"); put("description", "Block Z coordinate") }
                    putJsonObject("face") { put("type", "string"); put("description", "Block face to interact with (default: up)") }
                    putJsonObject("hand") { put("type", "string"); put("description", "Hand to use (default: main_hand)") }
                    putJsonObject("consequence_filter") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put("description", "Override event types to collect. Replaces defaults.")
                    }
                    putJsonObject("consequence_wait") {
                        put("type", "integer")
                        put("description", "Override ticks to wait for consequences. 0 disables collection.")
                    }
                },
                required = listOf("x", "y", "z")
            )
        ) { request ->
            handleAction(bridge, "interact_block", request.arguments)
        }

        // 10. do_attack_block
        server.addTool(
            name = "do_attack_block",
            description = "Left-click/attack a block (start breaking). Waits 3 ticks for block_changed within 8 blocks. Override with consequence_filter/consequence_wait.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("x") { put("type", "integer"); put("description", "Block X coordinate") }
                    putJsonObject("y") { put("type", "integer"); put("description", "Block Y coordinate") }
                    putJsonObject("z") { put("type", "integer"); put("description", "Block Z coordinate") }
                    putJsonObject("face") { put("type", "string"); put("description", "Block face to attack") }
                    putJsonObject("consequence_filter") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put("description", "Override event types to collect. Replaces defaults.")
                    }
                    putJsonObject("consequence_wait") {
                        put("type", "integer")
                        put("description", "Override ticks to wait for consequences. 0 disables collection.")
                    }
                },
                required = listOf("x", "y", "z")
            )
        ) { request ->
            handleAction(bridge, "attack_block", request.arguments)
        }

        // 11. do_interact_entity
        server.addTool(
            name = "do_interact_entity",
            description = "Right-click/interact with an entity. Waits 5 ticks for screen_opened, inventory_changed. Override with consequence_filter/consequence_wait.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("uuid") { put("type", "string"); put("description", "Entity UUID") }
                    putJsonObject("hand") { put("type", "string"); put("description", "Hand to use") }
                    putJsonObject("consequence_filter") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put("description", "Override event types to collect. Replaces defaults.")
                    }
                    putJsonObject("consequence_wait") {
                        put("type", "integer")
                        put("description", "Override ticks to wait for consequences. 0 disables collection.")
                    }
                },
                required = listOf("uuid")
            )
        ) { request ->
            handleAction(bridge, "interact_entity", request.arguments)
        }

        // 12. do_attack_entity
        server.addTool(
            name = "do_attack_entity",
            description = "Left-click/attack an entity. Waits 5 ticks for entity_removed. Override with consequence_filter/consequence_wait.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("uuid") { put("type", "string"); put("description", "Entity UUID") }
                    putJsonObject("consequence_filter") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put("description", "Override event types to collect. Replaces defaults.")
                    }
                    putJsonObject("consequence_wait") {
                        put("type", "integer")
                        put("description", "Override ticks to wait for consequences. 0 disables collection.")
                    }
                },
                required = listOf("uuid")
            )
        ) { request ->
            handleAction(bridge, "attack_entity", request.arguments)
        }

        // 13. do_click_slot
        server.addTool(
            name = "do_click_slot",
            description = "Click a slot in the current screen. Waits 2 ticks for inventory_changed. Override with consequence_filter/consequence_wait.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("slot_index") { put("type", "integer"); put("description", "Slot index to click") }
                    putJsonObject("button") { put("type", "integer"); put("description", "Mouse button (default: 0)") }
                    putJsonObject("shift") { put("type", "boolean"); put("description", "Use shift-click / QUICK_MOVE (default: false)") }
                    putJsonObject("consequence_filter") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put("description", "Override event types to collect. Replaces defaults.")
                    }
                    putJsonObject("consequence_wait") {
                        put("type", "integer")
                        put("description", "Override ticks to wait for consequences. 0 disables collection.")
                    }
                },
                required = listOf("slot_index", "button")
            )
        ) { request ->
            handleAction(bridge, "click_slot", request.arguments)
        }

        // 14. do_close_screen
        server.addTool(
            name = "do_close_screen",
            description = "Close the currently open screen. Waits 2 ticks for screen_closed, inventory_changed. Override with consequence_filter/consequence_wait.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("consequence_filter") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put("description", "Override event types to collect. Replaces defaults.")
                    }
                    putJsonObject("consequence_wait") {
                        put("type", "integer")
                        put("description", "Override ticks to wait for consequences. 0 disables collection.")
                    }
                },
                required = emptyList()
            )
        ) { request ->
            handleAction(bridge, "close_screen", request.arguments)
        }

        // 15. do_set_held_slot
        server.addTool(
            name = "do_set_held_slot",
            description = "Set the player's held item slot. No consequence collection by default.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("slot") { put("type", "integer"); put("description", "Hotbar slot index (0-8)") }
                    putJsonObject("consequence_filter") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put("description", "Override event types to collect. Replaces defaults.")
                    }
                    putJsonObject("consequence_wait") {
                        put("type", "integer")
                        put("description", "Override ticks to wait for consequences. 0 disables collection.")
                    }
                },
                required = listOf("slot")
            )
        ) { request ->
            handleAction(bridge, "set_held_slot", request.arguments)
        }

        // 16. do_send_chat
        server.addTool(
            name = "do_send_chat",
            description = "Send a chat message. Waits 3 ticks for chat_message. Override with consequence_filter/consequence_wait.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("message") { put("type", "string"); put("description", "Chat message to send") }
                    putJsonObject("consequence_filter") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put("description", "Override event types to collect. Replaces defaults.")
                    }
                    putJsonObject("consequence_wait") {
                        put("type", "integer")
                        put("description", "Override ticks to wait for consequences. 0 disables collection.")
                    }
                },
                required = listOf("message")
            )
        ) { request ->
            handleAction(bridge, "send_chat", request.arguments)
        }
    }

    private fun registerOtherTools(server: Server, bridge: MinecraftBridge) {
        // 17. run_command
        server.addTool(
            name = "run_command",
            description = "Execute a server command. Waits 3 ticks for all consequence types. Override with consequence_wait.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("command") { put("type", "string"); put("description", "Server command to execute") }
                    putJsonObject("consequence_wait") {
                        put("type", "integer")
                        put("description", "Override ticks to wait for consequences. 0 disables collection.")
                    }
                },
                required = listOf("command")
            )
        ) { request ->
            if (!bridge.isConnected) return@addTool notConnectedResult()
            try {
                val args = request.arguments ?: JsonObject(emptyMap())
                val waitValue = args["consequence_wait"]?.jsonPrimitive?.intOrNull ?: 3
                val strippedArgs = JsonObject(args.filterKeys { it != "consequence_wait" })
                val command = strippedArgs["command"]?.jsonPrimitive?.content
                    ?: return@addTool CallToolResult(content = listOf(TextContent("Error: missing 'command' parameter")), isError = true)
                val result = bridge.sendRequest(
                    CommandRequest(
                        id = UUID.randomUUID().toString(),
                        command = command,
                        consequenceWait = waitValue
                    )
                )
                CallToolResult(content = listOf(TextContent(result.toString())))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
            }
        }

        // 18. batch
        server.addTool(
            name = "batch",
            description = "Execute multiple read-only queries in a single request. Actions are not supported in batch — use individual do_ tools for actions that need consequence feedback.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("commands") {
                        put("type", "array")
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("method") { put("type", "string") }
                                putJsonObject("params") { put("type", "object") }
                            }
                            put("required", JsonArray(listOf(
                                JsonPrimitive("method")
                            )))
                        }
                        put("description", "Array of commands to execute")
                    }
                },
                required = listOf("commands")
            )
        ) { request ->
            if (!bridge.isConnected) return@addTool notConnectedResult()
            try {
                val args = request.arguments ?: JsonObject(emptyMap())
                val commandsArray = args["commands"]?.jsonArray
                    ?: return@addTool CallToolResult(content = listOf(TextContent("Error: missing 'commands' parameter")), isError = true)
                val batchCommands = commandsArray.map { element ->
                    val obj = element.jsonObject
                    BatchCommand(
                        method = obj["method"]!!.jsonPrimitive.content,
                        params = obj["params"]?.jsonObject ?: JsonObject(emptyMap())
                    )
                }
                val result = bridge.sendRequest(
                    BatchRequest(
                        id = UUID.randomUUID().toString(),
                        commands = batchCommands
                    )
                )
                CallToolResult(content = listOf(TextContent(result.toString())))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
            }
        }

        // 19. subscribe
        server.addTool(
            name = "subscribe",
            description = "Subscribe to game events",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("eventTypes") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put("description", "List of event types to subscribe to")
                    }
                    putJsonObject("filter") { put("type", "object"); put("description", "Optional event filter") }
                },
                required = listOf("eventTypes")
            )
        ) { request ->
            if (!bridge.isConnected) return@addTool notConnectedResult()
            try {
                val args = request.arguments ?: JsonObject(emptyMap())
                val eventTypes = args["eventTypes"]?.jsonArray?.map { it.jsonPrimitive.content }
                    ?: return@addTool CallToolResult(content = listOf(TextContent("Error: missing 'eventTypes' parameter")), isError = true)
                val filter = args["filter"]?.jsonObject
                val result = bridge.sendRequest(
                    SubscribeRequest(
                        id = UUID.randomUUID().toString(),
                        eventTypes = eventTypes,
                        filter = filter
                    )
                )
                CallToolResult(content = listOf(TextContent(result.toString())))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
            }
        }

        // 20. unsubscribe
        server.addTool(
            name = "unsubscribe",
            description = "Unsubscribe from game events",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("subscriptionId") { put("type", "string"); put("description", "Subscription ID to cancel") }
                },
                required = listOf("subscriptionId")
            )
        ) { request ->
            if (!bridge.isConnected) return@addTool notConnectedResult()
            try {
                val args = request.arguments ?: JsonObject(emptyMap())
                val subscriptionId = args["subscriptionId"]?.jsonPrimitive?.content
                    ?: return@addTool CallToolResult(content = listOf(TextContent("Error: missing 'subscriptionId' parameter")), isError = true)
                val result = bridge.sendRequest(
                    UnsubscribeRequest(
                        id = UUID.randomUUID().toString(),
                        subscriptionId = subscriptionId
                    )
                )
                CallToolResult(content = listOf(TextContent(result.toString())))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
            }
        }

        // 21. poll_events
        server.addTool(
            name = "poll_events",
            description = "Drain buffered push events. Optionally filter by subscriptionId.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("subscriptionId") { put("type", "string"); put("description", "Subscription ID to drain events for (omit for all)") }
                },
                required = emptyList()
            )
        ) { request ->
            if (!bridge.isConnected) return@addTool notConnectedResult()
            try {
                val args = request.arguments ?: JsonObject(emptyMap())
                val subId = args["subscriptionId"]?.jsonPrimitive?.content
                val events = bridge.drainEvents(subId)
                val result = buildJsonObject {
                    put("count", events.size)
                    putJsonArray("events") { events.forEach { add(it) } }
                }
                CallToolResult(content = listOf(TextContent(result.toString())))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
            }
        }

        // 22. connection_status
        server.addTool(
            name = "connection_status",
            description = "Check the current connection status to Minecraft. Returns connection state, game info, and connection file details."
        ) { _ ->
            try {
                val connected = bridge.isConnected
                val gameInfo = bridge.gameInfo
                val connFile = Config.readConnectionFile()

                val result = buildJsonObject {
                    put("connected", connected)
                    if (gameInfo != null) {
                        putJsonObject("game_info") {
                            put("minecraft_version", gameInfo.minecraftVersion)
                            put("mod_loader", gameInfo.modLoader)
                            put("mod_version", gameInfo.modVersion)
                            put("server_type", gameInfo.serverType)
                        }
                    } else {
                        put("game_info", null as String?)
                    }
                    if (connFile != null) {
                        putJsonObject("connection_file") {
                            put("exists", true)
                            put("port", connFile.port)
                            connFile.pid?.let { put("pid", it) }
                            put("pid_alive", Config.isPidAlive(connFile.pid))
                        }
                    } else {
                        putJsonObject("connection_file") {
                            put("exists", false)
                        }
                    }
                    if (!connected) {
                        put("hint", "Not connected. Start Minecraft with: ./gradlew :mod:fabric:runClient &")
                    }
                }
                CallToolResult(content = listOf(TextContent(result.toString())))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
            }
        }

        // 23. wait_for_instance
        server.addTool(
            name = "wait_for_instance",
            description = "Wait for a Minecraft instance to become available. Polls for a connection file and connects when found.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("timeout_seconds") { put("type", "integer"); put("description", "Max seconds to wait (default: 120)") }
                    putJsonObject("poll_interval_ms") { put("type", "integer"); put("description", "Polling interval in ms (default: 2000)") }
                },
                required = emptyList()
            )
        ) { request ->
            try {
                val args = request.arguments ?: JsonObject(emptyMap())
                val timeoutSeconds = args["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 120
                val pollIntervalMs = args["poll_interval_ms"]?.jsonPrimitive?.intOrNull ?: 2000
                val startTime = System.currentTimeMillis()
                val timeoutMs = timeoutSeconds * 1000L

                // If already connected, return immediately
                if (bridge.isConnected) {
                    val elapsed = System.currentTimeMillis() - startTime
                    val gi = bridge.gameInfo
                    val result = buildJsonObject {
                        put("status", "connected")
                        if (gi != null) {
                            putJsonObject("connection_info") {
                                put("minecraft_version", gi.minecraftVersion)
                                put("mod_loader", gi.modLoader)
                                put("mod_version", gi.modVersion)
                                put("server_type", gi.serverType)
                            }
                        }
                        put("elapsed_ms", elapsed)
                        put("message", buildConnectionMessage(gi, elapsed))
                    }
                    return@addTool CallToolResult(content = listOf(TextContent(result.toString())))
                }

                // Snapshot current connection file state
                val snapshot = Config.readConnectionFile()

                // Poll loop
                while (System.currentTimeMillis() - startTime < timeoutMs) {
                    val connInfo = Config.readConnectionFile()
                    if (connInfo != null) {
                        val changed = snapshot == null ||
                            connInfo.port != snapshot.port ||
                            connInfo.pid != snapshot.pid

                        if (changed && Config.isPidAlive(connInfo.pid)) {
                            val newConfig = ParrotConfig(
                                host = bridge.config.host,
                                port = connInfo.port,
                                token = connInfo.token
                            )
                            bridge.reconnectTo(newConfig, CoroutineScope(Dispatchers.IO))
                            // Wait for the bridge to establish connection
                            val connectDeadline = System.currentTimeMillis() + 15_000
                            while (System.currentTimeMillis() < connectDeadline && System.currentTimeMillis() - startTime < timeoutMs) {
                                delay(500)
                                if (bridge.isConnected) {
                                    val elapsed = System.currentTimeMillis() - startTime
                                    val gi = bridge.gameInfo
                                    val result = buildJsonObject {
                                        put("status", "connected")
                                        putJsonObject("connection_info") {
                                            put("port", connInfo.port)
                                            connInfo.pid?.let { put("pid", it) }
                                            if (gi != null) {
                                                put("minecraft_version", gi.minecraftVersion)
                                                put("mod_loader", gi.modLoader)
                                                put("mod_version", gi.modVersion)
                                                put("server_type", gi.serverType)
                                            }
                                        }
                                        put("elapsed_ms", elapsed)
                                        put("message", buildConnectionMessage(gi, elapsed))
                                    }
                                    return@addTool CallToolResult(content = listOf(TextContent(result.toString())))
                                }
                            }
                        }
                    }
                    delay(pollIntervalMs.toLong())
                }

                // Timeout
                val result = buildJsonObject {
                    put("status", "timeout")
                    put("elapsed_ms", System.currentTimeMillis() - startTime)
                    put("message", "Timed out waiting for Minecraft after ${timeoutSeconds}s")
                }
                CallToolResult(content = listOf(TextContent(result.toString())))
            } catch (e: Exception) {
                val result = buildJsonObject {
                    put("status", "error")
                    put("message", "Error: ${e.message}")
                }
                CallToolResult(content = listOf(TextContent(result.toString())), isError = true)
            }
        }

        // 24. list_methods
        server.addTool(
            name = "list_methods",
            description = "List all available query and action methods"
        ) { request ->
            handleQuery(bridge, "list_methods", request.arguments)
        }
    }

    private suspend fun handleQuery(bridge: MinecraftBridge, method: String, arguments: JsonObject?): CallToolResult {
        if (!bridge.isConnected) return notConnectedResult()
        return try {
            val args = arguments ?: JsonObject(emptyMap())
            val result = bridge.sendRequest(
                QueryRequest(
                    id = UUID.randomUUID().toString(),
                    method = method,
                    params = args
                )
            )
            CallToolResult(content = listOf(TextContent(result.toString())))
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
        }
    }

    private suspend fun handleAction(bridge: MinecraftBridge, method: String, arguments: JsonObject?): CallToolResult {
        if (!bridge.isConnected) return notConnectedResult()
        return try {
            val args = arguments ?: JsonObject(emptyMap())
            val defaults = DEFAULT_FILTERS[method]

            // Extract overrides from args
            val overrideFilter = args["consequence_filter"]?.jsonArray?.map { it.jsonPrimitive.content }
            val overrideWait = args["consequence_wait"]?.jsonPrimitive?.intOrNull

            // Strip consequence params before forwarding to mod
            val strippedArgs = JsonObject(args.filterKeys { it != "consequence_filter" && it != "consequence_wait" })

            val consequenceWait = overrideWait ?: defaults?.wait ?: 0
            val consequenceFilter = overrideFilter ?: defaults?.filter

            val result = bridge.sendRequest(
                ActionRequest(
                    id = UUID.randomUUID().toString(),
                    method = method,
                    params = strippedArgs,
                    consequenceWait = consequenceWait,
                    consequenceFilter = consequenceFilter
                )
            )
            CallToolResult(content = listOf(TextContent(result.toString())))
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
        }
    }

    private fun buildConnectionMessage(gameInfo: GameInfo?, elapsedMs: Long): String {
        val seconds = elapsedMs / 1000
        return if (gameInfo != null) {
            "Connected to Minecraft ${gameInfo.minecraftVersion} (${gameInfo.modLoader}) in ${seconds}s"
        } else {
            "Connected to Minecraft in ${seconds}s"
        }
    }

    private fun notConnectedResult() = CallToolResult(
        content = listOf(TextContent("Not connected to Minecraft. Start the game with the Parrot mod, then use wait_for_instance to connect.")),
        isError = true
    )
}

package dev.parrot.mod.engine

import dev.parrot.mod.commands.CommandContext
import dev.parrot.mod.commands.CommandRegistry
import dev.parrot.mod.commands.ParrotException
import dev.parrot.mod.engine.bridge.PlatformBridge
import dev.parrot.protocol.*
import io.netty.channel.Channel
import kotlinx.serialization.json.*
import net.minecraft.server.MinecraftServer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue

class CommandQueue(
    private val registry: CommandRegistry,
    private val platformBridge: PlatformBridge? = null,
    private val maxPerTick: Int = 20
) {
    private val queue = ConcurrentLinkedQueue<PendingCommand>()

    fun enqueue(message: ParrotMessage, channel: Channel): CompletableFuture<ParrotMessage> {
        val future = CompletableFuture<ParrotMessage>()
        queue.add(PendingCommand(message, channel, future))
        return future
    }

    fun drainAndExecute(server: MinecraftServer, tickCount: Long) {
        var executed = 0
        while (executed < maxPerTick) {
            val pending = queue.poll() ?: break
            try {
                // Dispatch to command registry (implemented in Phase 3)
                val response = dispatch(pending.message, server, tickCount)
                pending.future.complete(response)
            } catch (e: Exception) {
                pending.future.complete(ErrorResponse(
                    id = pending.message.id,
                    code = ErrorCode.INTERNAL_ERROR.code,
                    message = e.message ?: "Internal error"
                ))
            }
            executed++
        }
    }

    private fun dispatch(message: ParrotMessage, server: MinecraftServer, tickCount: Long): ParrotMessage {
        val context = CommandContext(
            server = server,
            player = server.playerList.players.firstOrNull(),
            tickCount = tickCount,
            screenReader = platformBridge?.getScreenReader()
        )

        return when (message) {
            is QueryRequest -> {
                val handler = registry.get(message.method)
                    ?: return ErrorResponse(id = message.id, code = ErrorCode.UNKNOWN_METHOD.code, message = "Unknown method: ${message.method}")
                try {
                    val result = handler.handle(message.params, context)
                    QueryResult(id = message.id, tick = tickCount, result = result)
                } catch (e: ParrotException) {
                    ErrorResponse(id = message.id, code = e.errorCode.code, message = e.message)
                }
            }

            is ActionRequest -> {
                val handler = registry.get(message.method)
                    ?: return ErrorResponse(id = message.id, code = ErrorCode.UNKNOWN_METHOD.code, message = "Unknown method: ${message.method}")
                try {
                    val result = handler.handle(message.params, context)
                    ActionResult(id = message.id, success = true, tick = tickCount, result = result)
                } catch (e: ParrotException) {
                    ErrorResponse(id = message.id, code = e.errorCode.code, message = e.message)
                }
            }

            is CommandRequest -> {
                val handler = registry.get("run_command")
                    ?: return ErrorResponse(id = message.id, code = ErrorCode.UNKNOWN_METHOD.code, message = "run_command handler not registered")
                try {
                    val params = buildJsonObject { put("command", message.command) }
                    val result = handler.handle(params, context)
                    CommandResult(
                        id = message.id,
                        success = result["success"]?.toString() == "true",
                        tick = tickCount,
                        output = result["output"]?.toString() ?: ""
                    )
                } catch (e: ParrotException) {
                    ErrorResponse(id = message.id, code = e.errorCode.code, message = e.message)
                }
            }

            is BatchRequest -> {
                val handler = registry.get("batch")
                    ?: return ErrorResponse(id = message.id, code = ErrorCode.UNKNOWN_METHOD.code, message = "batch handler not registered")
                try {
                    val params = buildJsonObject {
                        putJsonArray("commands") {
                            for (cmd in message.commands) {
                                add(buildJsonObject {
                                    put("method", cmd.method)
                                    put("params", cmd.params)
                                })
                            }
                        }
                    }
                    val result = handler.handle(params, context)
                    val resultArray = result["results"]?.jsonArray ?: JsonArray(emptyList())
                    val resultObjects = resultArray.map { it.jsonObject }
                    BatchResult(id = message.id, results = resultObjects)
                } catch (e: ParrotException) {
                    ErrorResponse(id = message.id, code = e.errorCode.code, message = e.message)
                }
            }

            is SubscribeRequest -> {
                val handler = registry.get("subscribe_events")
                    ?: return ErrorResponse(id = message.id, code = ErrorCode.UNKNOWN_METHOD.code, message = "subscribe handler not registered")
                try {
                    val params = buildJsonObject {
                        putJsonArray("event_types") { message.eventTypes.forEach { add(JsonPrimitive(it)) } }
                        message.filter?.let { put("spatial_filter", it) }
                    }
                    val result = handler.handle(params, context)
                    SubscribeAck(
                        id = message.id,
                        subscriptionId = result["subscription_id"]?.jsonPrimitive?.content ?: "",
                        subscribedEvents = message.eventTypes
                    )
                } catch (e: ParrotException) {
                    ErrorResponse(id = message.id, code = e.errorCode.code, message = e.message)
                }
            }

            is UnsubscribeRequest -> {
                val handler = registry.get("unsubscribe_events")
                    ?: return ErrorResponse(id = message.id, code = ErrorCode.UNKNOWN_METHOD.code, message = "unsubscribe handler not registered")
                try {
                    val params = buildJsonObject { put("subscription_id", message.subscriptionId) }
                    val result = handler.handle(params, context)
                    UnsubscribeAck(
                        id = message.id,
                        success = result["success"]?.toString() == "true"
                    )
                } catch (e: ParrotException) {
                    ErrorResponse(id = message.id, code = e.errorCode.code, message = e.message)
                }
            }

            else -> ErrorResponse(
                id = message.id,
                code = ErrorCode.INVALID_REQUEST.code,
                message = "Unsupported message type: ${message::class.simpleName}"
            )
        }
    }
}

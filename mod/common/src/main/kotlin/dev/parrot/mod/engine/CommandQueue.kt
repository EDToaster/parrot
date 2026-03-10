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
    private val maxPerTick: Int = 20,
    private val consequenceCollector: ConsequenceCollector? = null
) {
    private val queue = ConcurrentLinkedQueue<PendingCommand>()
    private val deferred = mutableListOf<PendingCommand>()

    fun enqueue(message: ParrotMessage, channel: Channel): CompletableFuture<ParrotMessage> {
        val future = CompletableFuture<ParrotMessage>()
        queue.add(PendingCommand(message, channel, future))
        return future
    }

    fun drainAndExecute(server: MinecraftServer, tickCount: Long) {
        // Phase 2: Complete deferred commands whose collection windows are done
        val completed = deferred.filter { it.collectionHandle?.future?.isDone == true }
        for (pending in completed) {
            try {
                val consequences = pending.collectionHandle!!.future.get()
                val response = buildDeferredResponse(pending, consequences)
                pending.future.complete(response)
            } catch (e: Exception) {
                pending.future.complete(ErrorResponse(
                    id = pending.message.id,
                    code = ErrorCode.INTERNAL_ERROR.code,
                    message = "Error completing deferred response: ${e.message}"
                ))
            }
        }
        deferred.removeAll(completed.toSet())

        // Phase 1: Drain new commands
        var executed = 0
        while (executed < maxPerTick) {
            val pending = queue.poll() ?: break
            try {
                val response = dispatch(pending, server, tickCount)
                if (response != null) {
                    pending.future.complete(response)
                }
                // if null, command was deferred — future will be completed in phase 2 later
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

    private fun dispatch(pending: PendingCommand, server: MinecraftServer, tickCount: Long): ParrotMessage? {
        val message = pending.message
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
                    if (message.consequenceWait > 0 && consequenceCollector != null) {
                        val handle = consequenceCollector.startCollecting(
                            startTick = tickCount,
                            tickWindow = message.consequenceWait,
                            filter = message.consequenceFilter
                        )
                        deferred.add(PendingCommand(
                            message = message, channel = pending.channel, future = pending.future,
                            collectionHandle = handle,
                            deferredResult = DeferredResult.Action(result = result, tick = tickCount)
                        ))
                        null // response deferred
                    } else {
                        ActionResult(id = message.id, success = true, tick = tickCount, result = result)
                    }
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
                    if (message.consequenceWait > 0 && consequenceCollector != null) {
                        val handle = consequenceCollector.startCollecting(
                            startTick = tickCount,
                            tickWindow = message.consequenceWait,
                            filter = null
                        )
                        deferred.add(PendingCommand(
                            message = message, channel = pending.channel, future = pending.future,
                            collectionHandle = handle,
                            deferredResult = DeferredResult.Command(
                                output = result["output"]?.jsonPrimitive?.content ?: "",
                                returnValue = result["return_value"]?.jsonPrimitive?.intOrNull,
                                tick = tickCount
                            )
                        ))
                        null // response deferred
                    } else {
                        CommandResult(
                            id = message.id,
                            success = result["success"]?.toString() == "true",
                            tick = tickCount,
                            output = result["output"]?.toString() ?: ""
                        )
                    }
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

    private fun buildDeferredResponse(pending: PendingCommand, consequences: List<Consequence>): ParrotMessage {
        return when (val dr = pending.deferredResult!!) {
            is DeferredResult.Action -> ActionResult(
                id = pending.message.id, success = true, tick = dr.tick,
                result = dr.result, consequences = consequences
            )
            is DeferredResult.Command -> CommandResult(
                id = pending.message.id, success = true, tick = dr.tick,
                output = dr.output, returnValue = dr.returnValue, consequences = consequences
            )
        }
    }
}

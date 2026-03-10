package dev.parrot.mod.engine

import dev.parrot.mod.commands.CommandRegistry
import dev.parrot.protocol.*
import io.netty.channel.Channel
import net.minecraft.server.MinecraftServer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue

class CommandQueue(
    private val registry: CommandRegistry,
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
        // Placeholder -- Phase 3 wires this to CommandRegistry
        return ErrorResponse(
            id = message.id,
            code = ErrorCode.UNKNOWN_METHOD.code,
            message = "Command dispatch not yet implemented"
        )
    }
}

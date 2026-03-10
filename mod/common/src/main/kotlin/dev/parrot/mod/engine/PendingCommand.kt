package dev.parrot.mod.engine

import dev.parrot.protocol.ParrotMessage
import io.netty.channel.Channel
import java.util.concurrent.CompletableFuture

data class PendingCommand(
    val message: ParrotMessage,
    val channel: Channel,
    val future: CompletableFuture<ParrotMessage>,
    val submittedTime: Long = System.currentTimeMillis()
)

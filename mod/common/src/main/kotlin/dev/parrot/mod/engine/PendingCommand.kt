package dev.parrot.mod.engine

import dev.parrot.protocol.ParrotMessage
import io.netty.channel.Channel
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CompletableFuture

sealed class DeferredResult {
    data class Action(val result: JsonObject, val tick: Long) : DeferredResult()
    data class Command(val output: String, val returnValue: Int?, val tick: Long) : DeferredResult()
}

data class PendingCommand(
    val message: ParrotMessage,
    val channel: Channel,
    val future: CompletableFuture<ParrotMessage>,
    val submittedTime: Long = System.currentTimeMillis(),
    val collectionHandle: CollectionHandle? = null,
    val deferredResult: DeferredResult? = null
)

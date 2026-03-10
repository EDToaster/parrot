package dev.parrot.mod.engine

import dev.parrot.protocol.Consequence
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

class ConsequenceCollector {
    private val activeHandles = CopyOnWriteArrayList<CollectionHandle>()

    fun startCollecting(
        startTick: Long,
        tickWindow: Int,
        filter: List<String>?,
        originX: Int? = null, originY: Int? = null, originZ: Int? = null
    ): CollectionHandle {
        val handle = CollectionHandle(
            deadline = startTick + tickWindow,
            wallClockDeadline = System.currentTimeMillis() + (tickWindow * 100L),
            filter = filter,
            originX = originX, originY = originY, originZ = originZ
        )
        activeHandles.add(handle)
        return handle
    }

    fun onConsequence(type: String, tick: Long, data: JsonObject, x: Int? = null, y: Int? = null, z: Int? = null) {
        for (handle in activeHandles) {
            if (handle.future.isDone) continue
            if (handle.filter != null && type !in handle.filter) continue
            // Spatial filtering (8-block radius)
            if (handle.originX != null && x != null) {
                val dx = x - handle.originX
                val dy = (y ?: 0) - (handle.originY ?: 0)
                val dz = (z ?: 0) - (handle.originZ ?: 0)
                if (dx * dx + dy * dy + dz * dz > 64) continue // 8^2
            }
            handle.consequences.add(Consequence(type, tick, data))
        }
    }

    fun tick(currentTick: Long) {
        val now = System.currentTimeMillis()
        val iterator = activeHandles.iterator()
        while (iterator.hasNext()) {
            val handle = iterator.next()
            if (currentTick >= handle.deadline || now >= handle.wallClockDeadline) {
                handle.complete()
                activeHandles.remove(handle)
            }
        }
    }
}

class CollectionHandle(
    val deadline: Long,
    val wallClockDeadline: Long,
    val filter: List<String>?,
    val originX: Int?,
    val originY: Int?,
    val originZ: Int?,
    val spatialRadius: Int = 8
) {
    val consequences = mutableListOf<Consequence>()
    val future = CompletableFuture<List<Consequence>>()

    fun complete() {
        future.complete(consequences.toList())
    }
}

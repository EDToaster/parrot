package dev.parrot.mod.events

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class SpatialFilter(val x: Double, val y: Double, val z: Double, val radius: Double)
data class Subscription(val id: String, val eventTypes: Set<ParrotEventType>, val spatialFilter: SpatialFilter?, val channelId: String)

class SubscriptionManager {
    private val subscriptions = ConcurrentHashMap<String, Subscription>()
    private val nextId = AtomicInteger(1)
    var eventSender: ((String, JsonObject) -> Unit)? = null

    fun subscribe(eventTypes: Set<ParrotEventType>, spatialFilter: SpatialFilter?, channelId: String): String {
        val id = "sub-${nextId.getAndIncrement()}"
        subscriptions[id] = Subscription(id, eventTypes, spatialFilter, channelId)
        return id
    }

    fun unsubscribe(subscriptionId: String): Boolean = subscriptions.remove(subscriptionId) != null

    fun cleanupChannel(channelId: String) { subscriptions.entries.removeIf { it.value.channelId == channelId } }

    fun dispatch(eventType: ParrotEventType, tick: Long, data: JsonObject, position: Vec3? = null) {
        for ((_, sub) in subscriptions) {
            if (eventType !in sub.eventTypes) continue
            if (sub.spatialFilter != null && position != null) {
                val dist = position.distanceTo(Vec3(sub.spatialFilter.x, sub.spatialFilter.y, sub.spatialFilter.z))
                if (dist > sub.spatialFilter.radius) continue
            }
            val event = buildJsonObject {
                put("type", "event"); put("subscription_id", sub.id)
                put("tick", tick); put("event_type", eventType.id); put("data", data)
            }
            eventSender?.invoke(sub.id, event)
        }
    }

    fun handleScreenObservation(observation: dev.parrot.mod.engine.bridge.ScreenObservation) {
        val eventType = when (observation.type) {
            dev.parrot.mod.engine.bridge.ScreenObservationType.OPENED -> ParrotEventType.SCREEN_OPENED
            dev.parrot.mod.engine.bridge.ScreenObservationType.CLOSED -> ParrotEventType.SCREEN_CLOSED
            else -> return
        }
        dispatch(eventType, observation.tick, buildJsonObject {
            observation.screenState?.let { put("title", it.title ?: ""); put("screen_type", it.screenType ?: "") }
        })
    }
}

package dev.parrot.mod.commands

import dev.parrot.mod.events.ParrotEventType
import dev.parrot.mod.events.SpatialFilter
import dev.parrot.mod.events.SubscriptionManager
import kotlinx.serialization.json.*

class SubscribeHandler(private val subscriptionManager: SubscriptionManager) : CommandHandler {
    override val method: String = "subscribe_events"
    override val isReadOnly: Boolean = false

    override fun handle(params: JsonObject, context: CommandContext): JsonObject {
        val eventTypesArray = params["event_types"]?.jsonArray
            ?: throw ParrotException(ErrorCode.INVALID_PARAMS, "Missing required parameter: event_types")

        val eventTypes = eventTypesArray.map { element ->
            val id = element.jsonPrimitive.content
            ParrotEventType.fromId(id)
                ?: throw ParrotException(ErrorCode.INVALID_PARAMS, "Unknown event type: $id")
        }.toSet()

        if (eventTypes.isEmpty()) {
            throw ParrotException(ErrorCode.INVALID_PARAMS, "event_types must not be empty")
        }

        val spatialFilter = params["spatial_filter"]?.jsonObject?.let { filter ->
            SpatialFilter(
                x = filter["x"]?.jsonPrimitive?.double
                    ?: throw ParrotException(ErrorCode.INVALID_PARAMS, "spatial_filter missing x"),
                y = filter["y"]?.jsonPrimitive?.double
                    ?: throw ParrotException(ErrorCode.INVALID_PARAMS, "spatial_filter missing y"),
                z = filter["z"]?.jsonPrimitive?.double
                    ?: throw ParrotException(ErrorCode.INVALID_PARAMS, "spatial_filter missing z"),
                radius = filter["radius"]?.jsonPrimitive?.double
                    ?: throw ParrotException(ErrorCode.INVALID_PARAMS, "spatial_filter missing radius")
            )
        }

        val channelId = params["channel_id"]?.jsonPrimitive?.contentOrNull ?: "default"

        val subscriptionId = subscriptionManager.subscribe(eventTypes, spatialFilter, channelId)

        return buildJsonObject {
            put("subscription_id", subscriptionId)
            putJsonArray("event_types") {
                eventTypes.forEach { add(it.id) }
            }
        }
    }
}

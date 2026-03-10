package dev.parrot.mod.commands

import dev.parrot.mod.events.SubscriptionManager
import kotlinx.serialization.json.*

class UnsubscribeHandler(private val subscriptionManager: SubscriptionManager) : CommandHandler {
    override val method: String = "unsubscribe_events"
    override val isReadOnly: Boolean = false

    override fun handle(params: JsonObject, context: CommandContext): JsonObject {
        val subscriptionId = params["subscription_id"]?.jsonPrimitive?.contentOrNull
            ?: throw ParrotException(ErrorCode.INVALID_PARAMS, "Missing required parameter: subscription_id")

        val success = subscriptionManager.unsubscribe(subscriptionId)

        return buildJsonObject {
            put("success", success)
            put("subscription_id", subscriptionId)
        }
    }
}

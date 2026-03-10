package dev.parrot.mod.commands.actions

import dev.parrot.mod.commands.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.network.chat.Component

class SendChatHandler : CommandHandler {
    override val method = "send_chat"
    override val isReadOnly = false

    override fun handle(params: JsonObject, context: CommandContext): JsonObject {
        val message = params.stringOrNull("message")
            ?: throw ParrotException(ErrorCode.INVALID_PARAMS, "Missing required parameter: message")

        context.server.playerList.broadcastSystemMessage(Component.literal(message), false)

        return buildJsonObject {
            put("success", true)
        }
    }
}

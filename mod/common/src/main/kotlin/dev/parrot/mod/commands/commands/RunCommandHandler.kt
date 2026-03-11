package dev.parrot.mod.commands.commands

import dev.parrot.mod.commands.*
import kotlinx.serialization.json.*
import net.minecraft.commands.CommandSource
import net.minecraft.network.chat.Component

class RunCommandHandler : CommandHandler {
    override val method = "run_command"
    override val isReadOnly = false

    override fun handle(params: JsonObject, context: CommandContext): JsonObject {
        val command = (params.stringOrNull("command")
            ?: throw ParrotException(ErrorCode.INVALID_PARAMS, "Missing 'command' parameter"))
            .removePrefix("/")

        val outputLines = mutableListOf<String>()
        val source = context.server.createCommandSourceStack()
            .withSource(object : CommandSource {
                override fun sendSystemMessage(component: Component) { outputLines.add(component.string) }
                override fun acceptsSuccess(): Boolean = true
                override fun acceptsFailure(): Boolean = true
                override fun shouldInformAdmins(): Boolean = false
            })
            .withPermission(4) // op level

        val returnValue = try {
            context.server.commands.dispatcher.execute(command, source)
        } catch (e: Exception) {
            return buildJsonObject {
                put("success", false); put("return_value", 0)
                putJsonArray("output") {}; put("error", e.message ?: "Command failed")
            }
        }

        return buildJsonObject {
            put("success", returnValue > 0)
            put("return_value", returnValue)
            putJsonArray("output") { outputLines.forEach { add(JsonPrimitive(it)) } }
        }
    }
}

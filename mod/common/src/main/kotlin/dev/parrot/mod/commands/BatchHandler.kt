package dev.parrot.mod.commands

import kotlinx.serialization.json.*

class BatchHandler(private val registry: CommandRegistry) : CommandHandler {
    override val method = "batch"
    override val isReadOnly = true

    override fun handle(params: JsonObject, context: CommandContext): JsonObject {
        val commands = params["commands"]?.jsonArray
            ?: throw ParrotException(ErrorCode.INVALID_PARAMS, "Missing 'commands' array")

        // Validate all are read-only before executing any
        for (cmd in commands) {
            val method = cmd.jsonObject.stringOrNull("method")
                ?: throw ParrotException(ErrorCode.INVALID_PARAMS, "Each command needs a 'method'")
            val handler = registry.get(method)
                ?: throw ParrotException(ErrorCode.UNKNOWN_METHOD, "Unknown method: $method")
            if (!handler.isReadOnly)
                throw ParrotException(ErrorCode.BATCH_ACTIONS_FORBIDDEN, "Batch only accepts read-only queries. '$method' is an action.")
        }

        val results = commands.map { cmd ->
            val obj = cmd.jsonObject
            val method = obj["method"]!!.jsonPrimitive.content
            val cmdParams = obj["params"]?.jsonObject ?: JsonObject(emptyMap())
            try {
                registry.get(method)!!.handle(cmdParams, context)
            } catch (e: ParrotException) {
                buildJsonObject { put("error", e.errorCode.code); put("message", e.message) }
            } catch (e: Exception) {
                buildJsonObject { put("error", "INTERNAL_ERROR"); put("message", e.message ?: "Unknown error") }
            }
        }

        return buildJsonObject { put("results", JsonArray(results)) }
    }
}

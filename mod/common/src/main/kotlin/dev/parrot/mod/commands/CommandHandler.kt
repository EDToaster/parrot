package dev.parrot.mod.commands

import kotlinx.serialization.json.JsonObject

interface CommandHandler {
    val method: String
    val isReadOnly: Boolean
    fun handle(params: JsonObject, context: CommandContext): JsonObject
}

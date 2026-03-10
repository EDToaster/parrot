package dev.parrot.mod.commands.actions

import dev.parrot.mod.commands.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SetHeldSlotHandler : CommandHandler {
    override val method = "set_held_slot"
    override val isReadOnly = false

    override fun handle(params: JsonObject, context: CommandContext): JsonObject {
        val slot = params.int("slot")

        if (slot < 0 || slot > 8) {
            throw ParrotException(ErrorCode.INVALID_SLOT, "Slot must be 0-8, got $slot")
        }

        val player = context.resolvePlayer()
        player.inventory.setSelectedSlot(slot)

        val item = player.inventory.getSelectedItem().toJson()

        return buildJsonObject {
            put("success", true)
            put("slot", slot)
            put("item", item)
        }
    }
}

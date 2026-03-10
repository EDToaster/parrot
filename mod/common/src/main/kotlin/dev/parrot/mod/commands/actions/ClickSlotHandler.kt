package dev.parrot.mod.commands.actions

import dev.parrot.mod.commands.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.world.inventory.ClickType

class ClickSlotHandler : CommandHandler {
    override val method = "click_slot"
    override val isReadOnly = false

    override fun handle(params: JsonObject, context: CommandContext): JsonObject {
        val slotIndex = params.int("slot_index")
        val button = params.int("button")
        val shift = params.booleanOrDefault("shift", false)

        val player = context.resolvePlayer()
        val menu = player.containerMenu

        if (slotIndex < -1 || slotIndex >= menu.slots.size) {
            throw ParrotException(ErrorCode.INVALID_SLOT, "Slot index $slotIndex is out of range (0-${menu.slots.size - 1})")
        }

        val clickType = if (shift) ClickType.QUICK_MOVE else ClickType.PICKUP

        menu.clicked(slotIndex, button, clickType, player)

        val cursorItem = menu.carried.toJson()
        val slotAfter = if (slotIndex >= 0 && slotIndex < menu.slots.size) {
            menu.slots[slotIndex].item.toJson()
        } else {
            buildJsonObject { put("item", "minecraft:air"); put("count", 0) }
        }

        return buildJsonObject {
            put("success", true)
            put("cursor_item", cursorItem)
            put("slot_after", slotAfter)
        }
    }
}

package dev.parrot.mod.commands.actions

import dev.parrot.mod.commands.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.core.registries.BuiltInRegistries

class CloseScreenHandler : CommandHandler {
    override val method = "close_screen"
    override val isReadOnly = false

    override fun handle(params: JsonObject, context: CommandContext): JsonObject {
        val player = context.resolvePlayer()

        val menuType = player.containerMenu.type
        val closedScreen = if (player.containerMenu == player.inventoryMenu) {
            "inventory"
        } else {
            BuiltInRegistries.MENU.getKey(menuType)?.toString() ?: "unknown"
        }

        player.closeContainer()

        return buildJsonObject {
            put("success", true)
            put("closed_screen", closedScreen)
        }
    }
}

package dev.parrot.mod.commands.queries

import dev.parrot.mod.commands.*
import kotlinx.serialization.json.*

class GetInventoryHandler : CommandHandler {
    override val method = "get_inventory"
    override val isReadOnly = true

    override fun handle(params: JsonObject, context: CommandContext): JsonObject {
        val target = params.stringOrNull("target") ?: "player"
        val player = context.resolvePlayer(params.stringOrNull("name"))

        return when (target) {
            "player" -> {
                val inventory = player.inventory
                val slots = mutableListOf<JsonObject>()
                for (i in 0 until inventory.containerSize) {
                    val stack = inventory.getItem(i)
                    if (!stack.isEmpty) {
                        slots.add(buildJsonObject {
                            put("index", i)
                            put("item", stack.toJson())
                            put("count", stack.count)
                        })
                    }
                }
                buildJsonObject {
                    put("inventory_type", "player")
                    put("size", inventory.containerSize)
                    putJsonArray("slots") { slots.forEach { add(it) } }
                }
            }
            "screen" -> {
                val menu = player.containerMenu
                    ?: throw ParrotException(ErrorCode.NO_SCREEN_OPEN, "No screen is currently open")

                val slots = mutableListOf<JsonObject>()
                for (i in 0 until menu.slots.size) {
                    val slot = menu.slots[i]
                    val stack = slot.item
                    if (!stack.isEmpty) {
                        slots.add(buildJsonObject {
                            put("index", i)
                            put("item", stack.toJson())
                            put("count", stack.count)
                        })
                    }
                }
                buildJsonObject {
                    put("inventory_type", "screen")
                    put("size", menu.slots.size)
                    putJsonArray("slots") { slots.forEach { add(it) } }
                }
            }
            else -> throw ParrotException(ErrorCode.INVALID_PARAMS, "Invalid target: $target. Must be 'player' or 'screen'")
        }
    }
}

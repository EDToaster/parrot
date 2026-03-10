package dev.parrot.mod.commands.queries

import dev.parrot.mod.commands.*
import kotlinx.serialization.json.*
import net.minecraft.world.entity.EquipmentSlot

class GetPlayerHandler : CommandHandler {
    override val method = "get_player"
    override val isReadOnly = true

    override fun handle(params: JsonObject, context: CommandContext): JsonObject {
        val player = context.resolvePlayer(params.stringOrNull("name"))

        return buildJsonObject {
            put("name", player.gameProfile.name)
            put("uuid", player.stringUUID)
            putJsonObject("position") { put("x", player.x); put("y", player.y); put("z", player.z) }
            putJsonObject("rotation") { put("yaw", player.yRot.toDouble()); put("pitch", player.xRot.toDouble()) }
            put("dimension", player.level().dimension().location().toString())
            put("health", player.health.toDouble())
            put("max_health", player.maxHealth.toDouble())
            put("food_level", player.foodData.foodLevel)
            put("saturation", player.foodData.saturationLevel.toDouble())
            put("experience_level", player.experienceLevel)
            put("game_mode", player.gameMode.gameModeForPlayer.name.lowercase())
            put("is_on_ground", player.onGround())
            putJsonArray("active_effects") {
                for (effect in player.activeEffects) { add(effect.toJson()) }
            }
            putJsonObject("equipment") {
                for (slot in EquipmentSlot.entries) {
                    put(slot.name.lowercase(), player.getItemBySlot(slot).toJson())
                }
            }
        }
    }
}

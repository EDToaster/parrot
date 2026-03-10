package dev.parrot.mod.commands.actions

import dev.parrot.mod.commands.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity

class AttackEntityHandler : CommandHandler {
    override val method = "attack_entity"
    override val isReadOnly = false

    override fun handle(params: JsonObject, context: CommandContext): JsonObject {
        val uuidStr = params.stringOrNull("uuid")
            ?: throw ParrotException(ErrorCode.INVALID_PARAMS, "Missing required parameter: uuid")
        val uuid = java.util.UUID.fromString(uuidStr)

        val player = context.resolvePlayer()
        val level = player.level()

        val entity = (level as? ServerLevel)?.getEntity(uuid)
            ?: throw ParrotException(ErrorCode.ENTITY_NOT_FOUND, "Entity with UUID $uuid not found")

        val healthBefore = if (entity is LivingEntity) entity.health.toDouble() else 0.0

        player.attack(entity)

        val healthAfter = if (entity is LivingEntity) entity.health.toDouble() else 0.0
        val alive = if (entity is LivingEntity) entity.isAlive else !entity.isRemoved

        return buildJsonObject {
            put("success", true)
            put("damage_dealt", healthBefore - healthAfter)
            put("target_health_after", healthAfter)
            put("target_alive", alive)
        }
    }
}

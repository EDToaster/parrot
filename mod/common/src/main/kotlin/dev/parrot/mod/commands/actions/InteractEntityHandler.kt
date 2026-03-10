package dev.parrot.mod.commands.actions

import dev.parrot.mod.commands.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.InteractionHand

class InteractEntityHandler : CommandHandler {
    override val method = "interact_entity"
    override val isReadOnly = false

    override fun handle(params: JsonObject, context: CommandContext): JsonObject {
        val entityId = params.int("entity_id")
        val handName = params.stringOrNull("hand") ?: "main_hand"

        val player = context.resolvePlayer()
        val level = player.level()

        val entity = level.getEntity(entityId)
            ?: throw ParrotException(ErrorCode.ENTITY_NOT_FOUND, "Entity with id $entityId not found")

        val hand = when (handName) {
            "off_hand" -> InteractionHand.OFF_HAND
            else -> InteractionHand.MAIN_HAND
        }

        player.interactOn(entity, hand)

        val entityType = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type)?.toString() ?: "unknown"

        return buildJsonObject {
            put("success", true)
            put("entity_type", entityType)
        }
    }
}

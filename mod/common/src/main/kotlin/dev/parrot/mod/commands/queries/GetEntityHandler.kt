package dev.parrot.mod.commands.queries

import dev.parrot.mod.commands.*
import kotlinx.serialization.json.*
import net.minecraft.world.entity.LivingEntity
import java.util.UUID

class GetEntityHandler : CommandHandler {
    override val method = "get_entity"
    override val isReadOnly = true

    override fun handle(params: JsonObject, context: CommandContext): JsonObject {
        val entityId = params.intOrNull("entity_id")
        val uuidStr = params.stringOrNull("uuid")

        if (entityId == null && uuidStr == null) {
            throw ParrotException(ErrorCode.INVALID_PARAMS, "Must provide either 'entity_id' or 'uuid'")
        }

        val level = context.resolveLevel(params.stringOrNull("dimension"))

        val entity = if (entityId != null) {
            level.getEntity(entityId)
        } else {
            val uuid = try {
                UUID.fromString(uuidStr)
            } catch (e: IllegalArgumentException) {
                throw ParrotException(ErrorCode.INVALID_PARAMS, "Invalid UUID format: $uuidStr")
            }
            level.getEntity(uuid)
        }

        entity ?: throw ParrotException(ErrorCode.ENTITY_NOT_FOUND, "Entity not found")

        return if (entity is LivingEntity) {
            entity.toDetailedJson()
        } else {
            entity.toSummaryJson()
        }
    }
}

package dev.parrot.mod.commands.queries

import dev.parrot.mod.commands.*
import kotlinx.serialization.json.*
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

class GetEntitiesHandler : CommandHandler {
    override val method = "get_entities"
    override val isReadOnly = true

    override fun handle(params: JsonObject, context: CommandContext): JsonObject {
        val player = context.resolvePlayer(params.stringOrNull("name"))
        val level = context.resolveLevel(params.stringOrNull("dimension"))

        val cx = params["x"]?.jsonPrimitive?.doubleOrNull ?: player.x
        val cy = params["y"]?.jsonPrimitive?.doubleOrNull ?: player.y
        val cz = params["z"]?.jsonPrimitive?.doubleOrNull ?: player.z
        val radius = minOf(params["radius"]?.jsonPrimitive?.doubleOrNull ?: 32.0, 64.0)
        val typeFilter = params.stringOrNull("type")
        val limit = params.intOrNull("limit") ?: 50

        val center = Vec3(cx, cy, cz)
        val aabb = AABB(cx - radius, cy - radius, cz - radius, cx + radius, cy + radius, cz + radius)

        val entities = level.getEntities(null, aabb) { entity ->
            if (typeFilter != null) {
                val entityType = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type)?.toString()
                entityType == typeFilter
            } else {
                true
            }
        }

        val sorted = entities.sortedBy { it.position().distanceTo(center) }
        val totalFound = sorted.size
        val limited = sorted.take(limit)

        return buildJsonObject {
            putJsonArray("entities") {
                for (entity in limited) { add(entity.toSummaryJson()) }
            }
            put("total_found", totalFound)
            put("returned", limited.size)
            put("truncated", totalFound > limit)
        }
    }
}

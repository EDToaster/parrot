package dev.parrot.mod.commands

import kotlinx.serialization.json.*
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NumericTag
import net.minecraft.nbt.StringTag
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3

fun BlockState.toJson(): JsonObject = buildJsonObject {
    put("block", BuiltInRegistries.BLOCK.getKey(block)?.toString() ?: "unknown")
    putJsonObject("properties") {
        for ((property, value) in values.entries) {
            put(property.name, value.toString())
        }
    }
}

fun ItemStack.toJson(): JsonObject {
    if (isEmpty) return buildJsonObject { put("item", "minecraft:air"); put("count", 0) }
    return buildJsonObject {
        put("item", BuiltInRegistries.ITEM.getKey(item)?.toString() ?: "unknown")
        put("count", count)
    }
}

fun Entity.toSummaryJson(): JsonObject = buildJsonObject {
    put("entity_id", id)
    put("uuid", stringUUID)
    put("type", BuiltInRegistries.ENTITY_TYPE.getKey(type)?.toString() ?: "unknown")
    customName?.let { put("custom_name", it.string) }
    putJsonObject("position") { put("x", x); put("y", y); put("z", z) }
    if (this@toSummaryJson is LivingEntity) {
        put("health", health.toDouble())
        put("max_health", maxHealth.toDouble())
    }
}

fun LivingEntity.toDetailedJson(): JsonObject = buildJsonObject {
    put("entity_id", id)
    put("uuid", stringUUID)
    put("type", BuiltInRegistries.ENTITY_TYPE.getKey(type)?.toString() ?: "unknown")
    customName?.let { put("custom_name", it.string) }
    putJsonObject("position") { put("x", x); put("y", y); put("z", z) }
    putJsonObject("velocity") { put("x", deltaMovement.x); put("y", deltaMovement.y); put("z", deltaMovement.z) }
    putJsonObject("rotation") { put("yaw", yRot.toDouble()); put("pitch", xRot.toDouble()) }
    put("health", health.toDouble())
    put("max_health", maxHealth.toDouble())
    putJsonArray("active_effects") {
        for (effect in activeEffects) { add(effect.toJson()) }
    }
    putJsonObject("equipment") {
        for (slot in EquipmentSlot.entries) {
            put(slot.name.lowercase(), getItemBySlot(slot).toJson())
        }
    }
    putJsonArray("passengers") { for (p in passengers) { add(JsonPrimitive(p.id)) } }
    vehicle?.let { put("vehicle", it.id) }
    putJsonArray("tags") { for (tag in tags) { add(JsonPrimitive(tag)) } }
}

fun MobEffectInstance.toJson(): JsonObject = buildJsonObject {
    put("effect", BuiltInRegistries.MOB_EFFECT.getKey(effect.value())?.toString() ?: "unknown")
    put("amplifier", amplifier)
    put("duration", duration)
    put("ambient", isAmbient)
}

fun BlockEntity.toJson(registryAccess: net.minecraft.core.RegistryAccess): JsonObject {
    val tag = saveWithoutMetadata(registryAccess)
    return tag.toJson()
}

fun CompoundTag.toJson(): JsonObject = buildJsonObject {
    for (key in keySet()) {
        val tag = get(key) ?: continue
        when (tag) {
            is NumericTag -> put(key, tag.box().toDouble())
            is StringTag -> put(key, tag.asString().orElse(""))
            is CompoundTag -> put(key, tag.toJson())
            is ListTag -> putJsonArray(key) {
                for (element in tag) {
                    when (element) {
                        is CompoundTag -> add(element.toJson())
                        is StringTag -> add(JsonPrimitive(element.asString().orElse("")))
                        is NumericTag -> add(JsonPrimitive(element.box().toDouble()))
                        else -> add(JsonPrimitive(element.toString()))
                    }
                }
            }
            else -> put(key, tag.toString())
        }
    }
}

fun Vec3.toJson(): JsonObject = buildJsonObject { put("x", x); put("y", y); put("z", z) }

fun BlockPos.toJson(): JsonObject = buildJsonObject { put("x", x); put("y", y); put("z", z) }

// Extension to get optional params from JsonObject
fun JsonObject.intOrNull(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
fun JsonObject.int(key: String): Int = intOrNull(key) ?: throw ParrotException(ErrorCode.INVALID_PARAMS, "Missing required parameter: $key")
fun JsonObject.stringOrNull(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
fun JsonObject.booleanOrDefault(key: String, default: Boolean): Boolean = this[key]?.jsonPrimitive?.booleanOrNull ?: default

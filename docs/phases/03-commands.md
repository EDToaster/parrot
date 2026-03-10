# Phase 3: Command Framework + Handlers + Events

**Goal:** Implement the command handler framework, all 8 query handlers, 8 action handlers, batch handler, run_command handler, and the event subscription system.

**Architecture:** All handlers implement a shared `CommandHandler` interface, are registered in a `CommandRegistry`, and execute on the server tick thread. Queries are read-only. Actions trigger consequence collection via a tick-based window. Events use a `SubscriptionManager` with spatial filtering.

---

## Task 3.1: Command Framework

**Files:**
- Create: `mod/common/src/main/kotlin/dev/parrot/mod/commands/CommandHandler.kt`
- Create: `mod/common/src/main/kotlin/dev/parrot/mod/commands/CommandContext.kt`
- Create: `mod/common/src/main/kotlin/dev/parrot/mod/commands/CommandRegistry.kt`
- Create: `mod/common/src/main/kotlin/dev/parrot/mod/commands/ParrotException.kt`

### CommandHandler.kt

```kotlin
package dev.parrot.mod.commands

import kotlinx.serialization.json.JsonObject

interface CommandHandler {
    val method: String
    val isReadOnly: Boolean
    fun handle(params: JsonObject, context: CommandContext): JsonObject
}
```

### CommandContext.kt

```kotlin
package dev.parrot.mod.commands

import dev.parrot.mod.engine.bridge.ScreenReader
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer

data class CommandContext(
    val server: MinecraftServer,
    val player: ServerPlayer?,
    val tickCount: Long,
    val screenReader: ScreenReader?
) {
    fun resolvePlayer(name: String? = null): ServerPlayer {
        if (name != null) return server.playerList.getPlayerByName(name)
            ?: throw ParrotException(ErrorCode.NO_PLAYER, "Player '$name' not found")
        return player ?: server.playerList.players.firstOrNull()
            ?: throw ParrotException(ErrorCode.NO_PLAYER, "No player available")
    }

    fun resolveLevel(dimension: String? = null): ServerLevel {
        if (dimension == null) return player?.serverLevel() ?: server.overworld()
        val key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimension))
        return server.getLevel(key) ?: throw ParrotException(ErrorCode.INVALID_PARAMS, "Unknown dimension: $dimension")
    }
}

enum class ErrorCode(val code: String) {
    INVALID_PARAMS("INVALID_PARAMS"),
    BLOCK_OUT_OF_RANGE("BLOCK_OUT_OF_RANGE"),
    ENTITY_NOT_FOUND("ENTITY_NOT_FOUND"),
    NO_SCREEN_OPEN("NO_SCREEN_OPEN"),
    NO_PLAYER("NO_PLAYER"),
    INVALID_SLOT("INVALID_SLOT"),
    COMMAND_FAILED("COMMAND_FAILED"),
    AREA_TOO_LARGE("AREA_TOO_LARGE"),
    UNKNOWN_METHOD("UNKNOWN_METHOD"),
    INTERNAL_ERROR("INTERNAL_ERROR"),
    BATCH_ACTIONS_FORBIDDEN("BATCH_ACTIONS_FORBIDDEN")
}

class ParrotException(
    val errorCode: ErrorCode,
    override val message: String
) : RuntimeException(message)
```

### CommandRegistry.kt

```kotlin
package dev.parrot.mod.commands

class CommandRegistry {
    private val handlers = mutableMapOf<String, CommandHandler>()

    fun register(handler: CommandHandler) { handlers[handler.method] = handler }
    fun get(method: String): CommandHandler? = handlers[method]
    fun listMethods(): List<String> = handlers.keys.sorted()
    fun listReadOnlyMethods(): List<String> = handlers.values.filter { it.isReadOnly }.map { it.method }.sorted()
}
```

### Commit

```bash
git add mod/common/src/main/kotlin/dev/parrot/mod/commands/
git commit -m "feat: add command handler framework (CommandHandler, CommandContext, CommandRegistry)"
```

---

## Task 3.2: JSON Helper Utilities

**Files:**
- Create: `mod/common/src/main/kotlin/dev/parrot/mod/commands/JsonHelpers.kt`

Shared functions for converting Minecraft types to `JsonObject`:

```kotlin
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
    for (key in allKeys) {
        val tag = get(key) ?: continue
        when (tag) {
            is NumericTag -> put(key, tag.asNumber.toDouble())
            is StringTag -> put(key, tag.asString)
            is CompoundTag -> put(key, tag.toJson())
            is ListTag -> putJsonArray(key) {
                for (element in tag) {
                    when (element) {
                        is CompoundTag -> add(element.toJson())
                        is StringTag -> add(JsonPrimitive(element.asString))
                        is NumericTag -> add(JsonPrimitive(element.asNumber.toDouble()))
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
```

### Commit

```bash
git add mod/common/src/main/kotlin/dev/parrot/mod/commands/JsonHelpers.kt
git commit -m "feat: add JSON helper utilities for Minecraft type conversion"
```

---

## Task 3.3: Query Handlers

**Files (all in `mod/common/src/main/kotlin/dev/parrot/mod/commands/queries/`):**
- Create: `GetBlockHandler.kt`
- Create: `GetBlocksAreaHandler.kt`
- Create: `GetWorldInfoHandler.kt`
- Create: `GetPlayerHandler.kt`
- Create: `GetInventoryHandler.kt`
- Create: `GetEntitiesHandler.kt`
- Create: `GetEntityHandler.kt`
- Create: `GetScreenHandler.kt`

Each handler implements `CommandHandler` with `isReadOnly = true`. Key patterns:

### GetBlockHandler.kt

```kotlin
package dev.parrot.mod.commands.queries

import dev.parrot.mod.commands.*
import kotlinx.serialization.json.*
import net.minecraft.core.BlockPos
import net.minecraft.world.level.LightLayer

class GetBlockHandler : CommandHandler {
    override val method = "get_block"
    override val isReadOnly = true

    override fun handle(params: JsonObject, context: CommandContext): JsonObject {
        val x = params.int("x"); val y = params.int("y"); val z = params.int("z")
        val level = context.resolveLevel(params.stringOrNull("dimension"))
        val pos = BlockPos(x, y, z)

        if (!level.isLoaded(pos)) throw ParrotException(ErrorCode.BLOCK_OUT_OF_RANGE, "Block at ($x, $y, $z) is not in a loaded chunk")

        val state = level.getBlockState(pos)
        val blockEntity = level.getBlockEntity(pos)

        return buildJsonObject {
            put("block", state.toJson())
            put("has_block_entity", blockEntity != null)
            blockEntity?.let { put("block_entity", it.toJson(level.registryAccess())) }
            put("light_level", level.getMaxLocalRawBrightness(pos))
            level.getBiome(pos).unwrapKey().ifPresent { put("biome", it.location().toString()) }
        }
    }
}
```

### GetBlocksAreaHandler.kt

- Validates volume <= 32768 (32^3), throws `AREA_TOO_LARGE` otherwise
- Iterates `BlockPos.betweenClosed(from, to)`
- Skips air unless `include_air` is true
- Filters by block ID if `filter` provided
- Caps results at 10000, sets `truncated` flag
- Returns `{blocks: [...], total_blocks, returned_blocks, truncated}`

### GetWorldInfoHandler.kt

- Reads `server.overworld().dayTime`, weather, difficulty, game rules
- Approximates TPS from `server.tickTimesNanos`
- Returns `{day_time, game_time, is_day, weather, difficulty, game_rules, loaded_dimensions, server_tps, online_players}`

### GetPlayerHandler.kt

- Resolves player by name or defaults to first
- Returns `{name, uuid, position, rotation, dimension, health, max_health, food_level, saturation, experience_level, game_mode, is_on_ground, active_effects, equipment}`

### GetInventoryHandler.kt

- Input: `target: "player" | "screen"`
- For "player": iterates `player.getInventory()` slots
- For "screen": reads `player.containerMenu` slots, throws `NO_SCREEN_OPEN` if none
- Returns `{inventory_type, size, slots: [{index, item, count}], title?}`

### GetEntitiesHandler.kt

- Defaults position to player position
- Clamps radius to max 64
- Uses `level.getEntities(null, aabb)` with AABB
- Applies type filter, sorts by distance, truncates at limit (default 50)
- Returns `{entities: [...], total_found, returned, truncated}`

### GetEntityHandler.kt

- Looks up by `entity_id` (numeric) or `uuid` (string)
- Returns detailed entity data via `toDetailedJson()`
- Throws `ENTITY_NOT_FOUND` if not found

### GetScreenHandler.kt

- Delegates to `context.screenReader`
- Returns `{is_open, screen_type, title, menu_type, slots, widgets}`
- Throws `NO_SCREEN_OPEN` if no screen reader or no screen

### Commit

```bash
git add mod/common/src/main/kotlin/dev/parrot/mod/commands/queries/
git commit -m "feat: add query handlers (get_block, get_blocks_area, get_world_info, get_player, get_inventory, get_entities, get_entity, get_screen)"
```

---

## Task 3.4: Action Handlers

**Files (all in `mod/common/src/main/kotlin/dev/parrot/mod/commands/actions/`):**
- Create: `InteractBlockHandler.kt`
- Create: `AttackBlockHandler.kt`
- Create: `InteractEntityHandler.kt`
- Create: `AttackEntityHandler.kt`
- Create: `ClickSlotHandler.kt`
- Create: `CloseScreenHandler.kt`
- Create: `SetHeldSlotHandler.kt`
- Create: `SendChatHandler.kt`

All have `isReadOnly = false`. Key patterns:

### InteractBlockHandler.kt (interact_block)

```kotlin
// Uses player.gameMode.useItemOn(player, level, itemInHand, hand, BlockHitResult(...))
// Returns {success, result: "consumed"|"pass"|"fail"}
```

### AttackBlockHandler.kt (attack_block)

```kotlin
// In creative: player.gameMode.handleBlockBreakAction(pos, START_DESTROY_BLOCK, direction, maxBuildHeight)
// Returns {success, block_broken: blockId}
```

### InteractEntityHandler.kt (interact_entity)

```kotlin
// player.interactOn(entity, hand)
// Returns {success, entity_type}
```

### AttackEntityHandler.kt (attack_entity)

```kotlin
// Records health before, calls player.attack(entity), records health after
// Returns {success, damage_dealt, target_health_after, target_alive}
```

### ClickSlotHandler.kt (click_slot)

```kotlin
// Maps button + shift to ClickType
// player.containerMenu.clicked(slotIndex, mouseButton, clickType, player)
// Returns {success, cursor_item, slot_after}
```

### CloseScreenHandler.kt (close_screen)

```kotlin
// player.closeContainer()
// Returns {success, closed_screen}
```

### SetHeldSlotHandler.kt (set_held_slot)

```kotlin
// player.getInventory().selected = slot (0-8)
// Returns {success, slot, item}
```

### SendChatHandler.kt (send_chat)

```kotlin
// server.playerList.broadcastSystemMessage(Component.literal(message), false)
// Returns {success}
```

### Commit

```bash
git add mod/common/src/main/kotlin/dev/parrot/mod/commands/actions/
git commit -m "feat: add action handlers (interact_block, attack_block, interact_entity, attack_entity, click_slot, close_screen, set_held_slot, send_chat)"
```

---

## Task 3.5: RunCommandHandler

**File:** `mod/common/src/main/kotlin/dev/parrot/mod/commands/commands/RunCommandHandler.kt`

```kotlin
package dev.parrot.mod.commands.commands

import dev.parrot.mod.commands.*
import kotlinx.serialization.json.*
import net.minecraft.commands.CommandSource
import net.minecraft.network.chat.Component

class RunCommandHandler : CommandHandler {
    override val method = "run_command"
    override val isReadOnly = false

    override fun handle(params: JsonObject, context: CommandContext): JsonObject {
        val command = params.stringOrNull("command")
            ?: throw ParrotException(ErrorCode.INVALID_PARAMS, "Missing 'command' parameter")

        val outputLines = mutableListOf<String>()
        val source = context.server.createCommandSourceStack()
            .withSource(object : CommandSource {
                override fun sendSystemMessage(component: Component) { outputLines.add(component.string) }
                override fun acceptsSuccess(): Boolean = true
                override fun acceptsFailure(): Boolean = true
                override fun shouldInformAdmins(): Boolean = false
            })
            .withPermission(4) // op level

        val returnValue = try {
            context.server.commands.performPrefixedCommand(source, command)
        } catch (e: Exception) {
            return buildJsonObject {
                put("success", false); put("return_value", 0)
                putJsonArray("output") {}; put("error", e.message ?: "Command failed")
            }
        }

        return buildJsonObject {
            put("success", returnValue > 0)
            put("return_value", returnValue)
            putJsonArray("output") { outputLines.forEach { add(JsonPrimitive(it)) } }
        }
    }
}
```

### Commit

```bash
git add mod/common/src/main/kotlin/dev/parrot/mod/commands/commands/
git commit -m "feat: add run_command handler with output capture and op permissions"
```

---

## Task 3.6: BatchHandler

**File:** `mod/common/src/main/kotlin/dev/parrot/mod/commands/BatchHandler.kt`

```kotlin
package dev.parrot.mod.commands

import kotlinx.serialization.json.*

class BatchHandler(private val registry: CommandRegistry) : CommandHandler {
    override val method = "batch"
    override val isReadOnly = true

    override fun handle(params: JsonObject, context: CommandContext): JsonObject {
        val commands = params["commands"]?.jsonArray
            ?: throw ParrotException(ErrorCode.INVALID_PARAMS, "Missing 'commands' array")

        // Validate all are read-only before executing any
        for (cmd in commands) {
            val method = cmd.jsonObject.stringOrNull("method")
                ?: throw ParrotException(ErrorCode.INVALID_PARAMS, "Each command needs a 'method'")
            val handler = registry.get(method)
                ?: throw ParrotException(ErrorCode.UNKNOWN_METHOD, "Unknown method: $method")
            if (!handler.isReadOnly)
                throw ParrotException(ErrorCode.BATCH_ACTIONS_FORBIDDEN, "Batch only accepts read-only queries. '$method' is an action.")
        }

        val results = commands.map { cmd ->
            val obj = cmd.jsonObject
            val method = obj["method"]!!.jsonPrimitive.content
            val cmdParams = obj["params"]?.jsonObject ?: JsonObject(emptyMap())
            try {
                registry.get(method)!!.handle(cmdParams, context)
            } catch (e: ParrotException) {
                buildJsonObject { put("error", e.errorCode.code); put("message", e.message) }
            } catch (e: Exception) {
                buildJsonObject { put("error", "INTERNAL_ERROR"); put("message", e.message ?: "Unknown error") }
            }
        }

        return buildJsonObject { put("results", JsonArray(results)) }
    }
}
```

### Commit

```bash
git add mod/common/src/main/kotlin/dev/parrot/mod/commands/BatchHandler.kt
git commit -m "feat: add batch handler for multiple read-only queries in one request"
```

---

## Task 3.7: Event Subscription System

**Files:**
- Create: `mod/common/src/main/kotlin/dev/parrot/mod/events/EventTypes.kt`
- Create: `mod/common/src/main/kotlin/dev/parrot/mod/events/SubscriptionManager.kt`
- Create: `mod/common/src/main/kotlin/dev/parrot/mod/events/EventBridge.kt`
- Create: `mod/common/src/main/kotlin/dev/parrot/mod/commands/SubscribeHandler.kt`
- Create: `mod/common/src/main/kotlin/dev/parrot/mod/commands/UnsubscribeHandler.kt`

### EventTypes.kt

```kotlin
package dev.parrot.mod.events

enum class ParrotEventType(val id: String) {
    SCREEN_OPENED("screen_opened"), SCREEN_CLOSED("screen_closed"),
    BLOCK_CHANGED("block_changed"), ENTITY_SPAWNED("entity_spawned"),
    ENTITY_REMOVED("entity_removed"), CHAT_MESSAGE("chat_message"),
    INVENTORY_CHANGED("inventory_changed"), PLAYER_HEALTH_CHANGED("player_health_changed"),
    PLAYER_MOVED("player_moved"), DIMENSION_CHANGED("dimension_changed"),
    DEATH("death"), RESPAWN("respawn"),
    ADVANCEMENT("advancement"), BLOCK_BREAK_PROGRESS("block_break_progress");

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(id: String): ParrotEventType? = byId[id]
    }
}
```

### SubscriptionManager.kt

```kotlin
package dev.parrot.mod.events

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class SpatialFilter(val x: Double, val y: Double, val z: Double, val radius: Double)
data class Subscription(val id: String, val eventTypes: Set<ParrotEventType>, val spatialFilter: SpatialFilter?, val channelId: String)

class SubscriptionManager {
    private val subscriptions = ConcurrentHashMap<String, Subscription>()
    private val nextId = AtomicInteger(1)
    var eventSender: ((String, JsonObject) -> Unit)? = null

    fun subscribe(eventTypes: Set<ParrotEventType>, spatialFilter: SpatialFilter?, channelId: String): String {
        val id = "sub-${nextId.getAndIncrement()}"
        subscriptions[id] = Subscription(id, eventTypes, spatialFilter, channelId)
        return id
    }

    fun unsubscribe(subscriptionId: String): Boolean = subscriptions.remove(subscriptionId) != null

    fun cleanupChannel(channelId: String) { subscriptions.entries.removeIf { it.value.channelId == channelId } }

    fun dispatch(eventType: ParrotEventType, tick: Long, data: JsonObject, position: Vec3? = null) {
        for ((_, sub) in subscriptions) {
            if (eventType !in sub.eventTypes) continue
            if (sub.spatialFilter != null && position != null) {
                val dist = position.distanceTo(Vec3(sub.spatialFilter.x, sub.spatialFilter.y, sub.spatialFilter.z))
                if (dist > sub.spatialFilter.radius) continue
            }
            val event = buildJsonObject {
                put("type", "event"); put("subscription_id", sub.id)
                put("tick", tick); put("event_type", eventType.id); put("data", data)
            }
            eventSender?.invoke(sub.id, event)
        }
    }

    fun handleScreenObservation(observation: dev.parrot.mod.engine.bridge.ScreenObservation) {
        val eventType = when (observation.type) {
            dev.parrot.mod.engine.bridge.ScreenObservationType.OPENED -> ParrotEventType.SCREEN_OPENED
            dev.parrot.mod.engine.bridge.ScreenObservationType.CLOSED -> ParrotEventType.SCREEN_CLOSED
            else -> return
        }
        dispatch(eventType, observation.tick, buildJsonObject {
            observation.screenState?.let { put("title", it.title ?: ""); put("screen_type", it.screenType ?: "") }
        })
    }
}
```

### EventBridge.kt

```kotlin
package dev.parrot.mod.events

import net.minecraft.server.MinecraftServer

interface EventBridge {
    fun registerListeners(subscriptionManager: SubscriptionManager, server: MinecraftServer)
    fun unregisterListeners()
}
```

### SubscribeHandler.kt and UnsubscribeHandler.kt

Handlers that accept `subscribe_events` / `unsubscribe_events` commands and delegate to `SubscriptionManager`.

### Commit

```bash
git add mod/common/src/main/kotlin/dev/parrot/mod/events/ mod/common/src/main/kotlin/dev/parrot/mod/commands/SubscribeHandler.kt mod/common/src/main/kotlin/dev/parrot/mod/commands/UnsubscribeHandler.kt
git commit -m "feat: add event subscription system with 14 event types and spatial filtering"
```

---

## Task 3.8: Wire CommandRegistry

**File:** `mod/common/src/main/kotlin/dev/parrot/mod/commands/CommandRegistryInit.kt`

```kotlin
package dev.parrot.mod.commands

import dev.parrot.mod.commands.queries.*
import dev.parrot.mod.commands.actions.*
import dev.parrot.mod.commands.commands.*
import dev.parrot.mod.events.SubscriptionManager

fun createCommandRegistry(subscriptionManager: SubscriptionManager): CommandRegistry {
    val registry = CommandRegistry()
    registry.register(GetBlockHandler())
    registry.register(GetBlocksAreaHandler())
    registry.register(GetWorldInfoHandler())
    registry.register(GetPlayerHandler())
    registry.register(GetInventoryHandler())
    registry.register(GetEntitiesHandler())
    registry.register(GetEntityHandler())
    registry.register(GetScreenHandler())
    registry.register(InteractBlockHandler())
    registry.register(AttackBlockHandler())
    registry.register(InteractEntityHandler())
    registry.register(AttackEntityHandler())
    registry.register(ClickSlotHandler())
    registry.register(CloseScreenHandler())
    registry.register(SetHeldSlotHandler())
    registry.register(SendChatHandler())
    registry.register(RunCommandHandler())
    registry.register(BatchHandler(registry))
    registry.register(SubscribeHandler(subscriptionManager))
    registry.register(UnsubscribeHandler(subscriptionManager))
    return registry
}
```

### Commit

```bash
git add mod/common/src/main/kotlin/dev/parrot/mod/commands/CommandRegistryInit.kt
git commit -m "feat: wire all command handlers into CommandRegistry"
```

---

## Key Challenges

| Challenge | Approach |
|-----------|----------|
| DataComponents vs NBT (1.21.x) | ItemStack.toJson() uses registry-based serialization, not legacy getTag() |
| CommandSourceStack output capture | Custom CommandSource with overridden sendSystemMessage() |
| ScreenReader thread safety | Client writes snapshot to AtomicReference, server reads it |
| Game rule iteration | Use GameRules.visitGameRuleTypes() visitor pattern |
| Block change detection across loaders | Snapshot comparison in common code (loader-independent) |

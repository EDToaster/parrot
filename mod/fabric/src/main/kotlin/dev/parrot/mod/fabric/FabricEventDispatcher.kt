package dev.parrot.mod.fabric

import dev.parrot.mod.events.ParrotEventType
import dev.parrot.mod.events.SubscriptionManager
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3

object FabricEventDispatcher {
    @Volatile
    private var subscriptionManager: SubscriptionManager? = null

    // Polling state for tick-based events
    private var lastPlayerHealth: Float? = null
    private var lastPlayerPos: Vec3? = null
    private var lastInventoryHash: Int? = null

    private var registered = false

    fun register(subMgr: SubscriptionManager) {
        subscriptionManager = subMgr
        lastPlayerHealth = null
        lastPlayerPos = null
        lastInventoryHash = null
        if (!registered) {
            registered = true
            registerFabricApiEvents()
        }
    }

    fun unregister() {
        subscriptionManager = null
        lastPlayerHealth = null
        lastPlayerPos = null
        lastInventoryHash = null
    }

    /** Called from ServerLevelMixin — block state changes (lever, door, piston, redstone, etc.) */
    fun onBlockChanged(level: ServerLevel, pos: BlockPos, newState: BlockState) {
        val mgr = subscriptionManager ?: return
        val tick = level.server.tickCount.toLong()
        val data = buildJsonObject {
            put("x", pos.x)
            put("y", pos.y)
            put("z", pos.z)
            put("block", newState.block.descriptionId)
            put("source", "state_change")
        }
        mgr.dispatch(ParrotEventType.BLOCK_CHANGED, tick, data, Vec3.atCenterOf(pos))
    }

    private fun registerFabricApiEvents() {
        // Block break (also fires block_changed)
        PlayerBlockBreakEvents.AFTER.register { world, player, pos, state, _ ->
            val mgr = subscriptionManager ?: return@register
            if (world is ServerLevel) {
                val tick = world.server.tickCount.toLong()
                val data = buildJsonObject {
                    put("x", pos.x)
                    put("y", pos.y)
                    put("z", pos.z)
                    put("block", state.block.descriptionId)
                    put("source", "player_break")
                }
                mgr.dispatch(ParrotEventType.BLOCK_CHANGED, tick, data, Vec3.atCenterOf(pos))
            }
        }

        // Entity spawned
        ServerEntityEvents.ENTITY_LOAD.register { entity, world ->
            val mgr = subscriptionManager ?: return@register
            if (entity is Player) return@register // don't fire for player entities
            val tick = world.server.tickCount.toLong()
            val data = buildEntityData(entity)
            mgr.dispatch(ParrotEventType.ENTITY_SPAWNED, tick, data, entity.position())
        }

        // Entity removed
        ServerEntityEvents.ENTITY_UNLOAD.register { entity, world ->
            val mgr = subscriptionManager ?: return@register
            if (entity is Player) return@register
            val tick = world.server.tickCount.toLong()
            val data = buildEntityData(entity)
            mgr.dispatch(ParrotEventType.ENTITY_REMOVED, tick, data, entity.position())
        }

        // Chat message
        ServerMessageEvents.CHAT_MESSAGE.register { message, sender, _ ->
            val mgr = subscriptionManager ?: return@register
            val server = (sender.level() as? ServerLevel)?.server ?: return@register
            val tick = server.tickCount.toLong()
            val data = buildJsonObject {
                put("sender", sender.gameProfile.name)
                put("message", message.signedContent())
            }
            mgr.dispatch(ParrotEventType.CHAT_MESSAGE, tick, data)
        }

        // Player death
        ServerLivingEntityEvents.AFTER_DEATH.register { entity, damageSource ->
            val mgr = subscriptionManager ?: return@register
            if (entity is ServerPlayer) {
                val server = (entity.level() as? ServerLevel)?.server ?: return@register
                val tick = server.tickCount.toLong()
                val data = buildJsonObject {
                    put("player", entity.gameProfile.name)
                    put("cause", damageSource.type().msgId())
                }
                mgr.dispatch(ParrotEventType.DEATH, tick, data, entity.position())
            }
        }

        // Tick-based polling for health, position, inventory
        ServerTickEvents.END_SERVER_TICK.register { server ->
            pollPlayerState(server)
        }
    }

    private fun pollPlayerState(server: MinecraftServer) {
        val mgr = subscriptionManager ?: return
        val player = server.playerList.players.firstOrNull() ?: return
        val tick = server.tickCount.toLong()

        // Health changes
        val health = player.health
        val prevHealth = lastPlayerHealth
        if (prevHealth != null && health != prevHealth) {
            val data = buildJsonObject {
                put("health", health.toDouble())
                put("max_health", player.maxHealth.toDouble())
                put("previous_health", prevHealth.toDouble())
            }
            mgr.dispatch(ParrotEventType.PLAYER_HEALTH_CHANGED, tick, data, player.position())
        }
        lastPlayerHealth = health

        // Position changes (threshold: 0.1 blocks to avoid spamming on every micro-movement)
        val pos = player.position()
        val prevPos = lastPlayerPos
        if (prevPos != null && prevPos.distanceToSqr(pos) > 0.01) { // 0.1^2
            val data = buildJsonObject {
                put("x", pos.x)
                put("y", pos.y)
                put("z", pos.z)
                put("previous_x", prevPos.x)
                put("previous_y", prevPos.y)
                put("previous_z", prevPos.z)
            }
            mgr.dispatch(ParrotEventType.PLAYER_MOVED, tick, data, pos)
        }
        lastPlayerPos = pos

        // Inventory changes (hash-based detection)
        val inv = player.inventory
        var hash = 0
        for (i in 0 until inv.containerSize) {
            val stack = inv.getItem(i)
            if (!stack.isEmpty) {
                hash = 31 * hash + stack.item.hashCode()
                hash = 31 * hash + stack.count
            }
        }
        val prevHash = lastInventoryHash
        if (prevHash != null && hash != prevHash) {
            val data = buildJsonObject {
                put("changed", true)
            }
            mgr.dispatch(ParrotEventType.INVENTORY_CHANGED, tick, data, player.position())
        }
        lastInventoryHash = hash
    }

    private fun buildEntityData(entity: Entity) = buildJsonObject {
        put("uuid", entity.stringUUID)
        put("type", entity.type.descriptionId)
        put("x", entity.position().x)
        put("y", entity.position().y)
        put("z", entity.position().z)
        if (entity is LivingEntity) {
            put("health", entity.health.toDouble())
        }
    }
}

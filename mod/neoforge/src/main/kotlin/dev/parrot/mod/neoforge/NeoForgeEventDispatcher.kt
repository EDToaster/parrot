package dev.parrot.mod.neoforge

import dev.parrot.mod.events.ParrotEventType
import dev.parrot.mod.events.SubscriptionManager
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.level.BlockEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent

object NeoForgeEventDispatcher {
    @Volatile
    private var subscriptionManager: SubscriptionManager? = null
    private var registered = false

    // Polling state
    private var lastPlayerHealth: Float? = null
    private var lastPlayerPos: Vec3? = null
    private var lastInventoryHash: Int? = null

    fun register(subMgr: SubscriptionManager) {
        subscriptionManager = subMgr
        lastPlayerHealth = null
        lastPlayerPos = null
        lastInventoryHash = null
        if (!registered) {
            registered = true
            NeoForge.EVENT_BUS.register(this)
        }
    }

    fun unregister() {
        subscriptionManager = null
    }

    @SubscribeEvent
    fun onBlockPlace(event: BlockEvent.EntityPlaceEvent) {
        val mgr = subscriptionManager ?: return
        val level = event.level as? ServerLevel ?: return
        val pos = event.pos
        val tick = level.server.tickCount.toLong()
        val data = buildJsonObject {
            put("x", pos.x)
            put("y", pos.y)
            put("z", pos.z)
            put("block", event.placedBlock.block.descriptionId)
            put("source", "place")
        }
        mgr.dispatch(ParrotEventType.BLOCK_CHANGED, tick, data, Vec3.atCenterOf(pos))
    }

    @SubscribeEvent
    fun onBlockBreak(event: BlockEvent.BreakEvent) {
        val mgr = subscriptionManager ?: return
        val level = event.level as? ServerLevel ?: return
        val pos = event.pos
        val tick = level.server.tickCount.toLong()
        val data = buildJsonObject {
            put("x", pos.x)
            put("y", pos.y)
            put("z", pos.z)
            put("block", event.state.block.descriptionId)
            put("source", "player_break")
        }
        mgr.dispatch(ParrotEventType.BLOCK_CHANGED, tick, data, Vec3.atCenterOf(pos))
    }

    @SubscribeEvent
    fun onEntityJoin(event: EntityJoinLevelEvent) {
        val mgr = subscriptionManager ?: return
        val entity = event.entity
        if (entity is Player) return
        val level = event.level as? ServerLevel ?: return
        val tick = level.server.tickCount.toLong()
        val data = buildEntityData(entity)
        mgr.dispatch(ParrotEventType.ENTITY_SPAWNED, tick, data, entity.position())
    }

    @SubscribeEvent
    fun onEntityLeave(event: EntityLeaveLevelEvent) {
        val mgr = subscriptionManager ?: return
        val entity = event.entity
        if (entity is Player) return
        val level = event.level as? ServerLevel ?: return
        val tick = level.server.tickCount.toLong()
        val data = buildEntityData(entity)
        mgr.dispatch(ParrotEventType.ENTITY_REMOVED, tick, data, entity.position())
    }

    @SubscribeEvent
    fun onLivingDeath(event: LivingDeathEvent) {
        val mgr = subscriptionManager ?: return
        val entity = event.entity
        if (entity is ServerPlayer) {
            val server = entity.level().server ?: return
            val tick = server.tickCount.toLong()
            val data = buildJsonObject {
                put("player", entity.gameProfile.name)
                put("cause", event.source.type().msgId())
            }
            mgr.dispatch(ParrotEventType.DEATH, tick, data, entity.position())
        }
    }

    @SubscribeEvent
    fun onServerTick(event: ServerTickEvent.Post) {
        pollPlayerState(event.server)
    }

    private fun pollPlayerState(server: MinecraftServer) {
        val mgr = subscriptionManager ?: return
        val player = server.playerList.players.firstOrNull() ?: return
        val tick = server.tickCount.toLong()

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

        val pos = player.position()
        val prevPos = lastPlayerPos
        if (prevPos != null && prevPos.distanceToSqr(pos) > 0.01) {
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
            val data = buildJsonObject { put("changed", true) }
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

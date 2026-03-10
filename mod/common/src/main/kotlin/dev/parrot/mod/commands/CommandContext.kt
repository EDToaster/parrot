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
        if (dimension == null) return player?.level() ?: server.overworld()
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

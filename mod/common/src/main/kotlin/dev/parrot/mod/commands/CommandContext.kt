package dev.parrot.mod.commands

import dev.parrot.mod.engine.bridge.ScreenReader
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

data class CommandContext(
    val server: MinecraftServer,
    val player: ServerPlayer?,
    val tickCount: Long,
    val screenReader: ScreenReader?
)

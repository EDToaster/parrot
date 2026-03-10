package dev.parrot.mod.commands.actions

import dev.parrot.mod.commands.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket

class AttackBlockHandler : CommandHandler {
    override val method = "attack_block"
    override val isReadOnly = false

    override fun handle(params: JsonObject, context: CommandContext): JsonObject {
        val x = params.int("x"); val y = params.int("y"); val z = params.int("z")
        val directionName = params.stringOrNull("direction") ?: "up"

        val player = context.resolvePlayer()
        val level = player.level()
        val pos = BlockPos(x, y, z)

        if (!level.isLoaded(pos)) {
            throw ParrotException(ErrorCode.BLOCK_OUT_OF_RANGE, "Block at ($x, $y, $z) is not in a loaded chunk")
        }

        val direction = try {
            Direction.valueOf(directionName.uppercase())
        } catch (_: IllegalArgumentException) {
            Direction.UP
        }

        val blockBefore = level.getBlockState(pos)
        val blockId = BuiltInRegistries.BLOCK.getKey(blockBefore.block)?.toString() ?: "unknown"

        player.gameMode.handleBlockBreakAction(
            pos,
            ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
            direction,
            level.getMaxY(),
            0
        )

        val blockAfter = level.getBlockState(pos)
        val broken = blockAfter.isAir

        return buildJsonObject {
            put("success", true)
            put("block_broken", if (broken) blockId else null)
        }
    }
}

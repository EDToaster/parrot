package dev.parrot.mod.commands.actions

import dev.parrot.mod.commands.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

class InteractBlockHandler : CommandHandler {
    override val method = "interact_block"
    override val isReadOnly = false

    override fun handle(params: JsonObject, context: CommandContext): JsonObject {
        val x = params.int("x"); val y = params.int("y"); val z = params.int("z")
        val handName = params.stringOrNull("hand") ?: "main_hand"
        val directionName = params.stringOrNull("direction") ?: "up"

        val player = context.resolvePlayer()
        val level = player.level()
        val pos = BlockPos(x, y, z)

        if (!level.isLoaded(pos)) {
            throw ParrotException(ErrorCode.BLOCK_OUT_OF_RANGE, "Block at ($x, $y, $z) is not in a loaded chunk")
        }

        val hand = when (handName) {
            "off_hand" -> InteractionHand.OFF_HAND
            else -> InteractionHand.MAIN_HAND
        }

        val direction = try {
            Direction.valueOf(directionName.uppercase())
        } catch (_: IllegalArgumentException) {
            Direction.UP
        }

        val itemInHand = player.getItemInHand(hand)
        val hitResult = BlockHitResult(
            Vec3.atCenterOf(pos),
            direction,
            pos,
            false
        )

        val result = player.gameMode.useItemOn(player, level, itemInHand, hand, hitResult)

        return buildJsonObject {
            put("success", true)
            put("result", result.javaClass.simpleName.lowercase())
        }
    }
}

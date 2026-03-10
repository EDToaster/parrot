package dev.parrot.mod.commands.queries

import dev.parrot.mod.commands.*
import kotlinx.serialization.json.*
import net.minecraft.core.BlockPos

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

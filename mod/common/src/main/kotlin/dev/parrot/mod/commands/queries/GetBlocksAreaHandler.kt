package dev.parrot.mod.commands.queries

import dev.parrot.mod.commands.*
import kotlinx.serialization.json.*
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries

class GetBlocksAreaHandler : CommandHandler {
    override val method = "get_blocks_area"
    override val isReadOnly = true

    override fun handle(params: JsonObject, context: CommandContext): JsonObject {
        val x1 = params.int("x1"); val y1 = params.int("y1"); val z1 = params.int("z1")
        val x2 = params.int("x2"); val y2 = params.int("y2"); val z2 = params.int("z2")
        val includeAir = params.booleanOrDefault("include_air", false)
        val filter = params.stringOrNull("filter")
        val level = context.resolveLevel(params.stringOrNull("dimension"))

        val dx = kotlin.math.abs(x2 - x1) + 1
        val dy = kotlin.math.abs(y2 - y1) + 1
        val dz = kotlin.math.abs(z2 - z1) + 1
        val volume = dx.toLong() * dy.toLong() * dz.toLong()
        if (volume > 32768) throw ParrotException(ErrorCode.AREA_TOO_LARGE, "Area volume $volume exceeds maximum of 32768")

        val from = BlockPos(minOf(x1, x2), minOf(y1, y2), minOf(z1, z2))
        val to = BlockPos(maxOf(x1, x2), maxOf(y1, y2), maxOf(z1, z2))

        val blocks = mutableListOf<JsonObject>()
        var totalBlocks = 0
        val maxResults = 10000

        for (pos in BlockPos.betweenClosed(from, to)) {
            if (!level.isLoaded(pos)) continue
            val state = level.getBlockState(pos)
            val blockId = BuiltInRegistries.BLOCK.getKey(state.block)?.toString() ?: "unknown"

            if (!includeAir && state.isAir) continue
            if (filter != null && blockId != filter) continue

            totalBlocks++
            if (blocks.size < maxResults) {
                blocks.add(buildJsonObject {
                    put("x", pos.x); put("y", pos.y); put("z", pos.z)
                    put("block", blockId)
                    if (state.values.isNotEmpty()) {
                        putJsonObject("properties") {
                            for ((property, value) in state.values.entries) {
                                put(property.name, value.toString())
                            }
                        }
                    }
                })
            }
        }

        return buildJsonObject {
            putJsonArray("blocks") { blocks.forEach { add(it) } }
            put("total_blocks", totalBlocks)
            put("returned_blocks", blocks.size)
            put("truncated", totalBlocks > maxResults)
        }
    }
}

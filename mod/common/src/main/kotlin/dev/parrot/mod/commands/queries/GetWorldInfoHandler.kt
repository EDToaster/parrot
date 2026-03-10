package dev.parrot.mod.commands.queries

import dev.parrot.mod.commands.*
import kotlinx.serialization.json.*
import net.minecraft.world.level.GameRules

class GetWorldInfoHandler : CommandHandler {
    override val method = "get_world_info"
    override val isReadOnly = true

    override fun handle(params: JsonObject, context: CommandContext): JsonObject {
        val server = context.server
        val overworld = server.overworld()

        val dayTime = overworld.dayTime
        val gameTime = overworld.gameTime
        val isDay = dayTime % 24000 in 0..12999

        val weather = when {
            overworld.isThundering -> "thunder"
            overworld.isRaining -> "rain"
            else -> "clear"
        }

        val gameRules = buildJsonObject {
            server.gameRules.visitGameRuleTypes(object : GameRules.GameRuleTypeVisitor {
                override fun <T : GameRules.Value<T>> visit(key: GameRules.Key<T>, type: GameRules.Type<T>) {
                    val value = server.gameRules.getRule(key)
                    put(key.id, value.toString())
                }
            })
        }

        val dimensions = server.allLevels.map { it.dimension().location().toString() }

        val tickTimesNanos = server.tickTimesNanos
        val avgTickMs = if (tickTimesNanos.isNotEmpty()) {
            tickTimesNanos.average() / 1_000_000.0
        } else {
            50.0
        }
        val tps = minOf(20.0, 1000.0 / avgTickMs)

        val players = server.playerList.players.map { buildJsonObject {
            put("name", it.gameProfile.name)
            put("uuid", it.stringUUID)
        }}

        return buildJsonObject {
            put("day_time", dayTime)
            put("game_time", gameTime)
            put("is_day", isDay)
            put("weather", weather)
            put("difficulty", server.worldData.difficulty.key)
            put("game_rules", gameRules)
            putJsonArray("loaded_dimensions") { dimensions.forEach { add(JsonPrimitive(it)) } }
            put("server_tps", tps)
            putJsonArray("online_players") { players.forEach { add(it) } }
        }
    }
}

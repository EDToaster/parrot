package dev.parrot.mod.test

import dev.parrot.protocol.*
import kotlinx.serialization.json.*
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.block.Blocks
import java.nio.file.Files
import java.nio.file.Path

class ParrotGameTests : FabricGameTest {

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    fun webSocketLifecycleTest(helper: GameTestHelper) {
        // Verify the WebSocket server is running by attempting a connection
        val client = ParrotTestHelper.connectAndAuth()
        client.close()
        helper.succeed()
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    fun connectionFileTest(helper: GameTestHelper) {
        val connectionFile = Path.of(System.getProperty("user.home"), ".parrot", "connection.json")
        if (!Files.exists(connectionFile)) {
            helper.fail("Connection file does not exist at $connectionFile")
            return
        }
        val json = Files.readString(connectionFile)
        val connInfo = ParrotJson.decodeFromString(ConnectionInfo.serializer(), json)
        if (connInfo.port <= 0) {
            helper.fail("Invalid port in connection file: ${connInfo.port}")
            return
        }
        if (connInfo.token.isBlank()) {
            helper.fail("Empty token in connection file")
            return
        }
        helper.succeed()
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    fun getBlockTest(helper: GameTestHelper) {
        // Place a diamond block at a known position within the test structure
        val relPos = BlockPos(1, 1, 1)
        helper.setBlock(relPos, Blocks.DIAMOND_BLOCK)

        // Convert to absolute position for the query
        val absPos = helper.absolutePos(relPos)

        val client = ParrotTestHelper.connectAndAuth()
        try {
            val params = buildJsonObject {
                put("x", absPos.x)
                put("y", absPos.y)
                put("z", absPos.z)
            }
            val id = client.sendQuery("get_block", params)
            val response = client.waitForMessage(id)
                ?: run { helper.fail("No response for get_block"); return }

            if (response is QueryResult) {
                val blockId = response.result["block_id"]?.jsonPrimitive?.content ?: ""
                if (!blockId.contains("diamond_block")) {
                    helper.fail("Expected diamond_block, got: $blockId")
                    return
                }
                helper.succeed()
            } else if (response is ErrorResponse) {
                helper.fail("get_block error: ${response.code} — ${response.message}")
            } else {
                helper.fail("Unexpected response type: ${response::class.simpleName}")
            }
        } finally {
            client.close()
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    fun getEntitiesTest(helper: GameTestHelper) {
        // Spawn a cow at a known position
        val relPos = BlockPos(2, 1, 2)
        helper.spawn(EntityType.COW, relPos)

        val absPos = helper.absolutePos(relPos)

        val client = ParrotTestHelper.connectAndAuth()
        try {
            val params = buildJsonObject {
                put("x", absPos.x.toDouble())
                put("y", absPos.y.toDouble())
                put("z", absPos.z.toDouble())
                put("radius", 10.0)
            }
            val id = client.sendQuery("get_entities", params)
            val response = client.waitForMessage(id)
                ?: run { helper.fail("No response for get_entities"); return }

            if (response is QueryResult) {
                val entities = response.result["entities"]?.jsonArray ?: JsonArray(emptyList())
                val hasCow = entities.any { entity ->
                    val type = entity.jsonObject["type"]?.jsonPrimitive?.content ?: ""
                    type.contains("cow")
                }
                if (!hasCow) {
                    helper.fail("Expected cow in entities, got: $entities")
                    return
                }
                helper.succeed()
            } else if (response is ErrorResponse) {
                helper.fail("get_entities error: ${response.code} — ${response.message}")
            } else {
                helper.fail("Unexpected response type: ${response::class.simpleName}")
            }
        } finally {
            client.close()
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    fun interactBlockTest(helper: GameTestHelper) {
        // Place a chest at a known position
        val relPos = BlockPos(1, 1, 1)
        helper.setBlock(relPos, Blocks.CHEST)

        val absPos = helper.absolutePos(relPos)

        val client = ParrotTestHelper.connectAndAuth()
        try {
            val params = buildJsonObject {
                put("x", absPos.x)
                put("y", absPos.y)
                put("z", absPos.z)
            }
            val id = client.sendAction("interact_block", params, consequenceWait = 10)
            val response = client.waitForMessage(id, timeoutMs = 10000)
                ?: run { helper.fail("No response for interact_block"); return }

            if (response is ActionResult) {
                if (!response.success) {
                    helper.fail("interact_block failed")
                    return
                }
                // Check for screen_opened consequence
                val hasScreenOpened = response.consequences.any { it.type == "screen_opened" }
                if (!hasScreenOpened) {
                    helper.fail("Expected screen_opened consequence, got: ${response.consequences}")
                    return
                }
                helper.succeed()
            } else if (response is ErrorResponse) {
                helper.fail("interact_block error: ${response.code} — ${response.message}")
            } else {
                helper.fail("Unexpected response type: ${response::class.simpleName}")
            }
        } finally {
            client.close()
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    fun runCommandTest(helper: GameTestHelper) {
        val client = ParrotTestHelper.connectAndAuth()
        try {
            val id = client.sendCommand("time set day")
            val response = client.waitForMessage(id)
                ?: run { helper.fail("No response for run_command"); return }

            if (response is CommandResult) {
                if (!response.success) {
                    helper.fail("run_command failed: ${response.output}")
                    return
                }
                helper.succeed()
            } else if (response is ErrorResponse) {
                helper.fail("run_command error: ${response.code} — ${response.message}")
            } else {
                helper.fail("Unexpected response type: ${response::class.simpleName}")
            }
        } finally {
            client.close()
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 200)
    fun eventSubscriptionTest(helper: GameTestHelper) {
        val client = ParrotTestHelper.connectAndAuth()
        try {
            // Subscribe to block_changed events
            val subId = client.sendSubscribe(listOf("block_changed"))
            val subResponse = client.waitForMessage(subId)
                ?: run { helper.fail("No response for subscribe"); return }

            if (subResponse !is SubscribeAck) {
                helper.fail("Expected SubscribeAck, got: ${subResponse::class.simpleName}")
                return
            }

            // Break a block to trigger the event — place then remove
            val relPos = BlockPos(3, 1, 3)
            helper.setBlock(relPos, Blocks.STONE)

            // Wait a tick for the block to be placed, then break it
            helper.runAfterDelay(5) {
                helper.destroyBlock(relPos)

                // Wait for the event
                val event = client.waitForEvent(timeoutMs = 5000)
                if (event == null) {
                    helper.fail("No block_changed event received")
                    return@runAfterDelay
                }
                if (event.eventType != "block_changed") {
                    helper.fail("Expected block_changed event, got: ${event.eventType}")
                    return@runAfterDelay
                }
                helper.succeed()
            }
        } catch (e: Exception) {
            client.close()
            helper.fail("Exception: ${e.message}")
        }
        // Note: client.close() is called after succeed/fail in the delayed task
    }
}

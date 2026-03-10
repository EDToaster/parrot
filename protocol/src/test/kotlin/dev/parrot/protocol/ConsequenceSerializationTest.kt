package dev.parrot.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class ConsequenceSerializationTest {

    @Test
    fun `screen_opened consequence round-trips`() {
        val c = Consequence(type = "screen_opened", tick = 50001L, data = buildJsonObject { put("title", "Chest") })
        val json = ParrotJson.encodeToString(Consequence.serializer(), c)
        val decoded = ParrotJson.decodeFromString(Consequence.serializer(), json)
        assertEquals(c, decoded)
    }

    @Test
    fun `screen_closed consequence round-trips`() {
        val c = Consequence(type = "screen_closed", tick = 50002L, data = buildJsonObject { put("title", "Chest") })
        val json = ParrotJson.encodeToString(Consequence.serializer(), c)
        val decoded = ParrotJson.decodeFromString(Consequence.serializer(), json)
        assertEquals(c, decoded)
    }

    @Test
    fun `block_changed consequence round-trips`() {
        val data = buildJsonObject {
            put("x", 10); put("y", 64); put("z", -20)
            put("oldBlock", "minecraft:stone")
            put("newBlock", "minecraft:air")
        }
        val c = Consequence(type = "block_changed", tick = 50003L, data = data)
        val json = ParrotJson.encodeToString(Consequence.serializer(), c)
        val decoded = ParrotJson.decodeFromString(Consequence.serializer(), json)
        assertEquals(c, decoded)
    }

    @Test
    fun `entity_spawned consequence round-trips`() {
        val data = buildJsonObject {
            put("entityId", 42)
            put("entityType", "minecraft:item")
        }
        val c = Consequence(type = "entity_spawned", tick = 50004L, data = data)
        val json = ParrotJson.encodeToString(Consequence.serializer(), c)
        val decoded = ParrotJson.decodeFromString(Consequence.serializer(), json)
        assertEquals(c, decoded)
    }

    @Test
    fun `chat_message consequence round-trips`() {
        val c = Consequence(type = "chat_message", tick = 50005L, data = buildJsonObject { put("text", "Hello world") })
        val json = ParrotJson.encodeToString(Consequence.serializer(), c)
        val decoded = ParrotJson.decodeFromString(Consequence.serializer(), json)
        assertEquals(c, decoded)
    }

    @Test
    fun `inventory_changed consequence round-trips`() {
        val data = buildJsonObject {
            put("slot", 0)
            put("itemId", "minecraft:diamond")
            put("count", 64)
        }
        val c = Consequence(type = "inventory_changed", tick = 50006L, data = data)
        val json = ParrotJson.encodeToString(Consequence.serializer(), c)
        val decoded = ParrotJson.decodeFromString(Consequence.serializer(), json)
        assertEquals(c, decoded)
    }

    @Test
    fun `consequence with empty data round-trips`() {
        val c = Consequence(type = "unknown_event", tick = 50007L)
        val json = ParrotJson.encodeToString(Consequence.serializer(), c)
        val decoded = ParrotJson.decodeFromString(Consequence.serializer(), json)
        assertEquals(c, decoded)
        assertEquals(JsonObject(emptyMap()), decoded.data)
    }

    @Test
    fun `multiple consequences in ActionResult round-trip`() {
        val consequences = listOf(
            Consequence(type = "block_changed", tick = 50001L, data = buildJsonObject { put("newBlock", "minecraft:air") }),
            Consequence(type = "entity_spawned", tick = 50002L, data = buildJsonObject { put("entityType", "minecraft:item") }),
            Consequence(type = "inventory_changed", tick = 50003L, data = buildJsonObject { put("slot", 0) })
        )
        val msg = ActionResult(
            id = "1", success = true, tick = 50000L,
            result = buildJsonObject { put("success", true) },
            consequences = consequences
        )
        val json = ParrotJson.encodeToString(ParrotMessage.serializer(), msg)
        val decoded = ParrotJson.decodeFromString(ParrotMessage.serializer(), json) as ActionResult
        assertEquals(3, decoded.consequences.size)
        assertEquals("block_changed", decoded.consequences[0].type)
        assertEquals("entity_spawned", decoded.consequences[1].type)
        assertEquals("inventory_changed", decoded.consequences[2].type)
    }

    @Test
    fun `consequence preserves tick value`() {
        val c = Consequence(type = "test", tick = Long.MAX_VALUE, data = buildJsonObject {})
        val json = ParrotJson.encodeToString(Consequence.serializer(), c)
        val decoded = ParrotJson.decodeFromString(Consequence.serializer(), json)
        assertEquals(Long.MAX_VALUE, decoded.tick)
    }
}

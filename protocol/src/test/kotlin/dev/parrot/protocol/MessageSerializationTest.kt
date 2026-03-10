package dev.parrot.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MessageSerializationTest {

    private fun roundTrip(msg: ParrotMessage): ParrotMessage {
        val json = ParrotJson.encodeToString(ParrotMessage.serializer(), msg)
        return ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
    }

    @Test
    fun `HelloAck round-trips`() {
        val msg = HelloAck(
            id = "1",
            protocolVersion = 1,
            minecraftVersion = "1.21.10",
            modLoader = "fabric",
            modVersion = "0.1.0",
            capabilities = listOf("query", "action", "command", "subscribe"),
            tickRate = 20,
            serverType = "integrated"
        )
        val decoded = roundTrip(msg)
        assertIs<HelloAck>(decoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `Ping round-trips`() {
        val msg = Ping(id = "2", timestamp = 1710000000000L)
        val decoded = roundTrip(msg)
        assertIs<Ping>(decoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `Pong round-trips`() {
        val msg = Pong(id = "3", timestamp = 1710000000001L)
        val decoded = roundTrip(msg)
        assertIs<Pong>(decoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `Goodbye round-trips`() {
        val msg = Goodbye(id = "4")
        val decoded = roundTrip(msg)
        assertIs<Goodbye>(decoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `GoodbyeAck round-trips`() {
        val msg = GoodbyeAck(id = "5")
        val decoded = roundTrip(msg)
        assertIs<GoodbyeAck>(decoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `QueryRequest round-trips`() {
        val params = buildJsonObject { put("x", 10); put("y", 64); put("z", -20) }
        val msg = QueryRequest(id = "6", method = "get_block", params = params)
        val decoded = roundTrip(msg)
        assertIs<QueryRequest>(decoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `QueryRequest with default params round-trips`() {
        val msg = QueryRequest(id = "6b", method = "get_player")
        val decoded = roundTrip(msg)
        assertIs<QueryRequest>(decoded)
        assertEquals(JsonObject(emptyMap()), decoded.params)
    }

    @Test
    fun `QueryResult round-trips`() {
        val result = buildJsonObject {
            put("blockId", "minecraft:stone")
            put("x", 10)
        }
        val msg = QueryResult(id = "7", tick = 50000L, result = result)
        val decoded = roundTrip(msg)
        assertIs<QueryResult>(decoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `CommandRequest round-trips`() {
        val msg = CommandRequest(id = "8", command = "/time set day", consequenceWait = 5)
        val decoded = roundTrip(msg)
        assertIs<CommandRequest>(decoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `CommandRequest with default consequenceWait round-trips`() {
        val msg = CommandRequest(id = "8b", command = "/gamemode creative")
        val decoded = roundTrip(msg)
        assertIs<CommandRequest>(decoded)
        assertEquals(3, decoded.consequenceWait)
    }

    @Test
    fun `CommandResult round-trips`() {
        val consequences = listOf(
            Consequence(type = "chat_message", tick = 50001L, data = buildJsonObject { put("text", "Set the time to 1000") })
        )
        val msg = CommandResult(
            id = "9", success = true, tick = 50000L,
            output = "Set the time to 1000", returnValue = 1,
            consequences = consequences
        )
        val decoded = roundTrip(msg)
        assertIs<CommandResult>(decoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `CommandResult with null returnValue round-trips`() {
        val msg = CommandResult(id = "9b", success = false, tick = 50000L, output = "Unknown command")
        val decoded = roundTrip(msg)
        assertIs<CommandResult>(decoded)
        assertEquals(null, decoded.returnValue)
        assertEquals(emptyList(), decoded.consequences)
    }

    @Test
    fun `SubscribeRequest round-trips`() {
        val filter = buildJsonObject { put("radius", 32) }
        val msg = SubscribeRequest(id = "10", eventTypes = listOf("block_changed", "entity_spawned"), filter = filter)
        val decoded = roundTrip(msg)
        assertIs<SubscribeRequest>(decoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `SubscribeRequest with null filter round-trips`() {
        val msg = SubscribeRequest(id = "10b", eventTypes = listOf("chat_message"))
        val decoded = roundTrip(msg)
        assertIs<SubscribeRequest>(decoded)
        assertEquals(null, decoded.filter)
    }

    @Test
    fun `SubscribeAck round-trips`() {
        val msg = SubscribeAck(id = "11", subscriptionId = "sub-001", subscribedEvents = listOf("block_changed", "entity_spawned"))
        val decoded = roundTrip(msg)
        assertIs<SubscribeAck>(decoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `UnsubscribeRequest round-trips`() {
        val msg = UnsubscribeRequest(id = "12", subscriptionId = "sub-001")
        val decoded = roundTrip(msg)
        assertIs<UnsubscribeRequest>(decoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `UnsubscribeAck round-trips`() {
        val msg = UnsubscribeAck(id = "13", success = true)
        val decoded = roundTrip(msg)
        assertIs<UnsubscribeAck>(decoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `PushEvent round-trips`() {
        val data = buildJsonObject {
            put("blockId", "minecraft:diamond_ore")
            put("x", 5); put("y", 12); put("z", -3)
        }
        val msg = PushEvent(id = "14", subscriptionId = "sub-001", tick = 60000L, eventType = "block_changed", data = data)
        val decoded = roundTrip(msg)
        assertIs<PushEvent>(decoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `PushEvent with default empty id round-trips`() {
        val data = buildJsonObject { put("message", "hello") }
        val msg = PushEvent(subscriptionId = "sub-002", tick = 60001L, eventType = "chat_message", data = data)
        val decoded = roundTrip(msg)
        assertIs<PushEvent>(decoded)
        assertEquals("", decoded.id)
    }

    @Test
    fun `BatchResult round-trips`() {
        val results = listOf(
            buildJsonObject { put("blockId", "minecraft:stone") },
            buildJsonObject { put("health", 20.0) }
        )
        val msg = BatchResult(id = "15", results = results)
        val decoded = roundTrip(msg)
        assertIs<BatchResult>(decoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `ActionResult round-trips with consequences`() {
        val result = buildJsonObject { put("success", true) }
        val consequences = listOf(
            Consequence(type = "block_changed", tick = 50001L, data = buildJsonObject { put("blockId", "minecraft:air") })
        )
        val msg = ActionResult(id = "16", success = true, tick = 50000L, result = result, consequences = consequences)
        val decoded = roundTrip(msg)
        assertIs<ActionResult>(decoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `ActionResult with defaults round-trips`() {
        val msg = ActionResult(id = "16b", success = false, tick = 50000L)
        val decoded = roundTrip(msg)
        assertIs<ActionResult>(decoded)
        assertEquals(JsonObject(emptyMap()), decoded.result)
        assertEquals(emptyList(), decoded.consequences)
    }
}

package dev.parrot.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SerializationTest {
    @Test
    fun `Hello round-trips through JSON`() {
        val msg = Hello(id = "1", protocolVersion = 1, authToken = "abc123")
        val json = ParrotJson.encodeToString(ParrotMessage.serializer(), msg)
        val decoded = ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
        assertEquals(msg, decoded)
    }

    @Test
    fun `polymorphic deserialization resolves correct type`() {
        val json = """{"type":"hello","id":"1","protocolVersion":1,"authToken":"test"}"""
        val msg = ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
        assertIs<Hello>(msg)
        assertEquals("test", msg.authToken)
    }

    @Test
    fun `ActionRequest with defaults round-trips`() {
        val msg = ActionRequest(id = "7", method = "interact_block")
        val json = ParrotJson.encodeToString(ParrotMessage.serializer(), msg)
        val decoded = ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
        assertIs<ActionRequest>(decoded)
        assertEquals(5, decoded.consequenceWait)
        assertEquals(null, decoded.consequenceFilter)
    }

    @Test
    fun `ErrorResponse round-trips with details`() {
        val details = buildJsonObject { put("position", "100,64,-200") }
        val msg = ErrorResponse(id = "7", code = "BLOCK_OUT_OF_RANGE", message = "Not loaded", details = details)
        val json = ParrotJson.encodeToString(ParrotMessage.serializer(), msg)
        val decoded = ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
        assertEquals(msg, decoded)
    }

    @Test
    fun `ConnectionInfo serializes correctly`() {
        val info = ConnectionInfo(port = 25566, token = "abc", pid = 12345)
        val json = ParrotJson.encodeToString(ConnectionInfo.serializer(), info)
        val decoded = ParrotJson.decodeFromString(ConnectionInfo.serializer(), json)
        assertEquals(info, decoded)
    }

    @Test
    fun `BatchRequest round-trips`() {
        val msg = BatchRequest(id = "1", commands = listOf(
            BatchCommand(method = "get_block", params = buildJsonObject { put("x", 1); put("y", 2); put("z", 3) }),
            BatchCommand(method = "get_player")
        ))
        val json = ParrotJson.encodeToString(ParrotMessage.serializer(), msg)
        val decoded = ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
        assertIs<BatchRequest>(decoded)
        assertEquals(2, decoded.commands.size)
    }

    @Test
    fun `Consequence serializes with data`() {
        val c = Consequence(type = "screen_opened", tick = 50001, data = buildJsonObject { put("title", "Chest") })
        val json = ParrotJson.encodeToString(Consequence.serializer(), c)
        val decoded = ParrotJson.decodeFromString(Consequence.serializer(), json)
        assertEquals(c, decoded)
    }
}

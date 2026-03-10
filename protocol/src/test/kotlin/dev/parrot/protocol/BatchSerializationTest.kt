package dev.parrot.protocol

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BatchSerializationTest {

    @Test
    fun `empty batch request round-trips`() {
        val msg = BatchRequest(id = "1", commands = emptyList())
        val json = ParrotJson.encodeToString(ParrotMessage.serializer(), msg)
        val decoded = ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
        assertIs<BatchRequest>(decoded)
        assertEquals(emptyList(), decoded.commands)
    }

    @Test
    fun `empty batch result round-trips`() {
        val msg = BatchResult(id = "2", results = emptyList())
        val json = ParrotJson.encodeToString(ParrotMessage.serializer(), msg)
        val decoded = ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
        assertIs<BatchResult>(decoded)
        assertEquals(emptyList(), decoded.results)
    }

    @Test
    fun `mixed batch with query and action commands round-trips`() {
        val commands = listOf(
            BatchCommand(method = "get_block", params = buildJsonObject { put("x", 0); put("y", 64); put("z", 0) }),
            BatchCommand(method = "get_player"),
            BatchCommand(method = "get_entities", params = buildJsonObject { put("radius", 32) })
        )
        val msg = BatchRequest(id = "3", commands = commands)
        val json = ParrotJson.encodeToString(ParrotMessage.serializer(), msg)
        val decoded = ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
        assertIs<BatchRequest>(decoded)
        assertEquals(3, decoded.commands.size)
        assertEquals("get_block", decoded.commands[0].method)
        assertEquals("get_player", decoded.commands[1].method)
        assertEquals("get_entities", decoded.commands[2].method)
    }

    @Test
    fun `batch result with mixed success and error results round-trips`() {
        val results = listOf(
            buildJsonObject { put("blockId", "minecraft:stone") },
            buildJsonObject { put("error", "BLOCK_OUT_OF_RANGE"); put("message", "Not loaded") },
            buildJsonObject { put("health", 20.0); put("name", "Steve") }
        )
        val msg = BatchResult(id = "4", results = results)
        val json = ParrotJson.encodeToString(ParrotMessage.serializer(), msg)
        val decoded = ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
        assertIs<BatchResult>(decoded)
        assertEquals(3, decoded.results.size)
    }

    @Test
    fun `batch result preserves order of results`() {
        val results = listOf(
            buildJsonObject { put("index", 0) },
            buildJsonObject { put("index", 1) },
            buildJsonObject { put("index", 2) }
        )
        val msg = BatchResult(id = "5", results = results)
        val json = ParrotJson.encodeToString(ParrotMessage.serializer(), msg)
        val decoded = ParrotJson.decodeFromString(ParrotMessage.serializer(), json) as BatchResult
        assertEquals(results, decoded.results)
    }

    @Test
    fun `BatchCommand with default params has empty JsonObject`() {
        val cmd = BatchCommand(method = "get_world_info")
        val json = ParrotJson.encodeToString(BatchCommand.serializer(), cmd)
        val decoded = ParrotJson.decodeFromString(BatchCommand.serializer(), json)
        assertEquals(cmd, decoded)
        assertEquals(kotlinx.serialization.json.JsonObject(emptyMap()), decoded.params)
    }

    @Test
    fun `large batch request round-trips`() {
        val commands = (0 until 50).map { i ->
            BatchCommand(method = "get_block", params = buildJsonObject { put("x", i); put("y", 64); put("z", 0) })
        }
        val msg = BatchRequest(id = "6", commands = commands)
        val json = ParrotJson.encodeToString(ParrotMessage.serializer(), msg)
        val decoded = ParrotJson.decodeFromString(ParrotMessage.serializer(), json) as BatchRequest
        assertEquals(50, decoded.commands.size)
    }
}

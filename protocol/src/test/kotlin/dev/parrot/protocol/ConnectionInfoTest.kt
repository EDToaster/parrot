package dev.parrot.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConnectionInfoTest {

    @Test
    fun `ConnectionInfo with null pid round-trips`() {
        val info = ConnectionInfo(port = 25566, token = "secret-token", pid = null)
        val json = ParrotJson.encodeToString(ConnectionInfo.serializer(), info)
        val decoded = ParrotJson.decodeFromString(ConnectionInfo.serializer(), json)
        assertEquals(info, decoded)
        assertNull(decoded.pid)
    }

    @Test
    fun `ConnectionInfo with pid round-trips`() {
        val info = ConnectionInfo(port = 25566, token = "abc123", pid = 99887L)
        val json = ParrotJson.encodeToString(ConnectionInfo.serializer(), info)
        val decoded = ParrotJson.decodeFromString(ConnectionInfo.serializer(), json)
        assertEquals(info, decoded)
        assertEquals(99887L, decoded.pid)
    }

    @Test
    fun `ConnectionInfo default pid is null`() {
        val info = ConnectionInfo(port = 8080, token = "tok")
        assertNull(info.pid)
    }

    @Test
    fun `ConnectionInfo JSON contains expected fields`() {
        val info = ConnectionInfo(port = 25566, token = "tok", pid = 123L)
        val json = ParrotJson.encodeToString(ConnectionInfo.serializer(), info)
        assert(json.contains("\"port\":25566"))
        assert(json.contains("\"token\":\"tok\""))
        assert(json.contains("\"pid\":123"))
    }

    @Test
    fun `ConnectionInfo with null pid includes null in JSON`() {
        val info = ConnectionInfo(port = 25566, token = "tok", pid = null)
        val json = ParrotJson.encodeToString(ConnectionInfo.serializer(), info)
        assert(json.contains("\"pid\":null"))
    }

    @Test
    fun `ConnectionInfo deserializes from raw JSON with missing pid`() {
        val json = """{"port":25566,"token":"abc"}"""
        val decoded = ParrotJson.decodeFromString(ConnectionInfo.serializer(), json)
        assertEquals(25566, decoded.port)
        assertEquals("abc", decoded.token)
        assertNull(decoded.pid)
    }
}

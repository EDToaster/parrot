package dev.parrot.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ErrorCodeSerializationTest {

    @Test
    fun `INVALID_REQUEST has matching code`() {
        assertEquals("INVALID_REQUEST", ErrorCode.INVALID_REQUEST.code)
    }

    @Test
    fun `UNKNOWN_METHOD has matching code`() {
        assertEquals("UNKNOWN_METHOD", ErrorCode.UNKNOWN_METHOD.code)
    }

    @Test
    fun `INVALID_PARAMS has matching code`() {
        assertEquals("INVALID_PARAMS", ErrorCode.INVALID_PARAMS.code)
    }

    @Test
    fun `AUTH_FAILED has matching code`() {
        assertEquals("AUTH_FAILED", ErrorCode.AUTH_FAILED.code)
    }

    @Test
    fun `NOT_AUTHENTICATED has matching code`() {
        assertEquals("NOT_AUTHENTICATED", ErrorCode.NOT_AUTHENTICATED.code)
    }

    @Test
    fun `BLOCK_OUT_OF_RANGE has matching code`() {
        assertEquals("BLOCK_OUT_OF_RANGE", ErrorCode.BLOCK_OUT_OF_RANGE.code)
    }

    @Test
    fun `ENTITY_NOT_FOUND has matching code`() {
        assertEquals("ENTITY_NOT_FOUND", ErrorCode.ENTITY_NOT_FOUND.code)
    }

    @Test
    fun `NO_SCREEN_OPEN has matching code`() {
        assertEquals("NO_SCREEN_OPEN", ErrorCode.NO_SCREEN_OPEN.code)
    }

    @Test
    fun `INVALID_SLOT has matching code`() {
        assertEquals("INVALID_SLOT", ErrorCode.INVALID_SLOT.code)
    }

    @Test
    fun `COMMAND_FAILED has matching code`() {
        assertEquals("COMMAND_FAILED", ErrorCode.COMMAND_FAILED.code)
    }

    @Test
    fun `TIMEOUT has matching code`() {
        assertEquals("TIMEOUT", ErrorCode.TIMEOUT.code)
    }

    @Test
    fun `INTERNAL_ERROR has matching code`() {
        assertEquals("INTERNAL_ERROR", ErrorCode.INTERNAL_ERROR.code)
    }

    @Test
    fun `RATE_LIMITED has matching code`() {
        assertEquals("RATE_LIMITED", ErrorCode.RATE_LIMITED.code)
    }

    @Test
    fun `all ErrorCode entries have name matching code`() {
        for (errorCode in ErrorCode.entries) {
            assertEquals(errorCode.name, errorCode.code, "ErrorCode.${errorCode.name} has mismatched code: ${errorCode.code}")
        }
    }

    @Test
    fun `ErrorCode has expected number of entries`() {
        assertEquals(13, ErrorCode.entries.size)
    }

    @Test
    fun `ErrorCode can be used in ErrorResponse`() {
        val msg = ErrorResponse(
            id = "1",
            code = ErrorCode.BLOCK_OUT_OF_RANGE.code,
            message = "Block is not in loaded chunks"
        )
        val json = ParrotJson.encodeToString(ParrotMessage.serializer(), msg)
        val decoded = ParrotJson.decodeFromString(ParrotMessage.serializer(), json) as ErrorResponse
        assertNotNull(ErrorCode.entries.find { it.code == decoded.code })
    }
}

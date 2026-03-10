package dev.parrot.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Consequence(
    val type: String,
    val tick: Long,
    val data: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class BatchCommand(
    val method: String,
    val params: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class ConnectionInfo(
    val port: Int,
    val token: String,
    val pid: Long? = null
)

enum class ErrorCode(val code: String) {
    INVALID_REQUEST("INVALID_REQUEST"),
    UNKNOWN_METHOD("UNKNOWN_METHOD"),
    INVALID_PARAMS("INVALID_PARAMS"),
    AUTH_FAILED("AUTH_FAILED"),
    NOT_AUTHENTICATED("NOT_AUTHENTICATED"),
    BLOCK_OUT_OF_RANGE("BLOCK_OUT_OF_RANGE"),
    ENTITY_NOT_FOUND("ENTITY_NOT_FOUND"),
    NO_SCREEN_OPEN("NO_SCREEN_OPEN"),
    INVALID_SLOT("INVALID_SLOT"),
    COMMAND_FAILED("COMMAND_FAILED"),
    TIMEOUT("TIMEOUT"),
    INTERNAL_ERROR("INTERNAL_ERROR"),
    RATE_LIMITED("RATE_LIMITED")
}

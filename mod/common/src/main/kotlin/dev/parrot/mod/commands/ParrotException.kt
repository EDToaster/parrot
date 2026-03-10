package dev.parrot.mod.commands

enum class ErrorCode(val code: String) {
    INVALID_PARAMS("INVALID_PARAMS"),
    BLOCK_OUT_OF_RANGE("BLOCK_OUT_OF_RANGE"),
    ENTITY_NOT_FOUND("ENTITY_NOT_FOUND"),
    NO_SCREEN_OPEN("NO_SCREEN_OPEN"),
    NO_PLAYER("NO_PLAYER"),
    INVALID_SLOT("INVALID_SLOT"),
    COMMAND_FAILED("COMMAND_FAILED"),
    AREA_TOO_LARGE("AREA_TOO_LARGE"),
    UNKNOWN_METHOD("UNKNOWN_METHOD"),
    INTERNAL_ERROR("INTERNAL_ERROR"),
    BATCH_ACTIONS_FORBIDDEN("BATCH_ACTIONS_FORBIDDEN")
}

class ParrotException(val errorCode: ErrorCode, override val message: String) : RuntimeException(message)

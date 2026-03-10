package dev.parrot.mod.commands.queries

import dev.parrot.mod.commands.*
import kotlinx.serialization.json.*

class GetScreenHandler : CommandHandler {
    override val method = "get_screen"
    override val isReadOnly = true

    override fun handle(params: JsonObject, context: CommandContext): JsonObject {
        val screenReader = context.screenReader
            ?: throw ParrotException(ErrorCode.NO_SCREEN_OPEN, "No screen reader available")

        val screenState = screenReader.getCurrentScreen()
            ?: throw ParrotException(ErrorCode.NO_SCREEN_OPEN, "No screen is currently open")

        return buildJsonObject {
            put("is_open", screenState.isOpen)
            screenState.screenType?.let { put("screen_type", it) }
            screenState.title?.let { put("title", it) }
            screenState.menuType?.let { put("menu_type", it) }
            putJsonArray("slots") {
                for (slot in screenState.slots) {
                    add(buildJsonObject {
                        put("index", slot.index)
                        put("item", slot.item)
                        put("count", slot.count)
                    })
                }
            }
            putJsonArray("widgets") {
                for (widget in screenState.widgets) {
                    add(buildJsonObject {
                        put("type", widget.type)
                        put("x", widget.x)
                        put("y", widget.y)
                        put("width", widget.width)
                        put("height", widget.height)
                        widget.message?.let { put("message", it) }
                        put("active", widget.active)
                    })
                }
            }
        }
    }
}

package dev.parrot.mod.commands

class CommandRegistry {
    private val handlers = mutableMapOf<String, CommandHandler>()

    fun register(handler: CommandHandler) {
        handlers[handler.method] = handler
    }

    fun get(method: String): CommandHandler? = handlers[method]

    fun listMethods(): List<String> = handlers.keys.sorted()

    fun listReadOnlyMethods(): List<String> =
        handlers.values.filter { it.isReadOnly }.map { it.method }.sorted()
}

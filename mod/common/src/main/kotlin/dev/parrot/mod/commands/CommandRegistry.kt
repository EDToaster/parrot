package dev.parrot.mod.commands

class CommandRegistry {
    private val handlers = mutableMapOf<String, CommandHandler>()

    fun register(handler: CommandHandler) {
        handlers[handler.method] = handler
    }

    fun get(method: String): CommandHandler? = handlers[method]

    fun list(): Collection<String> = handlers.keys
}

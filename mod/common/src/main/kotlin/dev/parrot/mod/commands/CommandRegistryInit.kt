package dev.parrot.mod.commands

import dev.parrot.mod.commands.queries.*
import dev.parrot.mod.commands.actions.*
import dev.parrot.mod.commands.commands.*
import dev.parrot.mod.events.SubscriptionManager

fun createCommandRegistry(subscriptionManager: SubscriptionManager): CommandRegistry {
    val registry = CommandRegistry()
    registry.register(GetBlockHandler())
    registry.register(GetBlocksAreaHandler())
    registry.register(GetWorldInfoHandler())
    registry.register(GetPlayerHandler())
    registry.register(GetInventoryHandler())
    registry.register(GetEntitiesHandler())
    registry.register(GetEntityHandler())
    registry.register(GetScreenHandler())
    registry.register(InteractBlockHandler())
    registry.register(AttackBlockHandler())
    registry.register(InteractEntityHandler())
    registry.register(AttackEntityHandler())
    registry.register(ClickSlotHandler())
    registry.register(CloseScreenHandler())
    registry.register(SetHeldSlotHandler())
    registry.register(SendChatHandler())
    registry.register(RunCommandHandler())
    registry.register(BatchHandler(registry))
    registry.register(SubscribeHandler(subscriptionManager))
    registry.register(UnsubscribeHandler(subscriptionManager))
    return registry
}

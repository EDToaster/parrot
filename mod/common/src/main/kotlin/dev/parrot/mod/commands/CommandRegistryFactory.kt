package dev.parrot.mod.commands

import dev.parrot.mod.events.SubscriptionManager

fun createCommandRegistry(subscriptionManager: SubscriptionManager): CommandRegistry {
    return CommandRegistry()
}

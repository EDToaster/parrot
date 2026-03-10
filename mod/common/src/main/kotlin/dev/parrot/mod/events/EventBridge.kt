package dev.parrot.mod.events

import net.minecraft.server.MinecraftServer

interface EventBridge {
    fun registerListeners(subscriptionManager: SubscriptionManager, server: MinecraftServer)
    fun unregisterListeners()
}

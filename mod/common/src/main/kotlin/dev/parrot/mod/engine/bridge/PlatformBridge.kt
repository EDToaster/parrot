package dev.parrot.mod.engine.bridge

import dev.parrot.mod.events.SubscriptionManager

interface PlatformBridge {
    fun registerEventListeners(subscriptionManager: SubscriptionManager)
    fun getScreenReader(): ScreenReader?
}

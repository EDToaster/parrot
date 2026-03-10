package dev.parrot.mod.neoforge

import dev.parrot.mod.engine.bridge.PlatformBridge
import dev.parrot.mod.engine.bridge.ScreenReader
import dev.parrot.mod.events.SubscriptionManager

class NeoForgePlatformBridge : PlatformBridge {
    companion object {
        @Volatile
        var clientScreenReader: ScreenReader? = null
    }

    override fun registerEventListeners(subscriptionManager: SubscriptionManager) {
        // Event listeners registered on client side via ParrotNeoForgeClient
    }

    override fun getScreenReader(): ScreenReader? = clientScreenReader
}

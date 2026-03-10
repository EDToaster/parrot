package dev.parrot.mod.fabric

import dev.parrot.mod.engine.bridge.PlatformBridge
import dev.parrot.mod.engine.bridge.ScreenReader
import dev.parrot.mod.events.SubscriptionManager

class FabricPlatformBridge : PlatformBridge {
    companion object {
        @Volatile
        var clientScreenReader: ScreenReader? = null
    }

    override fun registerEventListeners(subscriptionManager: SubscriptionManager) {
        // Event listeners registered on client side via ParrotFabricClient
    }

    override fun getScreenReader(): ScreenReader? = clientScreenReader
}

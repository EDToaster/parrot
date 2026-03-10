package dev.parrot.mod.fabric.client

import dev.parrot.mod.engine.ParrotEngine
import dev.parrot.mod.engine.bridge.ScreenObservation
import dev.parrot.mod.engine.bridge.ScreenObservationType
import dev.parrot.mod.fabric.FabricPlatformBridge
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.Minecraft

object ParrotFabricClient : ClientModInitializer {
    private val screenReader = FabricScreenReader()

    override fun onInitializeClient() {
        FabricPlatformBridge.clientScreenReader = screenReader

        ScreenEvents.AFTER_INIT.register { client, screen, scaledWidth, scaledHeight ->
            if (client.hasSingleplayerServer()) {
                val state = screenReader.getCurrentScreen()
                ParrotEngine.onScreenObservation(
                    ScreenObservation(ScreenObservationType.OPENED, state, client.level?.gameTime ?: 0L)
                )
            }

            ScreenEvents.remove(screen).register { removedScreen ->
                if (client.hasSingleplayerServer()) {
                    ParrotEngine.onScreenObservation(
                        ScreenObservation(ScreenObservationType.CLOSED, null, client.level?.gameTime ?: 0L)
                    )
                }
            }
        }
    }
}

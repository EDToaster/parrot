package dev.parrot.mod.neoforge.client

import dev.parrot.mod.engine.ParrotEngine
import dev.parrot.mod.engine.bridge.ScreenObservation
import dev.parrot.mod.engine.bridge.ScreenObservationType
import dev.parrot.mod.neoforge.NeoForgePlatformBridge
import net.minecraft.client.Minecraft
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.client.event.ScreenEvent
import net.neoforged.neoforge.common.NeoForge

@Mod(value = "parrot", dist = [Dist.CLIENT])
class ParrotNeoForgeClient(modBus: IEventBus) {
    private val screenReader = NeoForgeScreenReader()

    init {
        NeoForgePlatformBridge.clientScreenReader = screenReader
        NeoForge.EVENT_BUS.addListener(::onScreenOpen)
        NeoForge.EVENT_BUS.addListener(::onScreenClose)
    }

    private fun onScreenOpen(event: ScreenEvent.Opening) {
        val client = Minecraft.getInstance()
        if (client.hasSingleplayerServer()) {
            val state = screenReader.getCurrentScreen()
            ParrotEngine.onScreenObservation(
                ScreenObservation(ScreenObservationType.OPENED, state, client.level?.gameTime ?: 0L)
            )
        }
    }

    private fun onScreenClose(event: ScreenEvent.Closing) {
        val client = Minecraft.getInstance()
        if (client.hasSingleplayerServer()) {
            ParrotEngine.onScreenObservation(
                ScreenObservation(ScreenObservationType.CLOSED, null, client.level?.gameTime ?: 0L)
            )
        }
    }
}

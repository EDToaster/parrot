package dev.parrot.mod.neoforge

import dev.parrot.mod.engine.ParrotEngine
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent

@Mod("parrot")
class ParrotNeoForge(modBus: IEventBus) {
    init {
        val gameBus = NeoForge.EVENT_BUS
        gameBus.addListener(::onServerStarted)
        gameBus.addListener(::onServerStopping)
        gameBus.addListener(::onServerTick)
    }

    private fun onServerStarted(event: ServerStartedEvent) {
        ParrotEngine.start(event.server, NeoForgePlatformBridge())
    }

    private fun onServerStopping(event: ServerStoppingEvent) {
        ParrotEngine.stop()
    }

    private fun onServerTick(event: ServerTickEvent.Post) {
        ParrotEngine.tick(event.server)
    }
}

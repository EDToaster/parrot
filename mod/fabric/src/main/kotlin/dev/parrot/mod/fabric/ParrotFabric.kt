package dev.parrot.mod.fabric

import dev.parrot.mod.engine.ParrotEngine
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents

object ParrotFabric : ModInitializer {
    override fun onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            ParrotEngine.start(server, FabricPlatformBridge())
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            ParrotEngine.stop()
        }
        ServerTickEvents.END_SERVER_TICK.register { server ->
            ParrotEngine.tick(server)
        }
    }
}

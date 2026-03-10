package dev.parrot.mod.engine

import dev.parrot.mod.commands.createCommandRegistry
import dev.parrot.mod.engine.bridge.PlatformBridge
import dev.parrot.mod.engine.bridge.ScreenObservation
import dev.parrot.mod.events.SubscriptionManager
import dev.parrot.mod.server.ConnectionFileManager
import dev.parrot.mod.server.ParrotWebSocketServer
import net.minecraft.server.MinecraftServer
import java.util.concurrent.atomic.AtomicReference

object ParrotEngine {
    private val webSocketServer = AtomicReference<ParrotWebSocketServer?>(null)
    private val commandQueue = AtomicReference<CommandQueue?>(null)
    private val subscriptionManager = AtomicReference<SubscriptionManager?>(null)
    private val platformBridge = AtomicReference<PlatformBridge?>(null)

    fun start(server: MinecraftServer, bridge: PlatformBridge) {
        val subMgr = SubscriptionManager()
        val registry = createCommandRegistry(subMgr)
        val queue = CommandQueue(registry, bridge)
        subscriptionManager.set(subMgr)
        commandQueue.set(queue)
        platformBridge.set(bridge)
        bridge.registerEventListeners(subMgr)
        val wsServer = ParrotWebSocketServer(queue, subMgr)
        wsServer.start()
        webSocketServer.set(wsServer)
        ConnectionFileManager.write(wsServer.port, wsServer.token)
    }

    fun stop() {
        webSocketServer.getAndSet(null)?.stop()
        commandQueue.set(null)
        subscriptionManager.set(null)
        platformBridge.set(null)
        ConnectionFileManager.delete()
    }

    fun tick(server: MinecraftServer) {
        commandQueue.get()?.drainAndExecute(server, server.tickCount.toLong())
    }

    fun onScreenObservation(observation: ScreenObservation) {
        subscriptionManager.get()?.handleScreenObservation(observation)
    }
}

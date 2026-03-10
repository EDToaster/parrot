package dev.parrot.mod.engine

import dev.parrot.mod.commands.createCommandRegistry
import dev.parrot.mod.engine.bridge.PlatformBridge
import dev.parrot.mod.engine.bridge.ScreenObservation
import dev.parrot.mod.events.SubscriptionManager
import dev.parrot.mod.server.ConnectionFileManager
import dev.parrot.mod.server.ParrotWebSocketServer
import dev.parrot.protocol.ParrotJson
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import kotlinx.serialization.json.JsonObject
import net.minecraft.server.MinecraftServer
import java.util.concurrent.atomic.AtomicReference

object ParrotEngine {
    private val webSocketServer = AtomicReference<ParrotWebSocketServer?>(null)
    private val commandQueue = AtomicReference<CommandQueue?>(null)
    private val subscriptionManager = AtomicReference<SubscriptionManager?>(null)
    private val platformBridge = AtomicReference<PlatformBridge?>(null)
    private val consequenceCollector = AtomicReference<ConsequenceCollector?>(null)

    fun start(server: MinecraftServer, bridge: PlatformBridge) {
        val collector = ConsequenceCollector()
        consequenceCollector.set(collector)

        val subMgr = SubscriptionManager()
        val registry = createCommandRegistry(subMgr)
        val queue = CommandQueue(registry, bridge, consequenceCollector = collector)
        subscriptionManager.set(subMgr)
        commandQueue.set(queue)
        platformBridge.set(bridge)
        bridge.registerEventListeners(subMgr)

        subMgr.consequenceSink = { type, tick, data, pos ->
            collector.onConsequence(type, tick, data, pos?.x?.toInt(), pos?.y?.toInt(), pos?.z?.toInt())
        }

        val wsServer = ParrotWebSocketServer(queue, subMgr)
        wsServer.start()
        webSocketServer.set(wsServer)

        subMgr.eventSender = { _, event ->
            wsServer.currentSession?.let { session ->
                val json = ParrotJson.encodeToString(JsonObject.serializer(), event)
                session.channel.writeAndFlush(TextWebSocketFrame(json))
            }
        }

        ConnectionFileManager.write(wsServer.port, wsServer.token)
    }

    fun stop() {
        webSocketServer.getAndSet(null)?.stop()
        commandQueue.set(null)
        subscriptionManager.set(null)
        platformBridge.set(null)
        consequenceCollector.set(null)
        ConnectionFileManager.delete()
    }

    fun tick(server: MinecraftServer) {
        consequenceCollector.get()?.tick(server.tickCount.toLong())
        commandQueue.get()?.drainAndExecute(server, server.tickCount.toLong())
    }

    fun onScreenObservation(observation: ScreenObservation) {
        subscriptionManager.get()?.handleScreenObservation(observation)
    }
}

package dev.parrot.mod.server

import dev.parrot.mod.engine.CommandQueue
import dev.parrot.mod.events.SubscriptionManager
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.util.concurrent.DefaultThreadFactory
import java.net.InetSocketAddress
import java.util.UUID

class ParrotWebSocketServer(
    private val commandQueue: CommandQueue,
    private val subscriptionManager: SubscriptionManager
) {
    private val bossGroup = NioEventLoopGroup(1, DefaultThreadFactory("parrot-boss", true))
    private val workerGroup = NioEventLoopGroup(1, DefaultThreadFactory("parrot-worker", true))
    private var channel: Channel? = null

    val token: String = UUID.randomUUID().toString().replace("-", "")
    var port: Int = 0; private set
    var currentSession: ClientSession? = null

    fun start(requestedPort: Int = 25566) {
        val bootstrap = ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(WebSocketServerInitializer(this, commandQueue, subscriptionManager))

        val bindFuture = bootstrap.bind(InetSocketAddress("127.0.0.1", requestedPort)).sync()
        channel = bindFuture.channel()
        port = (channel!!.localAddress() as InetSocketAddress).port
    }

    fun stop() {
        currentSession?.channel?.close()
        channel?.close()?.sync()
        bossGroup.shutdownGracefully(0, 2, java.util.concurrent.TimeUnit.SECONDS)
        workerGroup.shutdownGracefully(0, 2, java.util.concurrent.TimeUnit.SECONDS)
    }

    fun onNewSession(session: ClientSession) {
        currentSession?.channel?.close()
        currentSession = session
    }

    fun onSessionClosed(session: ClientSession) {
        if (currentSession == session) {
            currentSession = null
            subscriptionManager.cleanupChannel(session.channelId)
        }
    }
}

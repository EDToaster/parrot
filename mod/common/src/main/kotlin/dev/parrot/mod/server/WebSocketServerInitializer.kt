package dev.parrot.mod.server

import dev.parrot.mod.engine.CommandQueue
import dev.parrot.mod.events.SubscriptionManager
import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler

class WebSocketServerInitializer(
    private val server: ParrotWebSocketServer,
    private val commandQueue: CommandQueue,
    private val subscriptionManager: SubscriptionManager
) : ChannelInitializer<SocketChannel>() {
    override fun initChannel(ch: SocketChannel) {
        ch.pipeline().addLast(
            HttpServerCodec(),
            HttpObjectAggregator(65536),
            WebSocketServerProtocolHandler("/parrot"),
            ParrotMessageHandler(server, commandQueue, subscriptionManager)
        )
    }
}

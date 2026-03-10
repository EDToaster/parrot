package dev.parrot.mod.server

import io.netty.channel.Channel

class ClientSession(
    val channel: Channel,
    var authenticated: Boolean = false,
    val channelId: String = channel.id().asShortText(),
    val connectedAt: Long = System.currentTimeMillis()
)

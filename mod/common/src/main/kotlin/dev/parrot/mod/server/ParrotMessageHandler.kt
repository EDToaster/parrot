package dev.parrot.mod.server

import dev.parrot.mod.engine.CommandQueue
import dev.parrot.mod.events.SubscriptionManager
import dev.parrot.protocol.*
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketFrame
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class ParrotMessageHandler(
    private val server: ParrotWebSocketServer,
    private val commandQueue: CommandQueue,
    private val subscriptionManager: SubscriptionManager
) : SimpleChannelInboundHandler<WebSocketFrame>() {

    private var session: ClientSession? = null
    private var authTimeout: ScheduledFuture<*>? = null

    override fun channelActive(ctx: ChannelHandlerContext) {
        val newSession = ClientSession(ctx.channel())
        session = newSession
        // 5-second auth timeout
        authTimeout = ctx.executor().schedule({
            if (session?.authenticated != true) {
                sendMessage(ctx, ErrorResponse(id = "0", code = ErrorCode.AUTH_FAILED.code, message = "Auth timeout"))
                ctx.close()
            }
        }, 5, TimeUnit.SECONDS)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        authTimeout?.cancel(false)
        session?.let { server.onSessionClosed(it) }
        session = null
    }

    override fun channelRead0(ctx: ChannelHandlerContext, frame: WebSocketFrame) {
        if (frame !is TextWebSocketFrame) return
        val text = frame.text()

        val message = try {
            ParrotJson.decodeFromString(ParrotMessage.serializer(), text)
        } catch (e: Exception) {
            sendMessage(ctx, ErrorResponse(id = "0", code = ErrorCode.INVALID_REQUEST.code, message = "Malformed JSON: ${e.message}"))
            return
        }

        val currentSession = session ?: return

        if (!currentSession.authenticated) {
            if (message is Hello) {
                if (message.authToken == server.token) {
                    currentSession.authenticated = true
                    authTimeout?.cancel(false)
                    server.onNewSession(currentSession)
                    sendMessage(ctx, HelloAck(
                        id = message.id,
                        minecraftVersion = "1.21.10",
                        modLoader = "unknown", // Set by platform bridge
                        modVersion = "0.1.0",
                        capabilities = listOf("actions", "queries", "events", "gui_observation"),
                        serverType = "integrated"
                    ))
                } else {
                    sendMessage(ctx, ErrorResponse(id = message.id, code = ErrorCode.AUTH_FAILED.code, message = "Invalid token"))
                    ctx.close()
                }
            } else {
                sendMessage(ctx, ErrorResponse(id = message.id, code = ErrorCode.NOT_AUTHENTICATED.code, message = "Send hello first"))
            }
            return
        }

        // Authenticated message routing
        when (message) {
            is Ping -> sendMessage(ctx, Pong(id = message.id, timestamp = message.timestamp))
            is Goodbye -> { sendMessage(ctx, GoodbyeAck(id = message.id)); ctx.close() }
            is ActionRequest, is QueryRequest, is CommandRequest,
            is BatchRequest, is SubscribeRequest, is UnsubscribeRequest -> {
                commandQueue.enqueue(message, ctx.channel()).thenAccept { response ->
                    sendMessage(ctx, response)
                }
            }
            else -> sendMessage(ctx, ErrorResponse(id = message.id, code = ErrorCode.INVALID_REQUEST.code, message = "Unexpected message type"))
        }
    }

    private fun sendMessage(ctx: ChannelHandlerContext, message: ParrotMessage) {
        if (ctx.channel().isActive) {
            val json = ParrotJson.encodeToString(ParrotMessage.serializer(), message)
            ctx.writeAndFlush(TextWebSocketFrame(json))
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        ctx.close()
    }
}

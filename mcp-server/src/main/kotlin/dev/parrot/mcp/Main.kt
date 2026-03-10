package dev.parrot.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

fun main() = runBlocking {
    System.err.println("[parrot-mcp] Starting Parrot MCP Server...")
    val config = Config.discover()
    val bridge = MinecraftBridge(config)

    val server = Server(
        serverInfo = Implementation(name = "parrot-mcp", version = "0.1.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
                resources = ServerCapabilities.Resources(subscribe = true, listChanged = true)
            )
        )
    )

    ToolRegistrar.registerAll(server, bridge)
    ResourceRegistrar.registerAll(server, bridge)

    launch { bridge.connectWithRetry() }

    val transport = StdioServerTransport(
        System.`in`.asSource().buffered(),
        System.out.asSink().buffered()
    )
    server.createSession(transport)

    val done = Job()
    server.onClose { done.complete() }
    done.join()
}

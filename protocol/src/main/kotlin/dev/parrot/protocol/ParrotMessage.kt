package dev.parrot.protocol

import kotlinx.serialization.*
import kotlinx.serialization.json.JsonObject

@Serializable
sealed class ParrotMessage {
    abstract val id: String
}

// --- Client -> Server ---

@Serializable @SerialName("hello")
data class Hello(
    override val id: String,
    val protocolVersion: Int = 1,
    val authToken: String
) : ParrotMessage()

@Serializable @SerialName("ping")
data class Ping(override val id: String, val timestamp: Long) : ParrotMessage()

@Serializable @SerialName("pong")
data class Pong(override val id: String, val timestamp: Long) : ParrotMessage()

@Serializable @SerialName("goodbye")
data class Goodbye(override val id: String) : ParrotMessage()

@Serializable @SerialName("action")
data class ActionRequest(
    override val id: String,
    val method: String,
    val params: JsonObject = JsonObject(emptyMap()),
    val consequenceWait: Int = 5,
    val consequenceFilter: List<String>? = null
) : ParrotMessage()

@Serializable @SerialName("query")
data class QueryRequest(
    override val id: String,
    val method: String,
    val params: JsonObject = JsonObject(emptyMap())
) : ParrotMessage()

@Serializable @SerialName("command")
data class CommandRequest(
    override val id: String,
    val command: String,
    val consequenceWait: Int = 3
) : ParrotMessage()

@Serializable @SerialName("subscribe")
data class SubscribeRequest(
    override val id: String,
    val eventTypes: List<String>,
    val filter: JsonObject? = null
) : ParrotMessage()

@Serializable @SerialName("unsubscribe")
data class UnsubscribeRequest(
    override val id: String,
    val subscriptionId: String
) : ParrotMessage()

@Serializable @SerialName("batch")
data class BatchRequest(
    override val id: String,
    val commands: List<BatchCommand>
) : ParrotMessage()

// --- Server -> Client ---

@Serializable @SerialName("hello_ack")
data class HelloAck(
    override val id: String,
    val protocolVersion: Int = 1,
    val minecraftVersion: String,
    val modLoader: String,
    val modVersion: String,
    val capabilities: List<String>,
    val tickRate: Int = 20,
    val serverType: String
) : ParrotMessage()

@Serializable @SerialName("goodbye_ack")
data class GoodbyeAck(override val id: String) : ParrotMessage()

@Serializable @SerialName("action_result")
data class ActionResult(
    override val id: String,
    val success: Boolean,
    val tick: Long,
    val result: JsonObject = JsonObject(emptyMap()),
    val consequences: List<Consequence> = emptyList()
) : ParrotMessage()

@Serializable @SerialName("query_result")
data class QueryResult(
    override val id: String,
    val tick: Long,
    val result: JsonObject
) : ParrotMessage()

@Serializable @SerialName("command_result")
data class CommandResult(
    override val id: String,
    val success: Boolean,
    val tick: Long,
    val output: String,
    val returnValue: Int? = null,
    val consequences: List<Consequence> = emptyList()
) : ParrotMessage()

@Serializable @SerialName("subscribe_ack")
data class SubscribeAck(
    override val id: String,
    val subscriptionId: String,
    val subscribedEvents: List<String>
) : ParrotMessage()

@Serializable @SerialName("unsubscribe_ack")
data class UnsubscribeAck(
    override val id: String,
    val success: Boolean
) : ParrotMessage()

@Serializable @SerialName("event")
data class PushEvent(
    override val id: String = "",
    val subscriptionId: String,
    val tick: Long,
    val eventType: String,
    val data: JsonObject
) : ParrotMessage()

@Serializable @SerialName("error")
data class ErrorResponse(
    override val id: String,
    val code: String,
    val message: String,
    val details: JsonObject? = null
) : ParrotMessage()

@Serializable @SerialName("batch_result")
data class BatchResult(
    override val id: String,
    val results: List<JsonObject>
) : ParrotMessage()

package dev.parrot.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

val ParrotJson = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
    serializersModule = SerializersModule {
        polymorphic(ParrotMessage::class) {
            subclass(Hello::class)
            subclass(Ping::class)
            subclass(Pong::class)
            subclass(Goodbye::class)
            subclass(ActionRequest::class)
            subclass(QueryRequest::class)
            subclass(CommandRequest::class)
            subclass(SubscribeRequest::class)
            subclass(UnsubscribeRequest::class)
            subclass(BatchRequest::class)
            subclass(HelloAck::class)
            subclass(GoodbyeAck::class)
            subclass(ActionResult::class)
            subclass(QueryResult::class)
            subclass(CommandResult::class)
            subclass(SubscribeAck::class)
            subclass(UnsubscribeAck::class)
            subclass(PushEvent::class)
            subclass(ErrorResponse::class)
            subclass(BatchResult::class)
        }
    }
}

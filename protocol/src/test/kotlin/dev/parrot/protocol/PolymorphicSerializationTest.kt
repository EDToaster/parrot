package dev.parrot.protocol

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class PolymorphicSerializationTest {

    @Test
    fun `deserialize ping from raw JSON`() {
        val json = """{"type":"ping","id":"1","timestamp":1710000000000}"""
        val msg = ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
        assertIs<Ping>(msg)
        assertEquals(1710000000000L, msg.timestamp)
    }

    @Test
    fun `deserialize pong from raw JSON`() {
        val json = """{"type":"pong","id":"2","timestamp":1710000000001}"""
        val msg = ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
        assertIs<Pong>(msg)
        assertEquals(1710000000001L, msg.timestamp)
    }

    @Test
    fun `deserialize goodbye from raw JSON`() {
        val json = """{"type":"goodbye","id":"3"}"""
        val msg = ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
        assertIs<Goodbye>(msg)
    }

    @Test
    fun `deserialize goodbye_ack from raw JSON`() {
        val json = """{"type":"goodbye_ack","id":"4"}"""
        val msg = ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
        assertIs<GoodbyeAck>(msg)
    }

    @Test
    fun `deserialize hello_ack from raw JSON`() {
        val json = """{"type":"hello_ack","id":"5","protocolVersion":1,"minecraftVersion":"1.21.10","modLoader":"fabric","modVersion":"0.1.0","capabilities":["query"],"tickRate":20,"serverType":"integrated"}"""
        val msg = ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
        assertIs<HelloAck>(msg)
        assertEquals("1.21.10", msg.minecraftVersion)
    }

    @Test
    fun `deserialize query from raw JSON`() {
        val json = """{"type":"query","id":"6","method":"get_block","params":{"x":1}}"""
        val msg = ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
        assertIs<QueryRequest>(msg)
        assertEquals("get_block", msg.method)
    }

    @Test
    fun `deserialize query_result from raw JSON`() {
        val json = """{"type":"query_result","id":"7","tick":50000,"result":{"blockId":"minecraft:stone"}}"""
        val msg = ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
        assertIs<QueryResult>(msg)
        assertEquals(50000L, msg.tick)
    }

    @Test
    fun `deserialize command from raw JSON`() {
        val json = """{"type":"command","id":"8","command":"/time set day","consequenceWait":3}"""
        val msg = ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
        assertIs<CommandRequest>(msg)
        assertEquals("/time set day", msg.command)
    }

    @Test
    fun `deserialize command_result from raw JSON`() {
        val json = """{"type":"command_result","id":"9","success":true,"tick":50000,"output":"Done","returnValue":1,"consequences":[]}"""
        val msg = ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
        assertIs<CommandResult>(msg)
        assertEquals(true, msg.success)
    }

    @Test
    fun `deserialize subscribe from raw JSON`() {
        val json = """{"type":"subscribe","id":"10","eventTypes":["block_changed"],"filter":null}"""
        val msg = ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
        assertIs<SubscribeRequest>(msg)
        assertEquals(listOf("block_changed"), msg.eventTypes)
    }

    @Test
    fun `deserialize subscribe_ack from raw JSON`() {
        val json = """{"type":"subscribe_ack","id":"11","subscriptionId":"sub-001","subscribedEvents":["block_changed"]}"""
        val msg = ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
        assertIs<SubscribeAck>(msg)
        assertEquals("sub-001", msg.subscriptionId)
    }

    @Test
    fun `deserialize unsubscribe from raw JSON`() {
        val json = """{"type":"unsubscribe","id":"12","subscriptionId":"sub-001"}"""
        val msg = ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
        assertIs<UnsubscribeRequest>(msg)
    }

    @Test
    fun `deserialize unsubscribe_ack from raw JSON`() {
        val json = """{"type":"unsubscribe_ack","id":"13","success":true}"""
        val msg = ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
        assertIs<UnsubscribeAck>(msg)
    }

    @Test
    fun `deserialize event from raw JSON`() {
        val json = """{"type":"event","id":"","subscriptionId":"sub-001","tick":60000,"eventType":"block_changed","data":{"x":1}}"""
        val msg = ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
        assertIs<PushEvent>(msg)
        assertEquals("block_changed", msg.eventType)
    }

    @Test
    fun `deserialize error from raw JSON`() {
        val json = """{"type":"error","id":"99","code":"INVALID_REQUEST","message":"Bad request"}"""
        val msg = ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
        assertIs<ErrorResponse>(msg)
        assertEquals("INVALID_REQUEST", msg.code)
    }

    @Test
    fun `deserialize batch from raw JSON`() {
        val json = """{"type":"batch","id":"20","commands":[{"method":"get_block","params":{"x":0,"y":0,"z":0}}]}"""
        val msg = ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
        assertIs<BatchRequest>(msg)
    }

    @Test
    fun `deserialize batch_result from raw JSON`() {
        val json = """{"type":"batch_result","id":"21","results":[{"blockId":"stone"}]}"""
        val msg = ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
        assertIs<BatchResult>(msg)
    }

    @Test
    fun `deserialize action from raw JSON`() {
        val json = """{"type":"action","id":"22","method":"interact_block","params":{},"consequenceWait":5,"consequenceFilter":null}"""
        val msg = ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
        assertIs<ActionRequest>(msg)
    }

    @Test
    fun `deserialize action_result from raw JSON`() {
        val json = """{"type":"action_result","id":"23","success":true,"tick":1000,"result":{},"consequences":[]}"""
        val msg = ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
        assertIs<ActionResult>(msg)
    }

    @Test
    fun `unknown type discriminator throws SerializationException`() {
        val json = """{"type":"unknown_type","id":"99"}"""
        assertFailsWith<SerializationException> {
            ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
        }
    }

    @Test
    fun `ignores unknown keys in polymorphic deserialization`() {
        val json = """{"type":"ping","id":"1","timestamp":100,"extraField":"ignored"}"""
        val msg = ParrotJson.decodeFromString(ParrotMessage.serializer(), json)
        assertIs<Ping>(msg)
        assertEquals(100L, msg.timestamp)
    }
}

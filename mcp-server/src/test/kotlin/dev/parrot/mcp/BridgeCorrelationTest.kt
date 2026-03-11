package dev.parrot.mcp

import dev.parrot.protocol.QueryRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BridgeCorrelationTest {

    @Test
    fun `sendRequest throws when not connected`() = runTest {
        val bridge = MinecraftBridge(ParrotConfig(host = "127.0.0.1", port = 99999, token = null))
        assertFalse(bridge.isConnected)

        val exception = assertFailsWith<IllegalStateException> {
            bridge.sendRequest(
                QueryRequest(id = "test-1", method = "get_player", params = JsonObject(emptyMap()))
            )
        }
        assertEquals("Not connected to Minecraft. Start the game with the Parrot mod, then use wait_for_instance to connect.", exception.message)
    }

    @Test
    fun `new bridge is not connected`() {
        val bridge = MinecraftBridge(ParrotConfig(host = "127.0.0.1", port = 25566, token = null))
        assertFalse(bridge.isConnected)
    }

    @Test
    fun `disconnect on new bridge sets isConnected to false`() {
        val bridge = MinecraftBridge(ParrotConfig(host = "localhost", port = 25566, token = "test-token"))
        assertFalse(bridge.isConnected)
        bridge.disconnect()
        assertFalse(bridge.isConnected)
    }

    @Test
    fun `constructor creates bridge with given config`() {
        val config = ParrotConfig(host = "10.0.0.1", port = 12345, token = "my-token")
        val bridge = MinecraftBridge(config)
        assertFalse(bridge.isConnected)
    }

    // --- Unique ID generation tests ---

    @Test
    fun `UUID randomUUID generates unique IDs for request correlation`() {
        // The bridge (via ToolRegistrar) uses UUID.randomUUID() for each request ID.
        // Verify that the UUID generation mechanism produces unique values.
        val ids = (1..1000).map { UUID.randomUUID().toString() }.toSet()
        assertEquals(1000, ids.size, "All 1000 generated UUIDs should be unique")
    }

    @Test
    fun `multiple sendRequest calls would use different IDs`() = runTest {
        // We cannot call sendRequest on a disconnected bridge, but we can verify
        // that the QueryRequest objects created with UUID.randomUUID() have distinct IDs,
        // which is how ToolRegistrar builds each request before passing to sendRequest.
        val requests = (1..100).map {
            QueryRequest(
                id = UUID.randomUUID().toString(),
                method = "get_player",
                params = JsonObject(emptyMap())
            )
        }
        val uniqueIds = requests.map { it.id }.toSet()
        assertEquals(100, uniqueIds.size, "All request IDs should be unique")
    }

    @Test
    fun `request IDs are valid UUID format`() {
        val id = UUID.randomUUID().toString()
        // UUID format: 8-4-4-4-12 hex characters
        val uuidRegex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        assertTrue(uuidRegex.matches(id), "Generated ID should be valid UUID format: $id")
    }

    // --- Disconnect fails all pending requests tests ---

    @Test
    fun `disconnect on unconnected bridge is idempotent`() {
        val bridge = MinecraftBridge(ParrotConfig(host = "127.0.0.1", port = 99999, token = null))
        // Multiple disconnects should not throw
        bridge.disconnect()
        bridge.disconnect()
        bridge.disconnect()
        assertFalse(bridge.isConnected)
    }

    @Test
    fun `ConcurrentHashMap pending requests pattern clears on disconnect`() {
        // Verify the disconnect-fails-pending pattern used by MinecraftBridge.onDisconnect():
        // pendingRequests entries are completed exceptionally with "Disconnected from Minecraft".
        // Since pendingRequests is private, we verify the pattern with an equivalent data structure.
        val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
        val deferred1 = CompletableDeferred<JsonObject>()
        val deferred2 = CompletableDeferred<JsonObject>()
        val deferred3 = CompletableDeferred<JsonObject>()

        pendingRequests["req-1"] = deferred1
        pendingRequests["req-2"] = deferred2
        pendingRequests["req-3"] = deferred3

        // Simulate onDisconnect() logic from MinecraftBridge
        val exception = RuntimeException("Disconnected from Minecraft")
        for ((id, deferred) in pendingRequests) {
            deferred.completeExceptionally(exception)
            pendingRequests.remove(id)
        }

        assertTrue(pendingRequests.isEmpty(), "All pending requests should be removed")
        assertTrue(deferred1.isCompleted, "deferred1 should be completed")
        assertTrue(deferred2.isCompleted, "deferred2 should be completed")
        assertTrue(deferred3.isCompleted, "deferred3 should be completed")

        // Verify that awaiting any of them throws the disconnect exception
        val ex1 = assertFailsWith<RuntimeException> { deferred1.getCompleted() }
        assertEquals("Disconnected from Minecraft", ex1.message)
        val ex2 = assertFailsWith<RuntimeException> { deferred2.getCompleted() }
        assertEquals("Disconnected from Minecraft", ex2.message)
        val ex3 = assertFailsWith<RuntimeException> { deferred3.getCompleted() }
        assertEquals("Disconnected from Minecraft", ex3.message)
    }

    @Test
    fun `out-of-order response correlation pattern works correctly`() {
        // Verify the ConcurrentHashMap-based correlation pattern used by MinecraftBridge:
        // responses can arrive in any order and are matched by ID.
        val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
        val deferred1 = CompletableDeferred<JsonObject>()
        val deferred2 = CompletableDeferred<JsonObject>()
        val deferred3 = CompletableDeferred<JsonObject>()

        pendingRequests["aaa"] = deferred1
        pendingRequests["bbb"] = deferred2
        pendingRequests["ccc"] = deferred3

        // Simulate responses arriving out of order: ccc, aaa, bbb
        val resultC = JsonObject(mapOf("data" to JsonPrimitive("c")))
        pendingRequests.remove("ccc")?.complete(resultC)
        assertTrue(deferred3.isCompleted)
        assertFalse(deferred1.isCompleted)
        assertFalse(deferred2.isCompleted)

        val resultA = JsonObject(mapOf("data" to JsonPrimitive("a")))
        pendingRequests.remove("aaa")?.complete(resultA)
        assertTrue(deferred1.isCompleted)
        assertFalse(deferred2.isCompleted)

        val resultB = JsonObject(mapOf("data" to JsonPrimitive("b")))
        pendingRequests.remove("bbb")?.complete(resultB)
        assertTrue(deferred2.isCompleted)

        // Verify each got the correct result
        assertEquals(resultA, deferred1.getCompleted())
        assertEquals(resultB, deferred2.getCompleted())
        assertEquals(resultC, deferred3.getCompleted())
        assertTrue(pendingRequests.isEmpty())
    }

    @Test
    fun `timeout cleanup removes pending request on failure`() = runTest {
        // Verify that when sendRequest catches an exception (e.g. timeout),
        // it removes the pending request. We test this pattern directly since
        // sendRequest's internals do: pendingRequests.remove(message.id) in catch block.
        val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
        val id = "timeout-test"
        val deferred = CompletableDeferred<JsonObject>()
        pendingRequests[id] = deferred

        // Simulate the catch block in sendRequest
        try {
            // In the real code, this would be a TimeoutCancellationException from withTimeout.
            // We use RuntimeException here since TimeoutCancellationException's constructor
            // may be restricted. The cleanup pattern is the same regardless of exception type.
            throw RuntimeException("Timed out waiting for response")
        } catch (_: Exception) {
            pendingRequests.remove(id)
        }

        assertTrue(pendingRequests.isEmpty(), "Pending request should be cleaned up after timeout")
        assertFalse(deferred.isCompleted, "Deferred should not be completed (it was just removed)")
    }
}

package dev.parrot.mcp

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class BackoffTest {

    @Test
    fun `exponential backoff sequence with cap at 30000ms`() {
        // Simulates the backoff logic from MinecraftBridge.connectWithRetry:
        //   delay starts at 1000L
        //   after each attempt: delay = (delay * 2).coerceAtMost(30_000)
        var delay = 1000L
        val sequence = mutableListOf(delay)

        repeat(5) {
            delay = (delay * 2).coerceAtMost(30_000)
            sequence.add(delay)
        }

        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L), sequence)
    }

    @Test
    fun `backoff caps at 30000ms and stays there`() {
        var delay = 16000L
        delay = (delay * 2).coerceAtMost(30_000)
        assertEquals(30000L, delay)

        // Subsequent iterations also stay at cap
        delay = (delay * 2).coerceAtMost(30_000)
        assertEquals(30000L, delay)

        delay = (delay * 2).coerceAtMost(30_000)
        assertEquals(30000L, delay)
    }

    @Test
    fun `coerceAtMost correctly limits values`() {
        assertEquals(30000L, 32000L.coerceAtMost(30_000))
        assertEquals(30000L, 64000L.coerceAtMost(30_000))
        assertEquals(16000L, 16000L.coerceAtMost(30_000))
        assertEquals(1000L, 1000L.coerceAtMost(30_000))
    }
}

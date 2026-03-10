package dev.parrot.mcp

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun `backoff from 1ms boundary cases`() {
        // Start from minimum
        var delay = 1L
        delay = (delay * 2).coerceAtMost(30_000)
        assertEquals(2L, delay)

        // Start from 0 (edge case)
        delay = 0L
        delay = (delay * 2).coerceAtMost(30_000)
        assertEquals(0L, delay, "Zero delay stays at zero (degenerate case)")
    }

    @Test
    fun `full backoff sequence reaches cap in exactly 5 doublings`() {
        var delay = 1000L
        var doublings = 0
        while (delay < 30_000L) {
            delay = (delay * 2).coerceAtMost(30_000)
            doublings++
        }
        assertEquals(5, doublings, "Should take exactly 5 doublings to reach 30s cap from 1s")
        assertEquals(30_000L, delay)
    }

    @Test
    fun `total wait time across all backoff steps`() {
        // Useful for understanding worst-case reconnect latency:
        // 1000 + 2000 + 4000 + 8000 + 16000 + 30000 = 61000ms total wait before 6 retries
        var delay = 1000L
        var totalWait = delay
        repeat(5) {
            delay = (delay * 2).coerceAtMost(30_000)
            totalWait += delay
        }
        assertEquals(61_000L, totalWait, "Total wait for first 6 attempts should be 61 seconds")
    }

    @Test
    fun `extended backoff accumulates at cap rate`() {
        // After reaching cap, every subsequent retry adds exactly 30s
        var delay = 1000L
        // Get to cap
        repeat(5) { delay = (delay * 2).coerceAtMost(30_000) }
        assertEquals(30_000L, delay)

        // 10 more retries at cap = 300s additional
        var additionalWait = 0L
        repeat(10) {
            delay = (delay * 2).coerceAtMost(30_000)
            additionalWait += delay
        }
        assertEquals(300_000L, additionalWait, "10 retries at cap should total 300 seconds")
    }

    // --- TestCoroutineScheduler-based delay verification ---

    @Test
    fun `backoff delay mechanism works with coroutine scheduler`() = runTest {
        // Verify that the actual kotlinx.coroutines.delay() call used by MinecraftBridge
        // behaves correctly with TestCoroutineScheduler. This validates that the delay
        // mechanism integrates properly with the coroutine framework.
        //
        // Note: We cannot directly test MinecraftBridge.connectWithRetry() here because
        // it enters an infinite retry loop with real WebSocket connection attempts.
        // Instead, we replicate the delay pattern and verify timing with the test scheduler.

        val delaySequence = mutableListOf<Long>()
        var backoffDelay = 1000L

        val job = launch {
            repeat(4) {
                delaySequence.add(backoffDelay)
                delay(backoffDelay)
                backoffDelay = (backoffDelay * 2).coerceAtMost(30_000)
            }
        }

        // After 0ms: first delay(1000) is pending
        assertEquals(0L, currentTime)

        // Advance past first delay
        advanceTimeBy(1000)
        assertEquals(1000L, currentTime)

        // Advance past second delay (2000ms)
        advanceTimeBy(2000)
        assertEquals(3000L, currentTime)

        // Advance past third delay (4000ms)
        advanceTimeBy(4000)
        assertEquals(7000L, currentTime)

        // Advance past fourth delay (8000ms)
        advanceTimeBy(8000)
        assertEquals(15000L, currentTime)

        job.join()
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L), delaySequence)
    }

    @Test
    fun `delay at cap is exactly 30 seconds in virtual time`() = runTest {
        var completed = false

        val job = launch {
            // Simulate being at the backoff cap
            delay(30_000L)
            completed = true
        }

        advanceTimeBy(29_999)
        assertTrue(!completed, "Should not complete before 30s")

        advanceTimeBy(1)
        job.join()
        assertTrue(completed, "Should complete at exactly 30s")
        assertEquals(30_000L, currentTime)
    }
}

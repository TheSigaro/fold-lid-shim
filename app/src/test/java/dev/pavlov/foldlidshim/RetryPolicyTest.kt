package dev.pavlov.foldlidshim

import org.junit.Assert.*
import org.junit.Test

class RetryPolicyTest {
    private val policy = RetryPolicy()
    @Test fun backsOffAndCapsAtOneMinute() {
        assertEquals(listOf(10_000L, 20_000L, 40_000L, 60_000L, 60_000L, 60_000L),
            (1..6).map { policy.nextDelayMs(it, 0) })
        assertEquals(60_000L, policy.nextDelayMs(Int.MAX_VALUE, 0))
    }
    @Test fun deadlineExcludesAttemptsAndRetriesAtThirtyMinutes() {
        assertTrue(policy.mayAttempt(0))
        assertTrue(policy.mayAttempt(policy.windowMs - 1))
        assertFalse(policy.mayAttempt(policy.windowMs))
        assertFalse(policy.mayAttempt(policy.windowMs + 1))
        assertFalse(policy.mayAttempt(-1))
        assertNull(policy.nextDelayMs(1, policy.windowMs - 10_000))
        assertEquals(10_000L, policy.nextDelayMs(1, policy.windowMs - 10_001))
        assertNull(policy.nextDelayMs(2, policy.windowMs))
    }
    @Test fun neverSchedulesOutsideTheWindow() {
        var elapsed = 0L
        var failures = 0
        while (true) {
            // Simulate attempts that themselves take 25 seconds.
            elapsed += 25_000
            val delay = policy.nextDelayMs(++failures, elapsed) ?: break
            elapsed += delay
            assertTrue(policy.mayAttempt(elapsed))
        }
        assertTrue(failures > 4)
        assertNull(policy.nextDelayMs(failures, elapsed))
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveFailureCount() { policy.nextDelayMs(0, 0) }
}

package dev.pavlov.foldlidshim

import org.junit.Assert.*
import org.junit.Test

class DirectMotionTest {
    private fun sample(m: DirectMotion, ms: Long, angle: Float, early: Boolean = true) =
        m.angle(angle, ms * 1_000_000, 10f, 15f, early, 45f)

    @Test fun fastClosingRequestsAt45AndKeepsClosedWhileDescending() {
        val m = DirectMotion()
        assertFalse(sample(m, 0, 180f))
        assertFalse(sample(m, 500, 90f))
        assertTrue(sample(m, 750, 45f))
        assertTrue(sample(m, 850, 30f))
        assertTrue(sample(m, 1000, 0f))
        assertFalse(sample(m, 1100, 15f))
    }
    @Test fun slowClosingWaitsForCloseThreshold() {
        val m = DirectMotion()
        for ((index, angle) in listOf(180f, 90f, 45f, 20f, 11f).withIndex())
            assertFalse(sample(m, index * 1000L, angle))
        assertTrue(sample(m, 5000, 10f))
    }
    @Test fun flexStopDoesNotClose() {
        val m = DirectMotion()
        for ((i, angle) in listOf(180f, 120f, 90f, 90f, 90f, 91f, 90f).withIndex())
            assertFalse(sample(m, i * 200L, angle))
    }
    @Test fun jitterUsesHysteresis() {
        val m = DirectMotion()
        assertTrue(sample(m, 0, 10f))
        for ((i, angle) in listOf(11f, 9f, 12f, 14f).withIndex())
            assertTrue(sample(m, (i + 1) * 100L, angle))
        assertFalse(sample(m, 600, 15f))
    }
    @Test fun closingAfterPauseNeedsFreshVelocity() {
        val m = DirectMotion()
        assertFalse(sample(m, 0, 90f))
        assertFalse(sample(m, 200, 60f))
        assertFalse(sample(m, 2000, 45f))
        assertTrue(sample(m, 2100, 30f))
    }
    @Test fun failedClosedWaitsForDirectionChange() {
        val m = DirectMotion()
        assertTrue(sample(m, 0, 10f))
        m.failed()
        sample(m, 100, 8f)
        assertTrue(m.failedClosed)
        assertFalse(sample(m, 200, 20f))
        assertFalse(m.failedClosed)
        assertTrue(sample(m, 300, 10f))
    }
    @Test fun earlyThresholdJitterDoesNotRepeatedlyClose() {
        val m = DirectMotion()
        assertFalse(sample(m, 0, 60f))
        assertTrue(sample(m, 100, 45f))
        assertFalse(sample(m, 200, 45.2f))
        assertFalse(sample(m, 300, 44.9f))
        assertFalse(sample(m, 400, 45.1f))
        assertFalse(sample(m, 500, 44.8f))
    }
    @Test fun disabledEarlyAndStaleTimestamp() {
        val m = DirectMotion()
        assertFalse(sample(m, 100, 90f, false))
        assertFalse(sample(m, 200, 40f, false))
        assertFalse(sample(m, 150, 0f))
        assertTrue(sample(m, 300, 10f, false))
    }
    @Test fun retryStopsOnConfirmationAndBelowOpen() {
        val r = CancelRetry()
        assertTrue(r.due(0, true, true))
        assertFalse(r.due(999, true, true))
        assertTrue(r.due(1000, true, true)) // rejected/timeout: still owned
        assertFalse(r.due(2000, false, true)) // callback confirmed
        assertFalse(r.due(3000, true, false))
        assertTrue(r.due(3100, true, true))
    }
    @Test fun secondStickyRestartWithinTenMinutesTrips() {
        assertFalse(RestartGuard.trips(emptyList(), 1000))
        assertTrue(RestartGuard.trips(listOf(1000), 601000))
        assertFalse(RestartGuard.trips(listOf(1000), 601001))
        assertFalse(RestartGuard.trips(listOf(9000), 1000))
    }
    @Test fun activeClosedNeverLaunchesSecondActivity() {
        for (state in listOf(null, 0, 2, 3))
            assertEquals(DirectPolicy.Command.NONE, DirectPolicy.decide(true, state, true))
    }
}

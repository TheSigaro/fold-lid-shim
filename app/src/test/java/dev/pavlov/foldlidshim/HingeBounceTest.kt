package dev.pavlov.foldlidshim

import org.junit.Assert.*
import org.junit.Test

class HingeBounceTest {
    private fun sample(b: HingeBounce, at: Long, angle: Float, magnet: Boolean = false) =
        b.closed(at, angle == 0f, angle, angle == 0f, magnet)

    @Test fun returnAfter198msIgnoresBounce() {
        val b = HingeBounce()
        b.requested(0)
        assertTrue(sample(b, 119, 90f))
        assertEquals(419L, b.deadline)
        assertTrue(sample(b, 317, 0f))
        assertEquals(198L, b.ignoredAfterMs)
        assertEquals(90f, b.ignoredAngle, 0f)
        assertNull(b.deadline)
    }
    @Test fun singleOpeningSampleExpiresWithoutMoreSamplesOrRearming() {
        val b = HingeBounce()
        b.requested(0)
        assertTrue(sample(b, 119, 90f))
        assertTrue(sample(b, 418, 90f))
        assertFalse(sample(b, 419, 90f))
        assertFalse(sample(b, 420, 90f))
        assertNull(b.deadline)
    }
    @Test fun magneticOpeningBypassesHold() {
        val b = HingeBounce()
        b.requested(0)
        assertTrue(sample(b, 100, 90f))
        assertFalse(sample(b, 110, 90f, true))
        assertNull(b.deadline)
        assertFalse(sample(b, 120, 90f))
    }
    @Test fun outsideWindowAndWithoutRequestNoHold() {
        val b = HingeBounce()
        assertFalse(sample(b, 0, 90f))
        b.requested(100)
        assertFalse(sample(b, 1600, 90f))
    }
    @Test fun openingStartedInsideWindowKeepsFull300ms() {
        val b = HingeBounce()
        b.requested(0)
        assertTrue(sample(b, 1499, 90f))
        assertTrue(sample(b, 1798, 90f))
        assertFalse(sample(b, 1799, 90f))
    }
}

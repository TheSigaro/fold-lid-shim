package dev.pavlov.foldlidshim

import org.junit.Assert.*
import org.junit.Test

class SleepPolicyTest {
    @Test fun sleepCancellationBlocksAndSpuriousWakeLocks() {
        val p = SleepPolicy()
        p.desired(true, true, true)
        assertTrue(p.canceled(100, false))
        assertFalse(p.allowClosed())
        assertEquals(SleepPolicy.Wake.LOCK, p.screenOn(435, true, false))
        assertFalse(p.allowClosed())
        assertEquals(SleepPolicy.Wake.LOCK, p.screenOn(1599, true, false))
        assertEquals(SleepPolicy.Wake.REQUEST_CLOSED, p.screenOn(1600, true, false))
        assertTrue(p.allowClosed())
    }
    @Test fun screenOffGraceIncludesOneSecond() {
        val p = SleepPolicy()
        p.screenOff(100)
        assertTrue(p.canceled(1100, true))
        assertFalse(p.canceled(1101, true))
    }
    @Test fun normalCancellationDoesNotBlock() {
        val p = SleepPolicy()
        assertFalse(p.canceled(100, true))
        assertTrue(p.allowClosed())
    }
    @Test fun sleepingCloseIsAllowedAndOpeningWakesOnlyOnTransition() {
        val p = SleepPolicy()
        assertFalse(p.desired(false, false, false))
        assertFalse(p.desired(true, false, false))
        assertTrue(p.allowClosed())
        assertTrue(p.desired(false, false, false))
        assertFalse(p.desired(false, false, false))
    }
    @Test fun ownedRequestUsesCancelAndOpeningClearsSleepHistory() {
        val p = SleepPolicy()
        p.desired(true, true, true)
        p.canceled(100, false)
        assertFalse(p.desired(false, false, true))
        assertNull(p.sleepCancelAt)
        assertEquals(SleepPolicy.Wake.NONE, p.screenOn(200, false, false))
        assertEquals(SleepPolicy.Wake.NONE, p.screenOn(200, true, true))
    }
    @Test fun ordinaryWakeAfterSleepingCloseRequestsClosed() {
        val p = SleepPolicy()
        p.desired(true, false, false)
        assertEquals(SleepPolicy.Wake.REQUEST_CLOSED, p.screenOn(100, true, false))
        assertTrue(p.allowClosed())
    }
    @Test fun preservedRequestSleepsAndWakesWithoutReplacementOrLock() {
        val p = SleepPolicy()
        p.desired(true, true, true)
        p.screenOff(100)
        assertTrue(p.allowClosed())
        assertNull(p.sleepCancelAt)
        assertEquals(SleepPolicy.Wake.NONE, p.screenOn(10_000, true, true))
    }

}

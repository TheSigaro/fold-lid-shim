package dev.pavlov.foldlidshim

import org.junit.Assert.*
import org.junit.Test

class SleepPolicyTest {
    @Test fun sleepCancellationDefersAndSpuriousWakeLocks() {
        val p = SleepPolicy()
        p.desired(true, true, true)
        assertTrue(p.canceled(100, false))
        assertFalse(p.allowClosed(false))
        assertEquals(SleepPolicy.Wake.LOCK, p.screenOn(435, true, false))
        assertFalse(p.allowClosed(true))
        assertEquals(SleepPolicy.Wake.LOCK, p.screenOn(1599, true, false))
        assertEquals(SleepPolicy.Wake.REQUEST_CLOSED, p.screenOn(1600, true, false))
        assertTrue(p.allowClosed(true))
    }
    @Test fun screenOffGraceIncludesOneSecond() {
        val p = SleepPolicy()
        p.screenOff(100)
        assertTrue(p.canceled(1100, true))
        assertFalse(p.canceled(1101, true))
    }
    @Test fun normalCancellationDoesNotDefer() {
        val p = SleepPolicy()
        assertFalse(p.canceled(100, true))
        assertTrue(p.allowClosed(true))
    }
    @Test fun sleepingCloseDefersAndOpeningWakesOnlyOnTransition() {
        val p = SleepPolicy()
        assertFalse(p.desired(false, false, false))
        assertFalse(p.desired(true, false, false))
        assertFalse(p.allowClosed(false))
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
        assertTrue(p.allowClosed(true))
    }
}

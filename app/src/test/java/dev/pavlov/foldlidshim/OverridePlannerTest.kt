package dev.pavlov.foldlidshim

import org.junit.Assert.*
import org.junit.Test
import dev.pavlov.foldlidshim.OverridePlanner.Action.*

class OverridePlannerTest {
    @Test fun workingHallNeedsNoRequestAndLogsOncePerClosure() {
        val p = OverridePlanner()
        val first = p.plan(true, 0, null, true, 0)
        assertEquals(NONE, first.action)
        assertEquals("system already CLOSED, no request", first.log)
        assertNull(p.plan(true, 0, null, false, 100).log)
        p.plan(false, 3, null, true, 200)
        assertEquals(first, p.plan(true, 0, null, false, 300))
    }

    @Test fun stuckOpenRequestsClosedImmediately() {
        val p = OverridePlanner()
        assertEquals(REQUEST_CLOSED, p.plan(true, 2, null, true, 0).action)
        assertEquals(NONE, p.plan(true, 2, 0, true, 1).action)
    }

    @Test fun sleepingFoldRequestsClosedWithRelockEvenWithoutScreenEvents() {
        val p = OverridePlanner()
        p.plan(false, 3, null, false, 0)
        val close = p.plan(true, 2, null, false, 10)
        assertEquals(REQUEST_CLOSED_AND_RELOCK, close.action)
        assertEquals("closed while asleep: request CLOSED", close.log)
        assertEquals(NONE, p.plan(true, 2, 0, true, 20).action)
    }

    @Test fun openingOwnClosedCancelsEvenWhileAsleep() {
        val p = OverridePlanner()
        p.plan(true, 2, 0, false, 0)
        assertEquals(CANCEL, p.plan(false, 2, 0, false, 10).action)
    }

    @Test fun stuckClosedWaitsExactly700AndRefusalWaitsForDirectionChange() {
        val p = OverridePlanner()
        assertEquals(NONE, p.plan(false, 0, null, true, 100).action)
        assertEquals(800L, p.openedDeadline)
        assertEquals(NONE, p.plan(false, 0, null, true, 799).action)
        val request = p.plan(false, 0, null, true, 800)
        assertEquals(REQUEST_OPENED, request.action)
        assertEquals("lid stuck closed: request OPENED", request.log)
        // Ownership remains absent after a refusal; elapsed time must not cause a retry.
        assertEquals(NONE, p.plan(false, 0, null, true, 10_000).action)
        p.plan(true, 0, null, true, 10_100)
        p.plan(false, 0, null, true, 10_200)
        assertEquals(REQUEST_OPENED, p.plan(false, 0, null, true, 10_900).action)
    }

    @Test fun recoveryMustFinishBeforeOpenedAndKeepsFirstSampleTime() {
        val p = OverridePlanner()
        p.plan(false, 0, null, true, 0, recoveryPending = true)
        assertEquals(NONE, p.plan(false, 0, null, true, 900, recoveryPending = true).action)
        assertEquals(REQUEST_OPENED, p.plan(false, 0, null, true, 1000).action)
    }

    @Test fun busyActivityDoesNotConsumeOpenedAttempt() {
        val p = OverridePlanner()
        p.plan(false, 0, null, true, 0)
        assertEquals(NONE, p.plan(false, 0, null, true, 700, requestsAllowed = false).action)
        assertEquals(REQUEST_OPENED, p.plan(false, 0, null, true, 800).action)
    }

    @Test fun graceRestartsWhenHingeLeavesOpenZoneOrSystemOpens() {
        val p = OverridePlanner()
        p.plan(false, 0, null, true, 0)
        p.plan(false, 0, null, true, 699, openZone = false)
        assertEquals(NONE, p.plan(false, 0, null, true, 700).action)
        p.plan(false, 3, null, true, 1399)
        assertEquals(NONE, p.plan(false, 0, null, true, 1400).action)
        assertEquals(REQUEST_OPENED, p.plan(false, 0, null, true, 2100).action)
    }

    @Test fun recoveredHallKeepsEitherOwnedRequestUntilOppositeDirection() {
        val p = OverridePlanner()
        assertEquals(NONE, p.plan(true, 0, 0, false, 0).action)
        assertEquals(CANCEL, p.plan(false, 3, 0, false, 10).action)
        assertEquals(NONE, p.plan(false, 3, 3, false, 20).action)
        assertEquals(CANCEL, p.plan(true, 0, 3, false, 30).action)
    }

    @Test fun unownedSleepingOpeningWakesOnlyOnceAndInitialOpenDoesNotWake() {
        val p = OverridePlanner()
        assertEquals(NONE, p.plan(false, 2, null, false, 0).action)
        p.plan(true, 2, null, false, 10)
        assertEquals(WAKE, p.plan(false, 2, null, false, 20).action)
        assertEquals(NONE, p.plan(false, 2, null, false, 30).action)
    }
    @Test fun magnetOpeningCanCancelButCannotRequestOpenedBeforeRealHinge() {
        val p = OverridePlanner()
        assertEquals(CANCEL, p.plan(false, 0, 0, true, 0,
            openZone = true, openedZone = false).action)
        assertEquals(NONE, p.plan(false, 0, null, true, 100,
            openZone = true, openedZone = false).action)
        assertEquals(NONE, p.plan(false, 0, null, true, 1000,
            openZone = true, openedZone = false).action)
        assertNull(p.openedDeadline)
    }

}

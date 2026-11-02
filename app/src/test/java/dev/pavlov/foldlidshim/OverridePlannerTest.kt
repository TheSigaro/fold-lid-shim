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

    @Test fun sleepingFoldWaitsBeforeRequestingClosedWithRelockEvenWithoutScreenEvents() {
        val p = OverridePlanner()
        p.plan(false, 3, null, false, 0)
        val wait = p.plan(true, 2, null, false, 10)
        assertEquals(NONE, wait.action)
        assertEquals("closed while asleep: waiting for system", wait.log)
        assertEquals(1010L, p.closedDeadline)
        assertEquals(NONE, p.plan(true, 2, null, false, 1009).action)
        val close = p.plan(true, 2, null, false, 1010)
        assertEquals(REQUEST_CLOSED_AND_RELOCK, close.action)
        assertEquals("closed while asleep: request CLOSED", close.log)
        assertEquals(NONE, p.plan(true, 2, 0, true, 1020).action)
    }

    @Test fun confirmedOpeningOwnClosedCancelsEvenWhileAsleep() {
        val p = OverridePlanner()
        p.plan(true, 2, 0, false, 0)
        assertEquals(CANCEL, p.plan(false, 2, 0, false, 10, openConfirmed = true).action)
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
        assertEquals(CANCEL, p.plan(false, 3, 0, false, 10, openConfirmed = true).action)
        assertEquals(NONE, p.plan(false, 3, 3, false, 20).action)
        assertEquals(CANCEL, p.plan(true, 0, 3, false, 30).action)
    }

    @Test fun unownedSleepingOpeningWakesOnlyOnceAndInitialOpenDoesNotWake() {
        val p = OverridePlanner()
        assertEquals(NONE, p.plan(false, 2, null, false, 0).action)
        p.plan(true, 2, null, false, 10)
        assertEquals(WAKE, p.plan(false, 2, null, false, 20, openConfirmed = true).action)
        assertEquals(NONE, p.plan(false, 2, null, false, 30, openConfirmed = true).action)
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

    @Test fun systemOpeningInvalidatesStickyClosedForEveryOpenStateAndScreenMode() {
        for (system in 1..3) for (interactive in listOf(false, true)) {
            val p = OverridePlanner()
            p.plan(true, 0, null, interactive, 0, closedEvidenceMs = 0)
            val reset = p.plan(true, system, null, interactive, 100, closedEvidenceMs = 0)
            assertEquals(NONE, reset.action)
            assertEquals("system left CLOSED, decision reset", reset.log)
            assertEquals(100L, p.systemOpenedAtMs)
            for (evidence in listOf(null, 0L, 100L)) {
                val stale = p.plan(true, system, null, interactive, 200, closedEvidenceMs = evidence)
                assertEquals(NONE, stale.action)
                assertNull(stale.log)
                assertNull(p.closedDeadline)
            }
            assertEquals(REQUEST_CLOSED, p.plan(true, system, null, true, 300,
                closedEvidenceMs = 201).action)
        }
    }

    @Test fun systemOpeningWithOwnedRequestDoesNotInvalidateDecision() {
        val p = OverridePlanner()
        p.plan(true, 0, 0, true, 0)
        assertNull(p.plan(true, 2, 0, true, 100).log)
        assertNull(p.systemOpenedAtMs)
        assertEquals(REQUEST_CLOSED, p.plan(true, 2, null, true, 200).action)
    }

    @Test fun sleepingFoldFollowsHallBeforeDeadlineWithoutLaunchingActivity() {
        val p = OverridePlanner()
        p.plan(true, 2, null, false, 10)
        assertNull(p.plan(true, 2, null, false, 100).log)
        assertEquals(1010L, p.closedDeadline)
        val hall = p.plan(true, 0, null, false, 146)
        assertEquals(NONE, hall.action)
        assertEquals("system already CLOSED, no request", hall.log)
        assertNull(p.closedDeadline)
        assertEquals(NONE, p.plan(true, 0, null, false, 1010).action)
    }

    @Test fun openingClearsSleepingClosedDeadlineAndNextClosureStartsNewGrace() {
        val p = OverridePlanner()
        p.plan(true, 2, null, false, 10)
        p.plan(false, 2, null, false, 100, openConfirmed = false)
        assertNull(p.closedDeadline)
        assertEquals(NONE, p.plan(true, 2, null, false, 200).action)
        assertEquals(1200L, p.closedDeadline)
    }

    @Test fun userWakingDuringGraceRequestsClosedImmediatelyWithoutRelock() {
        val p = OverridePlanner()
        p.plan(true, 2, null, false, 10)
        assertEquals(REQUEST_CLOSED, p.plan(true, 2, null, true, 100).action)
        assertNull(p.closedDeadline)
    }

    @Test fun sleepingOwnClosedWaitsForConfirmationAndFalseOpeningDoesNothing() {
        val p = OverridePlanner()
        p.plan(true, 2, 0, false, 0)
        val pending = p.plan(false, 2, 0, false, 10, openConfirmed = false)
        assertEquals(NONE, pending.action)
        assertEquals("opening unconfirmed: waiting", pending.log)
        assertNull(p.plan(false, 2, 0, false, 200, openConfirmed = false).log)
        assertEquals(NONE, p.plan(true, 2, 0, false, 810).action)
        assertEquals(NONE, p.plan(false, 2, 0, false, 1000, openConfirmed = false).action)
        assertEquals(CANCEL, p.plan(false, 2, 0, false, 1300, openConfirmed = true).action)
    }

    @Test fun sleepingUnownedOpeningKeepsWakeUntilConfirmation() {
        val p = OverridePlanner()
        p.plan(true, 2, null, false, 0)
        val pending = p.plan(false, 2, null, false, 10, openConfirmed = false)
        assertEquals(NONE, pending.action)
        assertEquals("opening unconfirmed: waiting", pending.log)
        assertNull(p.plan(false, 2, null, false, 100, openConfirmed = false).log)
        val confirmed = p.plan(false, 2, null, false, 310, openConfirmed = true)
        assertEquals(WAKE, confirmed.action)
        assertEquals("wake on open", confirmed.log)
        assertEquals(NONE, p.plan(false, 2, null, false, 320).action)
    }

    @Test fun falseUnownedOpeningAndUserWakeConsumeNoWakeAction() {
        for (falseOpening in listOf(false, true)) {
            val p = OverridePlanner()
            p.plan(true, 2, null, false, 0)
            p.plan(false, 2, null, false, 10, openConfirmed = false)
            if (falseOpening) {
                assertEquals(NONE, p.plan(true, 0, null, false, 810).action)
            } else {
                assertEquals(NONE, p.plan(false, 2, null, true, 300, openConfirmed = true).action)
                assertEquals(NONE, p.plan(false, 2, null, false, 400, openConfirmed = true).action)
            }
        }
    }

    @Test fun firmwareOpeningNeverWakesEvenWhenConfirmationArrivesLater() {
        for (closedAtTransition in listOf(false, true)) {
            val p = OverridePlanner()
            p.plan(true, 0, null, false, 0, closedEvidenceMs = 0)
            assertEquals(NONE, p.plan(closedAtTransition, 2, null, false, 100,
                closedEvidenceMs = 0, openConfirmed = false).action)
            assertEquals(NONE, p.plan(false, 2, null, false, 130,
                closedEvidenceMs = 0, openConfirmed = false).action)
            assertEquals(NONE, p.plan(false, 2, null, false, 400,
                closedEvidenceMs = 0, openConfirmed = true).action)
        }
    }

    @Test fun interactiveOpeningStillCancelsOnFirstUnconfirmedEvent() {
        val p = OverridePlanner()
        assertEquals(CANCEL, p.plan(false, 2, 0, true, 0, openConfirmed = false).action)
    }

    @Test fun freshSleepingCloseAfterSystemOpeningGetsFullGrace() {
        val p = OverridePlanner()
        p.plan(true, 0, null, false, 0, closedEvidenceMs = 0)
        p.plan(true, 2, null, false, 100, closedEvidenceMs = 0)
        assertEquals(NONE, p.plan(true, 2, null, false, 500, closedEvidenceMs = 500).action)
        assertEquals(1500L, p.closedDeadline)
        assertEquals(REQUEST_CLOSED_AND_RELOCK, p.plan(true, 2, null, false, 1500,
            closedEvidenceMs = 500).action)
        assertEquals(REQUEST_CLOSED_AND_RELOCK, p.plan(true, 2, null, false, 1501,
            closedEvidenceMs = 500).action)
    }

    @Test fun earlySystemObservationSignalsPendingClosedCancellationOnce() {
        val p = OverridePlanner()
        assertFalse(p.observeSystem(0, null, 0))
        assertTrue(p.observeSystem(2, null, 100))
        assertFalse(p.observeSystem(2, null, 101))
        val reset = p.plan(true, 2, null, false, 101, closedEvidenceMs = 0)
        assertEquals("system left CLOSED, decision reset", reset.log)
        assertEquals(NONE, reset.action)
        assertNull(p.plan(true, 2, null, false, 200, closedEvidenceMs = 0).log)
        p.observeSystem(0, null, 300)
        assertTrue(p.observeSystem(3, null, 400))
        assertEquals("system left CLOSED, decision reset",
            p.plan(true, 3, null, true, 400, closedEvidenceMs = 0).log)
    }

}

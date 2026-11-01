package dev.pavlov.foldlidshim

import org.junit.Assert.assertEquals
import org.junit.Test

class FoldDeciderTest {
    private val d = FoldDecider(10f, 15f, 0)

    @Test fun closesAtInclusiveThreshold() {
        assertEquals(Decision.RequestClosed, d.onAngle(10f, 0))
        assertEquals(Decision.None, d.onAngle(9f, 1))
        d.onApplied(true, true)
        assertEquals(Decision.None, d.onAngle(0f, 2))
    }

    @Test fun opensAtInclusiveThresholdAndDoesNotRepeat() {
        assertEquals(Decision.RequestClosed, d.onAngle(9f, 0))
        d.onApplied(true, true)
        assertEquals(Decision.RequestOpen, d.onAngle(15f, 1))
        assertEquals(Decision.None, d.onAngle(180f, 2))
        d.onApplied(false, true)
        assertEquals(Decision.None, d.onAngle(20f, 3))
    }

    @Test fun middleZoneNeverRequestsACommand() {
        assertEquals(Decision.None, d.onAngle(11f, 0))
        assertEquals(Decision.None, d.onAngle(14f, 1))
        d.onAngle(9f, 2)
        d.onApplied(true, true)
        assertEquals(Decision.None, d.onAngle(12f, 3))
    }

    @Test fun noiseYieldsExactlyOneCloseBeforeAndAfterAcknowledgement() {
        for (acknowledge in listOf(false, true)) {
            val decider = FoldDecider(10f, 15f, 0)
            val commands = listOf(9f, 11f, 9f, 11f).mapIndexed { i, angle ->
                decider.onAngle(angle, i.toLong()).also {
                    if (acknowledge && it == Decision.RequestClosed) decider.onApplied(true, true)
                }
            }.filter { it != Decision.None }
            assertEquals(listOf(Decision.RequestClosed), commands)
        }
    }

    @Test fun closeOpenCloseProducesThreeCommands() {
        assertEquals(Decision.RequestClosed, d.onAngle(9f, 0))
        d.onApplied(true, true) // Direct request accepted, even before activation.
        assertEquals(Decision.RequestOpen, d.onAngle(15f, 1))
        d.onApplied(false, true)
        assertEquals(Decision.RequestClosed, d.onAngle(10f, 2))
    }

    @Test fun oppositeAngleWhilePendingIsReconciledAfterResult() {
        d.onAngle(9f, 0)
        assertEquals(Decision.None, d.onAngle(20f, 1))
        d.onApplied(true, true)
        assertEquals(Decision.RequestOpen, d.onAngle(20f, 2))
    }

    @Test fun holdRequiresFullDurationAndMiddleZoneCancelsIt() {
        val decider = FoldDecider(10f, 15f, 400)
        assertEquals(Decision.None, decider.onAngle(10f, 0))
        assertEquals(Decision.None, decider.onAngle(9f, 399))
        assertEquals(Decision.None, decider.onAngle(11f, 400))
        assertEquals(Decision.None, decider.onAngle(9f, 500))
        assertEquals(Decision.None, decider.onAngle(9f, 899))
        assertEquals(Decision.RequestClosed, decider.onAngle(9f, 900))
        decider.onApplied(true, false)
        assertEquals(Decision.RequestClosed, decider.onAngle(9f, 901))
    }

    @Test fun failedCloseAndOpenRetryOnNextReading() {
        assertEquals(Decision.RequestClosed, d.onAngle(9f, 0))
        d.onApplied(true, false)
        assertEquals(Decision.RequestClosed, d.onAngle(9f, 1))
        d.onApplied(true, true)
        assertEquals(Decision.RequestOpen, d.onAngle(15f, 2))
        d.onApplied(false, false)
        assertEquals(Decision.RequestOpen, d.onAngle(15f, 3))
        d.onApplied(false, true)
        assertEquals(Decision.None, d.onAngle(15f, 4))
    }

    @Test fun startupResetWaitsForSuccessSuppressesPendingDuplicatesAndRetriesFailure() {
        assertEquals(Decision.RequestOpen, d.onAngle(15f, 0))
        assertEquals(Decision.None, d.onAngle(20f, 1))
        d.onApplied(false, false)
        assertEquals(Decision.RequestOpen, d.onAngle(20f, 2))
        d.onApplied(false, true)
        assertEquals(Decision.None, d.onAngle(20f, 3))
    }

    @Test fun resetRearmsStartupSafetyAndClearsPendingCommand() {
        d.onAngle(15f, 0)
        d.onApplied(false, true)
        d.reset()
        assertEquals(Decision.RequestOpen, d.onAngle(15f, 1))
        d.reset()
        assertEquals(Decision.RequestClosed, d.onAngle(9f, 2))
    }

    @Test fun settingsReloadPreservesActiveAndPendingCommands() {
        d.onAngle(9f, 0)
        d.configure(8f, 16f)
        assertEquals(Decision.None, d.onAngle(7f, 1))
        d.onApplied(true, true)
        d.configure(10f, 15f)
        assertEquals(Decision.None, d.onAngle(9f, 2))
        assertEquals(Decision.RequestOpen, d.onAngle(15f, 3))
        d.onApplied(false, true)
        d.configure(8f, 16f)
        assertEquals(Decision.None, d.onAngle(20f, 4))
    }
}

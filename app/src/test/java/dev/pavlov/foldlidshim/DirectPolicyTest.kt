package dev.pavlov.foldlidshim

import org.junit.Assert.assertEquals
import org.junit.Test
import dev.pavlov.foldlidshim.DirectPolicy.Command

class DirectPolicyTest {
    @Test fun closedAlreadyCurrent() {
        assertEquals(Command.NONE, DirectPolicy.decide(true, 0, false))
    }

    @Test fun closedOwnRequestIsNotRepeated() {
        for (state in listOf(null, 0, 1, 2, 3, 4, 5))
            assertEquals(Command.NONE, DirectPolicy.decide(true, state, true))
    }

    @Test fun closingRequestsClosedUnlessAlreadyClosed() {
        for (state in listOf(null, 1, 2, 3, 4, 5))
            assertEquals(Command.CLOSED, DirectPolicy.decide(true, state, false))
    }

    @Test fun openingCancelsOwnClosedEvenIfCurrentIsOpenOrUnknown() {
        for (state in listOf(null, 0, 1, 2, 3, 4, 5))
            assertEquals(Command.CANCEL, DirectPolicy.decide(false, state, true))
    }

    @Test fun openingAlreadyOpen() {
        for (state in listOf(2, 3))
            assertEquals(Command.NONE, DirectPolicy.decide(false, state, false))
    }

    @Test fun openingRequestsOpenedForStuckClosedOrOtherStates() {
        for (state in listOf(null, 0, 1, 4, 5))
            assertEquals(Command.OPENED, DirectPolicy.decide(false, state, false))
    }
}

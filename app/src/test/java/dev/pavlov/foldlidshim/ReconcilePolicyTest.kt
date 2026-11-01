package dev.pavlov.foldlidshim

import org.junit.Assert.*
import org.junit.Test

class ReconcilePolicyTest {
    @Test fun everyBaseAndOwnershipCombination() {
        for (closed in listOf(true, false)) for (base in 0..5)
            for (ours in listOf(true, false)) for (active in listOf(true, false)) {
                val matches = if (closed) base == 0 else base == 2 || base == 3
                val expected = if (matches) {
                    if (ours && active) Command.RESET else Command.NONE
                } else if (closed) Command.CLOSED else Command.OPENED
                assertEquals("closed=$closed base=$base ours=$ours active=$active", expected,
                    ReconcilePolicy.decide(closed, DeviceStateInfo(base, if (active) 3 else null, active), ours))
            }
    }
    @Test fun failedReadFallsBackToAngleOverride() {
        for (ours in listOf(true, false)) for (info in listOf(null, DeviceStateInfo(null, null, false))) {
            assertEquals(Command.CLOSED, ReconcilePolicy.decide(true, info, ours))
            assertEquals(Command.OPENED, ReconcilePolicy.decide(false, info, ours))
        }
    }
}

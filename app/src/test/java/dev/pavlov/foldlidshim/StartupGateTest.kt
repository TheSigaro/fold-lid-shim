package dev.pavlov.foldlidshim

import org.junit.Assert.*
import org.junit.Test

class StartupGateTest {
    @Test fun thirdReadingAppliesCurrentStateWithoutTransition() {
        for ((angle, expected) in listOf(0f to Decision.RequestClosed, 180f to Decision.RequestOpen)) {
            val gate = StartupGate()
            val decider = FoldDecider()
            val commands = mutableListOf<Decision>()
            repeat(3) { index ->
                if (gate.onAngle(angle)) commands.add(decider.onAngle(angle, index.toLong()))
                assertEquals(if (index < 2) 0 else 1, commands.size)
            }
            assertEquals(listOf(expected), commands)
        }
    }

    @Test fun usesThirdAngleRatherThanFirstAngle() {
        val gate = StartupGate()
        val decider = FoldDecider()
        assertFalse(gate.onAngle(0f))
        assertFalse(gate.onAngle(0f))
        assertTrue(gate.onAngle(180f))
        assertEquals(Decision.RequestOpen, decider.onAngle(180f, 2))
    }

    @Test fun invalidAnglesAndZeroVectorsDoNotAdvanceGate() {
        val gate = StartupGate()
        assertFalse(gate.onAngle(0f))
        val zeroAngle = HingeAngleMath.angleBetween(FloatArray(3), floatArrayOf(1f, 0f, 0f),
            booleanArrayOf(true, false, true))
        for (invalid in listOf(zeroAngle, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertFalse(gate.onAngle(invalid))
            assertEquals(1, gate.eventCount)
        }
        assertFalse(gate.onAngle(90f))
        assertTrue(gate.onAngle(180f))
        assertTrue(gate.ready)
    }

    @Test fun readinessPersistsAndResetRequiresThreeNewReadings() {
        val gate = StartupGate()
        repeat(10) { gate.onAngle(20f) }
        assertTrue(gate.ready)
        assertEquals(3, gate.eventCount)
        gate.reset()
        assertFalse(gate.ready)
        assertEquals(0, gate.eventCount)
        assertFalse(gate.onAngle(0f))
        assertFalse(gate.onAngle(0f))
        assertTrue(gate.onAngle(0f))
    }

    @Test fun middleZoneAtStartupKeepsHysteresis() {
        val gate = StartupGate()
        val decider = FoldDecider()
        repeat(3) { if (gate.onAngle(12f)) assertEquals(Decision.None, decider.onAngle(12f, 0)) }
        assertEquals(Decision.RequestOpen, decider.onAngle(180f, 1))
    }
}

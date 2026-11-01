package dev.pavlov.foldlidshim

import org.junit.Assert.*
import org.junit.Test

class HallDiagnosisTest {
    @Test fun noEvidenceIsUnknown() {
        val hall = HallDiagnosis()
        assertEquals(HallDiagnosis.Result.UNKNOWN, hall.diagnose(120_000))
        hall.observe(0)
        assertEquals(HallDiagnosis.Result.UNKNOWN, hall.diagnose(120_000))
        hall.onAngle(Float.NaN)
        assertEquals(HallDiagnosis.Result.UNKNOWN, hall.diagnose(120_000))
    }
    @Test fun closedNeedsZeroAngleAndFullMinute() {
        val hall = HallDiagnosis(1000)
        hall.observe(0); hall.onAngle(0f)
        assertEquals(HallDiagnosis.Result.UNKNOWN, hall.diagnose(60_999))
        assertEquals(HallDiagnosis.Result.STUCK_CLOSED, hall.diagnose(61_000))
        hall.onAngle(20f)
        assertEquals(HallDiagnosis.Result.UNKNOWN, hall.diagnose(70_000))
        assertEquals(20f, hall.maxAngle!!, 0f)
    }
    @Test fun stuckOpenNeedsAngleMovement() {
        for (base in listOf(2, 3)) {
            val hall = HallDiagnosis()
            hall.observe(base); hall.onAngle(0f)
            assertEquals(HallDiagnosis.Result.UNKNOWN, hall.diagnose(0))
            hall.onAngle(0.1f)
            assertEquals(HallDiagnosis.Result.STUCK_OPEN, hall.diagnose(1))
            hall.observe(0); hall.onAngle(0f)
            assertEquals(HallDiagnosis.Result.WORKING, hall.diagnose(2))
        }
    }
    @Test fun recoveryAndDisagreement() {
        val hall = HallDiagnosis()
        hall.observe(0); hall.onAngle(0f)
        assertEquals(HallDiagnosis.Result.STUCK_CLOSED, hall.diagnose(60_000))
        hall.onAngle(180f); hall.observe(3)
        assertEquals(HallDiagnosis.Result.WORKING, hall.diagnose(61_000))
        hall.observe(0)
        assertEquals(HallDiagnosis.Result.UNKNOWN, hall.diagnose(62_000))
        hall.onAngle(0f)
        assertEquals(HallDiagnosis.Result.WORKING, hall.diagnose(63_000))
    }
    @Test fun concurrentAndTentDoNotProveOpen() {
        for (base in listOf(1, 4, 5)) {
            val hall = HallDiagnosis()
            hall.observe(base); hall.onAngle(0f); hall.onAngle(180f)
            assertEquals(HallDiagnosis.Result.UNKNOWN, hall.diagnose(120_000))
        }
    }
}

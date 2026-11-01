package dev.pavlov.foldlidshim

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThresholdValidationTest {
    @Test fun acceptsEndpointsAndStrictlyOrderedThresholds() {
        for ((close, open) in listOf(0f to 180f, 0f to 0.001f, 179.999f to 180f, 10f to 15f)) {
            assertTrue("$close / $open", validateThresholds(close, open))
        }
    }

    @Test fun rejectsOutsideEqualAndReversedThresholds() {
        for ((close, open) in listOf(-0.001f to 15f, 10f to 180.001f, 0f to 0f,
            180f to 180f, 15f to 10f, -2f to -1f)) {
            assertFalse("$close / $open", validateThresholds(close, open))
        }
    }

    @Test fun rejectsNonFiniteValues() {
        for (value in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
            assertFalse(validateThresholds(value, 15f))
            assertFalse(validateThresholds(10f, value))
        }
    }
}

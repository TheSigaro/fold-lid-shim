package dev.pavlov.foldlidshim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HingeAngleMathTest {
    private val noInvert = booleanArrayOf(false, false, false)

    @Test fun oppositeVectorsAreClosed() {
        assertEquals(0f, HingeAngleMath.angleBetween(floatArrayOf(0f, 0f, 9.8f),
            floatArrayOf(0f, 0f, -9.8f), noInvert), 0.001f)
    }

    @Test fun perpendicularVectorsAreHalfOpen() {
        assertEquals(90f, HingeAngleMath.angleBetween(floatArrayOf(2f, 0f, 0f),
            floatArrayOf(0f, 7f, 0f), noInvert), 0.001f)
    }

    @Test fun alignedVectorsAreFlatRegardlessOfMagnitude() {
        assertEquals(180f, HingeAngleMath.angleBetween(floatArrayOf(1f, 2f, 3f),
            floatArrayOf(2f, 4f, 6f), noInvert), 0.001f)
    }

    @Test fun axisInversionCorrectsSubWithoutMutatingInputs() {
        val sub = floatArrayOf(-1f, 2f, -3f)
        assertEquals(180f, HingeAngleMath.angleBetween(floatArrayOf(1f, 2f, 3f),
            sub, booleanArrayOf(true, false, true)), 0.001f)
        assertEquals(-1f, sub[0], 0f)
        for (axis in 0..2) {
            val main = FloatArray(3).also { it[axis] = 1f }
            val invert = BooleanArray(3).also { it[axis] = true }
            assertEquals(0f, HingeAngleMath.angleBetween(main, main, invert), 0.001f)
        }
    }

    @Test fun zeroAndInvalidVectorsReturnNaN() {
        val valid = floatArrayOf(1f, 2f, 3f)
        assertTrue(HingeAngleMath.angleBetween(FloatArray(3), valid, noInvert).isNaN())
        assertTrue(HingeAngleMath.angleBetween(valid, FloatArray(3), noInvert).isNaN())
        assertTrue(HingeAngleMath.angleBetween(floatArrayOf(Float.NaN, 0f, 1f), valid, noInvert).isNaN())
        assertTrue(HingeAngleMath.angleBetween(floatArrayOf(Float.POSITIVE_INFINITY, 0f, 1f), valid, noInvert).isNaN())
    }
}

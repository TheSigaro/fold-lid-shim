package dev.pavlov.foldlidshim

import kotlin.math.acos
import kotlin.math.sqrt

object HingeAngleMath {
    /** Fold angle: opposite vectors = closed (0), aligned vectors = flat (180). */
    fun angleBetween(main: FloatArray, sub: FloatArray, invert: BooleanArray): Float {
        if (main.size < 3 || sub.size < 3 || invert.size < 3) return Float.NaN
        var dot = 0.0
        var mainNorm = 0.0
        var subNorm = 0.0
        for (i in 0..2) {
            val a = main[i].toDouble()
            val b = sub[i].toDouble() * if (invert[i]) -1 else 1
            dot += a * b
            mainNorm += a * a
            subNorm += b * b
        }
        if (mainNorm == 0.0 || subNorm == 0.0 || !dot.isFinite()) return Float.NaN
        val cosine = (dot / sqrt(mainNorm * subNorm)).coerceIn(-1.0, 1.0)
        return (180.0 - Math.toDegrees(acos(cosine))).toFloat()
    }
}

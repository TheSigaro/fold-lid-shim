package dev.pavlov.foldlidshim

/** Valid hinge thresholds, including physical endpoints. Also rejects NaN/infinity. */
fun validateThresholds(closeDeg: Float, openDeg: Float): Boolean =
    closeDeg >= 0f && closeDeg < openDeg && openDeg <= 180f

package dev.pavlov.foldlidshim

/** Counts valid source outputs, never timer or bridge callbacks. */
class StartupGate {
    var eventCount = 0
        private set
    val ready: Boolean get() = eventCount >= 3

    fun onAngle(angle: Float): Boolean {
        if (!angle.isFinite()) return false
        if (!ready) eventCount++
        return ready
    }

    fun reset() { eventCount = 0 }
}

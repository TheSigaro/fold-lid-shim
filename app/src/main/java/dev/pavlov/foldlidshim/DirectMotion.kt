package dev.pavlov.foldlidshim

/** Sensor timestamps are monotonic nanoseconds. No holds or scheduled evaluations. */
class DirectMotion {
    var closed = false
        private set
    var failedClosed = false
        private set
    private var previousAngle: Float? = null
    private var previousNs = 0L

    fun angle(angle: Float, timestampNs: Long, close: Float, open: Float,
              early: Boolean, earlyAngle: Float): Boolean {
        if (!angle.isFinite() || angle !in 0f..180f) return closed
        val previous = previousAngle
        val dt = timestampNs - previousNs
        // Ignore stale/out-of-order samples, including their velocity.
        if (previous != null && dt <= 0) return closed
        val speed = if (previous != null && dt in 1..500_000_000L)
            (previous - angle) * 1_000_000_000.0 / dt else 0.0
        val wasClosed = closed
        if (angle <= close || (early && angle <= earlyAngle && speed >= 90.0)) {
            closed = true
        } else if (angle >= open && (previous == null || angle > previous)) {
            closed = false
        }
        if (closed != wasClosed) failedClosed = false
        previousAngle = angle
        previousNs = timestampNs
        return closed
    }

    /** Evaluate a preliminary sample without changing the real hinge history or its velocity. */
    fun previewAngle(angle: Float, timestampNs: Long, close: Float, open: Float,
                     early: Boolean, earlyAngle: Float): Boolean {
        val copy = DirectMotion()
        copy.closed = closed
        copy.previousAngle = previousAngle
        copy.previousNs = previousNs
        return copy.angle(angle, maxOf(timestampNs, previousNs + 1), close, open, early, earlyAngle)
    }

    fun failed() { failedClosed = true }
}

/** A successful binder return is not cancellation confirmation. */
class CancelRetry {
    private var lastAttempt: Long? = null
    fun due(nowMs: Long, needsCancel: Boolean, open: Boolean): Boolean {
        if (!needsCancel || !open) {
            lastAttempt = null
            return false
        }
        if (lastAttempt?.let { nowMs - it < 1000 } == true) return false
        lastAttempt = nowMs
        return true
    }
}

/** Persist returned timestamps across process deaths. Wall clock survives system_server restart. */
object RestartGuard {
    const val MESSAGE = "Служба выключена после аварийных перезапусков системы"
    fun recent(previous: List<Long>, now: Long): List<Long> =
        (previous.filter { it <= now && now - it <= 600_000 } + now).takeLast(2)
    fun trips(previous: List<Long>, now: Long): Boolean = recent(previous, now).size >= 2
}

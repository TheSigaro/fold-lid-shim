package dev.pavlov.foldlidshim

sealed class Decision {
    object None : Decision()
    object RequestClosed : Decision()
    object RequestOpen : Decision()
}

/** Hinge hysteresis. All calls belong to one thread; nowMs is monotonic time. */
class FoldDecider(
    closeDeg: Float = Config.DEFAULT_CLOSE_DEG,
    openDeg: Float = Config.DEFAULT_OPEN_DEG,
    holdMs: Long = Config.CLOSE_HOLD_MS
) {
    var closeDeg: Float = closeDeg
        private set
    var openDeg: Float = openDeg
        private set
    var holdMs: Long = holdMs
        private set

    private var overrideClosed = false
    private var resetConfirmed = false
    private var pendingClosed: Boolean? = null
    private var holdStartMs: Long? = null
    private var holdSatisfied = false

    /** Deadline for a service timer when the sensor does not emit another reading. */
    val nextEvaluationMs: Long?
        get() = if (pendingClosed == null && !overrideClosed) holdStartMs?.plus(holdMs) else null

    init {
        require(validateThresholds(closeDeg, openDeg))
        require(holdMs >= 0)
    }

    /** Changing settings cancels a hold, but preserves applied and pending commands. */
    fun configure(closeDeg: Float, openDeg: Float, holdMs: Long = this.holdMs) {
        require(validateThresholds(closeDeg, openDeg))
        require(holdMs >= 0)
        this.closeDeg = closeDeg
        this.openDeg = openDeg
        this.holdMs = holdMs
        holdStartMs = null
        holdSatisfied = false
    }

    fun onAngle(angle: Float, nowMs: Long): Decision {
        if (!(angle <= closeDeg)) {
            holdStartMs = null
            holdSatisfied = false
        }
        if (pendingClosed != null) return Decision.None
        if (angle >= openDeg) {
            if (overrideClosed || !resetConfirmed) {
                pendingClosed = false
                return Decision.RequestOpen
            }
        } else if (angle <= closeDeg && !overrideClosed) {
            val start = holdStartMs ?: nowMs.also { holdStartMs = it }
            if (holdSatisfied || nowMs - start >= holdMs) {
                holdStartMs = null
                holdSatisfied = true
                pendingClosed = true
                return Decision.RequestClosed
            }
        }
        return Decision.None
    }

    fun onApplied(closed: Boolean, ok: Boolean) {
        if (pendingClosed != closed) return
        pendingClosed = null
        if (ok) {
            holdStartMs = null
            holdSatisfied = false
            overrideClosed = closed
            if (!closed) resetConfirmed = true
        }
    }

    fun reset() {
        overrideClosed = false
        resetConfirmed = false
        pendingClosed = null
        holdStartMs = null
        holdSatisfied = false
    }
}

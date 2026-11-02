package dev.pavlov.foldlidshim

/** Monotonic milliseconds; all calls belong to the service worker. */
class SleepPolicy {
    enum class Wake { NONE, LOCK, REQUEST_CLOSED }
    private var screenOffAt: Long? = null
    var sleepCancelAt: Long? = null
        private set
    private var sleepBlocked = false
    private var desired: Boolean? = null

    fun screenOff(now: Long) { screenOffAt = now }

    fun canceled(now: Long, interactive: Boolean): Boolean {
        if (interactive && screenOffAt?.let { now - it in 0..1000L } != true) return false
        sleepCancelAt = now
        sleepBlocked = true
        return true
    }

    fun allowClosed(): Boolean = !sleepBlocked

    fun desired(closed: Boolean, interactive: Boolean, owns: Boolean): Boolean {
        val wakeOpen = desired == true && !closed && !interactive && !owns
        desired = closed
        if (!closed) {
            sleepBlocked = false
            sleepCancelAt = null
        }
        return wakeOpen
    }

    fun screenOn(now: Long, closed: Boolean, owns: Boolean): Wake {
        if (!closed || owns) return Wake.NONE
        if (sleepCancelAt?.let { now - it in 0 until 1500L } == true) {
            sleepBlocked = true
            return Wake.LOCK
        }
        sleepBlocked = false
        return Wake.REQUEST_CLOSED
    }
}

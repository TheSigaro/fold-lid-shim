package dev.pavlov.foldlidshim

/** Stateful decisions using monotonic milliseconds, with no platform dependencies. */
class OverridePlanner {
    enum class Action { NONE, REQUEST_CLOSED, REQUEST_CLOSED_AND_RELOCK, REQUEST_OPENED, CANCEL, WAKE }
    data class Decision(val action: Action = Action.NONE, val log: String? = null)

    private var previousSystemState: Int? = null
    var systemOpenedAtMs: Long? = null
        private set
    private var systemResetLogPending = false
    private var openingLogged = false
    private var wakePending = false
    var closedDeadline: Long? = null
        private set

    /** Observe before the activity gate so a pending CLOSED can be abandoned immediately. */
    fun observeSystem(systemState: Int?, ownState: Int?, now: Long): Boolean {
        val opened = previousSystemState == 0 && systemState != null && systemState in 1..3 && ownState == null
        previousSystemState = systemState
        if (opened) {
            openedSince = null
            systemOpenedAtMs = now
            systemResetLogPending = true
            closedDeadline = null
            wakePending = false
        }
        return opened
    }

    private var previousClosed: Boolean? = null
    private var closedLogged = false
    private var openedAttempted = false
    private var openedSince: Long? = null
    var openedDeadline: Long? = null
        private set

    fun plan(closed: Boolean, systemState: Int?, ownState: Int?, interactive: Boolean,
             now: Long, openZone: Boolean = !closed, recoveryPending: Boolean = false, requestsAllowed: Boolean = true,
             openedZone: Boolean = openZone, closedEvidenceMs: Long? = null,
             openConfirmed: Boolean = false): Decision {
        observeSystem(systemState, ownState, now)
        val staleClosed = systemOpenedAtMs?.let { closedEvidenceMs == null || closedEvidenceMs <= it } == true
        val changed = previousClosed != closed
        if (previousClosed == true && !closed && !interactive && ownState == null && !staleClosed)
            wakePending = true
        if (closed || interactive || ownState != null || staleClosed) wakePending = false
        if (changed) {
            openingLogged = false
            closedLogged = false
            openedAttempted = false
            openedSince = null
        }
        previousClosed = closed
        openedDeadline = null
        if (!closed || interactive || ownState != null || systemState == 0 || staleClosed)
            closedDeadline = null
        if (systemResetLogPending) {
            systemResetLogPending = false
            return Decision(log = "system left CLOSED, decision reset")
        }
        if (closed) {
            if (ownState != null) return Decision(if (ownState == 3) Action.CANCEL else Action.NONE)
            if (systemState == 0) {
                val log = if (!closedLogged) "system already CLOSED, no request" else null
                closedLogged = true
                return Decision(log = log)
            }
            if (staleClosed) return Decision()
            if (interactive) return Decision(Action.REQUEST_CLOSED)
            val deadline = closedDeadline
            if (deadline == null) {
                closedDeadline = now + ASLEEP_CLOSED_GRACE_MS
                return Decision(log = "closed while asleep: waiting for system")
            }
            return if (now >= deadline) {
                Decision(Action.REQUEST_CLOSED_AND_RELOCK, "closed while asleep: request CLOSED")
            } else Decision()
        }
        if (!interactive && !openConfirmed) {
            val log = if (!openingLogged) "opening unconfirmed: waiting" else null
            openingLogged = true
            return Decision(log = log)
        }
        if (ownState != null) return Decision(if (ownState == 0 && openZone) Action.CANCEL else Action.NONE)
        if (!openedZone || systemState != 0) openedSince = null
        else if (!openedAttempted) {
            val since = openedSince ?: now.also { openedSince = it }
            if (!recoveryPending) {
                if (now - since >= OPENED_GRACE_MS && requestsAllowed) {
                    openedAttempted = true
                    return Decision(Action.REQUEST_OPENED, "lid stuck closed: request OPENED")
                }
                if (now - since < OPENED_GRACE_MS) openedDeadline = since + OPENED_GRACE_MS
            }
        }
        if (wakePending) {
            wakePending = false
            return Decision(Action.WAKE, "wake on open")
        }
        return Decision()
    }

    companion object {
        const val OPENED_GRACE_MS = 700L
        const val ASLEEP_CLOSED_GRACE_MS = 1000L
    }
}

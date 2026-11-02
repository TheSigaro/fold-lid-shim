package dev.pavlov.foldlidshim

/** Stateful decisions using monotonic milliseconds, with no platform dependencies. */
class OverridePlanner {
    enum class Action { NONE, REQUEST_CLOSED, REQUEST_CLOSED_AND_RELOCK, REQUEST_OPENED, CANCEL, WAKE }
    data class Decision(val action: Action = Action.NONE, val log: String? = null)

    private var previousClosed: Boolean? = null
    private var closedLogged = false
    private var openedAttempted = false
    private var openedSince: Long? = null
    var openedDeadline: Long? = null
        private set

    fun plan(closed: Boolean, systemState: Int?, ownState: Int?, interactive: Boolean,
             now: Long, openZone: Boolean = !closed, recoveryPending: Boolean = false, requestsAllowed: Boolean = true,
             openedZone: Boolean = openZone): Decision {
        val changed = previousClosed != closed
        val wake = previousClosed == true && !closed && !interactive && ownState == null
        if (changed) {
            closedLogged = false
            openedAttempted = false
            openedSince = null
        }
        previousClosed = closed
        openedDeadline = null
        if (closed) {
            if (ownState != null) return Decision(if (ownState == 3) Action.CANCEL else Action.NONE)
            if (systemState == 0) {
                val log = if (!closedLogged) "system already CLOSED, no request" else null
                closedLogged = true
                return Decision(log = log)
            }
            return if (interactive) Decision(Action.REQUEST_CLOSED)
            else Decision(Action.REQUEST_CLOSED_AND_RELOCK, "closed while asleep: request CLOSED")
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
        return if (wake) Decision(Action.WAKE, "wake on open") else Decision()
    }

    companion object { const val OPENED_GRACE_MS = 700L }
}

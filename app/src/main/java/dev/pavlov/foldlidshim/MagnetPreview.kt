package dev.pavlov.foldlidshim

/** Preliminary desired state. Real hinge motion is maintained independently for rollback. */
class MagnetPreview {
    data class Pending(val closed: Boolean, val tNs: Long, val desiredClosed: Boolean)
    var pending: Pending? = null
        private set
    var blockedUntilNs = Long.MIN_VALUE
        private set
    var falseCount = 0
        private set
    val desiredClosed: Boolean? get() = pending?.desiredClosed

    fun start(closed: Boolean, tNs: Long, desiredClosed: Boolean = closed): Boolean {
        if (tNs < blockedUntilNs || pending != null) return false
        pending = Pending(closed, tNs, desiredClosed)
        return true
    }
    fun hinge(tNs: Long, angle: Float): Long? {
        val p = pending ?: return null
        if (tNs < p.tNs || !(if (p.closed) angle == 0f else angle >= 90f)) return null
        pending = null
        return (tNs - p.tNs) / 1_000_000L
    }
    fun contradict(tNs: Long, angle: Float): Pending? {
        val p = pending ?: return null
        if (tNs < p.tNs || !p.closed || angle != 180f) return null
        return reject(tNs)
    }
    private fun reject(nowNs: Long): Pending? {
        val p = pending ?: return null
        pending = null
        falseCount++
        blockedUntilNs = nowNs + 30_000_000_000L
        return p
    }
    fun expire(nowNs: Long): Pending? {
        val p = pending ?: return null
        if (nowNs - p.tNs < 1_500_000_000L) return null
        return reject(nowNs)
    }
    fun clear() { pending = null }
}

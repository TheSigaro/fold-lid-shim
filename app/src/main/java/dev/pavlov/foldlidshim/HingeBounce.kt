package dev.pavlov.foldlidshim

/** Holds only hinge opening decisions, never magnetic opening decisions. */
class HingeBounce {
    private var sentAt: Long? = null
    private var openingAt: Long? = null
    private var openingAngle = 0f
    var ignoredAngle = 0f
        private set
    var ignoredAfterMs: Long? = null
        private set
    val deadline: Long? get() = openingAt?.plus(300L)

    fun release() { sentAt = null; openingAt = null; ignoredAfterMs = null }

    fun requested(now: Long) { sentAt = now; openingAt = null }

    fun closed(now: Long, desired: Boolean, angle: Float, inClosedZone: Boolean,
               magneticOpening: Boolean): Boolean {
        ignoredAfterMs = null
        if (desired || magneticOpening) {
            if (inClosedZone && openingAt != null && now - openingAt!! < 300L) {
                ignoredAngle = openingAngle
                ignoredAfterMs = now - openingAt!!
            }
            openingAt = null
            if (magneticOpening) sentAt = null
            return desired
        }
        val start = openingAt
        if (start != null) {
            if (now - start < 300L) return true
            release()
            return false
        }
        if (sentAt?.let { now - it in 0 until 1500L } != true) return false
        openingAt = now
        openingAngle = angle
        return true
    }
}

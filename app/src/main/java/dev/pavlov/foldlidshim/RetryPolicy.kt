package dev.pavlov.foldlidshim

/** Elapsed time uses a monotonic clock. Delay is measured after a failed attempt. */
class RetryPolicy(val windowMs: Long = 30 * 60 * 1000L) {
    fun mayAttempt(elapsedMs: Long): Boolean = elapsedMs in 0 until windowMs
    fun nextDelayMs(failures: Int, elapsedMs: Long): Long? {
        require(failures > 0)
        val delay = when (failures) { 1 -> 10_000L; 2 -> 20_000L; 3 -> 40_000L; else -> 60_000L }
        return delay.takeIf { mayAttempt(elapsedMs) && it < windowMs - elapsedMs }
    }
}

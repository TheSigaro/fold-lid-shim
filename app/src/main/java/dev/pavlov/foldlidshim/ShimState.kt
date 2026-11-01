package dev.pavlov.foldlidshim

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** Process-wide state shown by the UI; written by FoldService. */
object ShimState {
    @Volatile var autoStartAttempt: AutoStartAttempt? = null
    @Volatile var decision: String = "Служба не запущена"
    @Volatile var angle: Float? = null
    @Volatile var overrideClosed: Boolean = false
    /** Last callback result from direct mode: "activated", "suspended", "canceled", or null. */
    @Volatile var lastCallbackResult: String? = null

    private val listeners = CopyOnWriteArrayList<Runnable>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(listener: Runnable) {
        listeners.add(listener)
    }

    fun removeListener(listener: Runnable) {
        listeners.remove(listener)
    }

    fun changed() {
        main.post { listeners.forEach { it.run() } }
    }
}

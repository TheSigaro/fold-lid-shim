package dev.pavlov.foldlidshim

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** Process-wide state shown by the UI; written by FoldService. */
object ShimState {
    @Volatile var angle: Float? = null
    @Volatile var overrideClosed: Boolean = false

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

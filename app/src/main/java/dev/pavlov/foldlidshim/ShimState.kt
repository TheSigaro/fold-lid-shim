package dev.pavlov.foldlidshim

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** Process-wide state shown by the UI; written by FoldService. */
object ShimState {
    @Volatile var magnetSensors = "Датчики выкл"
    @Volatile var magnetEvent = "нет событий"
    @Volatile var magnetFalseCount = 0
    @Volatile var serviceRunning = false
    @Volatile var directCurrentState: Int? = null
        set(value) {
            val previous = field
            field = value
            if (previous != value) stateListeners.forEach { it(value) }
        }
    private val stateListeners = CopyOnWriteArrayList<(Int?) -> Unit>()
    fun addStateListener(listener: (Int?) -> Unit) { stateListeners.add(listener) }
    fun removeStateListener(listener: (Int?) -> Unit) { stateListeners.remove(listener) }
    /** Current-state sample received while no direct request belonged to us. */
    @Volatile var directUnrequestedState: Int? = null
    @Volatile var directBaseState: Int? = null
    @Volatile var directConfirmationPending = false
    @Volatile var directRequestGeneration = 0L
    @Volatile var hallDiagnosis = HallDiagnosis.Result.UNKNOWN
    @Volatile var decision: String = "Служба не запущена"
    @Volatile var angle: Float? = null
    @Volatile var overrideClosed: Boolean = false
    /** Last callback result from state requests: "activated", "suspended", "canceled", or null. */
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

package dev.pavlov.foldlidshim

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.ResultReceiver
import android.os.SystemClock
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Transparent activity used to make DeviceStateManager requests from a foreground context.
 * The system only allows device_state requests from the top app (foreground process).
 * This activity is launched by FoldService, grabs window focus, executes the request,
 * and finishes itself.
 */
class StateActivity : Activity() {

    companion object {
        const val EXTRA_ID = "request_id"
        const val EXTRA_RECEIVER = "result_receiver"
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_ERROR = "error"
        const val EXTRA_DEADLINE = "deadline"
        // The gate also prevents a delayed activity from executing after service timeout/reload.
        private val actionLock = Any()
        private var activeId: String? = null

        fun registerAction(id: String) = synchronized(actionLock) { activeId = id }
        fun abandonAction(id: String) = synchronized(actionLock) {
            if (activeId == id) activeId = null
        }

        const val EXTRA_ACTION = "action"
        const val ACTION_CLOSED = "closed"
        const val ACTION_OPEN = "open"
        private const val FOCUS_TIMEOUT_MS = 3000L
        private const val FINISH_DELAY_MS = 300L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val executed = AtomicBoolean(false)
    private var reported = false
    private var receiver: ResultReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receiver = intent.getParcelableExtra(EXTRA_RECEIVER, ResultReceiver::class.java)
        Log.i(Config.TAG, "StateActivity.onCreate: action=${intent?.getStringExtra(EXTRA_ACTION)}")
    }

    override fun onNewIntent(intent: Intent) {
        report(false, "activity replaced by a new action")
        handler.removeCallbacksAndMessages(null)
        super.onNewIntent(intent)
        setIntent(intent)
        receiver = intent.getParcelableExtra(EXTRA_RECEIVER, ResultReceiver::class.java)
        reported = false
        executed.set(false)
        handler.postDelayed(focusTimeoutRunnable, FOCUS_TIMEOUT_MS)
        if (hasWindowFocus()) onWindowFocusChanged(true)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        // One-shot guard: execute only once even if focus changes multiple times
        if (!executed.compareAndSet(false, true)) {
            Log.i(Config.TAG, "StateActivity: already executed, ignoring duplicate focus")
            return
        }

        Log.i(Config.TAG, "StateActivity: window gained focus, executing request")
        val action = intent?.getStringExtra(EXTRA_ACTION) ?: ACTION_CLOSED
        executeAndFinish(action)
    }

    private fun executeAndFinish(action: String) {
        var success = false
        var error: String? = null
        try {
            synchronized(actionLock) {
                val id = intent.getStringExtra(EXTRA_ID)
                if (id == null || id != activeId ||
                    SystemClock.elapsedRealtime() >= intent.getLongExtra(EXTRA_DEADLINE, 0L)) {
                    error = "action expired or canceled"
                } else {
                    activeId = null
                    success = when (action) {
                        ACTION_CLOSED -> DirectStateController.requestClosed(this)
                        ACTION_OPEN -> DirectStateController.cancel(this)
                        else -> false
                    }
                    if (!success) error = ShimState.lastCallbackResult ?: "direct action failed"
                }
            }
        } catch (e: Exception) {
            Log.e(Config.TAG, "StateActivity: request failed", e)
            error = e.message ?: e.javaClass.simpleName
        }
        report(success, error)

        // Finish after a short delay to ensure the system processes the state change
        handler.postDelayed({
            finish()
        }, FINISH_DELAY_MS)
    }

    /**
     * Safety timeout: if window focus never arrives, log and finish.
     */
    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(focusTimeoutRunnable)
        handler.postDelayed(focusTimeoutRunnable, FOCUS_TIMEOUT_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        report(false, "activity finished before action completed")
        super.onDestroy()
    }

    private fun report(success: Boolean, error: String?) {
        if (reported) return
        reported = true
        val id = intent.getStringExtra(EXTRA_ID)
        if (id != null) abandonAction(id)
        receiver?.send(0, Bundle().apply {
            putString(EXTRA_ID, id)
            putBoolean(EXTRA_SUCCESS, success)
            putString(EXTRA_ERROR, error)
        })
    }

    private val focusTimeoutRunnable = Runnable {
        executed.set(true)
        report(false, "focus timeout after ${FOCUS_TIMEOUT_MS}ms")
        finish()
    }
}

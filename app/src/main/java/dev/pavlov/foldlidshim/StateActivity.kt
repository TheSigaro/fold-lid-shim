package dev.pavlov.foldlidshim

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
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
        const val EXTRA_ACTION = "action"
        const val ACTION_CLOSED = "closed"
        const val ACTION_OPEN = "open"
        private const val FOCUS_TIMEOUT_MS = 3000L
        private const val FINISH_DELAY_MS = 300L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val executed = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(Config.TAG, "StateActivity.onCreate: action=${intent?.getStringExtra(EXTRA_ACTION)}")
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
        try {
            val success = if (action == ACTION_CLOSED) {
                DirectStateController.requestClosed(this)
            } else {
                DirectStateController.cancel(this)
            }
            // Only write activated for closed; open/cancel should not set overrideClosed=true
            if (success && action == ACTION_CLOSED) {
                ShimState.overrideClosed = true
                ShimState.lastCallbackResult = "activated"
            } else if (action == ACTION_OPEN || action == ACTION_OPEN) {
                // OPEN -> cancel request
                ShimState.overrideClosed = false
                ShimState.lastCallbackResult = if (success) "canceled" else null
            } else if (!success) {
                ShimState.lastCallbackResult = ShimState.lastCallbackResult ?: "failed"
            }
        } catch (e: SecurityException) {
            Log.e(Config.TAG, "StateActivity: SecurityException: ${e.message}")
            ShimState.lastCallbackResult = "SecurityException: ${e.message}"
        } catch (e: Exception) {
            Log.e(Config.TAG, "StateActivity: exception during request: ${e.message}", e)
            ShimState.lastCallbackResult = e.message ?: "exception"
        }

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

    override fun onStop() {
        handler.removeCallbacks(focusTimeoutRunnable)
        super.onStop()
    }

    private val focusTimeoutRunnable = Runnable {
        if (executed.compareAndSet(false, true)) {
            Log.w(Config.TAG, "StateActivity: focus timeout after ${FOCUS_TIMEOUT_MS}ms, finishing")
        }
        finish()
    }
}

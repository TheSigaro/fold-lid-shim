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
import java.util.UUID
import android.content.Context

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

        fun launch(context: Context, closed: Boolean) {
            val id = UUID.randomUUID().toString()
            registerAction(id)
            try {
                Timeline.event("startActivity manual closed=$closed id=$id")
                context.startActivity(Intent(context, StateActivity::class.java).apply {
                    putExtra(EXTRA_ACTION, if (closed) ACTION_CLOSED else ACTION_OPEN)
                    putExtra(EXTRA_ID, id)
                    putExtra(EXTRA_DEADLINE, SystemClock.elapsedRealtime() + FOCUS_TIMEOUT_MS)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                })
            } catch (e: Exception) {
                abandonAction(id)
                ShimState.lastCallbackResult = "StateActivity: ${e.message}"
                ShimState.changed()
            }
        }

        const val EXTRA_ACTION = "action"
        const val ACTION_CLOSED = "closed"
        const val ACTION_OPEN = "open"
        private const val FOCUS_TIMEOUT_MS = 3000L
        const val OPEN_UNAVAILABLE = "Система не даёт приложению запросить открытие. При залипании «закрыто» нужен Shizuku"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val executed = AtomicBoolean(false)
    private var resumed = false
    private var reported = false
    private var accepted = false
    private var actionError: String? = null
    private val stateChanged = Runnable {
        if (executed.get() && !ShimState.directConfirmationPending) finish()
    }
    private var receiver: ResultReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        ShimState.addListener(stateChanged)
        receiver = intent.getParcelableExtra(EXTRA_RECEIVER, ResultReceiver::class.java)
        Timeline.event("StateActivity.onCreate action=${intent?.getStringExtra(EXTRA_ACTION)}")
    }

    override fun onNewIntent(intent: Intent) {
        report(false, "activity replaced by a new action")
        handler.removeCallbacksAndMessages(null)
        super.onNewIntent(intent)
        setIntent(intent)
        receiver = intent.getParcelableExtra(EXTRA_RECEIVER, ResultReceiver::class.java)
        reported = false
        accepted = false
        actionError = null
        executed.set(false)
        handler.postDelayed(focusTimeoutRunnable, FOCUS_TIMEOUT_MS)
        if (hasWindowFocus()) onWindowFocusChanged(true)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (hasWindowFocus()) onWindowFocusChanged(true)
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !resumed) return
        // One-shot guard: execute only once even if focus changes multiple times
        if (!executed.compareAndSet(false, true)) {
            Log.i(Config.TAG, "StateActivity: already executed, ignoring duplicate focus")
            return
        }

        handler.removeCallbacks(focusTimeoutRunnable)
        Timeline.event("StateActivity focus")
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
                        ACTION_CLOSED -> DirectStateController.applyDesired(this, true)
                        ACTION_OPEN -> {
                            if (receiver == null) DirectStateController.manualOpen()
                            DirectStateController.cancel(this)
                        }
                        else -> false
                    }
                    if (!success) error = ShimState.lastCallbackResult ?: "direct action failed"
                }
            }
        } catch (e: Exception) {
            Log.e(Config.TAG, "StateActivity: request failed", e)
            error = e.message ?: e.javaClass.simpleName
        }
        accepted = success
        actionError = error
        if (error != null) Timeline.event("request exception $error")
        if (action == ACTION_OPEN && ShimState.directCurrentState == 0) {
            ShimState.lastCallbackResult = OPEN_UNAVAILABLE
            ShimState.changed()
        }
        // Result is sent by onDestroy, after relinquishing this foreground window.
        // Keep the foreground context for confirmation; callback ends it without polling.
        if (!success || !ShimState.directConfirmationPending) finish()
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
        ShimState.removeListener(stateChanged)
        report(accepted, actionError ?: if (accepted) null else "activity finished before action completed")
        super.onDestroy()
    }

    private fun report(success: Boolean, error: String?) {
        if (reported) return
        reported = true
        val id = intent.getStringExtra(EXTRA_ID)
        Timeline.event("StateActivity result id=$id accepted=$success error=$error")
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

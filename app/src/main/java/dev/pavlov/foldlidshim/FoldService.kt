package dev.pavlov.foldlidshim

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.SensorManager
import android.os.Bundle
import android.os.ResultReceiver
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.util.UUID

class FoldService : Service() {

    private var autoStarter: ShizukuAutoStarter? = null
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var notificationManager: NotificationManager
    private var sensorManager: SensorManager? = null
    private var angleSensors: AngleSensors? = null
    private var currentSource = Config.DEFAULT_SOURCE
    private var currentInvert = booleanArrayOf(true, false, true)
    private var forceOpened = true
    private var shizukuOverrideActive = false
    private val startupGate = StartupGate()
    private var lastPublishAt = 0L

    // Touched only on the handler thread.
    private var lastAngle: Float? = null
    private val decider = FoldDecider()
    private var lastNotifText = ""
    private var lastNotifAt = 0L
    private var currentMode = Prefs.MODE_DIRECT

    // Track pending StateActivity action to handle its result properly
    private var pendingAction: String? = null
    private var pendingId: String? = null
    private var stopping = false
    private val pendingTimeout = Runnable {
        completeDirectAction(false, "direct action timeout after 3000ms")
    }
    private val stateListener = Runnable {
        handler.post {
            if (!stopping) publish(force = true, notifyState = false)
        }
    }

    private val holdRunnable = Runnable {
        evaluate()
        publish(force = true)
        if (decider.nextEvaluationMs == null) releaseWakeLock()
    }

    private val bridgeListener = Runnable {
        handler.post {
            evaluate()
            publish(force = true)
        }
    }

    @Synchronized private fun ensureAutoStarter(): ShizukuAutoStarter =
        autoStarter ?: ShizukuAutoStarter(this).also { autoStarter = it }

    @Synchronized private fun stopAutoStarter() {
        autoStarter?.close()
        autoStarter = null
    }

    override fun onCreate() {
        super.onCreate()
        currentMode = Prefs.getMode(this)
        loadThresholds()
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_MIN)
        )
        startForeground(
            NOTIF_ID,
            buildNotification(getString(R.string.notif_waiting_angle, overrideText())),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )

        thread = HandlerThread("FoldLidShim-worker").also { it.start() }
        handler = Handler(thread.looper)
        ShimState.addListener(stateListener)
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FoldLidShim:event").apply {
            setReferenceCounted(false)
        }

        if (currentMode == Prefs.MODE_SHIZUKU) {
            ShizukuBridge.addListener(bridgeListener)
            ShizukuBridge.ensureBound()
            ensureAutoStarter()
        } else {
            Log.i(Config.TAG, "Running in direct mode, Shizuku not needed")
        }

        sensorManager = getSystemService(SensorManager::class.java)
        handler.post { registerAngleSource() }
        handler.post { publish(force = true) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_SHIZUKU) {
            ensureAutoStarter().requestNow()
        }
        if (intent?.action == ACTION_RELOAD) {
            handler.post { if (!stopping) handleReload() }
        }
        return START_STICKY
    }

    private fun handleReload() {
        cancelHold()
        loadThresholds()
        val newMode = Prefs.getMode(this)
        if (newMode != currentMode) {
            // Invalidate before cancellation. The activity gate waits for an executing action.
            clearPendingAction()
            val success = if (currentMode == Prefs.MODE_DIRECT) {
                if (ShimState.overrideClosed || DirectStateController.hasRequest) {
                    DirectStateController.cancel(this)
                } else true
            } else if (shizukuOverrideActive || ShimState.overrideClosed) {
                ShizukuBridge.exec(Config.ARG_RESET, 1500)?.code == 0
            } else true
            if (!success) {
                ShimState.lastCallbackResult = "reload: old override cancellation failed: ${ShimState.lastCallbackResult.orEmpty()}"
                publish(force = true)
                return
            }
            ShimState.overrideClosed = false
            shizukuOverrideActive = false
            if (currentMode == Prefs.MODE_SHIZUKU) ShizukuBridge.removeListener(bridgeListener)
            stopAutoStarter()
            currentMode = newMode
            decider.reset()
            if (currentMode == Prefs.MODE_SHIZUKU) {
                ShizukuBridge.addListener(bridgeListener)
                ShizukuBridge.ensureBound()
                ensureAutoStarter()
            }
        }
        val source = Prefs.angleSource(this)
        val invert = Prefs.invertAxes(this)
        if (source != currentSource || !invert.contentEquals(currentInvert)) {
            registerAngleSource()
        }
        val newForceOpened = Prefs.forceOpened(this)
        if (forceOpened != newForceOpened) {
            forceOpened = newForceOpened
            if (currentMode == Prefs.MODE_SHIZUKU) decider.reset()
        }
        evaluate()
        publish(force = true)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopAutoStarter()
        ShimState.removeListener(stateListener)
        handler.post {
            stopping = true
            angleSensors?.stop()
            ShizukuBridge.removeListener(bridgeListener)
            cancelHold()
            clearPendingAction()
            if (currentMode == Prefs.MODE_SHIZUKU || ShimState.overrideClosed ||
                (currentMode == Prefs.MODE_DIRECT && DirectStateController.hasRequest)) {
                val success = if (currentMode == Prefs.MODE_DIRECT) {
                    DirectStateController.cancel(this)
                } else {
                    ShizukuBridge.exec(Config.ARG_RESET, 2000)?.code == 0
                }
                if (success) ShimState.overrideClosed = false
                else ShimState.lastCallbackResult = ShimState.lastCallbackResult ?: "stop: cancel failed"
            }
            ShimState.angle = null
            ShimState.decision = "Служба остановлена"
            ShimState.changed()
            releaseWakeLock()
            handler.removeCallbacksAndMessages(null)
            thread.quitSafely()
        }
        super.onDestroy()
    }

    private fun registerAngleSource() {
        angleSensors?.stop()
        currentSource = Prefs.angleSource(this)
        currentInvert = Prefs.invertAxes(this)
        forceOpened = Prefs.forceOpened(this)
        lastAngle = null
        ShimState.angle = null
        startupGate.reset()
        ShimState.decision = "Ожидание 3 событий"
        clearPendingAction()
        decider.reset()
        angleSensors = sensorManager?.let { manager ->
            AngleSensors(manager, currentInvert, currentSource) { _, angle -> onAngle(angle) }
                .also { it.start(handler) }
        }
    }

    private fun onAngle(angle: Float) {
        if (stopping || !angle.isFinite()) return
        lastAngle = angle
        ShimState.angle = angle
        startupGate.onAngle(angle)
        if (!startupGate.ready) ShimState.decision = "Ожидание 3 событий: ${startupGate.eventCount}/3"
        else if (ShimState.decision.startsWith("Ожидание")) ShimState.decision = "Между порогами"
        evaluate()
        val now = SystemClock.elapsedRealtime()
        if (now - lastPublishAt >= 200) {
            lastPublishAt = now
            publish(force = false)
        }
        if (decider.nextEvaluationMs == null) releaseWakeLock()
    }

    private fun loadThresholds() {
        val (close, open) = Prefs.thresholds(this)
        decider.configure(close, open, Config.CLOSE_HOLD_MS)
    }

    private fun evaluate() {
        if (stopping || !startupGate.ready) return
        val angle = lastAngle ?: return
        // Feed every zone, even while an activity is pending, to cancel interrupted holds.
        // The decider suppresses pending commands. hasRequest must not block opening.
        val decision = decider.onAngle(angle, SystemClock.elapsedRealtime())
        ShimState.decision = when (decision) {
            Decision.RequestClosed -> "CLOSED"
            Decision.RequestOpen -> "OPENED"
            Decision.None -> ShimState.decision
        }
        if (decision != Decision.None) wakeLock.acquire(Config.WAKELOCK_MS)
        when (decision) {
            Decision.RequestClosed -> {
                if (currentMode == Prefs.MODE_DIRECT && DirectStateController.hasRequest) {
                    // An already accepted request can be suspended; do not submit it twice.
                    decider.onApplied(closed = true, ok = true)
                } else applyState(closed = true)
            }
            Decision.RequestOpen -> applyState(closed = false)
            Decision.None -> Unit
        }
        handler.removeCallbacks(holdRunnable)
        decider.nextEvaluationMs?.let { deadline ->
            wakeLock.acquire(Config.WAKELOCK_MS)
            handler.postDelayed(holdRunnable, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        }
    }

    private fun cancelHold() {
        handler.removeCallbacks(holdRunnable)
    }

    private fun clearPendingAction(abandoned: Boolean = true) {
        pendingId?.let { StateActivity.abandonAction(it) }
        if (abandoned) pendingAction?.let { decider.onApplied(it == StateActivity.ACTION_CLOSED, false) }
        handler.removeCallbacks(pendingTimeout)
        pendingAction = null
        pendingId = null
    }

    private fun completeDirectAction(success: Boolean, error: String?) {
        val action = pendingAction ?: return
        clearPendingAction(abandoned = false)
        val closed = action == StateActivity.ACTION_CLOSED
        // A request can have executed just before its activity result times out.
        // hasRequest means accepted (possibly suspended), and still needs cancellation.
        val applied = success || (closed && DirectStateController.hasRequest)
        decider.onApplied(closed, applied)
        if (success && !closed) ShimState.overrideClosed = false
        if (!success) {
            ShimState.lastCallbackResult = error ?: "direct action failed"
        }
        publish(force = true)
        // Reconcile an angle that crossed the opposite threshold during the activity.
        // Failure waits for a new sensor event instead of retrying in a tight loop.
        if (applied) evaluate()
    }

    private fun applyState(closed: Boolean): Boolean {
        if (currentMode == Prefs.MODE_DIRECT) {
            if (pendingAction != null) return false
            val id = UUID.randomUUID().toString()
            pendingAction = if (closed) StateActivity.ACTION_CLOSED else StateActivity.ACTION_OPEN
            pendingId = id
            StateActivity.registerAction(id)
            val receiver = object : ResultReceiver(handler) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    val data = resultData ?: return
                    if (stopping || pendingId != id ||
                        data.getString(StateActivity.EXTRA_ID) != id) return
                    completeDirectAction(
                        data.getBoolean(StateActivity.EXTRA_SUCCESS),
                        data.getString(StateActivity.EXTRA_ERROR)
                    )
                }
            }
            handler.postDelayed(pendingTimeout, DIRECT_TIMEOUT_MS)
            try {
                startActivity(Intent(this, StateActivity::class.java).apply {
                    putExtra(StateActivity.EXTRA_ACTION, pendingAction)
                    putExtra(StateActivity.EXTRA_ID, id)
                    putExtra(StateActivity.EXTRA_RECEIVER, receiver)
                    putExtra(StateActivity.EXTRA_DEADLINE, SystemClock.elapsedRealtime() + DIRECT_TIMEOUT_MS)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                return true
            } catch (e: Exception) {
                Log.e(Config.TAG, "Direct mode: failed to launch StateActivity", e)
                completeDirectAction(false, "failed to launch StateActivity: ${e.message}")
                return false
            }
        }

        val arg = if (closed) Config.ARG_CLOSED
            else if (forceOpened) Config.ARG_OPENED else Config.ARG_RESET
        val result = ShizukuBridge.exec(arg, 1500)
        val ok = result != null && result.code == 0
        decider.onApplied(closed, ok)
        if (ok) {
            ShimState.overrideClosed = closed
            shizukuOverrideActive = arg != Config.ARG_RESET
            Log.i(Config.TAG, "Shizuku mode state change: ${if (closed) "OPEN -> CLOSED" else "CLOSED -> OPEN"} angle=$lastAngle")
        } else {
            ShimState.lastCallbackResult = "Shizuku $arg failed: $result"
            Log.w(Config.TAG, "Shizuku mode state change to ${if (closed) "CLOSED" else "OPEN"} not applied, result=$result")
        }
        ShimState.changed()
        return ok
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun overrideText(): String =
        getString(if (ShimState.overrideClosed) R.string.state_closed else R.string.state_open)

    // Updates the mandatory foreground-service notification. POST_NOTIFICATIONS is
    // intentionally not requested: the service works when its notification is hidden.
    @SuppressLint("NotificationPermission")
    private fun publish(force: Boolean, notifyState: Boolean = true) {
        if (notifyState) ShimState.changed()
        val angle = lastAngle
        val text = when {
            angleSensors?.available != true -> getString(R.string.notif_no_sensor)
            currentMode == Prefs.MODE_DIRECT ->
                if (angle == null) getString(R.string.notif_waiting_angle, overrideText())
                else getString(
                    R.string.notif_status,
                    getString(R.string.angle_value, angle),
                    overrideText()
                )
            ShizukuBridge.status(this) != ShizukuStatus.READY ->
                getString(R.string.notif_waiting_shizuku, shizukuText(ShizukuBridge.status(this)))
            angle == null -> getString(R.string.notif_waiting_angle, overrideText())
            else -> getString(
                R.string.notif_status,
                getString(R.string.angle_value, angle),
                overrideText()
            )
        }
        val now = SystemClock.elapsedRealtime()
        if (text == lastNotifText) return
        if (!force && now - lastNotifAt < 1000) return
        lastNotifText = text
        lastNotifAt = now
        notificationManager.notify(NOTIF_ID, buildNotification(text))
    }

    private fun shizukuText(status: ShizukuStatus): String = getString(
        when (status) {
            ShizukuStatus.NOT_INSTALLED -> R.string.shizuku_not_installed
            ShizukuStatus.NOT_RUNNING -> R.string.shizuku_not_running
            ShizukuStatus.NO_PERMISSION -> R.string.shizuku_no_permission
            ShizukuStatus.READY -> R.string.shizuku_ready
        }
    )

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_rotate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        const val ACTION_START_SHIZUKU = "dev.pavlov.foldlidshim.START_SHIZUKU"
        private const val CHANNEL_ID = "hinge_state_min"
        private const val NOTIF_ID = 1
        private const val DIRECT_TIMEOUT_MS = 3000L
        const val ACTION_RELOAD = "reload"

        fun reload(context: Context) {
            context.startService(Intent(context, FoldService::class.java).apply { action = ACTION_RELOAD })
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FoldService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FoldService::class.java))
        }
    }
}

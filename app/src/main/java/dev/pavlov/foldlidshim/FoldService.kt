package dev.pavlov.foldlidshim

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
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

class FoldService : Service(), SensorEventListener {

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var notificationManager: NotificationManager
    private var sensorManager: SensorManager? = null
    private var sensor: Sensor? = null

    // Touched only on the handler thread.
    private var lastAngle: Float? = null
    private var holdPending = false
    private var resetConfirmed = false
    private var lastNotifText = ""
    private var lastNotifAt = 0L
    private var currentMode = Prefs.MODE_DIRECT

    // Track pending StateActivity action to handle its result properly
    private var pendingAction: String? = null
    private var pendingId: String? = null
    private var thresholds = Config.DEFAULT_CLOSE_DEG to Config.DEFAULT_OPEN_DEG
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
        holdPending = false
        val angle = lastAngle
        val (close, _) = thresholds
        if (angle != null && angle <= close && !ShimState.overrideClosed && pendingAction == null &&
            (currentMode != Prefs.MODE_DIRECT || !DirectStateController.hasRequest)) {
            applyState(closed = true)
        }
        publish(force = true)
        releaseWakeLock()
    }

    private val bridgeListener = Runnable {
        handler.post {
            evaluate()
            publish(force = true)
        }
    }

    override fun onCreate() {
        super.onCreate()
        currentMode = Prefs.getMode(this)
        thresholds = Prefs.thresholds(this)
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
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
        } else {
            Log.i(Config.TAG, "Running in direct mode, Shizuku not needed")
        }

        sensorManager = getSystemService(SensorManager::class.java)
        sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        val s = sensor
        if (s == null) {
            Log.w(Config.TAG, "TYPE_HINGE_ANGLE sensor not found")
        } else {
            sensorManager?.registerListener(this, s, SensorManager.SENSOR_DELAY_FASTEST, 0, handler)
            Log.i(Config.TAG, "Service started, hinge sensor registered (mode=$currentMode)")
        }
        handler.post { publish(force = true) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RELOAD) {
            handler.post { if (!stopping) handleReload() }
        }
        return START_STICKY
    }

    private fun handleReload() {
        cancelHold()
        thresholds = Prefs.thresholds(this)
        val newMode = Prefs.getMode(this)
        if (newMode != currentMode) {
            // Invalidate before cancellation. The activity gate waits for an executing action.
            clearPendingAction()
            val success = if (currentMode == Prefs.MODE_DIRECT) {
                if (ShimState.overrideClosed || DirectStateController.hasRequest) {
                    DirectStateController.cancel(this)
                } else true
            } else if (ShimState.overrideClosed) {
                ShizukuBridge.exec(Config.ARG_RESET, 1500)?.code == 0
            } else true
            if (!success) {
                ShimState.lastCallbackResult = "reload: old override cancellation failed: ${ShimState.lastCallbackResult.orEmpty()}"
                publish(force = true)
                return
            }
            ShimState.overrideClosed = false
            if (currentMode == Prefs.MODE_SHIZUKU) ShizukuBridge.removeListener(bridgeListener)
            currentMode = newMode
            resetConfirmed = false
            if (currentMode == Prefs.MODE_SHIZUKU) {
                ShizukuBridge.addListener(bridgeListener)
                ShizukuBridge.ensureBound()
            }
        }
        evaluate()
        publish(force = true)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        sensorManager?.unregisterListener(this)
        ShimState.removeListener(stateListener)
        handler.post {
            stopping = true
            ShizukuBridge.removeListener(bridgeListener)
            cancelHold()
            clearPendingAction()
            if (ShimState.overrideClosed ||
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
            ShimState.changed()
            releaseWakeLock()
            handler.removeCallbacksAndMessages(null)
            thread.quitSafely()
        }
        super.onDestroy()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        if (stopping || event.values.isEmpty()) return
        val angle = event.values[0]
        wakeLock.acquire(Config.WAKELOCK_MS)
        lastAngle = angle
        ShimState.angle = angle
        evaluate()
        publish(force = false)
        if (!holdPending) releaseWakeLock()
    }

    private fun evaluate() {
        if (stopping) return
        val angle = lastAngle ?: return
        val (close, open) = thresholds
        when {
            angle >= open -> {
                cancelHold()
                if (pendingAction == null && (ShimState.overrideClosed || !resetConfirmed ||
                    (currentMode == Prefs.MODE_DIRECT && DirectStateController.hasRequest))) {
                    if (applyState(closed = false) && currentMode == Prefs.MODE_SHIZUKU) {
                        resetConfirmed = true
                    }
                }
            }
            angle <= close -> {
                if (pendingAction != null ||
                    (currentMode == Prefs.MODE_DIRECT && DirectStateController.hasRequest)) return
                if (!ShimState.overrideClosed && !holdPending) {
                    if (Config.CLOSE_HOLD_MS == 0L) {
                        applyState(closed = true)
                    } else {
                        holdPending = true
                        wakeLock.acquire(Config.WAKELOCK_MS)
                        handler.postDelayed(holdRunnable, Config.CLOSE_HOLD_MS)
                    }
                }
            }
            else -> cancelHold()
        }
    }

    private fun cancelHold() {
        if (holdPending) {
            holdPending = false
            handler.removeCallbacks(holdRunnable)
        }
    }

    private fun clearPendingAction() {
        pendingId?.let { StateActivity.abandonAction(it) }
        handler.removeCallbacks(pendingTimeout)
        pendingAction = null
        pendingId = null
    }

    private fun completeDirectAction(success: Boolean, error: String?) {
        val action = pendingAction ?: return
        clearPendingAction()
        if (success) {
            if (action == StateActivity.ACTION_OPEN) {
                ShimState.overrideClosed = false
                resetConfirmed = true
            } else {
                resetConfirmed = false
            }
        } else {
            ShimState.lastCallbackResult = error ?: "direct action failed"
        }
        publish(force = true)
        // Reconcile an angle that crossed the opposite threshold during the activity.
        // Failure waits for a new sensor event instead of retrying in a tight loop.
        if (success) evaluate()
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

        val arg = if (closed) Config.ARG_CLOSED else Config.ARG_RESET
        val result = ShizukuBridge.exec(arg, 1500)
        if (result != null && result.code == 0) {
            ShimState.overrideClosed = closed
            Log.i(Config.TAG, "Shizuku mode state change: ${if (closed) "OPEN -> CLOSED" else "CLOSED -> OPEN"} angle=$lastAngle")
        } else {
            ShimState.lastCallbackResult = "Shizuku ${if (closed) "request" else "reset"} failed: $result"
            Log.w(Config.TAG, "Shizuku mode state change to ${if (closed) "CLOSED" else "OPEN"} not applied, result=$result")
        }
        ShimState.changed()
        return result != null && result.code == 0
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun overrideText(): String =
        getString(if (ShimState.overrideClosed) R.string.state_closed else R.string.state_open)

    private fun publish(force: Boolean, notifyState: Boolean = true) {
        if (notifyState) ShimState.changed()
        val angle = lastAngle
        val text = when {
            sensor == null -> getString(R.string.notif_no_sensor)
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
        private const val CHANNEL_ID = "hinge_state"
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

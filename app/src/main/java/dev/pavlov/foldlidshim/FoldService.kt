package dev.pavlov.foldlidshim

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

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

    private val holdRunnable = Runnable {
        holdPending = false
        val angle = lastAngle
        val (close, _) = Prefs.thresholds(this)
        if (angle != null && angle <= close && !ShimState.overrideClosed) {
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
            sensorManager?.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, 0, handler)
            Log.i(Config.TAG, "Service started, hinge sensor registered (mode=$currentMode)")
        }
        handler.post { publish(force = true) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onNewIntent(intent: Intent?) {
        // Handle intents delivered via singleInstance in direct mode
        if (currentMode == Prefs.MODE_DIRECT && intent != null) {
            val action = intent.getStringExtra(StateActivity.EXTRA_ACTION)
            if (action != null) {
                handleDirectAction(action)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        sensorManager?.unregisterListener(this)
        if (currentMode == Prefs.MODE_SHIZUKU) {
            ShizukuBridge.removeListener(bridgeListener)
        }
        handler.removeCallbacksAndMessages(null)
        if (ShimState.overrideClosed) {
            val resetThread = Thread {
                val success = if (currentMode == Prefs.MODE_DIRECT) {
                    DirectStateController.cancel(this)
                } else {
                    ShizukuBridge.exec(Config.ARG_RESET, 2000)?.code == 0
                }
                if (success) {
                    Log.i(Config.TAG, "State change: CLOSED -> OPEN (service stopped)")
                    ShimState.overrideClosed = false
                }
            }
            resetThread.start()
            resetThread.join(4000)
        }
        ShimState.angle = null
        ShimState.overrideClosed = false
        ShimState.changed()
        releaseWakeLock()
        thread.quitSafely()
        super.onDestroy()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        if (event.values.isEmpty()) return
        val angle = event.values[0]
        wakeLock.acquire(Config.WAKELOCK_MS)
        lastAngle = angle
        ShimState.angle = angle
        evaluate()
        publish(force = false)
        if (!holdPending) releaseWakeLock()
    }

    private fun evaluate() {
        val angle = lastAngle ?: return
        val (close, open) = Prefs.thresholds(this)
        when {
            angle >= open -> {
                cancelHold()
                // If we have a pending action, let StateActivity result handle it
                if (pendingAction == null && !ShimState.overrideClosed && !resetConfirmed) {
                    // No override and never reset - this is the first time opening
                    applyState(closed = false)
                    resetConfirmed = true
                } else if (ShimState.overrideClosed && pendingAction == null) {
                    // Already overridden, need to cancel
                    applyState(closed = false)
                    resetConfirmed = true
                }
            }
            angle <= close -> {
                if (!ShimState.overrideClosed && !holdPending) {
                    holdPending = true
                    wakeLock.acquire(Config.WAKELOCK_MS)
                    handler.postDelayed(holdRunnable, Config.CLOSE_HOLD_MS)
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

    /**
     * Handle a direct mode action delivered via onNewIntent.
     */
    private fun handleDirectAction(action: String) {
        Log.i(Config.TAG, "FoldService: handling direct action=$action")
        val closed = (action == StateActivity.ACTION_CLOSED)
        val success = if (closed) {
            DirectStateController.requestClosed(this)
        } else {
            DirectStateController.cancel(this)
        }
        if (success && closed) {
            ShimState.overrideClosed = true
            ShimState.lastCallbackResult = "activated"
        } else if (!closed) {
            ShimState.overrideClosed = false
            ShimState.lastCallbackResult = if (success) "canceled" else null
        } else if (!success) {
            ShimState.lastCallbackResult = ShimState.lastCallbackResult ?: "failed"
        }
    }

    private fun applyState(closed: Boolean): Boolean {
        if (currentMode == Prefs.MODE_DIRECT) {
            try {
                pendingAction = if (closed) StateActivity.ACTION_CLOSED else StateActivity.ACTION_OPEN
                val intent = Intent(this, StateActivity::class.java).apply {
                    putExtra(StateActivity.EXTRA_ACTION, if (closed) StateActivity.ACTION_CLOSED else StateActivity.ACTION_OPEN)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                Log.i(Config.TAG, "Direct mode: launched StateActivity for ${if (closed) "CLOSED" else "OPEN"}")
                // overrideClosed will be set by StateActivity on callback
                return true
            } catch (e: Exception) {
                Log.e(Config.TAG, "Direct mode: failed to launch StateActivity: ${e.message}", e)
                pendingAction = null
                return false
            }
        }

        val arg = if (closed) Config.ARG_CLOSED else Config.ARG_RESET
        val result = ShizukuBridge.exec(arg, 1500)
        if (result != null && result.code == 0) {
            ShimState.overrideClosed = closed
            Log.i(Config.TAG, "Shizuku mode state change: ${if (closed) "OPEN -> CLOSED" else "CLOSED -> OPEN"} angle=$lastAngle")
        } else {
            Log.w(Config.TAG, "Shizuku mode state change to ${if (closed) "CLOSED" else "OPEN"} not applied, result=$result")
        }
        ShimState.changed()
        return result != null && result.code == 0
    }

    /**
     * Called when a direct mode cancel actually completes.
     */
    private fun onDirectCancelCompleted(success: Boolean) {
        pendingAction = null
        if (success) {
            ShimState.overrideClosed = false
            ShimState.lastCallbackResult = "canceled"
            resetConfirmed = true
            ShimState.changed()
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun overrideText(): String =
        getString(if (ShimState.overrideClosed) R.string.state_closed else R.string.state_open)

    private fun publish(force: Boolean) {
        ShimState.changed()
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

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FoldService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FoldService::class.java))
        }
    }
}

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

        ShizukuBridge.addListener(bridgeListener)
        ShizukuBridge.ensureBound()

        sensorManager = getSystemService(SensorManager::class.java)
        sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        val s = sensor
        if (s == null) {
            Log.w(Config.TAG, "TYPE_HINGE_ANGLE sensor not found")
        } else {
            sensorManager?.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, 0, handler)
            Log.i(Config.TAG, "Service started, hinge sensor registered")
        }
        handler.post { publish(force = true) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        sensorManager?.unregisterListener(this)
        ShizukuBridge.removeListener(bridgeListener)
        handler.removeCallbacksAndMessages(null)
        if (ShimState.overrideClosed) {
            val resetThread = Thread {
                if (ShizukuBridge.exec(Config.ARG_RESET, 2000)?.code == 0) {
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
                if ((ShimState.overrideClosed || !resetConfirmed) && applyState(closed = false)) resetConfirmed = true
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

    private fun applyState(closed: Boolean): Boolean {
        val arg = if (closed) Config.ARG_CLOSED else Config.ARG_RESET
        val result = ShizukuBridge.exec(arg, 1500)
        if (result != null && result.code == 0) {
            ShimState.overrideClosed = closed
            Log.i(Config.TAG, "State change: ${if (closed) "OPEN -> CLOSED" else "CLOSED -> OPEN"} angle=$lastAngle")
        } else {
            Log.w(Config.TAG, "State change to ${if (closed) "CLOSED" else "OPEN"} not applied, result=$result")
        }
        ShimState.changed()
        return result != null && result.code == 0
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun overrideText(): String =
        getString(if (ShimState.overrideClosed) R.string.state_closed else R.string.state_open)

    private fun publish(force: Boolean) {
        ShimState.changed()
        val status = ShizukuBridge.status(this)
        val angle = lastAngle
        val text = when {
            sensor == null -> getString(R.string.notif_no_sensor)
            status != ShizukuStatus.READY ->
                getString(R.string.notif_waiting_shizuku, shizukuText(status))
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

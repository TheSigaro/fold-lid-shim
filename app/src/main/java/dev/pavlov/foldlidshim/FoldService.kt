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
import android.os.SystemClock
import android.util.Log
import java.util.UUID

class FoldService : Service() {

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var notificationManager: NotificationManager
    private var sensorManager: SensorManager? = null
    private var angleSensors: AngleSensors? = null
    private var magnetSensors: MagnetSensors? = null
    private val magnetPreview = MagnetPreview()
    private var realHingeAngle: Float? = null
    private var realHingeNs = Long.MIN_VALUE
    private val magnetWatchdog = Runnable {
        magnetPreview.expire(SystemClock.elapsedRealtimeNanos())?.let { p ->
            val event = "magnet ${if (p.closed) "closing" else "opening"} false"
            Timeline.event(event)
            ShimState.magnetEvent = event
            ShimState.magnetFalseCount = magnetPreview.falseCount
            magnetSensors?.detector?.inhibitUntil(magnetPreview.blockedUntilNs)
            evaluate()
            publish(force = true)
        }
    }

    private fun onMagnet(closed: Boolean, tNs: Long, score: Double) {
        fun ignored(reason: String) {
            Timeline.event("magnet ignored closed=$closed timestampNs=$tNs score=$score reason=$reason")
        }
        if (stopping || stopAfterCancel || !Prefs.magnetEnabled(this)) {
            ignored("disabled or stopping")
            return
        }
        // Hinge can overtake a delayed magnetometer callback from the hub.
        if ((closed && realHingeAngle == 0f) || (!closed && (realHingeAngle ?: 0f) >= 90f)) {
            ignored("hinge already arrived")
            return
        }
        val now = SystemClock.elapsedRealtimeNanos()
        // Old hub batches must not change today's display state.
        if (now - tNs !in 0..1_000_000_000L) {
            ignored("stale or future timestamp")
            return
        }
        val (close, open) = Prefs.thresholds(this)
        val desired = motion.previewAngle(if (closed) 0f else 90f, tNs, close, open,
            Prefs.earlyEnabled(this), Prefs.earlyAngle(this))
        if (!magnetPreview.start(closed, tNs, desired)) {
            ignored("pending or inhibited")
            return
        }
        val event = "magnet ${if (closed) "closing" else "opening"} timestampNs=$tNs score=$score"
        Timeline.event(event)
        ShimState.magnetEvent = event
        handler.removeCallbacks(magnetWatchdog)
        handler.postDelayed(magnetWatchdog, ((tNs + magnetPreview.timeoutNs(closed) - now + 999_999L) / 1_000_000L).coerceAtLeast(1))
        evaluate()
        publish(force = true)
    }

    private fun onHinge(tNs: Long, angle: Float) {
        if (!angle.isFinite() || angle !in 0f..180f || tNs <= realHingeNs) return
        realHingeAngle = angle
        realHingeNs = tNs
        magnetSensors?.hinge(tNs, angle)
        magnetPreview.contradict(tNs, angle)?.let {
            handler.removeCallbacks(magnetWatchdog)
            Timeline.event("magnet closing false reason=hinge180 timestampNs=$tNs")
            ShimState.magnetEvent = "magnet closing false reason=hinge180"
            ShimState.magnetFalseCount = magnetPreview.falseCount
            magnetSensors?.detector?.inhibitUntil(magnetPreview.blockedUntilNs)
        }
        val pending = magnetPreview.pending
        magnetPreview.hinge(tNs, angle)?.let { lead ->
            handler.removeCallbacks(magnetWatchdog)
            val event = "magnet ${if (pending?.closed == true) "closing" else "opening"} confirmed leadMs=$lead timestampNs=$tNs"
            Timeline.event(event)
            ShimState.magnetEvent = event
        }
    }
    private var currentSource = Config.DEFAULT_SOURCE
    private var currentInvert = booleanArrayOf(true, false, true)
    private val hall = HallDiagnosis(SystemClock.elapsedRealtime())
    private var hingeSensors: AngleSensors? = null
    private fun updateHallDiagnosis() {
        val (close, open) = Prefs.thresholds(this)
        ShimState.hallDiagnosis = hall.diagnose(SystemClock.elapsedRealtime(), close, open)
    }
    private var lastPublishAt = 0L

    // Touched only on the handler thread.
    private var lastAngle: Float? = null
    private var lastNotifText = ""
    private var lastNotifAt = 0L

    // Track pending StateActivity action to handle its result properly
    private var pendingAction: String? = null
    private var pendingId: String? = null
    @Volatile private var stopping = false
    private var directReady = false
    private var motion = DirectMotion()
    private var manualOpenGeneration = DirectStateController.manualOpenGeneration
    private val cancelRetry = CancelRetry()
    private var stopAfterCancel = false
    private var recoveryNeeded = true
    private var recoveryPending = false
    private var lastSensorZone: Int? = null
    private val retryCancel = Runnable { evaluateDirect(); publish(force = true) }
    private fun evaluateDirect() {
        if (stopping || !directReady) return
        val desiredClosed = magnetPreview.desiredClosed ?: motion.closed
        val angle = magnetPreview.pending?.let { if (it.closed) 0f else 90f } ?: lastAngle ?: return
        ShimState.decision = (if (desiredClosed) "CLOSED" else "Снять CLOSED") +
            (if (magnetPreview.pending != null) " (магнитометр)" else "")
        if (manualOpenGeneration != DirectStateController.manualOpenGeneration) {
            manualOpenGeneration = DirectStateController.manualOpenGeneration
            motion.failed() // Keep a manual cancellation until the next direction change.
        }
        val (_, open) = Prefs.thresholds(this)
        val opening = stopAfterCancel || (!desiredClosed && angle >= open)
        // Abandon under the activity gate before reading ownership: an in-flight
        // binder call must either complete first or never execute.
        if (opening && pendingAction == StateActivity.ACTION_CLOSED) clearPendingAction()
        val owns = DirectStateController.hasClosedRequest
        if (recoveryNeeded && ShimState.directCurrentState != null &&
            (owns || ShimState.directCurrentState != 0 || angle >= open)) {
            recoveryNeeded = false
            recoveryPending = !owns && ShimState.directCurrentState == 0 && angle >= open
        }
        if (recoveryPending && ShimState.directCurrentState != 0) {
            recoveryPending = false
            if (pendingAction == StateActivity.ACTION_OPEN) clearPendingAction()
        }
        val needsCancel = owns || recoveryPending
        handler.removeCallbacks(retryCancel)
        if (opening && needsCancel) {
            if (pendingAction == null && cancelRetry.due(SystemClock.elapsedRealtime(), true, true)) {
                applyState(false)
            }
            handler.postDelayed(retryCancel, 1000L)
            return
        }
        cancelRetry.due(SystemClock.elapsedRealtime(), false, false)
        if (stopAfterCancel && !needsCancel) { stopSelf(); return }

        if (pendingAction != null || stopAfterCancel) return
        if (ShimState.hallDiagnosis == HallDiagnosis.Result.STUCK_CLOSED) {
            ShimState.lastCallbackResult = StateActivity.OPEN_UNAVAILABLE
            return
        }
        if (desiredClosed && !motion.failedClosed) applyState(true)
    }

    private val pendingTimeout = Runnable {
        completeDirectAction(false, "direct action timeout after 3000ms")
    }
    private val stateListener = Runnable {
        handler.post {
            if (!stopping) {
                if (!DirectStateController.hasRequest) hall.observe(ShimState.directUnrequestedState)
                updateHallDiagnosis()
                evaluateDirect()

                publish(force = true, notifyState = false)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ShimState.serviceRunning = true
        ShimState.magnetFalseCount = 0
        ShimState.magnetEvent = "нет событий"
        Timeline.event("service onCreate")
        DirectStateController.tryRegisterCallback(applicationContext)
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

        Log.i(Config.TAG, "Starting screen switching")

        sensorManager = getSystemService(SensorManager::class.java)
        handler.post {
            magnetSensors = sensorManager?.let { manager ->
                MagnetSensors(this, manager, handler, object : MagnetFoldDetector.Listener {
                    override fun closing(tNs: Long, score: Double) = onMagnet(true, tNs, score)
                    override fun opening(tNs: Long, score: Double) = onMagnet(false, tNs, score)
                }).also { it.reload() }
            }
            if (Prefs.angleSource(this) != Config.SOURCE_HINGE) {
                hingeSensors = sensorManager?.let { manager ->
                    AngleSensors(manager, currentInvert, Config.SOURCE_HINGE) { _, angle ->
                        hall.onAngle(angle)
                        onHinge(hingeSensors?.timestampNs ?: SystemClock.elapsedRealtimeNanos(), angle)
                    }
                        .also { it.start(handler) }
                }
            }
            registerAngleSource()
        }
        handler.post { publish(force = true) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timeline.event("service start nullIntent=${intent == null}")
        if (Prefs.restartBlocked(this) || (intent == null && Prefs.recordRestart(this, System.currentTimeMillis()))) {
            ShimState.lastCallbackResult = RestartGuard.MESSAGE
            Timeline.event(RestartGuard.MESSAGE)
            handler.post {
                directReady = true
                stopAfterCancel = true
                if (lastAngle == null) lastAngle = Prefs.openDeg(this)
                evaluateDirect()
                if (!DirectStateController.hasRequest && pendingAction == null) stopSelf()
            }
            return START_NOT_STICKY
        }
        if (!Prefs.isEnabled(this) && intent?.action != ACTION_STOP) {
            handler.post {
                directReady = true
                stopAfterCancel = true
                if (lastAngle == null) lastAngle = Prefs.openDeg(this)
                evaluateDirect()
                if (!DirectStateController.hasRequest && pendingAction == null) stopSelf()
            }
            return START_NOT_STICKY
        }

        if (intent?.action != ACTION_STOP)
            handler.post { directReady = true; evaluateDirect() }
        if (intent?.action == ACTION_STOP) {
            handler.post {
                directReady = true
                stopAfterCancel = true
                clearPendingAction()
                if (lastAngle == null) lastAngle = Prefs.openDeg(this)
                evaluateDirect()
            }
        }

        if (intent?.action == ACTION_RELOAD) {
            handler.post { if (!stopping) handleReload() }
        }
        return START_STICKY
    }

    private fun handleReload() {
        magnetPreview.clear()
        handler.removeCallbacks(magnetWatchdog)
        magnetSensors?.detector?.resetWindow()
        magnetSensors?.reload()
        val source = Prefs.angleSource(this)
        val invert = Prefs.invertAxes(this)
        if (source != currentSource || !invert.contentEquals(currentInvert)) {
            registerAngleSource()
        }

        evaluate()
        publish(force = true)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopping = true
        ShimState.serviceRunning = false
        ShimState.directConfirmationPending = false
        ShimState.removeListener(stateListener)
        handler.post {
            stopping = true
            magnetSensors?.close()
            angleSensors?.stop()
            hingeSensors?.stop()
            clearPendingAction()

            ShimState.overrideClosed = false
            ShimState.angle = null
            ShimState.decision = "Служба остановлена"
            ShimState.changed()
            handler.removeCallbacksAndMessages(null)
            thread.quitSafely()
        }
        super.onDestroy()
    }

    private fun registerAngleSource() {
        angleSensors?.stop()
        currentSource = Prefs.angleSource(this)
        currentInvert = Prefs.invertAxes(this)
        hingeSensors?.stop()
        hingeSensors = if (currentSource != Config.SOURCE_HINGE) sensorManager?.let { manager ->
            AngleSensors(manager, currentInvert, Config.SOURCE_HINGE) { _, angle ->
                hall.onAngle(angle)
                onHinge(hingeSensors?.timestampNs ?: SystemClock.elapsedRealtimeNanos(), angle)
            }
                .also { it.start(handler) }
        } else null

        lastAngle = null
        ShimState.angle = null
        ShimState.decision = "Ожидание угла"
        clearPendingAction()
        angleSensors = sensorManager?.let { manager ->
            AngleSensors(manager, currentInvert, currentSource) { _, angle -> onAngle(angle) }
                .also { it.start(handler) }
        }
    }

    private fun onAngle(angle: Float) {
        if (stopping || !angle.isFinite()) return
        lastAngle = angle
        val preserveFailure = magnetPreview.desiredClosed == true && motion.failedClosed
        if (currentSource == Config.SOURCE_HINGE) {
            hall.onAngle(angle)
            onHinge(angleSensors?.timestampNs ?: SystemClock.elapsedRealtimeNanos(), angle)
        }
        ShimState.angle = angle
        val (close, open) = Prefs.thresholds(this)
        val before = motion.closed
        val timestamp = angleSensors?.timestampNs ?: SystemClock.elapsedRealtimeNanos()
        motion.angle(angle, timestamp, close, open, Prefs.earlyEnabled(this), Prefs.earlyAngle(this))
        if (preserveFailure && motion.closed) motion.failed()
        val zone = (if (angle <= close) 1 else 0) or
            (if (angle >= open) 2 else 0) or
            (if (angle <= Prefs.earlyAngle(this)) 4 else 0)
        if (before != motion.closed || zone != lastSensorZone) {
            Timeline.event("sensor timestampNs=$timestamp receivedNs=${angleSensors?.receivedNs} angle=$angle zone=$zone desiredClosed=${motion.closed}")
            lastSensorZone = zone
        }
        ShimState.decision = if (motion.closed) "CLOSED" else "Снять CLOSED"

        evaluate()
        val now = SystemClock.elapsedRealtime()
        if (now - lastPublishAt >= 200) {
            lastPublishAt = now
            publish(force = false)
        }
    }

    private fun evaluate() {
        if (stopping) return
        evaluateDirect()
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
        val closed = action == StateActivity.ACTION_CLOSED
        // A request can have executed just before its activity result times out.
        // hasRequest means accepted (possibly suspended), and still needs cancellation.
        val applied = success || (closed && DirectStateController.hasRequest)
        val wasRecovery = !closed && recoveryPending
        if (closed && !applied && (magnetPreview.desiredClosed ?: motion.closed)) motion.failed()
        if (!closed && recoveryPending) {
            recoveryPending = false // A dead owner's request has no callback here; recover once.
            if (!success) ShimState.lastCallbackResult = "Не удалось снять оставшийся запрос: ${error.orEmpty()}"
        }
        Timeline.event("activity result closed=$closed success=$success error=$error")

        if (!success) {
            ShimState.lastCallbackResult = if (wasRecovery)
                "Не удалось снять оставшийся запрос: ${error ?: "direct action failed"}"
                else error ?: "direct action failed"
        }
        publish(force = true)
        // Reconcile an angle that crossed the opposite threshold during the activity.
        // Failure waits for a new sensor event instead of retrying in a tight loop.
        evaluateDirect()
    }

    private fun applyState(closed: Boolean): Boolean {
        if (pendingAction != null) return false
        if (DirectPolicy.decide(closed, ShimState.directCurrentState,
                DirectStateController.hasClosedRequest) == DirectPolicy.Command.NONE &&
            !(!closed && recoveryPending)) {
            return true
        }
        Timeline.event("decision ${if (closed) "CLOSED" else "CANCEL"}")
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
            Timeline.event("startActivity action=$pendingAction id=$id")
            startActivity(Intent(this, StateActivity::class.java).apply {
                putExtra(StateActivity.EXTRA_ACTION, pendingAction)
                putExtra(StateActivity.EXTRA_ID, id)
                putExtra(StateActivity.EXTRA_RECEIVER, receiver)
                putExtra(StateActivity.EXTRA_DEADLINE, SystemClock.elapsedRealtime() + DIRECT_TIMEOUT_MS)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            })
            return true
        } catch (e: Exception) {
            Log.e(Config.TAG, "Failed to launch StateActivity", e)
            completeDirectAction(false, "failed to launch StateActivity: ${e.message}")
            return false
        }
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

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_rotate)
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_SECRET)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "hinge_state_min"
        private const val NOTIF_ID = 1
        private const val DIRECT_TIMEOUT_MS = 3000L
        const val ACTION_RELOAD = "reload"
        const val ACTION_STOP = "stop"

        fun reload(context: Context) {
            context.startService(Intent(context, FoldService::class.java).apply { action = ACTION_RELOAD })
        }

        fun start(context: Context) {
            Prefs.manualStart(context)
            context.startForegroundService(Intent(context, FoldService::class.java))
        }

        fun stop(context: Context) {
            if (ShimState.serviceRunning) {
                context.startService(Intent(context, FoldService::class.java).setAction(ACTION_STOP))
            } else context.stopService(Intent(context, FoldService::class.java))
        }
    }
}

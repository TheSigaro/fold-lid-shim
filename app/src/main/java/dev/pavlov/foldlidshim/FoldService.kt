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
        if (stopping || stopAfterCancel || reloadAfterCancel || !Prefs.magnetEnabled(this)) {
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
    private var forceOpened = true
    private val shizukuOverrideActive get() = ShizukuBridge.ownOverride
    private val hall = HallDiagnosis(SystemClock.elapsedRealtime())
    private var hingeSensors: AngleSensors? = null
    private val reconcileTick = object : Runnable {
        override fun run() {
            if (stopping) return
            if (currentMode == Prefs.MODE_SHIZUKU) {
                hall.observe(ShizukuBridge.releaseMatchingOverride()?.baseState)
                updateHallDiagnosis()
                evaluate()
                publish(force = true)
            } else {
                observeDeviceState()
                evaluate()
                publish(force = true)
            }
            handler.postDelayed(this, 30_000)
        }
    }

    private fun observeDeviceState() {
        if (currentMode == Prefs.MODE_DIRECT) {
            if (!DirectStateController.hasRequest) hall.observe(ShimState.directUnrequestedState)
        } else hall.observe(ShizukuBridge.readDeviceState()?.baseState)
        updateHallDiagnosis()
    }

    private fun updateHallDiagnosis() {
        val (close, open) = Prefs.thresholds(this)
        ShimState.hallDiagnosis = hall.diagnose(SystemClock.elapsedRealtime(), close, open)
    }
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
    @Volatile private var stopping = false
    private var directReady = false
    private var motion = DirectMotion()
    private var manualOpenGeneration = DirectStateController.manualOpenGeneration
    private val cancelRetry = CancelRetry()
    private var stopAfterCancel = false
    private var reloadAfterCancel = false
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
        val opening = stopAfterCancel || reloadAfterCancel || (!desiredClosed && angle >= open)
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
        if (reloadAfterCancel && !needsCancel) {
            reloadAfterCancel = false
            handleReload()
            return
        }
        if (pendingAction != null || stopAfterCancel || reloadAfterCancel) return
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
                if (currentMode == Prefs.MODE_DIRECT) {
                    if (!DirectStateController.hasRequest) hall.observe(ShimState.directUnrequestedState)
                    updateHallDiagnosis()
                    evaluateDirect()
                }
                publish(force = true, notifyState = false)
            }
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
        ShimState.serviceRunning = true
        ShimState.magnetFalseCount = 0
        ShimState.magnetEvent = "нет событий"
        Timeline.event("service onCreate")
        currentMode = Prefs.getMode(this)
        if (currentMode == Prefs.MODE_DIRECT) DirectStateController.tryRegisterCallback(applicationContext)
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
        if (currentMode == Prefs.MODE_SHIZUKU) createWakeLock()

        if (currentMode == Prefs.MODE_SHIZUKU) {
            ShizukuBridge.init()
            ShizukuBridge.addListener(bridgeListener)
            ShizukuBridge.ensureBound()
            ensureAutoStarter()
        } else {
            Log.i(Config.TAG, "Running in direct mode, Shizuku not needed")
        }

        sensorManager = getSystemService(SensorManager::class.java)
        handler.post {
            magnetSensors = sensorManager?.let { manager ->
                MagnetSensors(this, manager, handler, object : MagnetFoldDetector.Listener {
                    override fun closing(tNs: Long, score: Double) = onMagnet(true, tNs, score)
                    override fun opening(tNs: Long, score: Double) = onMagnet(false, tNs, score)
                }).also { it.reload() }
            }
            if (currentMode == Prefs.MODE_SHIZUKU || Prefs.angleSource(this) != Config.SOURCE_HINGE) {
                hingeSensors = sensorManager?.let { manager ->
                    AngleSensors(manager, currentInvert, Config.SOURCE_HINGE) { _, angle ->
                        hall.onAngle(angle)
                        onHinge(hingeSensors?.timestampNs ?: SystemClock.elapsedRealtimeNanos(), angle)
                    }
                        .also { it.start(handler) }
                }
            }
            registerAngleSource()
            if (currentMode == Prefs.MODE_SHIZUKU) reconcileTick.run()
        }
        handler.post { publish(force = true) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (currentMode == Prefs.MODE_DIRECT) {
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
        }
        if (currentMode == Prefs.MODE_DIRECT && intent?.action != ACTION_STOP)
            handler.post { directReady = true; evaluateDirect() }
        if (intent?.action == ACTION_STOP) {
            handler.post {
                directReady = true
                stopAfterCancel = true
                clearPendingAction()
                if (currentMode == Prefs.MODE_DIRECT) {
                    if (lastAngle == null) lastAngle = Prefs.openDeg(this)
                    evaluateDirect()
                } else stopSelf()
            }
        }
        if (intent?.action == ACTION_START_SHIZUKU && currentMode == Prefs.MODE_SHIZUKU) {
            ensureAutoStarter().requestNow()
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
        cancelHold()
        loadThresholds()
        val newMode = Prefs.getMode(this)
        if (newMode != currentMode) {
            if (currentMode == Prefs.MODE_DIRECT && DirectStateController.hasRequest) {
                reloadAfterCancel = true
                clearPendingAction()
                evaluateDirect()
                return
            }
            clearPendingAction()
            val success = if (currentMode == Prefs.MODE_DIRECT) {
                true
            } else if (shizukuOverrideActive || ShimState.overrideClosed) {
                ShizukuBridge.exec(Config.ARG_RESET, 1500)?.code == 0
            } else true
            if (!success) {
                ShimState.lastCallbackResult = "reload: old override cancellation failed: ${ShimState.lastCallbackResult.orEmpty()}"
                publish(force = true)
                return
            }
            ShimState.overrideClosed = false
            if (currentMode == Prefs.MODE_SHIZUKU) ShizukuBridge.removeListener(bridgeListener)
            stopAutoStarter()
            currentMode = newMode
            ShizukuBridge.setDirectMode(currentMode == Prefs.MODE_DIRECT)
            handler.removeCallbacks(reconcileTick)
            if (currentMode == Prefs.MODE_SHIZUKU) handler.post(reconcileTick)
            motion = DirectMotion()
            directReady = true
            recoveryNeeded = true
            if (currentMode == Prefs.MODE_DIRECT) {
                DirectStateController.tryRegisterCallback(applicationContext)
                registerAngleSource()
            }
            decider.reset()
            if (currentMode == Prefs.MODE_SHIZUKU) {
                if (!::wakeLock.isInitialized) createWakeLock()
                ShizukuBridge.init()
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
        stopping = true
        ShimState.serviceRunning = false
        ShimState.directConfirmationPending = false
        stopAutoStarter()
        ShimState.removeListener(stateListener)
        handler.post {
            stopping = true
            magnetSensors?.close()
            angleSensors?.stop()
            hingeSensors?.stop()
            ShizukuBridge.removeListener(bridgeListener)
            cancelHold()
            clearPendingAction()
            var success = true
            if (shizukuOverrideActive) {
                success = (ShizukuBridge.exec(Config.ARG_RESET, 2000)?.code == 0) && success
            }
            if (success) ShimState.overrideClosed = false
            else ShimState.lastCallbackResult = ShimState.lastCallbackResult ?: "stop: cancel failed"
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
        if (currentMode == Prefs.MODE_DIRECT) {
            hingeSensors?.stop()
            hingeSensors = if (currentSource != Config.SOURCE_HINGE) sensorManager?.let { manager ->
                AngleSensors(manager, currentInvert, Config.SOURCE_HINGE) { _, angle ->
                    hall.onAngle(angle)
                    onHinge(hingeSensors?.timestampNs ?: SystemClock.elapsedRealtimeNanos(), angle)
                }
                    .also { it.start(handler) }
            } else null
        }
        lastAngle = null
        ShimState.angle = null
        startupGate.reset()
        ShimState.decision = if (currentMode == Prefs.MODE_DIRECT) "Ожидание угла" else "Ожидание 3 событий"
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
        val preserveFailure = magnetPreview.desiredClosed == true && motion.failedClosed
        if (currentSource == Config.SOURCE_HINGE) {
            hall.onAngle(angle)
            onHinge(angleSensors?.timestampNs ?: SystemClock.elapsedRealtimeNanos(), angle)
        }
        ShimState.angle = angle
        startupGate.onAngle(angle)
        if (currentMode == Prefs.MODE_DIRECT) {
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
        }
        if (currentMode == Prefs.MODE_SHIZUKU) {
            if (!startupGate.ready) ShimState.decision = "Ожидание 3 событий: ${startupGate.eventCount}/3"
            else if (ShimState.decision.startsWith("Ожидание")) ShimState.decision = "Между порогами"
        }
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
        if (stopping) return
        if (currentMode == Prefs.MODE_DIRECT) { evaluateDirect(); return }
        if (!startupGate.ready) return
        if (currentMode == Prefs.MODE_SHIZUKU) {
            val (close, open) = Prefs.thresholds(this)
            ShimState.hallDiagnosis = hall.diagnose(SystemClock.elapsedRealtime(), close, open)
            if (ShimState.hallDiagnosis == HallDiagnosis.Result.STUCK_CLOSED) {
                ShimState.decision = "Ручное управление - используйте плитку Экран Fold"
                return
            }
        }
        val angle = magnetPreview.pending?.let { if (it.closed) 0f else 90f } ?: lastAngle ?: return
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
                applyState(closed = true)
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
        val wasRecovery = !closed && recoveryPending
        if (currentMode == Prefs.MODE_DIRECT) {
            if (closed && !applied && (magnetPreview.desiredClosed ?: motion.closed)) motion.failed()
            if (!closed && recoveryPending) {
                recoveryPending = false // A dead owner's request has no callback here; recover once.
                if (!success) ShimState.lastCallbackResult = "Не удалось снять оставшийся запрос: ${error.orEmpty()}"
            }
            Timeline.event("activity result closed=$closed success=$success error=$error")
        } else decider.onApplied(closed, applied)
        if (!success) {
            ShimState.lastCallbackResult = if (wasRecovery)
                "Не удалось снять оставшийся запрос: ${error ?: "direct action failed"}"
                else error ?: "direct action failed"
        }
        publish(force = true)
        // Reconcile an angle that crossed the opposite threshold during the activity.
        // Failure waits for a new sensor event instead of retrying in a tight loop.
        if (currentMode == Prefs.MODE_DIRECT) evaluateDirect()
        else if (applied) evaluate()
    }

    private fun applyState(closed: Boolean): Boolean {
        if (currentMode == Prefs.MODE_DIRECT) {
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
                Log.e(Config.TAG, "Direct mode: failed to launch StateActivity", e)
                completeDirectAction(false, "failed to launch StateActivity: ${e.message}")
                return false
            }
        }

        observeDeviceState()
        if (ShimState.hallDiagnosis == HallDiagnosis.Result.STUCK_CLOSED) {
            decider.onApplied(closed, false)
            return false
        }
        val ok = ShizukuBridge.reconcile(closed, forceOpened)
        decider.onApplied(closed, ok)
        if (!ok) ShimState.lastCallbackResult = "Не удалось изменить состояние через Shizuku"
        ShimState.changed()
        return ok
    }

    private fun createWakeLock() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FoldLidShim:event")
            .apply { setReferenceCounted(false) }
    }

    private fun releaseWakeLock() {
        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
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
        const val ACTION_START_SHIZUKU = "dev.pavlov.foldlidshim.START_SHIZUKU"
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
            if (Prefs.getMode(context) == Prefs.MODE_DIRECT && ShimState.serviceRunning) {
                context.startService(Intent(context, FoldService::class.java).setAction(ACTION_STOP))
            } else context.stopService(Intent(context, FoldService::class.java))
        }
    }
}

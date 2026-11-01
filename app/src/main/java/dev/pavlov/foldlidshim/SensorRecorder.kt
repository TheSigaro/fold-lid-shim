package dev.pavlov.foldlidshim

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.Executor

/** One bounded session per process. All IO and sensor callbacks share one worker. */
object SensorRecorder {
    data class Status(val recording: Boolean = false, val startNs: Long = 0,
        val bytes: Long = 0, val types: List<Int> = emptyList(), val note: String = "")
    @Volatile var status = Status()
        private set
    private var worker: Handler? = null

    @Synchronized
    fun start(context: Context, onStopped: () -> Unit) {
        if (worker != null) return
        val thread = HandlerThread("SensorRecorder").apply { start() }
        val handler = Handler(thread.looper)
        worker = handler
        val unixMs = System.currentTimeMillis()
        val start = SystemClock.elapsedRealtimeNanos()
        status = Status(true, start)
        handler.post { Session(context.applicationContext, thread, handler, start, unixMs, onStopped).start() }
    }

    @Synchronized fun stop() { worker?.let { handler -> handler.post { session?.stop() } } }
    @Synchronized fun mark(text: String = "Метка") {
        val received = SystemClock.elapsedRealtimeNanos()
        worker?.post { session?.write(SensorRecordingLogic.special(received, "mark", text)) }
    }
    private var session: Session? = null

    private class Session(val context: Context, val thread: HandlerThread, val handler: Handler,
        start: Long, val unixMs: Long, val onStopped: () -> Unit) : SensorEventListener {
        val budget = RecordingBudget(start)
        val manager = context.getSystemService(SensorManager::class.java)
        var writer: BufferedWriter? = null
        var wakeLock: PowerManager.WakeLock? = null
        var finished = false
        var lastState: Int? = null
        val stateSource = RecordingStateSource { state ->
            val received = SystemClock.elapsedRealtimeNanos()
            handler.post {
                if (!finished && state != null && state != lastState) {
                    lastState = state
                    write(SensorRecordingLogic.special(received, "state", state.toString()))
                }
            }
        }
        val flush = object : Runnable {
            override fun run() {
                if (finished) return
                if (budget.expired(SystemClock.elapsedRealtimeNanos())) { stop(); return }
                try { writer?.flush() } catch (e: Exception) { stop("Ошибка записи: ${e.message}"); return }
                handler.postDelayed(this, 1000)
            }
        }

        fun start() {
            session = this
            try {
                wakeLock = context.getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FoldLidShim:recording")
                    .apply { acquire(95_000) }
                writer = File(context.filesDir, "sensors.csv").bufferedWriter(Charsets.UTF_8)
                write(SensorRecordingLogic.header(budget.startNs, unixMs, Build.MODEL))
                if (finished) return
                // getRequiredPermission is hidden from the public SDK. App enables hidden API access.
                // If metadata cannot be read, stop rather than probing protected sensors.
                val permission = Sensor::class.java.getMethod("getRequiredPermission")
                val sensors = SensorRecordingLogic.select(manager.getSensorList(Sensor.TYPE_ALL).map {
                    RecordingSensor(it, it.type, it.reportingMode, permission.invoke(it) as? String, it.isWakeUpSensor)
                })
                val registered = sensors.filter {
                    runCatching { manager.registerListener(this, it, 0, 0, handler) }.getOrDefault(false)
                }
                status = status.copy(types = registered.map { it.type },
                    note = "Не зарегистрировано: ${sensors.size - registered.size}")
                stateSource.start(context, Executor { handler.post(it) })
                stateSource.error?.let { status = status.copy(note = "${status.note}; $it") }
                handler.postDelayed(flush, 1000)
                handler.postDelayed({ stop() },
                    ((budget.startNs + budget.durationNs - SystemClock.elapsedRealtimeNanos())
                        .coerceAtLeast(0) + 999_999L) / 1_000_000L)
            } catch (e: Exception) { stop("Ошибка записи: ${e.message}") }
        }

        fun write(line: String) {
            if (finished) return
            if (!budget.accept(SystemClock.elapsedRealtimeNanos(), line)) { stop(); return }
            try {
                writer!!.write(line)
                status = status.copy(bytes = budget.bytes)
                if (budget.bytes == budget.maxBytes) stop()
            } catch (e: Exception) { stop("Ошибка записи: ${e.message}") }
        }

        override fun onSensorChanged(event: SensorEvent) {
            val received = SystemClock.elapsedRealtimeNanos()
            write(SensorRecordingLogic.event(received, event.timestamp, event.sensor.type,
                event.sensor.name, event.values))
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        fun stop(note: String = status.note) {
            if (finished) return
            finished = true
            manager.unregisterListener(this)
            stateSource.stop()
            handler.removeCallbacksAndMessages(null)
            var result = note
            try { writer?.close() } catch (e: Exception) { result = "Ошибка закрытия: ${e.message}" }
            wakeLock?.let { if (it.isHeld) it.release() }
            synchronized(SensorRecorder) {
                status = status.copy(recording = false,
                    bytes = File(context.filesDir, "sensors.csv").length(), note = result)
                session = null
                worker = null
            }
            thread.quitSafely()
            onStopped()
        }
    }
}

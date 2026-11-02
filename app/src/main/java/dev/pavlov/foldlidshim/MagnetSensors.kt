package dev.pavlov.foldlidshim

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.SystemClock
import java.util.Locale

/** All methods and sensor callbacks run on FoldService's HandlerThread, never on main. */
class MagnetSensors(
    private val context: Context,
    private val manager: SensorManager,
    private val handler: Handler,
    listener: MagnetFoldDetector.Listener
) : SensorEventListener {
    private val mag = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)?.takeUnless { it.isWakeUpSensor }
    private val gyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.takeUnless { it.isWakeUpSensor }
    private val interrupt = manager.getSensorList(Sensor.TYPE_ALL).firstOrNull {
        !it.isWakeUpSensor && it.name.contains("interrupt_gyro", ignoreCase = true)
    }
    private var calibration = Prefs.magnetCalibration(context)
    val detector = MagnetFoldDetector(
        MagnetParams(hardIron = calibration?.h ?: MagnetParams().hardIron), listener
    ) {
        calibration = it
        Prefs.saveMagnetCalibration(context, it)
        Timeline.event("magnet calibration H=${it.h} rms=${it.rms} R=${it.radius} span=${it.span}")
        publish()
    }
    private var enabled = false
    private var economy = true
    private var running = false
    private var interruptRegistered = false
    private var angle: Float? = null
    private var hingeNs = Long.MIN_VALUE
    private var idleSince = 0L
    private var error = ""
    private val idle = Runnable { updatePower() }

    fun reload() {
        enabled = Prefs.magnetEnabled(context)
        economy = Prefs.magnetEconomy(context)
        if (enabled && economy && interrupt != null && !interruptRegistered) {
            interruptRegistered = manager.registerListener(this, interrupt, 10_000, 0, handler)
        } else if ((!enabled || !economy) && interruptRegistered) {
            interrupt?.let { manager.unregisterListener(this, it) }
            interruptRegistered = false
        }
        updatePower()
    }

    fun hinge(t: Long, value: Float) {
        if (t <= hingeNs || !value.isFinite() || value !in 0f..180f) return
        hingeNs = t
        if (angle != value) idleSince = t
        angle = value
        detector.onHinge(t, value)
        updatePower()
    }

    private fun updatePower() {
        handler.removeCallbacks(idle)
        val now = SystemClock.elapsedRealtimeNanos()
        val flexIdle = angle == 90f && interruptRegistered && now - idleSince >= 10_000_000_000L
        val wanted = enabled && (!economy || (angle != null && angle!! <= 90f && !flexIdle))
        if (wanted && !running) {
            error = ""
            running = mag != null && manager.registerListener(this, mag, 10_000, 0, handler)
            if (running) {
                if (gyro == null || !manager.registerListener(this, gyro, 10_000, 0, handler)) error = "; нет гироскопа, R=I"
            } else error = "; магнитометр недоступен"
            Timeline.event("magnet sensors on=$running$error")
        } else if (!wanted && running) {
            mag?.let { manager.unregisterListener(this, it) }
            gyro?.let { manager.unregisterListener(this, it) }
            running = false
            detector.resetWindow()
            Timeline.event("magnet sensors on=false")
        }
        if (enabled && economy && angle == 90f && interruptRegistered && !flexIdle) {
            handler.postDelayed(idle, ((idleSince + 10_000_000_000L - now) / 1_000_000L).coerceAtLeast(1))
        }
        publish()
    }

    private fun publish() {
        val h = detector.hardIron
        val m = detector.params.direction
        val status = String.format(Locale.ROOT,
            "Датчики %s%s; H=(%.1f, %.1f, %.1f) %s; M=(%.2f, %.2f, %.2f)",
            if (running) "вкл" else "выкл", error, h.x, h.y, h.z,
            calibration?.let { "калибровка rms=%.2f".format(Locale.ROOT, it.rms) } ?: "сид", m.x, m.y, m.z)
        if (ShimState.magnetSensors != status) {
            ShimState.magnetSensors = status
            ShimState.changed()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        when (event.sensor) {
            interrupt -> {
                idleSince = event.timestamp
                updatePower()
            }
            mag -> if (running && v.size >= 3) detector.onMag(event.timestamp, v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
            gyro -> if (running && v.size >= 3) detector.onGyro(event.timestamp, v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    fun close() {
        handler.removeCallbacks(idle)
        manager.unregisterListener(this)
        detector.resetWindow()
        running = false
        publish()
    }
}

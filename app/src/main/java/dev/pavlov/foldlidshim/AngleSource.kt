package dev.pavlov.foldlidshim

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler

interface AngleSource {
    val id: String
    val sensors: List<Sensor>
    val available: Boolean
    val angle: Float?
    /** Null means no valid new output. */
    fun onEvent(event: SensorEvent): Float?
}

class SingleAngleSource(
    override val id: String,
    sensor: Sensor?
) : AngleSource {
    override val sensors = listOfNotNull(sensor)
    override val available get() = sensors.isNotEmpty()
    override var angle: Float? = null
        private set

    override fun onEvent(event: SensorEvent): Float? {
        if (event.sensor !in sensors) return null
        val value = event.values.firstOrNull()
        angle = value?.takeIf { it.isFinite() && it in 0f..180f }
        return angle
    }
}

class AccelerometerAngleSource(
    main: Sensor?, sub: Sensor?, private val invert: BooleanArray
) : AngleSource {
    override val id = Config.SOURCE_ACCEL
    override val sensors = listOfNotNull(main, sub)
    override val available = main != null && sub != null
    private val mainSensor = main
    private val subSensor = sub
    private var mainValues: FloatArray? = null
    private var subValues: FloatArray? = null
    private val window = ArrayDeque<Float>()
    var unsmoothedAngle: Float? = null
        private set
    override var angle: Float? = null
        private set

    override fun onEvent(event: SensorEvent): Float? {
        if (event.sensor == mainSensor) mainValues = event.values.copyOf()
        else if (event.sensor == subSensor) subValues = event.values.copyOf()
        else return null
        val a = mainValues ?: return null
        val b = subValues ?: return null
        val value = HingeAngleMath.angleBetween(a, b, invert)
        unsmoothedAngle = value
        if (!value.isFinite()) return null
        window.addLast(value)
        if (window.size > 5) window.removeFirst()
        return window.average().toFloat().also { angle = it }
    }
}

/** One listener for all selected sensors, including both accelerometers. */
class AngleSensors(
    private val manager: SensorManager,
    invert: BooleanArray,
    selected: String? = null,
    private val onAngle: (String, Float) -> Unit = { _, _ -> }
) : SensorEventListener {
    data class Reading(val values: List<Float>, val timestampNs: Long)
    val sources: List<AngleSource>
    private val readings = mutableMapOf<Sensor, Reading>()
    private val registered = mutableSetOf<Sensor>()

    init {
        val all = manager.getSensorList(Sensor.TYPE_ALL)
        val samsung = all.firstOrNull { !it.isWakeUpSensor && it.stringType == Config.SAMSUNG_STRING_TYPE }
            ?: all.firstOrNull { !it.isWakeUpSensor && it.type == Config.SAMSUNG_TYPE }
            ?: all.firstOrNull { !it.isWakeUpSensor && it.name.contains("Folding Angle", ignoreCase = true) }
        val sub = all.firstOrNull { it.name.contains(Config.SUB_NAME, ignoreCase = true) }
        sources = listOf(
            SingleAngleSource(Config.SOURCE_SAMSUNG, samsung),
            AccelerometerAngleSource(manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), sub, invert.copyOf()),
            SingleAngleSource(Config.SOURCE_HINGE, manager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE))
        ).filter { selected == null || it.id == selected }
    }

    val available: Boolean get() = sources.any { source ->
        source.available && source.sensors.all { it in registered }
    }

    fun start(handler: Handler) {
        for (sensor in sources.flatMap { it.sensors }.distinct()) {
            if (manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST, 0, handler))
                registered.add(sensor)
        }
    }

    fun stop() {
        manager.unregisterListener(this)
        registered.clear()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor !in registered) return
        readings[event.sensor] = Reading(event.values.toList(), event.timestamp)
        for (source in sources) source.onEvent(event)?.let { onAngle(source.id, it) }
    }

    /** Called on the same handler as sensor callbacks. Timestamps are seconds since boot. */
    fun debugText(): String = buildString {
        for (source in sources) {
            val ready = source.available && source.sensors.all { it in registered }
            append("${source.id}: ${if (ready) "зарегистрирован" else "недоступен"}, угол=${source.angle ?: "-"}°\n")
            if (source is AccelerometerAngleSource)
                append("До сглаживания: ${source.unsmoothedAngle ?: "-"}°\n")
            for (sensor in source.sensors) {
                val reading = readings[sensor]
                append("${sensor.name} (${sensor.stringType}): ${reading?.values ?: "-"}\n")
                append("Последнее событие, с от загрузки: ${reading?.timestampNs?.div(1_000_000_000.0) ?: "-"}\n")
            }
        }
    }
}

package dev.pavlov.foldlidshim

import kotlin.math.*

data class MagnetVector(val x: Double, val y: Double, val z: Double) {
    operator fun plus(v: MagnetVector) = MagnetVector(x + v.x, y + v.y, z + v.z)
    operator fun minus(v: MagnetVector) = MagnetVector(x - v.x, y - v.y, z - v.z)
    operator fun times(k: Double) = MagnetVector(x * k, y * k, z * k)
    fun dot(v: MagnetVector) = x * v.x + y * v.y + z * v.z
    fun cross(v: MagnetVector) = MagnetVector(y * v.z - z * v.y, z * v.x - x * v.z, x * v.y - y * v.x)
    fun norm() = sqrt(dot(this))
    fun unit() = this * (1.0 / norm())
    fun finite() = x.isFinite() && y.isFinite() && z.isFinite()
}

data class MagnetParams(
    val windowNs: Long = 150_000_000L,
    val closeThreshold: Double = 20.0,
    val openThreshold: Double = 18.0,
    val maxPerpendicularRatio: Double = 0.65,
    val earthRadius: Double = 42.0,
    val magnetStrength: Double = 67.0,
    val fieldTolerance: Double = 20.0,
    val disturbanceHoldNs: Long = 30_000_000_000L,
    val gapNs: Long = 200_000_000L,
    val staleNs: Long = 1_000_000_000L,
    val flexNs: Long = 10_000_000_000L,
    val openingHoldNs: Long = 700_000_000L,
    val hardIron: MagnetVector = MagnetVector(67.0, -30.7, 11.9),
    val direction: MagnetVector = MagnetVector(62.0, -25.0, 1.0).unit()
)

data class MagnetCalibration(val h: MagnetVector, val rms: Double, val radius: Double, val span: MagnetVector)

/** Pure timestamp-driven detector. Call all inputs on one thread. System bias fields are unused. */
class MagnetFoldDetector(
    val params: MagnetParams = MagnetParams(),
    private val listener: Listener,
    private val calibrated: (MagnetCalibration) -> Unit = {}
) {
    interface Listener {
        fun closing(tNs: Long, score: Double)
        fun opening(tNs: Long, score: Double)
    }
    private data class Sample(val t: Long, val v: MagnetVector)
    private val direction = params.direction.unit()
    private val mag = ArrayDeque<Sample>()
    private val gyro = ArrayDeque<Sample>()
    private val calibration = ArrayDeque<MagnetVector>()
    var hardIron = params.hardIron
        private set
    var lastScore = 0.0
        private set
    var lastCalibration: MagnetCalibration? = null
        private set
    private var latest = Long.MIN_VALUE
    private var hingeTime = Long.MIN_VALUE
    private var hinge: Float? = null
    private var ninety: Long? = null
    private var closedAt: Long? = null
    private var didClose = false
    private var didOpen = false
    /** Closing needs a full open (hinge 180) since the last hinge 0: the field rebounds right after an opening. */
    private var openedSinceClosed = false
    private var lastCalibrationSample: Long? = null
    private var lastFit: Long? = null
    private var blockedUntil = Long.MIN_VALUE
    private var disturbedUntil = Long.MIN_VALUE
    private var previewWasOpened = false

    init {
        require(params.windowNs > 0 && params.gapNs > 0 && params.staleNs >= params.gapNs)
        require(params.closeThreshold.isFinite() && params.closeThreshold > 0)
        require(params.openThreshold.isFinite() && params.openThreshold > 0)
        require(params.maxPerpendicularRatio.isFinite() && params.maxPerpendicularRatio > 0)
        require(params.earthRadius.isFinite() && params.earthRadius > 0)
        require(params.magnetStrength.isFinite() && params.magnetStrength > 0)
        require(params.fieldTolerance.isFinite() && params.fieldTolerance >= 0 && params.disturbanceHoldNs >= 0)
        require(params.hardIron.finite() && params.direction.finite() && params.direction.norm() > 0)
    }

    private fun fresh(t: Long): Boolean {
        if (latest != Long.MIN_VALUE && t < latest - params.staleNs) return false
        latest = maxOf(latest, t)
        return true
    }

    fun resetWindow() { mag.clear(); gyro.clear(); lastScore = 0.0 }
    fun inhibitUntil(tNs: Long) {
        blockedUntil = tNs
        didOpen = false
        didClose = false
        // A rejected preview must not consume the real hinge cycle.
        openedSinceClosed = hinge == 180f || (hinge != 0f && (openedSinceClosed || previewWasOpened))
        previewWasOpened = false
        resetWindow()
    }

    fun onHinge(tNs: Long, angle: Float) {
        if (!angle.isFinite() || angle !in 0f..180f || tNs <= hingeTime) return
        // The first on-change hinge can be cached from long before sensor registration.
        if (hinge != null && !fresh(tNs)) return
        latest = maxOf(latest, tNs)
        hingeTime = tNs
        if (angle == 90f) ninety = tNs
        if (angle == 0f && hinge != 0f) closedAt = tNs
        if (angle == 0f) openedSinceClosed = false
        if (angle >= 90f) didOpen = false
        if (angle == 180f) { didClose = false; openedSinceClosed = true }
        hinge = angle
    }

    fun onGyro(tNs: Long, wx: Double, wy: Double, wz: Double) {
        val v = MagnetVector(wx, wy, wz)
        if (!v.finite() || !fresh(tNs) || (gyro.lastOrNull()?.t ?: Long.MIN_VALUE) >= tNs) return
        gyro.addLast(Sample(tNs, v))
        val retention = maxOf(500_000_000L, params.windowNs + params.gapNs)
        while (gyro.isNotEmpty() && gyro.first().t < tNs - retention) gyro.removeFirst()
    }

    fun onMag(tNs: Long, x: Double, y: Double, z: Double) {
        val b = MagnetVector(x, y, z)
        if (!b.finite() || !fresh(tNs)) return
        val previous = mag.lastOrNull()
        if (previous != null && tNs <= previous.t) return
        if (previous != null && tNs - previous.t > params.gapNs) resetWindow()
        // Even the closed phone cannot exceed Earth + lid magnet + tolerance.
        // Keep a quiet interval after foreign fields: their removal can imitate a lid opening.
        if ((b - hardIron).norm() > params.earthRadius + params.magnetStrength + params.fieldTolerance) {
            disturbedUntil = tNs + params.disturbanceHoldNs
        }
        if (hinge == 180f && tNs >= disturbedUntil) collectCalibration(tNs, b)
        mag.addLast(Sample(tNs, b))
        val target = tNs - params.windowNs
        // Retain about 300 ms, including an anchor at or before t-W. No look-ahead.
        val oldest = tNs - maxOf(300_000_000L, params.windowNs)
        while (mag.size > 1 && mag.elementAt(1).t <= oldest) mag.removeFirst()
        val anchor = mag.lastOrNull { it.t <= target } ?: return
        val earth = rotate(anchor.v - hardIron, anchor.t, tNs)
        val delta = (b - hardIron) - earth
        lastScore = delta.dot(direction)
        if (tNs < blockedUntil || tNs < disturbedUntil) return
        if ((delta - direction * lastScore).norm() > params.maxPerpendicularRatio * abs(lastScore)) return
        if (lastScore >= params.closeThreshold && hinge != null && hinge!! <= 90f &&
            ninety?.let { tNs - it in 0..params.flexNs } == true && !didClose && openedSinceClosed) {
            previewWasOpened = openedSinceClosed
            didClose = true
            didOpen = false
            openedSinceClosed = false
            closedAt = tNs
            listener.closing(tNs, lastScore)
        } else if (lastScore <= -params.openThreshold && hinge == 0f &&
            closedAt?.let { tNs - it >= params.openingHoldNs } == true && !didOpen) {
            didOpen = true
            didClose = false
            listener.opening(tNs, lastScore)
        }
    }

    private fun rotate(start: MagnetVector, from: Long, to: Long): MagnetVector {
        val samples = gyro.filter { it.t in from..to }
        if (samples.isEmpty()) return start
        var value = start
        var time = from
        var omega = gyro.lastOrNull { it.t <= from }?.v ?: samples.first().v
        for (sample in samples) {
            value = step(value, omega, (sample.t - time) * 1e-9)
            omega = sample.v
            time = sample.t
        }
        return step(value, omega, (to - time) * 1e-9)
    }

    private fun step(v: MagnetVector, omega: MagnetVector, dt: Double): MagnetVector {
        val speed = omega.norm()
        if (speed < 1e-9 || dt <= 0) return v
        val axis = omega * (1.0 / speed)
        val a = -speed * dt
        // Rodrigues preserves the norm, including on fast rotations.
        return v * cos(a) + axis.cross(v) * sin(a) + axis * (axis.dot(v) * (1 - cos(a)))
    }

    private fun collectCalibration(t: Long, b: MagnetVector) {
        if (lastFit == null) lastFit = t
        if (lastCalibrationSample?.let { t - it >= 100_000_000L } != false &&
            calibration.lastOrNull()?.let { (b - it).norm() >= 3.0 } != false) {
            lastCalibrationSample = t
            calibration.addLast(b)
            if (calibration.size > 2000) calibration.removeFirst()
        }
        if (t - lastFit!! < 30_000_000_000L) return
        lastFit = t
        fitSphere(calibration.toList())?.let {
            hardIron = it.h
            lastCalibration = it
            resetWindow()
            calibrated(it)
        }
    }

    companion object {
        /** Least squares sphere, pivoted 4x4 normal equations. Reject poor coverage/conditioning. */
        fun fitSphere(points: List<MagnetVector>): MagnetCalibration? {
            if (points.size < 30 || points.any { !it.finite() }) return null
            val span = MagnetVector(points.maxOf { it.x } - points.minOf { it.x },
                points.maxOf { it.y } - points.minOf { it.y }, points.maxOf { it.z } - points.minOf { it.z })
            if (minOf(span.x, span.y, span.z) < 40) return null
            val a = Array(4) { DoubleArray(5) }
            for (p in points) {
                val row = doubleArrayOf(2 * p.x, 2 * p.y, 2 * p.z, 1.0)
                for (i in 0..3) {
                    for (j in 0..3) a[i][j] += row[i] * row[j]
                    a[i][4] += row[i] * p.dot(p)
                }
            }
            for (i in 0..3) {
                val pivot = (i..3).maxBy { abs(a[it][i]) }
                val swap = a[i]; a[i] = a[pivot]; a[pivot] = swap
                val d = a[i][i]
                if (abs(d) < 1e-8) return null
                for (j in i..4) a[i][j] /= d
                for (k in 0..3) if (k != i) {
                    val factor = a[k][i]
                    for (j in i..4) a[k][j] -= factor * a[i][j]
                }
            }
            val h = MagnetVector(a[0][4], a[1][4], a[2][4])
            val radius = sqrt(a[3][4] + h.dot(h))
            val rms = sqrt(points.sumOf { ((it - h).norm() - radius).pow(2) } / points.size)
            if (!radius.isFinite() || !rms.isFinite() || radius !in 35.0..65.0 || rms > 5.0) return null
            return MagnetCalibration(h, rms, radius, span)
        }
    }
}

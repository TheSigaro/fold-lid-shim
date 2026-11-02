package dev.pavlov.foldlidshim

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val NAME = "foldlidshim"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MODE = "mode"
    private const val KEY_CLOSE = "close_deg"
    private const val KEY_OPEN = "open_deg"

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = sp(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun magnetEnabled(context: Context): Boolean = sp(context).getBoolean("magnet_enabled", true)
    fun magnetEconomy(context: Context): Boolean = sp(context).getBoolean("magnet_economy", true)
    fun setMagnetEnabled(context: Context, value: Boolean) {
        sp(context).edit().putBoolean("magnet_enabled", value).apply()
    }
    fun setMagnetEconomy(context: Context, value: Boolean) {
        sp(context).edit().putBoolean("magnet_economy", value).apply()
    }
    fun magnetCalibration(context: Context): MagnetCalibration? {
        val p = sp(context)
        if (!p.contains("magnet_hx")) return null
        val h = MagnetVector(p.getFloat("magnet_hx", 67f).toDouble(),
            p.getFloat("magnet_hy", -30.7f).toDouble(), p.getFloat("magnet_hz", 11.9f).toDouble())
        val rms = p.getFloat("magnet_rms", Float.NaN).toDouble()
        val radius = p.getFloat("magnet_radius", Float.NaN).toDouble()
        if (!h.finite() || !rms.isFinite() || rms !in 0.0..5.0 || radius !in 35.0..65.0) return null
        return MagnetCalibration(h, rms, radius, MagnetVector(0.0, 0.0, 0.0))
    }
    fun saveMagnetCalibration(context: Context, value: MagnetCalibration) {
        sp(context).edit().putFloat("magnet_hx", value.h.x.toFloat())
            .putFloat("magnet_hy", value.h.y.toFloat()).putFloat("magnet_hz", value.h.z.toFloat())
            .putFloat("magnet_rms", value.rms.toFloat()).putFloat("magnet_radius", value.radius.toFloat()).apply()
    }

    fun earlyEnabled(context: Context): Boolean = sp(context).getBoolean("early_enabled", true)
    fun earlyAngle(context: Context): Float = sp(context).getFloat("early_angle", 45f)
        .takeIf { it.isFinite() && it in 15f..90f }?.coerceAtLeast(closeDeg(context)) ?: 45f
    fun setEarly(context: Context, enabled: Boolean, angle: Float): Boolean {
        if (!angle.isFinite() || angle !in 15f..90f || angle < closeDeg(context)) return false
        sp(context).edit().putBoolean("early_enabled", enabled).putFloat("early_angle", angle).apply()
        return true
    }
    fun restartBlocked(context: Context): Boolean = sp(context).getBoolean("restart_blocked", false)
    fun recordRestart(context: Context, now: Long): Boolean {
        val previous = sp(context).getString("restarts", "").orEmpty().split(",").mapNotNull { it.toLongOrNull() }
        val recent = RestartGuard.recent(previous, now)
        val blocked = recent.size >= 2
        sp(context).edit().putString("restarts", recent.joinToString(","))
            .putBoolean("restart_blocked", blocked).apply()
        if (blocked) setEnabled(context, false)
        return blocked
    }
    fun manualStart(context: Context) {
        sp(context).edit().remove("restarts").putBoolean("restart_blocked", false).apply()
        if (ShimState.lastCallbackResult == RestartGuard.MESSAGE) ShimState.lastCallbackResult = null
    }

    const val MODE_DIRECT = "direct"
    const val MODE_SHIZUKU = "shizuku"

    fun getMode(context: Context): String =
        sp(context).getString(KEY_MODE, MODE_SHIZUKU) ?: MODE_SHIZUKU

    fun setMode(context: Context, mode: String) {
        val valid = if (mode == MODE_DIRECT || mode == MODE_SHIZUKU) true else false
        if (valid) sp(context).edit().putString(KEY_MODE, mode).apply()
    }

    fun angleSource(context: Context): String =
        sp(context).getString("angle_source", Config.DEFAULT_SOURCE)
            ?.takeIf { it in listOf(Config.SOURCE_SAMSUNG, Config.SOURCE_ACCEL, Config.SOURCE_HINGE) }
            ?: Config.DEFAULT_SOURCE

    fun setAngleSource(context: Context, source: String) {
        if (source in listOf(Config.SOURCE_SAMSUNG, Config.SOURCE_ACCEL, Config.SOURCE_HINGE))
            sp(context).edit().putString("angle_source", source).apply()
    }

    fun disableWirelessAfterStart(context: Context): Boolean = sp(context).getBoolean("disable_adb_after_start", true)
    fun setDisableWirelessAfterStart(context: Context, value: Boolean) {
        sp(context).edit().putBoolean("disable_adb_after_start", value).apply()
    }

    fun forceOpened(context: Context): Boolean = sp(context).getBoolean("force_opened", true)
    fun setForceOpened(context: Context, value: Boolean) {
        sp(context).edit().putBoolean("force_opened", value).apply()
    }

    fun invertAxes(context: Context): BooleanArray = BooleanArray(3) {
        sp(context).getBoolean("invert_sub_$it", it != 1)
    }

    fun setInvertAxes(context: Context, axes: BooleanArray) {
        val editor = sp(context).edit()
        for (i in 0..2) editor.putBoolean("invert_sub_$i", axes[i])
        editor.apply()
    }

    fun closeDeg(context: Context): Float = thresholds(context).first

    fun openDeg(context: Context): Float = thresholds(context).second

    /** Returns (close, open); falls back to defaults if stored values are inconsistent. */
    fun thresholds(context: Context): Pair<Float, Float> {
        val p = sp(context)
        val close = p.getFloat(KEY_CLOSE, Config.DEFAULT_CLOSE_DEG)
        val open = p.getFloat(KEY_OPEN, Config.DEFAULT_OPEN_DEG)
        return if (isValid(close, open)) close to open
        else Config.DEFAULT_CLOSE_DEG to Config.DEFAULT_OPEN_DEG
    }

    fun isValid(close: Float, open: Float): Boolean =
        validateThresholds(close, open)

    fun setThresholds(context: Context, close: Float, open: Float): Boolean {
        if (!isValid(close, open)) return false
        sp(context).edit().putFloat(KEY_CLOSE, close).putFloat(KEY_OPEN, open).apply()
        return true
    }
}

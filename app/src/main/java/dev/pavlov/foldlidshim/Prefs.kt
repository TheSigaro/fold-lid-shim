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

    const val MODE_DIRECT = "direct"
    const val MODE_SHIZUKU = "shizuku"

    fun getMode(context: Context): String =
        sp(context).getString(KEY_MODE, MODE_DIRECT) ?: MODE_DIRECT

    fun setMode(context: Context, mode: String) {
        val valid = if (mode == MODE_DIRECT || mode == MODE_SHIZUKU) true else false
        if (valid) sp(context).edit().putString(KEY_MODE, mode).apply()
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

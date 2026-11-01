package dev.pavlov.foldlidshim.adb

import android.content.Context

/** Only an imported or successfully paired identity may be used for auto-start. */
object AdbIdentity {
    private fun preferences(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("adb_identity", Context.MODE_PRIVATE)
    fun exists(context: Context) = preferences(context).let { it.contains("adbkey") && it.getBoolean("adb_key_paired", false) }
    fun status(context: Context): String = preferences(context).let {
        when {
            !exists(context) -> if (it.contains("adbkey")) "нет (нужен импорт)" else "нет"
            it.getBoolean("adb_key_imported", false) -> "импортирован"
            else -> "сопряжён"
        }
    }
    @Synchronized fun load(context: Context): AdbKey {
        check(exists(context)) { "Нет ключа adb - импортируйте ключ или выполните сопряжение" }
        return AdbKey(PreferenceAdbKeyStore(preferences(context)), "foldlidshim")
    }
    @Synchronized fun save(context: Context, bytes: ByteArray, imported: Boolean) {
        val prefs = preferences(context)
        PreferenceAdbKeyStore(prefs).putPaired(bytes, imported)
    }
}

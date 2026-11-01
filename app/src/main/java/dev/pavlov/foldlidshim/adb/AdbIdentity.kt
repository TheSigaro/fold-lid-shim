package dev.pavlov.foldlidshim.adb

import android.content.Context

/** A single identity, accessible before first unlock; never silently replaced on read errors. */
object AdbIdentity {
    private fun preferences(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("adb_identity", Context.MODE_PRIVATE)
    fun exists(context: Context) = preferences(context).contains("adbkey")
    @Synchronized fun load(context: Context, create: Boolean = false): AdbKey {
        check(create || exists(context)) { "Нет ключа adb - выполните сопряжение" }
        return AdbKey(PreferenceAdbKeyStore(preferences(context)), "foldlidshim")
    }
}

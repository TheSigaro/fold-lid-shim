package dev.pavlov.foldlidshim

import android.app.Application
import org.lsposed.hiddenapibypass.HiddenApiBypass

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            HiddenApiBypass.addHiddenApiExemptions("L")
        } catch (_: Exception) {
        }
        Timeline.init(this)
        ShizukuBridge.setDirectMode(Prefs.getMode(this) == Prefs.MODE_DIRECT)
        if (Prefs.getMode(this) == Prefs.MODE_SHIZUKU) ShizukuBridge.init()
    }
}

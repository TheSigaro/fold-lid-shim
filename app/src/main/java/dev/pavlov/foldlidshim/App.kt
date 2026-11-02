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
    }
}

package dev.pavlov.foldlidshim

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ShizukuBridge.init()
    }
}

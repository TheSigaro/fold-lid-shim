package dev.pavlov.foldlidshim

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs.isEnabled(context)) return
        Log.i(Config.TAG, "BOOT_COMPLETED: starting service")
        context.startForegroundService(Intent(context, FoldService::class.java))
    }
}

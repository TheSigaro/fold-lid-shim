package dev.pavlov.foldlidshim

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // A package update kills the running service; both broadcasts may start a foreground service.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Prefs.isEnabled(context)) return
        Log.i(Config.TAG, "${intent.action}: starting service")
        context.startForegroundService(Intent(context, FoldService::class.java))
    }
}

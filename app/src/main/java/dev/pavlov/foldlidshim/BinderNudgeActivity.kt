package dev.pavlov.foldlidshim

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import rikka.shizuku.Shizuku

/** Brief foreground transition lets the Shizuku server discover this process. */
class BinderNudgeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { finish() }
    private val binderReceived = object : Shizuku.OnBinderReceivedListener {
        override fun onBinderReceived() {
            Shizuku.removeBinderReceivedListener(this)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addBinderReceivedListener(binderReceived)
        handler.postDelayed(timeout, 1_500)
        if (Shizuku.pingBinder()) {
            Shizuku.removeBinderReceivedListener(binderReceived)
            finish()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        Shizuku.removeBinderReceivedListener(binderReceived)
        super.onDestroy()
        // Runs in its own ":nudge" process so the Shizuku server sees a new pid even when
        // the main process is already in the foreground; free that process right away.
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}

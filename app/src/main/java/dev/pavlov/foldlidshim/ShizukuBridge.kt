package dev.pavlov.foldlidshim

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import rikka.shizuku.Shizuku

enum class ShizukuStatus { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

data class ExecResult(val code: Int, val output: String)

/** Owns the Shizuku UserService connection (shell uid) and runs device_state commands through it. */
object ShizukuBridge {
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val BIND_RETRY_MS = 10_000L

    @Volatile private var service: IShellService? = null
    @Volatile private var binding = false
    @Volatile private var bindStartedAt = 0L
    @Volatile private var initialized = false

    private val listeners = CopyOnWriteArrayList<Runnable>()

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(Config.PACKAGE, ShellUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(false)
            .version(1)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
            service = if (binder != null && binder.pingBinder()) IShellService.Stub.asInterface(binder) else null
            binding = false
            Log.i(Config.TAG, "UserService connected: ${service != null}")
            fireChanged()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            binding = false
            Log.i(Config.TAG, "UserService disconnected")
            fireChanged()
        }
    }

    fun addListener(listener: Runnable) {
        listeners.add(listener)
    }

    fun removeListener(listener: Runnable) {
        listeners.remove(listener)
    }

    private fun fireChanged() {
        listeners.forEach { it.run() }
        ShimState.changed()
    }

    /** Call once from Application.onCreate. */
    @Synchronized
    fun init() {
        if (initialized) return
        initialized = true
        Shizuku.addBinderReceivedListenerSticky {
            Log.i(Config.TAG, "Shizuku binder received")
            ensureBound()
            fireChanged()
        }
        Shizuku.addBinderDeadListener {
            Log.i(Config.TAG, "Shizuku binder dead")
            service = null
            binding = false
            fireChanged()
        }
    }

    fun status(context: Context): ShizukuStatus {
        val alive = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
        if (!alive) {
            val installed = try {
                context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
            return if (installed) ShizukuStatus.NOT_RUNNING else ShizukuStatus.NOT_INSTALLED
        }
        val granted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
        return if (granted) ShizukuStatus.READY else ShizukuStatus.NO_PERMISSION
    }

    fun ensureBound() {
        try {
            if (!Shizuku.pingBinder()) return
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return
        } catch (t: Throwable) {
            Log.w(Config.TAG, "Shizuku check failed: $t")
            return
        }
        if (service != null) return
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (service != null) return
            if (binding && now - bindStartedAt < BIND_RETRY_MS) return
            binding = true
            bindStartedAt = now
        }
        try {
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (t: Throwable) {
            Log.e(Config.TAG, "bindUserService failed: $t")
            binding = false
        }
    }

    /** Blocking. Returns null if the shell service is not available within waitMs. */
    fun exec(arg: String, waitMs: Long): ExecResult? {
        val deadline = SystemClock.elapsedRealtime() + waitMs
        var svc = service
        while (svc == null) {
            ensureBound()
            svc = service
            if (svc != null || SystemClock.elapsedRealtime() >= deadline) break
            Thread.sleep(50)
        }
        if (svc == null) {
            Log.w(Config.TAG, "cmd device_state state $arg: shell service unavailable")
            return null
        }
        return try {
            val raw = svc.runDeviceState(arg) ?: ""
            val first = raw.substringBefore('\n')
            val code = first.removePrefix("exit=").toIntOrNull() ?: -4
            val out = raw.substringAfter('\n', "").trim()
            Log.i(Config.TAG, "cmd device_state state $arg: code=$code output=\"$out\"")
            ExecResult(code, out)
        } catch (t: Throwable) {
            Log.e(Config.TAG, "cmd device_state state $arg: binder call failed: $t")
            null
        }
    }
}

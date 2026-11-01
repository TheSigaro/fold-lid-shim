package dev.pavlov.foldlidshim

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import dev.pavlov.foldlidshim.adb.AdbClient
import dev.pavlov.foldlidshim.adb.AdbIdentity
import dev.pavlov.foldlidshim.adb.AdbMdns
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** One serialized worker per service lifetime; network callbacks never perform blocking IO. */
class ShizukuAutoStarter(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val policy = RetryPolicy()
    private val startedAt = SystemClock.elapsedRealtime()
    private var failures = 0
    private var pending: Future<*>? = null
    private var running = false
    private var wifiPending = false
    @Volatile private var closed = false
    private var registered = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { requestNow("Wi-Fi подключён") }
    }

    init {
        try {
            // Each Wi-Fi connection triggers an attempt, including an already connected network.
            connectivity.registerNetworkCallback(NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
            registered = true
        } catch (e: Exception) { record("Wi-Fi callback", e.message) }
        requestNow("Старт службы")
    }

    @Synchronized fun requestNow(reason: String = "Ручной запуск") {
        if (closed) return
        if (!policy.mayAttempt(elapsed())) { record("Остановлено", "Истекли 30 минут после старта службы"); return }
        if (running) { wifiPending = true; return }
        pending?.cancel(false)
        pending = executor.schedule({ attempt(reason) }, 0, TimeUnit.MILLISECONDS)
    }

    private fun elapsed() = SystemClock.elapsedRealtime() - startedAt
    private fun record(stage: String, error: String? = null) {
        val state = AutoStartAttempt(System.currentTimeMillis(), stage, error)
        ShimState.autoStartAttempt = state
        Log.i(Config.TAG, "Shizuku autostart: $stage${error?.let { ": $it" }.orEmpty()}")
        ShimState.changed()
    }
    private fun checkActive() {
        check(!closed && !Thread.currentThread().isInterrupted && policy.mayAttempt(elapsed())) {
            "Автозапуск остановлен: служба закрыта или истекли 30 минут"
        }
    }
    private fun attempt(reason: String) {
        synchronized(this) {
            if (closed || running) return
            running = true
            pending = null
        }
        var retry = false
        try {
            checkActive()
            if (Shizuku.pingBinder()) { record("Shizuku уже работает"); return }
            record(reason)
            if (!AdbIdentity.exists(context)) { record("Ключ", "Нет ключа adb - выполните сопряжение"); return }
            if (!hasSecureSettings(context)) { record("Разрешение", "Нет WRITE_SECURE_SETTINGS"); return }
            retry = true
            check(connectivity.allNetworks.any {
                connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }) { "Wi-Fi не подключён" }
            val key = AdbIdentity.load(context)
            record("Включение беспроводной отладки")
            val wasEnabled = Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
            setWireless(true)
            val port = AdbMdns(context, AdbMdns.TLS_CONNECT).use { mdns ->
                record("Поиск порта adb")
                var found = if (wasEnabled) mdns.awaitPort(5000) else null
                if (wasEnabled && found == null) {
                    checkActive()
                    record("Переключение беспроводной отладки 0 -> 1")
                    setWireless(false)
                    Thread.sleep(300)
                    setWireless(true)
                }
                if (found == null) found = mdns.awaitPort(20_000)
                found ?: error("Порт adb не найден за 20 секунд")
            }
            checkActive()
            record("Подключение adb: 127.0.0.1:$port")
            var alreadyRunning = false
            // starter may start the server before a shell stream ends with an IO error.
            // Binder is the authoritative success signal in either case.
            val commandError = runCatching {
                AdbClient("127.0.0.1", port, key).use { client ->
                    client.connect()
                    checkActive()
                    record("Запрос binder")
                    client.shellCommand(AdbCommands.nudge(context.packageName), null)
                    alreadyRunning = awaitBinder(3_000)
                    if (!alreadyRunning) {
                        checkActive()
                        val info = context.packageManager.getApplicationInfo("moe.shizuku.privileged.api", 0)
                        record("Запуск сервера Shizuku")
                        val starterError = runCatching {
                            client.shellCommand(AdbCommands.starter(info.nativeLibraryDir, info.sourceDir)) { bytes ->
                                checkActive()
                                Log.i(Config.TAG, "Shizuku starter: ${bytes.toString(Charsets.UTF_8)}")
                            }
                        }.exceptionOrNull()
                        checkActive()
                        // The fresh server registers its process observer with a delay:
                        // a nudge sent at once is missed, so nudge a few times with pauses.
                        val nudgeError = runCatching {
                            repeat(3) {
                                if (Shizuku.pingBinder()) return@repeat
                                Thread.sleep(1_500)
                                checkActive()
                                record("Запрос binder")
                                client.shellCommand(AdbCommands.nudge(context.packageName), null)
                                awaitBinder(2_500)
                            }
                        }.exceptionOrNull()
                        starterError?.let { throw it }
                        nudgeError?.let { throw it }
                    }
                }
            }.exceptionOrNull()
            checkActive()
            if (!alreadyRunning) {
                record("Ожидание binder Shizuku", commandError?.message)
                check(awaitBinder(10_000)) {
                    "Shizuku не прислал binder за 10 секунд${commandError?.let { ": ${it.message}" }.orEmpty()}"
                }
            }
            retry = false
            if (Prefs.disableWirelessAfterStart(context)) {
                record("Выключение беспроводной отладки")
                setWireless(false)
            }
            record(if (alreadyRunning) "Сервер уже работал, binder получен" else "Shizuku запущен")
        } catch (e: Exception) {
            record(ShimState.autoStartAttempt?.stage ?: "Попытка", e.message ?: e.toString())
        } finally {
            synchronized(this) {
                running = false
                if (!closed) {
                    val immediate = wifiPending
                    wifiPending = false
                    if (immediate && policy.mayAttempt(elapsed())) requestNow("Событие Wi-Fi / ручной запуск")
                    else if (retry) {
                        val delay = policy.nextDelayMs(++failures, elapsed())
                        if (delay != null) {
                            record("Повтор через ${delay / 1000} с", ShimState.autoStartAttempt?.error)
                            pending = executor.schedule({ attempt("Повтор $failures") }, delay, TimeUnit.MILLISECONDS)
                        } else record("Остановлено", "Следующий повтор выходит за окно 30 минут")
                    }
                }
            }
        }
    }
    private fun awaitBinder(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!Shizuku.pingBinder() && SystemClock.elapsedRealtime() < deadline) {
            checkActive()
            Thread.sleep(200)
        }
        checkActive()
        return Shizuku.pingBinder()
    }
    private fun setWireless(enabled: Boolean) {
        check(Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", if (enabled) 1 else 0)) {
            "Не удалось изменить adb_wifi_enabled"
        }
    }
    @Synchronized override fun close() {
        closed = true
        pending?.cancel(true)
        if (registered) connectivity.unregisterNetworkCallback(callback)
        executor.shutdownNow()
    }
    companion object {
        fun hasSecureSettings(context: Context) = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    }
}

data class AutoStartAttempt(val timeMs: Long, val stage: String, val error: String?)

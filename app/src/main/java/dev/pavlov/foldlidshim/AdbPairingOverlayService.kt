package dev.pavlov.foldlidshim

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.pavlov.foldlidshim.adb.AdbIdentity
import dev.pavlov.foldlidshim.adb.AdbMdns
import dev.pavlov.foldlidshim.adb.AdbPairingClient
import java.util.concurrent.Executors

/** Started from the visible pairing screen. Overlay owns discovery and is explicitly closeable. */
class AdbPairingOverlayService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var manager: WindowManager
    private var view: LinearLayout? = null
    private var mdns: AdbMdns? = null
    @Volatile private var destroyed = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(WindowManager::class.java)
        try { show() } catch (e: Exception) {
            android.util.Log.e(Config.TAG, "Pairing overlay", e)
            stopSelf()
        }
    }
    private fun show() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            setBackgroundColor(android.graphics.Color.rgb(245, 245, 245))
        }
        val status = TextView(this).apply {
            setTextColor(android.graphics.Color.BLACK)
            text = "Сопряжение adb - откройте код сопряжения в настройках"
        }
        val code = EditText(this).apply {
            hint = "Шестизначный код"
            setTextColor(android.graphics.Color.BLACK)
            setHintTextColor(android.graphics.Color.DKGRAY)
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val pair = Button(this).apply { text = "Связать" }
        val close = Button(this).apply { text = "Закрыть"; setOnClickListener { stopSelf() } }
        listOf(status, code, pair, close).forEach { layout.addView(it) }
        view = layout
        manager.addView(layout, WindowManager.LayoutParams(
            (320 * resources.displayMetrics.density).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; y = (48 * resources.displayMetrics.density).toInt() })
        val discovery = AdbMdns(this, AdbMdns.TLS_PAIRING)
        mdns = discovery
        pair.setOnClickListener {
            val value = code.text.toString()
            if (!value.matches(Regex("[0-9]{6}"))) { status.text = "Введите 6 цифр"; return@setOnClickListener }
            pair.isEnabled = false
            status.text = "Поиск порта и сопряжение..."
            executor.execute {
                val result = runCatching {
                    val key = AdbIdentity.load(this, create = true)
                    val port = discovery.awaitPort(20_000) ?: error("Порт сопряжения не найден. Откройте диалог кода в настройках")
                    AdbPairingClient("127.0.0.1", port, value, key).use {
                        check(it.start()) { "Сопряжение не выполнено - проверьте код" }
                    }
                }
                main.post {
                    if (!destroyed) {
                        status.text = result.fold({ "Сопряжение выполнено" }, { "Ошибка: ${it.message ?: it.javaClass.simpleName}" })
                        code.text.clear()
                        discovery.close()
                        // Keep the result readable before removing the overlay, including failures.
                        main.postDelayed({ stopSelf() }, 5000)
                    }
                }
            }
        }
        main.postDelayed({ stopSelf() }, 5 * 60 * 1000L)
    }
    override fun onDestroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        mdns?.close()
        executor.shutdownNow()
        view?.let { manager.removeView(it) }
        view = null
        super.onDestroy()
    }
}

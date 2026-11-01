package dev.pavlov.foldlidshim

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.WindowInsets
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import dev.pavlov.foldlidshim.adb.*
import java.util.concurrent.Executors

class AdbPairingActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var discovery: AdbMdns? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(this).apply { addView(layout) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        layout.addView(TextView(this).apply { text = "Сопряжение adb"; textSize = 24f })
        layout.addView(TextView(this).apply {
            text = "Основной способ - импорт ключа adb с компьютера на главном экране. Запасной способ: откройте этот экран в split-screen вместе с Настройками. Включите беспроводную отладку и откройте сопряжение по коду. Настройки должны оставаться видимыми и активными, иначе сервер сопряжения закрывается."
        })
        val status = TextView(this)
        val code = EditText(this).apply {
            hint = "Шестизначный код"
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val pair = MaterialButton(this).apply { text = "Связать" }
        layout.addView(code)
        layout.addView(pair)
        layout.addView(status)
        pair.setOnClickListener {
            val value = code.text.toString()
            if (!value.matches(Regex("[0-9]{6}"))) { status.text = "Введите 6 цифр"; return@setOnClickListener }
            pair.isEnabled = false
            status.text = "Поиск порта и сопряжение..."
            worker.execute {
                val result = runCatching {
                    // Generate only in memory. Failed pairing never writes an identity.
                    val store = object : AdbKeyStore {
                        var bytes: ByteArray? = null
                        override fun get() = bytes
                        override fun put(bytes: ByteArray) { this.bytes = bytes }
                    }
                    val key = AdbKey(store, "foldlidshim")
                    val mdns = AdbMdns(this, AdbMdns.TLS_PAIRING)
                    discovery = mdns
                    try {
                        check(!destroyed)
                        val port = mdns.awaitPort(20_000) ?: error("Порт не найден - оставьте Настройки в split-screen")
                        AdbPairingClient("127.0.0.1", port, value, key).use {
                            check(it.start()) { "Сопряжение не выполнено - проверьте код" }
                        }
                        AdbIdentity.save(this, checkNotNull(store.bytes), imported = false)
                    } finally { mdns.close() }
                }
                main.post {
                    if (!destroyed) {
                        status.text = result.fold({ "Сопряжение выполнено" }, { "Ошибка: ${it.message}" })
                        code.text.clear()
                        pair.isEnabled = true
                    }
                }
            }
        }
        setContentView(scroll)
    }

    override fun onDestroy() {
        destroyed = true
        discovery?.close()
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

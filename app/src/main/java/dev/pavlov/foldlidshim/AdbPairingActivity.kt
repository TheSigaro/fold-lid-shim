package dev.pavlov.foldlidshim

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class AdbPairingActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Сопряжение adb"
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        layout.addView(TextView(this).apply {
            text = "Подключитесь к Wi-Fi. Откройте настройки, включите беспроводную отладку и выберите сопряжение по коду. Введите шестизначный код в плавающем окне. Уведомления не нужны."
        })
        layout.addView(Button(this).apply {
            text = "Открыть настройки беспроводной отладки"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@AdbPairingActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")))
                    return@setOnClickListener
                }
                startService(Intent(this@AdbPairingActivity, AdbPairingOverlayService::class.java))
                val intents = listOf(
                    Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"),
                    Intent().setClassName("com.android.settings", "com.android.settings.Settings${'$'}WirelessDebuggingActivity"),
                    Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                )
                for (intent in intents) {
                    try { startActivity(intent); break } catch (_: ActivityNotFoundException) { }
                }
            }
        })
        setContentView(layout)
    }
}

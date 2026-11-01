package dev.pavlov.foldlidshim

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.CheckBox
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

class MainActivity : androidx.appcompat.app.AppCompatActivity() {

    private lateinit var tvShizuku: TextView
    private lateinit var btnPermission: Button
    private lateinit var tvSensor: TextView
    private lateinit var tvAngle: TextView
    private lateinit var tvState: TextView
    private lateinit var swService: CompoundButton
    private lateinit var etClose: EditText
    private lateinit var etOpen: EditText
    private lateinit var btnTest: Button
    private lateinit var rgMode: RadioGroup
    private lateinit var tvOverlay: TextView
    private lateinit var btnOverlay: Button
    private lateinit var tvDsmStatus: TextView
    private lateinit var tvStatus: TextView

    private var debugSensors: AngleSensors? = null

    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, 200)
        }
    }

    private val serviceToggleListener = CompoundButton.OnCheckedChangeListener { _, checked ->
        Prefs.setEnabled(this, checked)
        if (checked) FoldService.start(this) else FoldService.stop(this)
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_SHIZUKU) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) ShizukuBridge.ensureBound()
            runOnUiThread { refresh() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.root)
        val pad = (16 * resources.displayMetrics.density).toInt()
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        root.setPadding(0, pad, 0, pad)

        tvShizuku = findViewById(R.id.tvShizuku)
        btnPermission = findViewById(R.id.btnPermission)
        tvSensor = findViewById(R.id.tvSensor)
        tvAngle = findViewById(R.id.tvAngle)
        tvState = findViewById(R.id.tvState)
        swService = findViewById(R.id.swService)
        etClose = findViewById(R.id.etClose)
        etOpen = findViewById(R.id.etOpen)
        btnTest = findViewById(R.id.btnTest)
        rgMode = findViewById(R.id.rgMode)
        tvOverlay = findViewById(R.id.tvOverlay)
        btnOverlay = findViewById(R.id.btnOverlay)
        tvDsmStatus = findViewById(R.id.tvDsmStatus)
        tvStatus = findViewById(R.id.tvStatus)

        val (close, open) = Prefs.thresholds(this)
        etClose.setText(formatDeg(close))
        etOpen.setText(formatDeg(open))

        // Restore mode selection
        val mode = Prefs.getMode(this)
        if (mode == Prefs.MODE_SHIZUKU) {
            rgMode.check(R.id.rbShizuku)
        } else {
            rgMode.check(R.id.rbDirect)
        }

        btnPermission.setOnClickListener { requestShizukuPermission() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveThresholds() }
        btnTest.setOnClickListener { runTest() }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { openBatterySettings() }
        swService.setOnCheckedChangeListener(serviceToggleListener)
        btnOverlay.setOnClickListener { requestOverlayPermission() }

        rgMode.setOnCheckedChangeListener { _, checkedId ->
            val newMode = if (checkedId == R.id.rbShizuku) Prefs.MODE_SHIZUKU else Prefs.MODE_DIRECT
            applyModeChange(newMode)
        }

        val sources = findViewById<RadioGroup>(R.id.rgSource)
        sources.check(when (Prefs.angleSource(this)) {
            Config.SOURCE_ACCEL -> R.id.rbAccel
            Config.SOURCE_HINGE -> R.id.rbHinge
            else -> R.id.rbSamsung
        })
        sources.setOnCheckedChangeListener { _, id ->
            Prefs.setAngleSource(this, when (id) {
                R.id.rbAccel -> Config.SOURCE_ACCEL
                R.id.rbHinge -> Config.SOURCE_HINGE
                else -> Config.SOURCE_SAMSUNG
            })
            if (isServiceRunning()) FoldService.reload(this)
        }
        findViewById<CompoundButton>(R.id.swForceOpened).apply {
            isChecked = Prefs.forceOpened(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setForceOpened(this@MainActivity, checked)
                if (isServiceRunning()) FoldService.reload(this@MainActivity)
            }
        }
        val axes = Prefs.invertAxes(this)
        listOf(R.id.cbInvertX, R.id.cbInvertY, R.id.cbInvertZ).forEachIndexed { index, id ->
            findViewById<CheckBox>(id).apply {
                isChecked = axes[index]
                setOnCheckedChangeListener { _, checked ->
                    axes[index] = checked
                    Prefs.setInvertAxes(this@MainActivity, axes)
                    restartDebugSensors()
                    if (isServiceRunning()) FoldService.reload(this@MainActivity)
                }
            }
        }

        findViewById<Button>(R.id.btnAdbPairing).setOnClickListener {
            startActivity(Intent(this, AdbPairingActivity::class.java))
        }
        findViewById<Button>(R.id.btnStartShizuku).setOnClickListener {
            startForegroundService(Intent(this, FoldService::class.java).setAction(FoldService.ACTION_START_SHIZUKU))
        }
        findViewById<CompoundButton>(R.id.swDisableAdb).apply {
            isChecked = Prefs.disableWirelessAfterStart(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setDisableWirelessAfterStart(this@MainActivity, checked) }
        }
        findViewById<Button>(R.id.btnImportKey).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }, REQUEST_IMPORT)
        }
        findViewById<Button>(R.id.btnForceOpen).setOnClickListener { forceState(false) }
        findViewById<Button>(R.id.btnForceClose).setOnClickListener { forceState(true) }
        findViewById<Button>(R.id.btnOpenShizuku).setOnClickListener {
            val intent = packageManager.getLaunchIntentForPackage(ShizukuBridge.SHIZUKU_PACKAGE)
            if (intent != null) startActivity(intent)
            else Toast.makeText(this, "Shizuku не установлен", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnSensors).setOnClickListener {
            val details = findViewById<View>(R.id.sensorDetails)
            details.visibility = if (details.visibility == View.GONE) View.VISIBLE else View.GONE
            (it as Button).text = if (details.visibility == View.GONE) "Датчики - раскрыть" else "Датчики - свернуть"
        }
        findViewById<Button>(R.id.btnShizukuNotifications).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ShizukuBridge.SHIZUKU_PACKAGE))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "Настройки уведомлений недоступны", Toast.LENGTH_SHORT).show()
            }
        }
        Shizuku.addRequestPermissionResultListener(permissionListener)
    }

    override fun onStart() {
        super.onStart()
        restartDebugSensors()
        ShizukuBridge.addListener(bridgeListener)
        Thread { ShizukuBridge.readDeviceState(); main.post { refresh() } }.start()
        main.post(tick)
    }

    override fun onStop() {
        debugSensors?.stop()
        debugSensors = null
        main.removeCallbacks(tick)
        ShizukuBridge.removeListener(bridgeListener)
        super.onStop()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }

    /**
     * Apply a mode change: stop old mode, start new mode, update service subscriptions.
     */
    private fun applyModeChange(newMode: String) {
        val oldMode = Prefs.getMode(this)
        if (oldMode == newMode) return
        Prefs.setMode(this, newMode)

        if (newMode == Prefs.MODE_SHIZUKU) ShizukuBridge.ensureBound()

        if (isServiceRunning()) FoldService.reload(this)
        refresh()
    }

    // Bridge listener used by Shizuku mode - must be a field for add/remove
    private val bridgeListener = Runnable {
        main.post {
            evaluateShizukuState()
            refresh()
        }
    }

    private fun evaluateShizukuState() {
        // Placeholder: ShizukuBridge handles its own state evaluation
    }

    private fun restartDebugSensors() {
        debugSensors?.stop()
        debugSensors = AngleSensors(getSystemService(SensorManager::class.java), Prefs.invertAxes(this))
            .also { it.start(main) }
    }

    private fun refresh() {
        val attempt = ShimState.autoStartAttempt
        findViewById<TextView>(R.id.tvAutoStart).text = getString(R.string.autostart_status,
            dev.pavlov.foldlidshim.adb.AdbIdentity.status(this),
            if (ShizukuAutoStarter.hasSecureSettings(this)) "есть" else "нет",
            attempt?.let { "${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it.timeMs))}: ${it.stage}${it.error?.let { error -> ": $error" }.orEmpty()}" } ?: "Попыток ещё не было")
        val mode = Prefs.getMode(this)
        val status = ShizukuBridge.status(this)
        if (status == ShizukuStatus.READY && mode == Prefs.MODE_SHIZUKU) ShizukuBridge.ensureBound()

        tvShizuku.visibility = View.VISIBLE
        tvShizuku.text = getString(R.string.shizuku_label, shizukuText(status))
        btnPermission.visibility = if (status == ShizukuStatus.NO_PERMISSION) View.VISIBLE else View.GONE
        btnPermission.isEnabled = status == ShizukuStatus.NO_PERMISSION
        btnTest.isEnabled = mode == Prefs.MODE_DIRECT || status == ShizukuStatus.READY
        findViewById<Button>(R.id.btnForceOpen).isEnabled = status == ShizukuStatus.READY
        findViewById<Button>(R.id.btnForceClose).isEnabled = status == ShizukuStatus.READY
        val diagnosis = ShimState.hallDiagnosis
        findViewById<TextView>(R.id.tvHall).text = "Датчик Холла: ${diagnosis.label}"
        findViewById<TextView>(R.id.tvAction).text = when {
            !isServiceRunning() -> "Служба остановлена"
            mode == Prefs.MODE_DIRECT -> "Прямое управление по углу - ${ShimState.decision}"
            status != ShizukuStatus.READY -> "Ожидаем Shizuku: ${shizukuText(status)}"
            diagnosis == HallDiagnosis.Result.STUCK_CLOSED -> diagnosis.action
            ShizukuBridge.ownOverride -> "Удерживаем ${if (ShizukuBridge.desiredClosed == true) "CLOSED" else "OPENED"}, сверяем с Холлом"
            else -> diagnosis.action
        }

        tvSensor.text = getString(R.string.sensor_debug,
            debugSensors?.debugText().orEmpty(), ShimState.decision)

        val angle = ShimState.angle
        tvAngle.text = getString(
            R.string.angle_label,
            if (angle == null) getString(R.string.angle_unknown) else getString(R.string.angle_value, angle)
        )
        tvState.text = when (ShizukuBridge.currentState.takeIf { mode == Prefs.MODE_SHIZUKU }) {
            0 -> "Закрыт"
            2, 3 -> "Открыт"
            1 -> "Между порогами"
            4, 5 -> "Два экрана"
            else -> {
                val (close, open) = Prefs.thresholds(this)
                when {
                    angle == null -> "Между порогами"
                    angle <= close -> "Закрыт"
                    angle >= open -> "Открыт"
                    else -> "Между порогами"
                }
            }
        }

        val running = isServiceRunning()
        if (swService.isChecked != running) {
            swService.setOnCheckedChangeListener(null)
            swService.isChecked = running
            swService.setOnCheckedChangeListener(serviceToggleListener)
        }

        // Overlay permission status
        val canDraw = Settings.canDrawOverlays(this)
        tvOverlay.text = getString(R.string.overlay_status, getString(if (canDraw) R.string.overlay_yes else R.string.overlay_no))
        btnOverlay.isEnabled = !canDraw

        // DeviceStateManager availability with reason
        if (mode == Prefs.MODE_DIRECT) {
            val avail = DirectStateController.available(this)
            val reason = DirectStateController.unavailabilityReason()
            tvDsmStatus.text = if (avail) {
                getString(R.string.dsm_status_available)
            } else {
                val base = getString(R.string.dsm_status_unavailable)
                if (!reason.isNullOrEmpty()) "$base ($reason)" else base
            }
        } else {
            tvDsmStatus.text = ""
        }

        // Status text: show last test result and error details
        updateStatusText(mode, status)
    }

    private fun updateStatusText(mode: String, shizukuStatus: ShizukuStatus) {
        val parts = mutableListOf<String>()

        // Show DSM unavailability reason if applicable
        if (mode == Prefs.MODE_DIRECT) {
            val avail = DirectStateController.available(this)
            if (!avail) {
                val reason = DirectStateController.unavailabilityReason()
                if (!reason.isNullOrEmpty()) {
                    parts.add(reason)
                }
            }
        }

        // Show last callback/test result
        val lastResult = ShimState.lastCallbackResult
        if (!lastResult.isNullOrEmpty()) {
            parts.add(lastResult)
        }

        // Show Shizuku status in shizuku mode
        if (mode == Prefs.MODE_SHIZUKU && shizukuStatus != ShizukuStatus.READY) {
            parts.add(shizukuText(shizukuStatus))
        }

        tvStatus.text = if (parts.isEmpty()) "" else parts.joinToString("; ")
    }

    private fun shizukuText(status: ShizukuStatus): String = getString(
        when (status) {
            ShizukuStatus.NOT_INSTALLED -> R.string.shizuku_not_installed
            ShizukuStatus.NOT_RUNNING -> R.string.shizuku_not_running
            ShizukuStatus.NO_PERMISSION -> R.string.shizuku_no_permission
            ShizukuStatus.READY -> R.string.shizuku_ready
        }
    )

    @Suppress("DEPRECATION")
    private fun isServiceRunning(): Boolean {
        val am = getSystemService(ActivityManager::class.java)
        return am.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == FoldService::class.java.name
        }
    }

    private fun requestShizukuPermission() {
        try {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                Toast.makeText(this, R.string.shizuku_no_permission, Toast.LENGTH_SHORT).show()
                return
            }
            Shizuku.requestPermission(REQUEST_SHIZUKU)
        } catch (t: Throwable) {
            Toast.makeText(this, t.toString(), Toast.LENGTH_LONG).show()
        }
    }

    private fun requestOverlayPermission() {
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.overlay_settings_not_found, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveThresholds() {
        val close = etClose.text.toString().replace(',', '.').toFloatOrNull()
        val open = etOpen.text.toString().replace(',', '.').toFloatOrNull()
        val ok = close != null && open != null && Prefs.setThresholds(this, close, open)
        Toast.makeText(
            this,
            if (ok) R.string.thresholds_saved else R.string.thresholds_invalid,
            Toast.LENGTH_SHORT
        ).show()
        if (ok && isServiceRunning()) FoldService.reload(this)
    }

    private fun runTest() {
        val mode = Prefs.getMode(this)
        if (mode == Prefs.MODE_DIRECT) {
            runDirectTest()
        } else {
            runShizukuTest()
        }
    }

    private fun runDirectTest() {
        Toast.makeText(this, R.string.test_started_direct, Toast.LENGTH_SHORT).show()
        Thread {
            val result = DirectStateController.requestClosed(this)
            val errorMsg = ShimState.lastCallbackResult
            reportDirect(if (result) "success" else "failed: ${errorMsg ?: "unknown error"}")
            Thread.sleep(3000)
            val cancelOk = DirectStateController.cancel(this)
            reportDirect(if (cancelOk) "reset success" else "reset failed: ${ShimState.lastCallbackResult ?: "unknown"}")
        }.start()
    }

    private fun runShizukuTest() {
        if (ShizukuBridge.status(this) != ShizukuStatus.READY) {
            Toast.makeText(this, R.string.test_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, R.string.test_started, Toast.LENGTH_SHORT).show()
        Thread {
            report(Config.ARG_CLOSED, ShizukuBridge.exec(Config.ARG_CLOSED, 5000))
            Thread.sleep(3000)
            val openArg = if (Prefs.forceOpened(this)) Config.ARG_OPENED else Config.ARG_RESET
            report(openArg, ShizukuBridge.exec(openArg, 5000))
        }.start()
    }

    private fun reportDirect(text: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle(R.string.btn_test_now)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun report(arg: String, result: ExecResult?) {
        runOnUiThread {
            val text = if (result == null) getString(R.string.test_failed, arg)
            else getString(R.string.test_result, arg, result.code)
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openBatterySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun forceState(closed: Boolean) {
        Thread {
            val arg = if (closed) Config.ARG_CLOSED else Config.ARG_OPENED
            report(arg, ShizukuBridge.exec(arg, 1500))
        }.start()
    }

    @Deprecated("Legacy activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            val result = runCatching {
                val text = contentResolver.openInputStream(uri)?.use {
                    val bytes = it.readNBytes(32_769)
                    require(bytes.size <= 32_768) { "Это не ключ adb" }
                    bytes.toString(Charsets.UTF_8)
                } ?: error("Не удалось прочитать файл")
                val bytes = dev.pavlov.foldlidshim.adb.AdbKeyPem.parse(text)
                dev.pavlov.foldlidshim.adb.AdbIdentity.save(this, bytes, imported = true)
            }
            runOnUiThread {
                findViewById<TextView>(R.id.tvImportHint).text = result.fold(
                    { "Ключ импортирован. Удалите adbkey из Download вручную." },
                    { it.message ?: "Это не ключ adb" })
                refresh()
            }
        }.start()
    }

    private fun formatDeg(v: Float): String =
        if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()

    companion object {
        private const val REQUEST_SHIZUKU = 100
        private const val REQUEST_IMPORT = 101
    }
}

package dev.pavlov.foldlidshim

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
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

class MainActivity : androidx.appcompat.app.AppCompatActivity() {

    private lateinit var tvSensor: TextView
    private lateinit var tvAngle: TextView
    private lateinit var tvState: TextView
    private lateinit var swService: CompoundButton
    private lateinit var etClose: EditText
    private lateinit var etOpen: EditText
    private lateinit var btnTest: Button
    private lateinit var tvOverlay: TextView
    private lateinit var btnOverlay: Button
    private lateinit var tvDsmStatus: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvMagnet: TextView

    private lateinit var recordingControls: SensorRecordingControls
    private var resumed = false
    private var debugSensors: AngleSensors? = null

    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            if (resumed && findViewById<View>(R.id.sensorDetails).visibility == View.VISIBLE)
                main.postDelayed(this, 200)
        }
    }

    private val serviceToggleListener = CompoundButton.OnCheckedChangeListener { _, checked ->
        Prefs.setEnabled(this, checked)
        if (checked) FoldService.start(this) else FoldService.stop(this)
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

        tvSensor = findViewById(R.id.tvSensor)
        tvAngle = findViewById(R.id.tvAngle)
        tvState = findViewById(R.id.tvState)
        swService = findViewById(R.id.swService)
        etClose = findViewById(R.id.etClose)
        etOpen = findViewById(R.id.etOpen)
        btnTest = findViewById(R.id.btnTest)
        tvOverlay = findViewById(R.id.tvOverlay)
        btnOverlay = findViewById(R.id.btnOverlay)
        tvDsmStatus = findViewById(R.id.tvDsmStatus)
        tvStatus = findViewById(R.id.tvStatus)

        val (close, open) = Prefs.thresholds(this)
        etClose.setText(formatDeg(close))
        etOpen.setText(formatDeg(open))

        val controls = etOpen.parent as android.view.ViewGroup
        val early = CheckBox(this).apply {
            text = "Ранний запрос при складывании"
            isChecked = Prefs.earlyEnabled(this@MainActivity)
        }
        val earlyAngle = EditText(this).apply {
            hint = "Угол раннего запроса (15-90°)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(formatDeg(Prefs.earlyAngle(this@MainActivity)))
        }
        controls.addView(early)
        controls.addView(earlyAngle)
        controls.addView(Button(this).apply {
            text = "Сохранить ранний запрос"
            setOnClickListener {
                val value = earlyAngle.text.toString().replace(',', '.').toFloatOrNull()
                if (value == null || !Prefs.setEarly(this@MainActivity, early.isChecked, value))
                    Toast.makeText(this@MainActivity, "Угол должен быть от порога закрытия до 90°, минимум 15°", Toast.LENGTH_LONG).show()
                else if (isServiceRunning()) FoldService.reload(this@MainActivity)
            }
        })
        controls.addView(Button(this).apply {
            text = "Журнал"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("Журнал")
                    .setMessage(Timeline.read().takeLast(24_000)).setPositiveButton(android.R.string.ok, null).show()
            }
        })

        controls.addView(CheckBox(this).apply {
            text = "Магнитометр"
            isChecked = Prefs.magnetEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setMagnetEnabled(this@MainActivity, checked)
                if (isServiceRunning()) FoldService.reload(this@MainActivity)
            }
        })
        controls.addView(CheckBox(this).apply {
            text = "Экономия датчиков"
            contentDescription = "Экономия датчиков: выключать при погашенном экране"
            isChecked = Prefs.magnetEconomy(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setMagnetEconomy(this@MainActivity, checked)
                if (isServiceRunning()) FoldService.reload(this@MainActivity)
            }
        })
        tvMagnet = TextView(this).also { controls.addView(it) }
        recordingControls = SensorRecordingControls(this, controls)

        findViewById<Button>(R.id.btnSave).setOnClickListener { saveThresholds() }
        btnTest.setOnClickListener { runDirectTest() }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { openBatterySettings() }
        swService.setOnCheckedChangeListener(serviceToggleListener)
        btnOverlay.setOnClickListener { requestOverlayPermission() }

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

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.overlay_settings_not_found, Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnForceOpen).setOnClickListener { forceState(false) }
        findViewById<Button>(R.id.btnForceClose).setOnClickListener { forceState(true) }
        findViewById<Button>(R.id.btnSensors).setOnClickListener {
            val details = findViewById<View>(R.id.sensorDetails)
            details.visibility = if (details.visibility == View.GONE) View.VISIBLE else View.GONE
            (it as Button).text = if (details.visibility == View.GONE) "Датчики - раскрыть" else "Датчики - свернуть"
            restartDebugSensors()
            main.removeCallbacks(tick)
            if (details.visibility == View.VISIBLE && resumed) main.post(tick)
        }
    }

    override fun onStart() {
        super.onStart()
        recordingControls.start()
        ShimState.addListener(stateListener)
        DirectStateController.tryRegisterCallback(applicationContext)

        refresh()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        restartDebugSensors()
        main.removeCallbacks(tick)
        if (findViewById<View>(R.id.sensorDetails).visibility == View.VISIBLE) main.post(tick)
    }

    override fun onPause() {
        resumed = false
        debugSensors?.stop()
        debugSensors = null
        main.removeCallbacks(tick)
        super.onPause()
    }

    override fun onStop() {
        recordingControls.stop()
        debugSensors?.stop()
        debugSensors = null
        main.removeCallbacks(tick)
        ShimState.removeListener(stateListener)
        super.onStop()
    }

    private val stateListener = Runnable { main.post { refresh() } }

    private fun restartDebugSensors() {
        debugSensors?.stop()
        debugSensors = null
        if (!resumed || findViewById<View>(R.id.sensorDetails).visibility != View.VISIBLE) return
        debugSensors = AngleSensors(getSystemService(SensorManager::class.java), Prefs.invertAxes(this))
            .also { it.start(main) }
    }

    private fun refresh() {
        tvMagnet.text = "${ShimState.magnetSensors}\n${ShimState.magnetEvent}; ложных: ${ShimState.magnetFalseCount}"
        findViewById<TextView>(R.id.tvAccessibility).text = getString(R.string.accessibility_status,
            if (FoldConfirmAccessibilityService.isEnabled(this)) "включено" else "выключено")
        val diagnosis = ShimState.hallDiagnosis
        findViewById<TextView>(R.id.tvHall).text = "Датчик Холла: ${diagnosis.label}"
        findViewById<TextView>(R.id.tvAction).text = if (!isServiceRunning()) "Служба остановлена"
            else "Прямое управление по углу - ${ShimState.decision}"

        tvSensor.text = getString(R.string.sensor_debug,
            debugSensors?.debugText().orEmpty(), ShimState.decision)

        val angle = ShimState.angle
        tvAngle.text = getString(
            R.string.angle_label,
            if (angle == null) getString(R.string.angle_unknown) else getString(R.string.angle_value, angle)
        )
        val currentState = ShimState.directCurrentState
        tvState.text = when (currentState) {
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

        val running = Prefs.isEnabled(this)
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
        val avail = DirectStateController.available(this)
        val reason = DirectStateController.unavailabilityReason()
        tvDsmStatus.text = if (avail) {
            getString(R.string.dsm_status_available)
        } else {
            val base = getString(R.string.dsm_status_unavailable)
            if (!reason.isNullOrEmpty()) "$base ($reason)" else base
        }

        // Status text: show last test result and error details
        updateStatusText()
    }

    private fun updateStatusText() {
        val parts = mutableListOf<String>()

        // Show DSM unavailability reason if applicable
        val avail = DirectStateController.available(this)
        if (!avail) {
            val reason = DirectStateController.unavailabilityReason()
            if (!reason.isNullOrEmpty()) {
                parts.add(reason)
            }
        }

        // Show last callback/test result
        if (Prefs.restartBlocked(this)) parts.add(RestartGuard.MESSAGE)
        val lastResult = ShimState.lastCallbackResult
        if (!lastResult.isNullOrEmpty()) {
            parts.add(lastResult)
        }

        tvStatus.text = if (parts.isEmpty()) "" else parts.joinToString("; ")
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(): Boolean {
        val am = getSystemService(ActivityManager::class.java)
        return am.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == FoldService::class.java.name
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

    private fun runDirectTest() {
        Toast.makeText(this, R.string.test_started_direct, Toast.LENGTH_SHORT).show()
        StateActivity.launch(this, true)
        main.postDelayed({ StateActivity.launch(this, false) }, 3000L)
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
        StateActivity.launch(this, closed)
    }

    private fun formatDeg(v: Float): String =
        if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
}

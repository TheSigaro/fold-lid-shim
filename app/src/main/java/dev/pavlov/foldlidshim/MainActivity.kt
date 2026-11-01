package dev.pavlov.foldlidshim

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var tvShizuku: TextView
    private lateinit var btnPermission: Button
    private lateinit var tvSensor: TextView
    private lateinit var tvAngle: TextView
    private lateinit var tvState: TextView
    private lateinit var swService: Switch
    private lateinit var etClose: EditText
    private lateinit var etOpen: EditText
    private lateinit var btnTest: Button
    private lateinit var rgMode: RadioGroup
    private lateinit var tvOverlay: TextView
    private lateinit var btnOverlay: Button
    private lateinit var tvDsmStatus: TextView
    private lateinit var tvStatus: TextView

    private val main = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { refresh() }
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, 1000)
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

        Shizuku.addRequestPermissionResultListener(permissionListener)

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        ShimState.addListener(refreshRunnable)
        main.post(tick)
    }

    override fun onStop() {
        main.removeCallbacks(tick)
        ShimState.removeListener(refreshRunnable)
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

        // Unsubscribe from old mode
        if (oldMode == Prefs.MODE_SHIZUKU) {
            ShizukuBridge.removeListener(bridgeListener)
        } else if (oldMode == Prefs.MODE_DIRECT) {
            DirectStateController.tryRegisterCallback(this)
        }

        // Cancel any pending override when switching away from direct mode
        if (newMode != Prefs.MODE_DIRECT && ShimState.overrideClosed) {
            val t = Thread {
                val ok = DirectStateController.cancel(this)
                if (ok) {
                    ShimState.overrideClosed = false
                    ShimState.changed()
                }
            }
            t.start()
            t.join(4000)
        }

        // Subscribe to new mode
        if (newMode == Prefs.MODE_SHIZUKU) {
            ShizukuBridge.addListener(bridgeListener)
            ShizukuBridge.ensureBound()
        } else {
            Log.i(Config.TAG, "Switched to direct mode, Shizuku not needed")
        }

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

    private fun refresh() {
        val mode = Prefs.getMode(this)
        val status = ShizukuBridge.status(this)
        if (status == ShizukuStatus.READY && mode == Prefs.MODE_SHIZUKU) ShizukuBridge.ensureBound()

        // Mode-specific UI
        if (mode == Prefs.MODE_DIRECT) {
            tvShizuku.visibility = View.GONE
            btnPermission.visibility = View.GONE
            // In direct mode, always enable the test button
            btnTest.isEnabled = true
            btnTest.visibility = View.VISIBLE
        } else {
            tvShizuku.visibility = View.VISIBLE
            btnPermission.visibility = View.VISIBLE
            btnTest.isEnabled = status == ShizukuStatus.READY
        }

        if (mode == Prefs.MODE_SHIZUKU) {
            tvShizuku.text = getString(
                R.string.shizuku_label,
                getString(
                    when (status) {
                        ShizukuStatus.NOT_INSTALLED -> R.string.shizuku_not_installed
                        ShizukuStatus.NOT_RUNNING -> R.string.shizuku_not_running
                        ShizukuStatus.NO_PERMISSION -> R.string.shizuku_no_permission
                        ShizukuStatus.READY -> R.string.shizuku_ready
                    }
                )
            )
            btnPermission.isEnabled = status == ShizukuStatus.NO_PERMISSION
        }

        val hasSensor = getSystemService(SensorManager::class.java)
            ?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) != null
        tvSensor.text = getString(
            R.string.sensor_label,
            getString(if (hasSensor) R.string.sensor_present else R.string.sensor_absent)
        )

        val angle = ShimState.angle
        tvAngle.text = getString(
            R.string.angle_label,
            if (angle == null) getString(R.string.angle_unknown) else getString(R.string.angle_value, angle)
        )
        tvState.text = getString(
            R.string.state_label,
            getString(if (ShimState.overrideClosed) R.string.state_closed else R.string.state_open)
        )

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
            report(Config.ARG_RESET, ShizukuBridge.exec(Config.ARG_RESET, 5000))
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

    private fun formatDeg(v: Float): String =
        if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()

    companion object {
        private const val REQUEST_SHIZUKU = 100
        private const val REQUEST_NOTIFICATIONS = 101
    }
}

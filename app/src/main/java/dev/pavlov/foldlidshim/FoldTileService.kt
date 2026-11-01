package dev.pavlov.foldlidshim

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import java.util.UUID
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

class FoldTileService : TileService() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var listening = false
    private val changed = Runnable { main.post { update() } }

    override fun onStartListening() {
        super.onStartListening()
        listening = true
        if (Prefs.getMode(this) == Prefs.MODE_SHIZUKU) ShizukuBridge.addListener(changed)
        ShimState.addListener(changed)
        if (Prefs.getMode(this) == Prefs.MODE_DIRECT) {
            DirectStateController.tryRegisterCallback(applicationContext)
        } else {
            ShizukuBridge.ensureBound()
            worker.execute { ShizukuBridge.readDeviceState(1500); main.post { update() } }
        }
        update()
    }

    override fun onStopListening() {
        listening = false
        ShizukuBridge.removeListener(changed)
        ShimState.removeListener(changed)
        super.onStopListening()
    }

    private fun update() {
        if (!listening) return
        val direct = Prefs.getMode(this) == Prefs.MODE_DIRECT
        val ready = if (direct) DirectStateController.available(this)
            else ShizukuBridge.status(this) == ShizukuStatus.READY
        val actualState = if (direct) ShimState.directCurrentState else ShizukuBridge.currentState
        qsTile?.apply {
            label = "Экран Fold"
            subtitle = if (!ready) { if (direct) "DeviceStateManager недоступен" else "Shizuku не готов" } else when (actualState) {
                0 -> "Закрыт"
                2, 3 -> "Открыт"
                1 -> "Между порогами"
                4, 5 -> "Два экрана"
                else -> "Состояние неизвестно"
            }
            this.state = if (!ready) Tile.STATE_UNAVAILABLE else if (actualState in listOf(2, 3)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (Prefs.getMode(this) == Prefs.MODE_DIRECT) {
            val id = UUID.randomUUID().toString()
            StateActivity.registerAction(id)
            val intent = Intent(this, StateActivity::class.java).apply {
                putExtra(StateActivity.EXTRA_ACTION, StateActivity.ACTION_OPEN)
                putExtra(StateActivity.EXTRA_ID, id)
                putExtra(StateActivity.EXTRA_DEADLINE, SystemClock.elapsedRealtime() + 3000L)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            Timeline.event("startActivity tile CANCEL id=$id")
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            return
        }
        if (ShizukuBridge.status(this) != ShizukuStatus.READY) { update(); return }
        worker.execute {
            if (ShizukuBridge.readDeviceState(1500) == null) {
                main.post { update() }
                return@execute
            }
            val closed = ShizukuBridge.currentState == 0
            ShizukuBridge.exec(if (closed) Config.ARG_OPENED else Config.ARG_CLOSED, 1500)
            main.post { update() }
        }
    }

    override fun onDestroy() {
        ShizukuBridge.removeListener(changed)
        ShimState.removeListener(changed)
        worker.shutdownNow()
        super.onDestroy()
    }
}

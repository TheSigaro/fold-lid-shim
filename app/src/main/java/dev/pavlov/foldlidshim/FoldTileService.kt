package dev.pavlov.foldlidshim

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import java.util.UUID
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.os.Handler
import android.os.Looper

class FoldTileService : TileService() {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var listening = false
    private val changed = Runnable { main.post { update() } }

    override fun onStartListening() {
        super.onStartListening()
        listening = true
        ShimState.addListener(changed)
        DirectStateController.tryRegisterCallback(applicationContext)

        update()
    }

    override fun onStopListening() {
        listening = false
        ShimState.removeListener(changed)
        super.onStopListening()
    }

    private fun update() {
        if (!listening) return
        val ready = DirectStateController.available(this)
        val actualState = ShimState.directCurrentState
        qsTile?.apply {
            label = "Экран Fold"
            subtitle = if (!ready) "DeviceStateManager недоступен" else when (actualState) {
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
    }

    override fun onDestroy() {
        ShimState.removeListener(changed)
        super.onDestroy()
    }
}

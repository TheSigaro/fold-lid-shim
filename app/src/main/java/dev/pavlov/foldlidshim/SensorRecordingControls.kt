package dev.pavlov.foldlidshim

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

internal class SensorRecordingControls(private val activity: MainActivity, parent: ViewGroup) {
    private val handler = Handler(Looper.getMainLooper())
    private val record = Button(activity)
    private val mark = Button(activity)
    private val summary = TextView(activity)
    private var visible = false
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            if (visible) handler.postDelayed(this, 1000)
        }
    }

    init {
        record.setOnClickListener {
            if (SensorRecorder.status.recording) SensorRecorder.stop()
            else try {
                activity.startForegroundService(Intent(activity, SensorRecordingService::class.java))
            } catch (e: Exception) {
                Toast.makeText(activity, "Запись не запущена: ${e.message}", Toast.LENGTH_LONG).show()
            }
            handler.removeCallbacks(tick)
            handler.postDelayed(tick, 100)
        }
        mark.text = "Метка"
        mark.setOnClickListener { SensorRecorder.mark() }
        parent.addView(record)
        parent.addView(mark)
        parent.addView(summary)
        refresh()
    }

    fun start() {
        visible = true
        handler.removeCallbacks(tick)
        handler.post(tick)
    }
    fun stop() {
        visible = false
        handler.removeCallbacks(tick)
    }
    private fun refresh() {
        val status = SensorRecorder.status
        val remaining = ((90_000_000_000L - (SystemClock.elapsedRealtimeNanos() - status.startNs))
            .coerceAtLeast(0) + 999_999_999L) / 1_000_000_000L
        record.text = if (status.recording) "Стоп, осталось $remaining с" else "Запись датчиков"
        mark.isEnabled = status.recording
        summary.text = if (status.startNs == 0L || status.recording) "" else
            "Файл: ${status.bytes} байт; датчиков: ${status.types.size}\n" +
                "Типы: ${status.types.joinToString()}. ${status.note}"
    }
}

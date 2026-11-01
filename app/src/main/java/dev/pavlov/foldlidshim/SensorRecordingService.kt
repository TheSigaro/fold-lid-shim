package dev.pavlov.foldlidshim

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** Keeps continuous sensors available when the recording UI is in the background. */
class SensorRecordingService : Service() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("sensor_recording", "Запись датчиков", NotificationManager.IMPORTANCE_LOW))
        startForeground(12, Notification.Builder(this, "sensor_recording")
            .setSmallIcon(android.R.drawable.ic_menu_save).setContentTitle("Запись датчиков")
            .setContentText("До 90 секунд или 30 МБ").setOngoing(true).build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        SensorRecorder.start(this) {
            Handler(Looper.getMainLooper()).post {
                if (!SensorRecorder.status.recording) stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        SensorRecorder.stop()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}

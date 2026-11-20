package com.bizfunnel.vidloot.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.bizfunnel.vidloot.MainActivity
import com.bizfunnel.vidloot.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps downloads running with the screen off and shows their progress in
 * the notification bar. Stops itself when nothing is downloading.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        val n = build("Starting downloads…", 0, true)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)

        scope.launch {
            DownloadManager.jobs.collectLatest { jobs ->
                val active = jobs.filter { Status.isActive(it.status) || it.status == Status.QUEUED }
                if (active.isEmpty()) {
                    ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collectLatest
                }
                val running = active.filter { Status.isActive(it.status) }
                val title = if (running.size == 1) running[0].title else "Downloading ${running.size.coerceAtLeast(1)} of ${active.size}"
                val pct = if (running.isEmpty()) 0 else running.map { it.percent }.average().toInt()
                val processing = running.isNotEmpty() && running.all { it.status == Status.PROCESSING }
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(title, pct, processing || running.isEmpty()))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun build(title: String, percent: Int, indeterminate: Boolean) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(if (indeterminate) "Working…" else "$percent%")
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "downloads"),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 1

        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
            }
        }

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            } catch (_: Exception) {
                // Android may refuse to start it from the background; downloads still run while the app is open.
            }
        }
    }
}

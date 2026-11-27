package app.youtubu.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Foreground service: keeps the tablet downloading songs (screen off, app closed), shows progress as a notification. */
class DownloadService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var job: Job? = null

    companion object {
        fun start(context: Context) {
            val i = Intent(context, DownloadService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            } catch (_: Exception) {
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("download", "下载儿歌", NotificationManager.IMPORTANCE_LOW))
        }
        val first = notification("准备下载儿歌…")
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, first)

        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "youtubu:download").apply { setReferenceCounted(false); acquire() }
        @Suppress("DEPRECATION")
        wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "youtubu:download").apply { setReferenceCounted(false); acquire() }

        val state = Core.get(this)
        job = Core.scope.launch {
            var last = ""
            while (true) {
                if (!state.store.autoPrecache) {
                    stopSelf()
                    return@launch
                }
                val count = state.offlineVideos.size
                val text = "已存 $count 首 · ${formatSize(Media.cacheUsed())} / ${formatSize(Media.cacheLimit)}"
                val line = text + "\n" + state.precacher.status
                if (line != last) {
                    last = line
                    nm.notify(1, notification(state.precacher.status, text))
                }
                delay(3_000)
            }
        }
    }

    private fun notification(status: String, title: String = "油图布儿歌"): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "download") else Notification.Builder(this)
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return b.setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(status)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        job?.cancel()
        wake?.let { if (it.isHeld) it.release() }
        wifi?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

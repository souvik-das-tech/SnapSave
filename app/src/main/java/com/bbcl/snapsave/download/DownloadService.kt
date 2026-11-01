package com.bbcl.snapsave.download

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
import com.bbcl.snapsave.MainActivity
import com.bbcl.snapsave.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Keeps the process alive (and the CPU and Wi-Fi awake) while anything is downloading,
 * and shows the overall progress as an ongoing notification.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        running = true
        createChannels(this)
        goForeground(Downloads.items.value)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SnapSave:download")
            .apply { acquire(6 * 60 * 60 * 1000L) }
        // Without this, many phones drop Wi-Fi into power-save with the screen off and
        // downloads crawl or stall until the screen comes back on.
        @Suppress("DEPRECATION")
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SnapSave:download")
            ?.apply { setReferenceCounted(false); acquire() }
        scope.launch {
            Downloads.items.sample(1000).collect { items ->
                if (items.none { it.status.isActive }) {
                    // Cleared before stopSelf so a download queued right now starts a fresh service.
                    running = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java).notify(ONGOING_ID, build(items))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(Downloads.items.value)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun goForeground(items: List<DownloadItem>) {
        val n = build(items)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ONGOING_ID, n)
        }
    }

    private fun build(items: List<DownloadItem>): Notification {
        val active = items.filter { it.status.isActive }
        val running = active.filter { it.status.isRunning }
        val title = when (active.size) {
            0 -> "Finishing up"
            1 -> active[0].title ?: "Downloading 1 video"
            else -> "Downloading ${active.size} videos"
        }
        val text = when {
            running.isEmpty() -> "Waiting…"
            active.size == 1 -> statusLine(active[0])
            else -> running.joinToString("  •  ") { "${(it.progress * 100).toInt()}%" }
        }
        val pct = if (running.isEmpty()) 0 else (running.sumOf { it.progress.toDouble() } / running.size * 100).toInt()
        return Notification.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, pct, running.isEmpty() || running.all { it.status == Status.FETCHING })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(this))
            .build()
    }

    companion object {
        private const val CHANNEL_ONGOING = "downloads"
        private const val CHANNEL_DONE = "finished"
        private const val ONGOING_ID = 1

        @Volatile private var running = false

        fun start(context: Context) {
            if (running) return
            try {
                context.startForegroundService(Intent(context, DownloadService::class.java))
            } catch (_: Exception) {
                // Not allowed from the background on Android 12+; the next foreground action retries.
            }
        }

        fun statusLine(item: DownloadItem): String = when (item.status) {
            Status.QUEUED -> "Waiting in queue"
            Status.FETCHING -> "Getting video info…"
            Status.DOWNLOADING -> buildList {
                add("${(item.progress * 100).toInt()}%")
                item.speed?.let(::add)
                if (item.etaSec > 0) add("${formatEta(item.etaSec)} left")
            }.joinToString(" · ")
            Status.PROCESSING -> if (item.pick.audioOnly) "Converting audio…" else "Joining video and audio…"
            Status.PAUSED -> "Paused · ${(item.progress * 100).toInt()}%"
            Status.COMPLETED -> "Completed"
            Status.FAILED -> item.error ?: "Failed"
            Status.CANCELLED -> "Cancelled"
        }

        fun formatEta(s: Long): String =
            if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ONGOING, "Downloads in progress", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        private fun openApp(context: Context) = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** One notification per finished or failed download; tapping a finished one plays it. */
        fun notifyFinished(context: Context, item: DownloadItem) {
            createChannels(context)
            val ok = item.status == Status.COMPLETED
            val tap = Downloads.openIntent(item)?.takeIf { ok }?.let {
                PendingIntent.getActivity(context, item.id.hashCode(), it, PendingIntent.FLAG_IMMUTABLE)
            } ?: openApp(context)
            val n = Notification.Builder(context, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle(if (ok) "Downloaded" else "Download failed")
                .setContentText(item.displayTitle)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            runCatching { context.getSystemService(NotificationManager::class.java).notify(item.id.hashCode(), n) }
        }
    }
}

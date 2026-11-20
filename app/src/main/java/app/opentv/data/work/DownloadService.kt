package app.opentv.data.work

import android.app.Notification
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
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.data.repo.DownloadEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs the download queue, one at a time, as a foreground service: a "Downloading" notification with progress while
 * it works, so Android keeps it (and its network) going with the screen off. Stops itself when the queue is empty.
 * Typed dataSync: Android 15 caps that at six hours a day, far more than a queue of films takes; when the cap is hit
 * (onTimeout) it stops, and the queue carries on next time the app opens.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private var wake: android.os.PowerManager.WakeLock? = null
    private var wifi: android.net.wifi.WifiManager.WifiLock? = null
    private var lastNotified = 0L
    private var refused = false
    @Volatile private var queued = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channel()
        val n = notification("Getting ready", null, 0, 0)
        // Android can refuse (started from the background, or the day's dataSync allowance is used up): stop quietly;
        // the queue carries on when the app next opens.
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ID, n)
        }.onFailure { android.util.Log.w("jaskDl", "not allowed to run now: $it") }.isSuccess
        if (!ok) { refused = true; stopSelf(); return }
        // Keep the CPU and Wi-Fi at full speed while the screen is off (Wi-Fi power saving halves a big download).
        wake = (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "jasktv:download").apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        wifi = runCatching {
            @Suppress("DEPRECATION")
            (applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager)
                .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "jasktv:download").apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (refused) return START_NOT_STICKY
        if (worker?.isActive != true) worker = scope.launch { run() }
        return START_STICKY
    }

    private suspend fun run() {
        val repo = ServiceLocator.get(this).downloadRepository
        try {
            while (true) {
                val next = repo.next() ?: break
                // How many wait behind it, looked at again every few seconds (a season is still being added when it starts).
                val counter = scope.launch { while (true) { queued = runCatching { repo.queueSize() }.getOrDefault(queued); kotlinx.coroutines.delay(5_000) } }
                try { repo.transfer(next) { live -> update(next.title, next.seriesTitle, live, queued) } } finally { counter.cancel() }
                if (repo.isDone(next.id)) finished(next)
            }
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun update(title: String, show: String?, live: DownloadEngine.Live, queued: Int) {
        val now = System.currentTimeMillis()
        if (now - lastNotified < 2_000 && live.phase == DownloadEngine.Phase.RUNNING) return
        lastNotified = now
        val name = show?.let { "$it · $title" } ?: title
        val line = when (live.phase) {
            DownloadEngine.Phase.CONNECTING -> "Connecting"
            DownloadEngine.Phase.RUNNING -> listOfNotNull(
                if (live.total > 0) "${live.bytes * 100 / live.total}%" else null,
                if (live.speed >= 1e6) "%.1f MB/s".format(live.speed / 1e6) else if (live.speed > 0) "%.0f KB/s".format(live.speed / 1e3) else null,
                if (live.total > 0 && live.speed > 0) left(((live.total - live.bytes) / live.speed).toLong()) else null,
            ).joinToString(" · ")
            DownloadEngine.Phase.RETRY_WAIT -> "${live.why ?: "Interrupted"} · trying again in ${((live.retryAt - now) / 1000).coerceAtLeast(0)} s"
            DownloadEngine.Phase.HELD, DownloadEngine.Phase.NO_NETWORK -> live.why ?: "Waiting"
        } + if (queued > 0) " · $queued more queued" else ""
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.notify(ID, notification(name, line, live.bytes, live.total)) }
    }

    /** "Downloaded" notification for each one that finishes (tap: the app). */
    private fun finished(d: app.opentv.data.model.Download) {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(d.seriesTitle?.let { "$it · ${d.title}" } ?: d.title)
            .setContentText("Downloaded · ready to watch offline")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true).setContentIntent(open).build()
        runCatching { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(DONE_BASE + d.id.toInt(), n) }
    }

    private fun left(seconds: Long) = when {
        seconds < 60 -> "under a minute left"
        seconds < 5400 -> "${seconds / 60} min left"
        else -> "${seconds / 3600} h ${seconds % 3600 / 60} min left"
    }

    private fun notification(title: String, text: String?, bytes: Long, total: Long): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { if (total > 0) setProgress(1000, (bytes * 1000 / total).toInt(), false) else setProgress(0, 0, true) }
            .setContentIntent(open)
            .build()
    }

    private fun channel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL) == null)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress of films and episodes being saved"; setShowBadge(false) })
    }

    /** Android 15: the daily dataSync allowance is used up. Stop; the queue carries on when the app next opens. */
    override fun onTimeout(startId: Int, fgsType: Int) { worker?.cancel(); stopSelf() }

    override fun onDestroy() {
        scope.cancel()
        DownloadEngine.busyHost = null
        runCatching { wake?.release() }; runCatching { wifi?.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val ID = 4211
        private const val DONE_BASE = 50_000

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            }.onFailure { android.util.Log.w("jaskDl", "couldn't start the downloader: $it") }
        }
    }
}

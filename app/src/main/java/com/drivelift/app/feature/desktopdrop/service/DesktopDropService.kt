package com.drivelift.app.feature.desktopdrop.service

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
import androidx.core.app.NotificationCompat
import com.drivelift.app.Graph
import com.drivelift.app.feature.desktopdrop.model.DesktopDropSnapshot
import com.drivelift.app.feature.desktopdrop.model.ServerStatus
import com.drivelift.app.ui.MainActivity
import com.drivelift.app.ui.formatBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground dataSync service hosting Desktop Drop's LAN/Hotspot HTTP server, transfer-scoped
 * WakeLock/WifiLock management, Android 14+ onTimeout safety, and idle auto-stop.
 */
class DesktopDropService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null
    private var idleWatchJob: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifyMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = Graph.get(this).desktopDrop.controller
        if (intent?.action == ACTION_STOP) {
            releasePowerLocks()
            controller.stopServer("Stopped from notification")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val initial = buildNotification(controller.snapshot.value)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, initial)
        }

        if (observerJob == null) {
            observerJob = serviceScope.launch {
                controller.snapshot.collect { snap ->
                    updatePowerLocks(snap.activeTransferCount > 0)
                    val now = System.currentTimeMillis()
                    if (snap.status == ServerStatus.RUNNING || snap.status == ServerStatus.STARTING) {
                        if (now - lastNotifyMs >= 500L || snap.activeTransferCount == 0) {
                            lastNotifyMs = now
                            getSystemService(NotificationManager::class.java)
                                ?.notify(NOTIFICATION_ID, buildNotification(snap))
                        }
                    } else if (snap.status == ServerStatus.STOPPED || snap.status == ServerStatus.ERROR) {
                        releasePowerLocks()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }

        if (idleWatchJob == null) {
            idleWatchJob = serviceScope.launch {
                while (isActive) {
                    delay(15_000L)
                    if (controller.checkIdleAutoStop()) {
                        releasePowerLocks()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        break
                    }
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        releasePowerLocks()
        runCatching {
            Graph.get(this).desktopDrop.controller.stopServer("Stopped by Android foreground timeout")
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTimeout(startId, fgsType)
    }

    override fun onDestroy() {
        observerJob?.cancel()
        idleWatchJob?.cancel()
        releasePowerLocks()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun updatePowerLocks(transferActive: Boolean) {
        if (transferActive) {
            acquirePowerLocks()
        } else {
            releasePowerLocks()
        }
    }

    private fun acquirePowerLocks() {
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            if (wm != null) {
                val mode = if (Build.VERSION.SDK_INT >= 29) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(mode, "DriveLift:DesktopDropWifi").apply {
                    setReferenceCounted(false)
                    runCatching { acquire() }
                }
            }
        }
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DriveLift:DesktopDropCpu").apply {
                    setReferenceCounted(false)
                    runCatching { acquire(30L * 60L * 1000L) }
                }
            }
        }
    }

    private fun releasePowerLocks() {
        wifiLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wifiLock = null
        wakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wakeLock = null
    }

    private fun buildNotification(snapshot: DesktopDropSnapshot): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_OPEN_DESKTOP_DROP, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, DesktopDropService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = "Desktop Drop (${snapshot.mode.label})"
        val text = when {
            snapshot.activeTransferCount > 0 -> {
                val speedText = if (snapshot.aggregateSpeedBytesPerSec > 0L) {
                    "${formatBytes(this, snapshot.aggregateSpeedBytesPerSec)}/s"
                } else {
                    "active"
                }
                val noun = if (snapshot.activeTransferCount == 1) "1 transfer" else "${snapshot.activeTransferCount} transfers"
                "$noun ($speedText)"
            }
            snapshot.dropUrl != null -> snapshot.dropUrl
            else -> "Preparing local server"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop Server", stopIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "desktop_drop"
        private const val NOTIFICATION_ID = 2001
        const val EXTRA_OPEN_DESKTOP_DROP = "open_desktop_drop"
        const val ACTION_START = "com.drivelift.app.action.DESKTOP_DROP_START"
        const val ACTION_STOP = "com.drivelift.app.action.DESKTOP_DROP_STOP"

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Desktop Drop local server",
                NotificationManager.IMPORTANCE_LOW,
            )
            nm.createNotificationChannel(channel)
        }

        fun startService(context: Context) {
            ensureChannel(context)
            val intent = Intent(context, DesktopDropService::class.java).setAction(ACTION_START)
            runCatching { context.startForegroundService(intent) }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, DesktopDropService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }
}

package com.drivelift.app.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.drivelift.app.ui.MainActivity

object Notifications {
    private const val CHANNEL_PROGRESS = "progress"
    private const val CHANNEL_ALERTS = "alerts"
    const val ID_PROGRESS = 1001
    private const val ID_VERIFIED = 1002
    private const val ID_AUTH = 1003
    const val EXTRA_OPEN_TRANSFERS = "open_transfers"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Transfer progress", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Transfer results", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN_TRANSFERS, true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun progress(context: Context, title: String, text: String, percent: Int): Notification =
        NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceIn(0, 100), percent < 0)
            .setContentIntent(openApp(context))
            .build()

    fun foreground(context: Context, title: String, text: String, percent: Int): ForegroundInfo =
        ForegroundInfo(
            ID_PROGRESS, progress(context, title, text, percent),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

    fun updateProgress(context: Context, title: String, text: String, percent: Int) {
        context.getSystemService(NotificationManager::class.java)
            .notify(ID_PROGRESS, progress(context, title, text, percent))
    }

    fun verified(context: Context, count: Int) {
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("$count files verified in Google Drive")
            .setContentText("Open DriveLift to remove them from this phone.")
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_VERIFIED, n)
    }

    fun authNeeded(context: Context) {
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Reconnect Google Drive")
            .setContentText("Transfers are waiting for you to sign in again.")
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_AUTH, n)
    }
}

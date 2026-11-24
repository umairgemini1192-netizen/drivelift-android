package com.drivelift.app.feature.automode

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput

/**
 * Posts and updates DriveLift's Wireless debugging pairing notification with an inline
 * [RemoteInput] field ("Enter pairing code") so the owner can type the 6-digit code directly
 * from the notification shade without leaving Android's Wireless debugging settings screen.
 */
object AdbPairingNotifier {
    const val CHANNEL_ID = "auto_mode_pairing"
    const val NOTIFICATION_ID = 4409
    const val ACTION_PAIRING_REPLY = "com.drivelift.app.action.ADB_PAIRING_REPLY"
    const val KEY_PAIRING_CODE = "key_adb_pairing_code"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Auto Mode setup",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Inline reply prompt for one-time Wireless debugging pairing."
            },
        )
    }

    fun showPairingPrompt(context: Context, errorHint: String? = null) {
        ensureChannel(context)
        val remoteInput = RemoteInput.Builder(KEY_PAIRING_CODE)
            .setLabel("Enter pairing code")
            .build()

        val replyIntent = Intent(ACTION_PAIRING_REPLY).setPackage(context.packageName)
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Enter pairing code",
            replyPendingIntent,
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .build()

        val bodyText = errorHint
            ?: "Open Wireless debugging, tap Pair device with pairing code, then reply here with the 6-digit code."

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("DriveLift Auto Mode pairing")
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setOnlyAlertOnce(errorHint == null)
            .addAction(replyAction)
            .build()

        context.getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, notification)
    }

    fun showProgress(context: Context, message: String) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Setting up Auto Mode")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, notification)
    }

    fun showReady(context: Context) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Auto Mode is on")
            .setContentText("Clean and Sleep now run silently on Wi-Fi, even after reboot.")
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, notification)
    }

    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(NOTIFICATION_ID)
    }
}

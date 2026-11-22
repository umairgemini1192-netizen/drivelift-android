package com.drivelift.app.feature.desktopdrop.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.drivelift.app.feature.desktopdrop.model.PeerSession
import com.drivelift.app.feature.desktopdrop.server.SessionAuthManager
import com.drivelift.app.ui.MainActivity

/**
 * High-priority "approve this laptop" notification, posted only while DriveLift is not on screen.
 * One tap opens DriveLift on the Drop tab and starts the biometric prompt for the waiting browser.
 */
object DropApprovalNotifier {
    private const val CHANNEL_ID = "desktop_drop_approval"
    private const val NOTIFICATION_ID = 2002
    private const val REQUEST_CODE = 12

    /** Intent extra that tells MainActivity to open the biometric prompt for the pending knock. */
    const val EXTRA_APPROVE_KNOCK = "approve_desktop_drop_knock"

    fun post(context: Context, knock: PeerSession) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(manager)
        val open = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(DesktopDropService.EXTRA_OPEN_DESKTOP_DROP, true)
                .putExtra(EXTRA_APPROVE_KNOCK, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Approve laptop connection")
            .setContentText("${knock.deviceLabel} at ${knock.peerIp}. Match code ${knock.matchCode}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(SessionAuthManager.KNOCK_TTL_MS)
            .setContentIntent(open)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel(manager: NotificationManager) {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Desktop Drop approvals",
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }
}

package com.drivelift.app.feature.cockpit.notifications

import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.drivelift.app.Graph
import java.util.concurrent.ConcurrentHashMap

/**
 * Listens for WhatsApp, Google Maps turn-by-turn, Clock timers/stopwatches, and audio/screen
 * recording notifications, forwarding parsed activities into [CockpitStateCoordinator] and
 * caching [RemoteInput] / content intents for inline quick actions from the expanded Cockpit card.
 */
class CockpitNotificationListener : NotificationListenerService() {

    private data class CachedNotificationActions(
        val contentIntent: PendingIntent?,
        val replyIntent: PendingIntent?,
        val remoteInput: RemoteInput?,
    )

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeInstance = this
        runCatching {
            activeNotifications?.forEach(::handlePosted)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        handlePosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        cachedActions.remove(sbn.key)
        runCatching {
            Graph.get(applicationContext).cockpit.coordinator.removeByNotificationKey(sbn.key)
        }
    }

    override fun onListenerDisconnected() {
        cachedActions.clear()
        if (activeInstance === this) activeInstance = null
        super.onListenerDisconnected()
    }

    private fun handlePosted(sbn: StatusBarNotification) {
        val snapshot = LiveNotificationParser.fromStatusBarNotification(sbn)
        val parsed = LiveNotificationParser.parse(snapshot)
        val cockpit = runCatching { Graph.get(applicationContext).cockpit }.getOrNull() ?: return
        val coordinator = cockpit.coordinator
        if (!cockpit.prefsRepo.isMasterEnabledMirrored()) {
            coordinator.setExpanded(false)
        }
        if (parsed == null) {
            cachedActions.remove(sbn.key)
            coordinator.removeByNotificationKey(sbn.key)
            return
        }
        val replyAction = sbn.notification.actions.orEmpty().firstOrNull { action ->
            action.remoteInputs?.isNotEmpty() == true && action.actionIntent != null
        }
        val remoteInput = replyAction?.remoteInputs?.firstOrNull()
        cachedActions[sbn.key] = CachedNotificationActions(
            contentIntent = sbn.notification.contentIntent,
            replyIntent = replyAction?.actionIntent,
            remoteInput = remoteInput,
        )
        coordinator.upsertActivity(parsed)
    }

    companion object {
        @Volatile
        private var activeInstance: CockpitNotificationListener? = null
        private val cachedActions = ConcurrentHashMap<String, CachedNotificationActions>()

        fun isPermissionGranted(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ).orEmpty()
            val expected = ComponentName(context, CockpitNotificationListener::class.java).flattenToString()
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }

        fun launchNotification(notificationKey: String?): Boolean {
            if (notificationKey.isNullOrBlank()) return false
            val intent = cachedActions[notificationKey]?.contentIntent ?: return false
            return runCatching {
                if (Build.VERSION.SDK_INT >= 34) {
                    val options = ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                        )
                        .toBundle()
                    intent.send(options)
                } else {
                    intent.send()
                }
                true
            }.getOrDefault(false)
        }

        fun sendInlineReply(context: Context, notificationKey: String, replyText: String): Boolean {
            val trimmed = replyText.trim()
            if (trimmed.isEmpty()) return false
            val cached = cachedActions[notificationKey] ?: return false
            val replyIntent = cached.replyIntent ?: return false
            val remoteInput = cached.remoteInput ?: return false
            val bundle = Bundle().apply {
                putCharSequence(remoteInput.resultKey, trimmed)
            }
            val fillIn = Intent()
            RemoteInput.addResultsToIntent(arrayOf(remoteInput), fillIn, bundle)
            return runCatching {
                replyIntent.send(context, 0, fillIn)
                true
            }.getOrDefault(false)
        }
    }
}

package com.drivelift.app.feature.cockpit.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.drivelift.app.Graph
import com.drivelift.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Fallback system-wide Foreground Service (`specialUse`) attaching a Compose Dynamic Island
 * [androidx.compose.ui.platform.ComposeView] to [WindowManager] (`TYPE_APPLICATION_OVERLAY`) when
 * [CockpitAccessibilityService] is not enabled. Respects persisted `masterEnabled` across restarts
 * so turning the Dynamic Island OFF prevents re-mounting on notifications or screen unlock.
 */
class CockpitOverlayService :
    Service(),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: CockpitOverlayWindowController? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        ensureChannel()
        controller = CockpitOverlayWindowController(
            serviceContext = this,
            lifecycleOwner = this,
            savedStateRegistryOwner = this,
            viewModelStoreOwner = this,
            windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        ).also { it.updateMetrics() }

        val cockpit = Graph.get(this).cockpit
        serviceScope.launch {
            cockpit.prefsRepo.state.collect { prefs ->
                if (!shouldMountFallbackOverlay(
                        masterEnabled = prefs.masterEnabled && cockpit.prefsRepo.isMasterEnabledMirrored(),
                        hasOverlayPermission = Settings.canDrawOverlays(this@CockpitOverlayService),
                        accessibilityOverlayActive = CockpitAccessibilityService.isRunning(),
                    )
                ) {
                    stopOverlayAndSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cockpit = Graph.get(this).cockpit
        if (intent?.action == ACTION_STOP ||
            !shouldMountFallbackOverlay(
                masterEnabled = cockpit.prefsRepo.isMasterEnabledMirrored(),
                hasOverlayPermission = Settings.canDrawOverlays(this),
                accessibilityOverlayActive = CockpitAccessibilityService.isRunning() ||
                    CockpitAccessibilityService.isServiceEnabled(this),
            )
        ) {
            stopOverlayAndSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        controller?.attachIfNeeded(serviceScope)
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller?.updateMetrics()
        controller?.applyVisibility()
    }

    override fun onDestroy() {
        controller?.detach()
        serviceScope.cancel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
        super.onDestroy()
    }

    private fun stopOverlayAndSelf() {
        runCatching { Graph.get(this).cockpit.coordinator.setExpanded(false) }
        controller?.detach()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "DriveLift Cockpit Overlay",
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "Keeps the system-wide Dynamic Island overlay active."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            31,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_OPEN_COCKPIT, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.drivelift.app.R.drawable.ic_cockpit_tile)
            .setContentTitle("DriveLift Cockpit active")
            .setContentText("Tap to configure Dynamic Island and pinned apps")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "cockpit_overlay"
        private const val NOTIFICATION_ID = 3001
        const val EXTRA_OPEN_COCKPIT = "open_cockpit"
        const val ACTION_START = "com.drivelift.app.action.COCKPIT_START"
        const val ACTION_STOP = "com.drivelift.app.action.COCKPIT_STOP"

        /**
         * Returns true only when `masterEnabled` is on, standard overlay permission is granted,
         * and the preferred `TYPE_ACCESSIBILITY_OVERLAY` service does not already own the overlay.
         */
        fun shouldMountFallbackOverlay(
            masterEnabled: Boolean,
            hasOverlayPermission: Boolean,
            accessibilityOverlayActive: Boolean,
        ): Boolean = masterEnabled && hasOverlayPermission && !accessibilityOverlayActive
    }
}

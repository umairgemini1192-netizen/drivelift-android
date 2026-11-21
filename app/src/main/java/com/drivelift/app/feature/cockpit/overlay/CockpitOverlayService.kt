package com.drivelift.app.feature.cockpit.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.drivelift.app.Graph
import com.drivelift.app.feature.cockpit.activities.CockpitLiveActivity
import com.drivelift.app.feature.cockpit.notifications.CockpitNotificationListener
import com.drivelift.app.feature.cockpit.ui.CockpitTokens
import com.drivelift.app.feature.cockpit.ui.DynamicIslandOverlay
import com.drivelift.app.ui.DriveLiftTheme
import com.drivelift.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Permanent system-wide Foreground Service (`specialUse`) attaching a Compose Dynamic Island
 * [ComposeView] to [WindowManager] (`TYPE_APPLICATION_OVERLAY`), anchored around the Pixel
 * camera punch-hole with touch pass-through when collapsed, focus unlock on WhatsApp inline reply,
 * compositor blur-behind when expanded, and automatic hide during landscape fullscreen video/games.
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
    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val isLandscapeFullscreen = mutableStateOf(false)
    private val cutoutTopPx = mutableIntStateOf(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        ensureChannel()
        windowManager = getSystemService(WindowManager::class.java)
        updateFullscreenAndCutoutMetrics()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            removeOverlayView()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
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
        attachOverlayIfNeeded()
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateFullscreenAndCutoutMetrics()
        val view = overlayView ?: return
        view.visibility = if (isLandscapeFullscreen.value) View.GONE else View.VISIBLE
    }

    override fun onDestroy() {
        removeOverlayView()
        serviceScope.cancel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
        super.onDestroy()
    }

    private fun attachOverlayIfNeeded() {
        if (overlayView != null) return
        val wm = windowManager ?: return
        val cockpit = Graph.get(this).cockpit

        val params = createBaseLayoutParams()
        layoutParams = params

        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(this@CockpitOverlayService)
            setViewTreeSavedStateRegistryOwner(this@CockpitOverlayService)
            setViewTreeViewModelStoreOwner(this@CockpitOverlayService)
            setContent {
                val prefs by cockpit.prefsRepo.state.collectAsStateWithLifecycle()
                val slotState by cockpit.coordinator.slotState.collectAsStateWithLifecycle()
                val telemetry by cockpit.telemetryAdapter.summary.collectAsStateWithLifecycle()
                val torchState by cockpit.torchController.state.collectAsStateWithLifecycle()
                val pinnedApps = remember(prefs.pinnedPackages) {
                    cockpit.pinnedAppsRepo.resolvePinnedApps(prefs.pinnedPackages)
                }

                LaunchedEffect(
                    slotState.isExpanded,
                    slotState.inlineReplyOpen,
                    prefs.cutoutOffsetX,
                    prefs.cutoutOffsetY,
                    cutoutTopPx.intValue,
                ) {
                    updateWindowParams(
                        isExpanded = slotState.isExpanded,
                        inlineReplyOpen = slotState.inlineReplyOpen,
                        offsetX = prefs.cutoutOffsetX,
                        offsetY = prefs.cutoutOffsetY,
                    )
                }

                if (!isLandscapeFullscreen.value && prefs.masterEnabled) {
                    DriveLiftTheme {
                        Box(
                            modifier = Modifier.padding(top = com.drivelift.app.ui.Tokens.Snug),
                        ) {
                            DynamicIslandOverlay(
                                slotState = slotState,
                                telemetry = telemetry,
                                torchState = torchState,
                                pinnedApps = pinnedApps,
                                widthDeltaDp = prefs.cutoutWidthDelta,
                                onTapPrimary = { activity -> launchActivityTarget(activity) },
                                onTapSecondary = { activity -> cockpit.coordinator.focusActivity(activity.id) },
                                onExpand = { cockpit.coordinator.setExpanded(true) },
                                onCollapse = { cockpit.coordinator.setExpanded(false) },
                                onCycleNext = { cockpit.coordinator.cycleNext() },
                                onCyclePrevious = { cockpit.coordinator.cyclePrevious() },
                                onSoftDismiss = { id -> cockpit.coordinator.softDismiss(id) },
                                onRestoreDismissed = { cockpit.coordinator.restoreAllDismissed() },
                                onStopDesktopDrop = { cockpit.telemetryAdapter.stopDesktopDrop() },
                                onOpenDriveLiftCockpit = { openCockpitHubActivity() },
                                onToggleInlineReply = { open -> cockpit.coordinator.setInlineReplyOpen(open) },
                                onSendWhatsAppReply = { key, reply ->
                                    if (CockpitNotificationListener.sendInlineReply(this@CockpitOverlayService, key, reply)) {
                                        cockpit.coordinator.softDismiss("wa_$key")
                                    }
                                },
                                onToggleTorch = { cockpit.torchController.toggleTorch() },
                                onTorchBrightnessChange = { lvl -> cockpit.torchController.setBrightnessLevel(lvl) },
                                onToggleTorchSos = { cockpit.torchController.toggleSos() },
                                onLaunchPinnedApp = { pkg ->
                                    cockpit.pinnedAppsRepo.launchApp(pkg)
                                    cockpit.coordinator.setExpanded(false)
                                },
                            )
                        }
                    }
                }
            }
        }

        overlayView = view
        serviceScope.launch {
            runCatching { wm.addView(view, params) }
        }
    }

    private fun removeOverlayView() {
        val view = overlayView ?: return
        overlayView = null
        runCatching { windowManager?.removeViewImmediate(view) }
    }

    private fun createBaseLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            y = cutoutTopPx.intValue
        }
    }

    private fun updateWindowParams(
        isExpanded: Boolean,
        inlineReplyOpen: Boolean,
        offsetX: Int,
        offsetY: Int,
    ) {
        val wm = windowManager ?: return
        val view = overlayView ?: return
        val params = layoutParams ?: return
        val density = resources.displayMetrics.density

        var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        if (!isExpanded || !inlineReplyOpen) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (Build.VERSION.SDK_INT >= 31 && isExpanded) {
            flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            params.blurBehindRadius = CockpitTokens.OVERLAY_BLUR_RADIUS_PX
        } else if (Build.VERSION.SDK_INT >= 31) {
            params.blurBehindRadius = 0
        }

        params.flags = flags
        params.x = (offsetX * density).toInt()
        params.y = cutoutTopPx.intValue + (offsetY * density).toInt()
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun updateFullscreenAndCutoutMetrics() {
        isLandscapeFullscreen.value =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val wm = windowManager ?: return
        val cutout = runCatching {
            wm.currentWindowMetrics.windowInsets.displayCutout
        }.getOrNull()
        val rectTop = cutout?.boundingRectTop?.top ?: 0
        cutoutTopPx.intValue = rectTop.coerceAtLeast(0)
    }

    private fun launchActivityTarget(activity: CockpitLiveActivity) {
        val cockpit = Graph.get(this).cockpit
        when (activity) {
            is CockpitLiveActivity.TransferActivity -> openDriveLiftApp()
            is CockpitLiveActivity.WhatsAppAlertActivity -> {
                if (!CockpitNotificationListener.launchNotification(activity.notificationKey)) {
                    val pkg = if (activity.isBusiness) "com.whatsapp.w4b" else "com.whatsapp"
                    cockpit.pinnedAppsRepo.launchApp(pkg)
                }
            }
            is CockpitLiveActivity.MapsNavActivity -> {
                if (!CockpitNotificationListener.launchNotification(activity.notificationKey)) {
                    cockpit.pinnedAppsRepo.launchApp(activity.packageName)
                }
            }
            is CockpitLiveActivity.TimerRecordingActivity -> {
                if (!CockpitNotificationListener.launchNotification(activity.notificationKey)) {
                    cockpit.pinnedAppsRepo.launchApp(activity.packageName)
                }
            }
            is CockpitLiveActivity.IdleQuickDockActivity -> openCockpitHubActivity()
        }
    }

    private fun openDriveLiftApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { startActivity(intent) }
    }

    private fun openCockpitHubActivity() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN_COCKPIT, true)
        runCatching { startActivity(intent) }
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
    }
}

package com.drivelift.app.feature.cockpit.overlay

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
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
import kotlinx.coroutines.launch

/**
 * Shared WindowManager controller used by [CockpitAccessibilityService] (preferred
 * `TYPE_ACCESSIBILITY_OVERLAY` above SystemUI's status-bar cutout) and [CockpitOverlayService]
 * (`TYPE_APPLICATION_OVERLAY` fallback). Keeps window flags, cutout anchoring, tap-outside
 * collapse, inline-reply focus unlock, and activity launch behavior identical across both.
 */
internal class CockpitOverlayWindowController(
    private val serviceContext: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val savedStateRegistryOwner: SavedStateRegistryOwner,
    private val viewModelStoreOwner: ViewModelStoreOwner,
    private val windowType: Int,
) {
    private val windowManager: WindowManager? = serviceContext.getSystemService(WindowManager::class.java)
    private var overlayView: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    val isLandscapeFullscreen = mutableStateOf(false)
    val cutoutTopPx = mutableIntStateOf(0)

    val isAttached: Boolean
        get() = overlayView != null

    fun updateMetrics() {
        isLandscapeFullscreen.value =
            serviceContext.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val wm = windowManager ?: return
        val cutout = runCatching {
            wm.currentWindowMetrics.windowInsets.displayCutout
        }.getOrNull()
        val rectTop = cutout?.boundingRectTop?.top ?: 0
        cutoutTopPx.intValue = rectTop.coerceAtLeast(0)
        overlayView?.visibility = if (isLandscapeFullscreen.value) View.GONE else View.VISIBLE
    }

    fun attachIfNeeded(scope: CoroutineScope) {
        if (overlayView != null) return
        val wm = windowManager ?: return
        val cockpit = Graph.get(serviceContext).cockpit
        if (!cockpit.prefsRepo.isMasterEnabledMirrored()) return

        updateMetrics()
        val params = createBaseLayoutParams()
        layoutParams = params

        val view = ComposeView(serviceContext).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
            setViewTreeViewModelStoreOwner(viewModelStoreOwner)
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE &&
                    cockpit.coordinator.slotState.value.isExpanded
                ) {
                    cockpit.coordinator.setExpanded(false)
                    true
                } else {
                    false
                }
            }
            visibility = if (isLandscapeFullscreen.value) View.GONE else View.VISIBLE
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
                        Box {
                            DynamicIslandOverlay(
                                slotState = slotState,
                                telemetry = telemetry,
                                torchState = torchState,
                                pinnedApps = pinnedApps,
                                widthDeltaDp = prefs.cutoutWidthDelta,
                                onTapPrimary = { activity ->
                                    launchActivityTarget(activity)
                                    cockpit.coordinator.setExpanded(false)
                                },
                                onTapSecondary = { activity ->
                                    cockpit.coordinator.focusActivity(activity.id)
                                    cockpit.coordinator.setExpanded(true)
                                },
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
                                    if (CockpitNotificationListener.sendInlineReply(serviceContext, key, reply)) {
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
        scope.launch {
            if (!cockpit.prefsRepo.isMasterEnabledMirrored()) {
                overlayView = null
                return@launch
            }
            val added = runCatching { wm.addView(view, params) }.isSuccess
            if (!added && overlayView === view) {
                overlayView = null
            }
        }
    }

    fun detach() {
        val view = overlayView ?: return
        overlayView = null
        layoutParams = null
        runCatching { windowManager?.removeViewImmediate(view) }
    }

    private fun createBaseLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
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
        val density = serviceContext.resources.displayMetrics.density

        var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        if (isExpanded) {
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }
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
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        params.x = (offsetX * density).toInt()
        params.y = cutoutTopPx.intValue + (offsetY * density).toInt()
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun launchActivityTarget(activity: CockpitLiveActivity) {
        val cockpit = Graph.get(serviceContext).cockpit
        when (activity) {
            is CockpitLiveActivity.TransferActivity -> openDriveLiftApp()
            is CockpitLiveActivity.WhatsAppAlertActivity -> {
                if (!CockpitNotificationListener.launchNotification(activity.notificationKey)) {
                    val pkg = if (activity.isBusiness) "com.whatsapp.w4b" else "com.whatsapp"
                    if (!cockpit.pinnedAppsRepo.launchApp(pkg)) openDriveLiftApp()
                }
            }
            is CockpitLiveActivity.MapsNavActivity -> {
                if (!CockpitNotificationListener.launchNotification(activity.notificationKey)) {
                    if (!cockpit.pinnedAppsRepo.launchApp(activity.packageName)) openDriveLiftApp()
                }
            }
            is CockpitLiveActivity.TimerRecordingActivity -> {
                if (!CockpitNotificationListener.launchNotification(activity.notificationKey)) {
                    if (!cockpit.pinnedAppsRepo.launchApp(activity.packageName)) openDriveLiftApp()
                }
            }
            is CockpitLiveActivity.IdleQuickDockActivity -> openCockpitHubActivity()
        }
    }

    private fun openDriveLiftApp() {
        val intent = Intent(serviceContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { serviceContext.startActivity(intent) }
    }

    private fun openCockpitHubActivity() {
        val intent = Intent(serviceContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(CockpitOverlayService.EXTRA_OPEN_COCKPIT, true)
        runCatching { serviceContext.startActivity(intent) }
    }
}

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.drivelift.app.Graph
import com.drivelift.app.feature.cockpit.activities.CockpitLiveActivity
import com.drivelift.app.feature.cockpit.hardware.CockpitHaptics
import com.drivelift.app.feature.cockpit.notifications.CockpitNotificationListener
import com.drivelift.app.feature.cockpit.ui.CockpitTokens
import com.drivelift.app.feature.cockpit.ui.DynamicIslandOverlay
import com.drivelift.app.ui.DriveLiftTheme
import com.drivelift.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Pure window bounds coordinator ensuring zero `updateViewLayout()` calls happen mid-animation:
 * - Collapsed pill stays tightly bounded (`WRAP_CONTENT x WRAP_CONTENT`) so background taps pass through.
 * - On expand: switches immediately ONCE to `MATCH_PARENT x MATCH_PARENT` anchored at `topCutoutOffsetPx`.
 * - On collapse: keeps `MATCH_PARENT x MATCH_PARENT` while Compose animates all the way back to pill
 *   bounds, shrinking back to `WRAP_CONTENT x WRAP_CONTENT` ONLY inside [onCollapseAnimationFinished].
 */
internal class CockpitWindowBoundsCoordinator(
    private val applyLayoutParams: (WindowManager.LayoutParams) -> Unit,
) {
    enum class WindowSpan {
        COLLAPSED_WRAP,
        FULL_SCREEN_OVERLAY,
    }

    var currentSpan: WindowSpan = WindowSpan.COLLAPSED_WRAP
        private set

    private var targetExpanded: Boolean = false
    private var inlineReplyOpen: Boolean = false
    private var lastAppliedSignature: String? = null

    fun createBaseLayoutParams(
        windowType: Int,
        cutoutTopPx: Int,
    ): WindowManager.LayoutParams = WindowManager.LayoutParams(
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
        y = cutoutTopPx
    }

    fun onStateChanged(
        params: WindowManager.LayoutParams,
        isExpanded: Boolean,
        inlineReplyOpen: Boolean,
        cutoutTopPx: Int,
        offsetXPx: Int,
        offsetYPx: Int,
        blurRadiusPx: Int = CockpitTokens.OVERLAY_BLUR_RADIUS_PX,
    ): Boolean {
        targetExpanded = isExpanded
        this.inlineReplyOpen = inlineReplyOpen
        if (isExpanded) {
            currentSpan = WindowSpan.FULL_SCREEN_OVERLAY
        }
        return applyParamsIfChanged(
            params = params,
            cutoutTopPx = cutoutTopPx,
            offsetXPx = offsetXPx,
            offsetYPx = offsetYPx,
            blurRadiusPx = blurRadiusPx,
        )
    }

    fun onCollapseAnimationFinished(
        params: WindowManager.LayoutParams,
        cutoutTopPx: Int,
        offsetXPx: Int,
        offsetYPx: Int,
        blurRadiusPx: Int = CockpitTokens.OVERLAY_BLUR_RADIUS_PX,
    ): Boolean {
        if (targetExpanded || currentSpan == WindowSpan.COLLAPSED_WRAP) return false
        currentSpan = WindowSpan.COLLAPSED_WRAP
        return applyParamsIfChanged(
            params = params,
            cutoutTopPx = cutoutTopPx,
            offsetXPx = offsetXPx,
            offsetYPx = offsetYPx,
            blurRadiusPx = blurRadiusPx,
        )
    }

    fun reset() {
        currentSpan = WindowSpan.COLLAPSED_WRAP
        targetExpanded = false
        inlineReplyOpen = false
        lastAppliedSignature = null
    }

    private fun applyParamsIfChanged(
        params: WindowManager.LayoutParams,
        cutoutTopPx: Int,
        offsetXPx: Int,
        offsetYPx: Int,
        blurRadiusPx: Int,
    ): Boolean {
        val fullOverlay = currentSpan == WindowSpan.FULL_SCREEN_OVERLAY
        params.width = if (fullOverlay) {
            WindowManager.LayoutParams.MATCH_PARENT
        } else {
            WindowManager.LayoutParams.WRAP_CONTENT
        }
        params.height = if (fullOverlay) {
            WindowManager.LayoutParams.MATCH_PARENT
        } else {
            WindowManager.LayoutParams.WRAP_CONTENT
        }
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL

        var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        if (fullOverlay && targetExpanded) {
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }
        if (!targetExpanded || !inlineReplyOpen) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (Build.VERSION.SDK_INT >= 31 && fullOverlay) {
            flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            params.blurBehindRadius = blurRadiusPx
        } else if (Build.VERSION.SDK_INT >= 31) {
            params.blurBehindRadius = 0
        }

        params.flags = flags
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        params.x = if (fullOverlay) 0 else offsetXPx
        params.y = cutoutTopPx + if (fullOverlay) 0 else offsetYPx

        val blurVal = if (Build.VERSION.SDK_INT >= 31) params.blurBehindRadius else 0
        val signature = "${params.width}:${params.height}:${params.flags}:${params.x}:${params.y}:$blurVal"
        if (signature == lastAppliedSignature) return false
        lastAppliedSignature = signature
        applyLayoutParams(params)
        return true
    }
}

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
    private val boundsCoordinator = CockpitWindowBoundsCoordinator { params ->
        val wm = windowManager ?: return@CockpitWindowBoundsCoordinator
        val view = overlayView ?: return@CockpitWindowBoundsCoordinator
        runCatching { wm.updateViewLayout(view, params) }
    }

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
        boundsCoordinator.reset()
        val params = boundsCoordinator.createBaseLayoutParams(
            windowType = windowType,
            cutoutTopPx = cutoutTopPx.intValue,
        )
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
                val quickControls by cockpit.quickControlsController.state.collectAsStateWithLifecycle()
                val pinnedApps = remember(prefs.pinnedPackages) {
                    cockpit.pinnedAppsRepo.resolvePinnedApps(prefs.pinnedPackages)
                }
                var fullOverlayActive by remember { mutableStateOf(slotState.isExpanded) }

                LaunchedEffect(
                    slotState.isExpanded,
                    slotState.inlineReplyOpen,
                    prefs.cutoutOffsetX,
                    prefs.cutoutOffsetY,
                    cutoutTopPx.intValue,
                ) {
                    val density = serviceContext.resources.displayMetrics.density
                    if (slotState.isExpanded) {
                        fullOverlayActive = true
                        cockpit.quickControlsController.refreshAll()
                    }
                    boundsCoordinator.onStateChanged(
                        params = params,
                        isExpanded = slotState.isExpanded,
                        inlineReplyOpen = slotState.inlineReplyOpen,
                        cutoutTopPx = cutoutTopPx.intValue,
                        offsetXPx = (prefs.cutoutOffsetX * density).toInt(),
                        offsetYPx = (prefs.cutoutOffsetY * density).toInt(),
                    )
                }

                if (!isLandscapeFullscreen.value && prefs.masterEnabled) {
                    DriveLiftTheme {
                        val boxModifier = if (fullOverlayActive) {
                            Modifier
                                .fillMaxSize()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    cockpit.coordinator.setExpanded(false)
                                }
                        } else {
                            Modifier.wrapContentSize()
                        }
                        Box(
                            modifier = boxModifier,
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            DynamicIslandOverlay(
                                slotState = slotState,
                                telemetry = telemetry,
                                torchState = torchState,
                                quickControls = quickControls,
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
                                onExpand = {
                                    CockpitHaptics.performClick(serviceContext)
                                    cockpit.coordinator.setExpanded(true)
                                },
                                onCollapse = { cockpit.coordinator.setExpanded(false) },
                                onCollapseAnimationFinished = {
                                    fullOverlayActive = false
                                    val density = serviceContext.resources.displayMetrics.density
                                    boundsCoordinator.onCollapseAnimationFinished(
                                        params = params,
                                        cutoutTopPx = cutoutTopPx.intValue,
                                        offsetXPx = (prefs.cutoutOffsetX * density).toInt(),
                                        offsetYPx = (prefs.cutoutOffsetY * density).toInt(),
                                    )
                                },
                                onCycleNext = { cockpit.coordinator.cycleNext() },
                                onCyclePrevious = { cockpit.coordinator.cyclePrevious() },
                                onSoftDismiss = { id -> cockpit.coordinator.softDismiss(id) },
                                onRestoreDismissed = { cockpit.coordinator.restoreAllDismissed() },
                                onStopDesktopDrop = { cockpit.telemetryAdapter.stopDesktopDrop() },
                                onOpenDriveLiftCockpit = {
                                    openCockpitHubActivity()
                                    cockpit.coordinator.setExpanded(false)
                                },
                                onToggleInlineReply = { open -> cockpit.coordinator.setInlineReplyOpen(open) },
                                onSendWhatsAppReply = { key, reply ->
                                    if (CockpitNotificationListener.sendInlineReply(serviceContext, key, reply)) {
                                        cockpit.coordinator.softDismiss("wa_$key")
                                    }
                                },
                                onTapWifi = { cockpit.quickControlsController.openInternetPanel() },
                                onLongPressWifi = { cockpit.quickControlsController.openWifiSettings() },
                                onTapMobileData = { cockpit.quickControlsController.onMobileDataTap() },
                                onLongPressMobileData = { cockpit.quickControlsController.openDataUsageSettings() },
                                onTapBluetooth = { cockpit.quickControlsController.openBluetoothSettings() },
                                onCycleRingerMode = { cockpit.quickControlsController.cycleRingerMode() },
                                onToggleTorch = {
                                    CockpitHaptics.performClick(serviceContext)
                                    cockpit.torchController.toggleTorch()
                                },
                                onTorchBrightnessChange = { lvl -> cockpit.torchController.setBrightnessLevel(lvl) },
                                onToggleTorchSos = {
                                    CockpitHaptics.performClick(serviceContext)
                                    cockpit.torchController.toggleSos()
                                },
                                onLaunchPinnedApp = { pkg ->
                                    CockpitHaptics.performClick(serviceContext)
                                    cockpit.pinnedAppsRepo.launchApp(pkg)
                                    cockpit.coordinator.setExpanded(false)
                                },
                                modifier = if (fullOverlayActive) {
                                    Modifier.offset(
                                        x = prefs.cutoutOffsetX.dp,
                                        y = prefs.cutoutOffsetY.dp,
                                    )
                                } else {
                                    Modifier
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
        boundsCoordinator.reset()
        runCatching { windowManager?.removeViewImmediate(view) }
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

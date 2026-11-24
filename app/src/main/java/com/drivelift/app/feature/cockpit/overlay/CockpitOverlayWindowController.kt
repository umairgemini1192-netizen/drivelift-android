package com.drivelift.app.feature.cockpit.overlay

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.view.DisplayCutout
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.IntOffset
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
import com.drivelift.app.feature.cockpit.activities.TripDistanceFormat
import com.drivelift.app.feature.cockpit.hardware.CockpitHaptics
import com.drivelift.app.feature.cockpit.notifications.CockpitNotificationListener
import com.drivelift.app.feature.cockpit.ui.CockpitActivityActions
import com.drivelift.app.feature.cockpit.ui.DynamicIslandOverlay
import com.drivelift.app.ui.DriveLiftTheme
import com.drivelift.app.ui.MainActivity
import com.drivelift.app.ui.Tokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Pixel anchor derived strictly from `WindowInsets.displayCutout` bounds with zero hardcoded
 * pixel offsets:
 * - [centerOffsetXPx]: signed horizontal delta from screen horizontal center (`Gravity.CENTER_HORIZONTAL`)
 *   to the exact horizontal center of `boundingRectTop`.
 * - [topPx]: vertical window origin so the pill's vertical center aligns with the cutout's vertical center.
 */
internal data class CutoutAnchor(
    val centerOffsetXPx: Int = 0,
    val topPx: Int = 0,
)

internal object CutoutAnchorCalculator {
    fun fromWindowInsets(
        windowInsets: WindowInsets?,
        displayWidthPx: Int,
        pillHeightPx: Int,
    ): CutoutAnchor = fromDisplayCutout(
        cutout = windowInsets?.displayCutout,
        displayWidthPx = displayWidthPx,
        pillHeightPx = pillHeightPx,
    )

    fun fromDisplayCutout(
        cutout: DisplayCutout?,
        displayWidthPx: Int,
        pillHeightPx: Int,
    ): CutoutAnchor {
        val topRect = cutout?.boundingRectTop?.takeIf { !it.isEmpty }
            ?: cutout?.boundingRects?.firstOrNull { !it.isEmpty }
        return fromCutoutBounds(
            cutoutTopRect = topRect,
            displayWidthPx = displayWidthPx,
            pillHeightPx = pillHeightPx,
        )
    }

    fun fromCutoutBounds(
        cutoutTopRect: Rect?,
        displayWidthPx: Int,
        pillHeightPx: Int,
    ): CutoutAnchor {
        if (cutoutTopRect == null || cutoutTopRect.isEmpty) {
            return CutoutAnchor(centerOffsetXPx = 0, topPx = 0)
        }
        val cutoutCenterX = (cutoutTopRect.left + cutoutTopRect.right) / 2
        val centerOffsetX = if (displayWidthPx > 0) {
            cutoutCenterX - (displayWidthPx / 2)
        } else {
            0
        }
        val cutoutCenterY = (cutoutTopRect.top + cutoutTopRect.bottom) / 2
        val safePillHeight = pillHeightPx.coerceAtLeast(0)
        val topPx = (cutoutCenterY - (safePillHeight / 2)).coerceAtLeast(0)
        return CutoutAnchor(
            centerOffsetXPx = centerOffsetX,
            topPx = topPx,
        )
    }
}

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
        cutoutCenterOffsetXPx: Int = 0,
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
        x = cutoutCenterOffsetXPx
        y = cutoutTopPx
    }

    fun onStateChanged(
        params: WindowManager.LayoutParams,
        isExpanded: Boolean,
        inlineReplyOpen: Boolean,
        cutoutTopPx: Int,
        offsetXPx: Int,
        offsetYPx: Int,
        blurRadiusPx: Int = Tokens.Cockpit.OVERLAY_BLUR_RADIUS_PX,
        cutoutCenterOffsetXPx: Int = 0,
    ): Boolean {
        targetExpanded = isExpanded
        this.inlineReplyOpen = inlineReplyOpen
        if (isExpanded) {
            currentSpan = WindowSpan.FULL_SCREEN_OVERLAY
        }
        return applyParamsIfChanged(
            params = params,
            cutoutTopPx = cutoutTopPx,
            cutoutCenterOffsetXPx = cutoutCenterOffsetXPx,
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
        blurRadiusPx: Int = Tokens.Cockpit.OVERLAY_BLUR_RADIUS_PX,
        cutoutCenterOffsetXPx: Int = 0,
    ): Boolean {
        if (targetExpanded || currentSpan == WindowSpan.COLLAPSED_WRAP) return false
        currentSpan = WindowSpan.COLLAPSED_WRAP
        return applyParamsIfChanged(
            params = params,
            cutoutTopPx = cutoutTopPx,
            cutoutCenterOffsetXPx = cutoutCenterOffsetXPx,
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
        cutoutCenterOffsetXPx: Int,
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
        params.x = if (fullOverlay) 0 else cutoutCenterOffsetXPx + offsetXPx
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
    val cutoutCenterOffsetXPx = mutableIntStateOf(0)

    val isAttached: Boolean
        get() = overlayView != null

    fun updateMetrics() {
        isLandscapeFullscreen.value =
            serviceContext.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val wm = windowManager ?: return
        val density = serviceContext.resources.displayMetrics.density
        val pillHeightPx = (Tokens.Cockpit.PillHeight.value * density).toInt()
        val anchor = runCatching {
            val metrics = wm.currentWindowMetrics
            CutoutAnchorCalculator.fromWindowInsets(
                windowInsets = metrics.windowInsets,
                displayWidthPx = metrics.bounds.width(),
                pillHeightPx = pillHeightPx,
            )
        }.getOrDefault(CutoutAnchor())
        cutoutTopPx.intValue = anchor.topPx
        cutoutCenterOffsetXPx.intValue = anchor.centerOffsetXPx
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
            cutoutCenterOffsetXPx = cutoutCenterOffsetXPx.intValue,
        )
        layoutParams = params

        val view = ComposeView(serviceContext).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
            setViewTreeViewModelStoreOwner(viewModelStoreOwner)
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_OUTSIDE -> {
                        if (cockpit.coordinator.slotState.value.isExpanded) {
                            cockpit.coordinator.setExpanded(false)
                            true
                        } else {
                            false
                        }
                    }
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_POINTER_DOWN,
                    -> {
                        cockpit.coordinator.onUserInteraction()
                        false
                    }
                    else -> false
                }
            }
            visibility = if (isLandscapeFullscreen.value) View.GONE else View.VISIBLE
            setContent {
                val prefs by cockpit.prefsRepo.state.collectAsStateWithLifecycle()
                val slotState by cockpit.coordinator.slotState.collectAsStateWithLifecycle()
                val telemetry by cockpit.telemetryAdapter.summary.collectAsStateWithLifecycle()
                val torchState by cockpit.torchController.state.collectAsStateWithLifecycle()
                val quickControls by cockpit.quickControlsController.state.collectAsStateWithLifecycle()
                val callAudio by cockpit.callActionsController.audioState.collectAsStateWithLifecycle()
                val screenOn by cockpit.telemetryMonitor.isScreenOn.collectAsStateWithLifecycle()
                val activityActions = remember(cockpit) {
                    CockpitActivityActions(
                        onAnswerCall = { call ->
                            if (!cockpit.callActionsController.answer(call.notificationKey)) {
                                launchActivityTarget(call)
                            }
                        },
                        onDeclineCall = { call ->
                            if (!cockpit.callActionsController.decline(call.notificationKey)) {
                                launchActivityTarget(call)
                            }
                        },
                        onHangUpCall = { call ->
                            if (!cockpit.callActionsController.hangUp(call.notificationKey)) {
                                launchActivityTarget(call)
                            }
                        },
                        onToggleMute = { call -> cockpit.callActionsController.toggleMute(call.notificationKey) },
                        onCycleAudioOutput = { call ->
                            cockpit.callActionsController.cycleAudioOutput(call.notificationKey)
                        },
                        onOpenCall = { call ->
                            launchActivityTarget(call)
                            cockpit.coordinator.setExpanded(false)
                        },
                        onStopRecording = { recording ->
                            cockpit.callActionsController.stopRecording(recording.notificationKey)
                            cockpit.coordinator.setExpanded(false)
                        },
                        onOpenNavigation = { nav ->
                            launchActivityTarget(nav)
                            cockpit.coordinator.setExpanded(false)
                        },
                        onRefreshCallAudio = { call ->
                            cockpit.callActionsController.refreshAudioState(call.notificationKey)
                        },
                    )
                }
                val pinnedApps = remember(prefs.pinnedPackages) {
                    cockpit.pinnedAppsRepo.resolvePinnedApps(prefs.pinnedPackages)
                }
                val hasActiveNavigation = slotState.visibleQueue.any { it is CockpitLiveActivity.MapsNavActivity }
                DisposableEffect(hasActiveNavigation) {
                    if (!hasActiveNavigation) {
                        onDispose {}
                    } else {
                        val hasFine = ContextCompat.checkSelfPermission(
                            serviceContext,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                        ) == PackageManager.PERMISSION_GRANTED
                        val hasCoarse = ContextCompat.checkSelfPermission(
                            serviceContext,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ) == PackageManager.PERMISSION_GRANTED
                        val locationManager = if (hasFine || hasCoarse) {
                            serviceContext.getSystemService(LocationManager::class.java)
                        } else {
                            null
                        }
                        val listener = LocationListener { location: Location ->
                            if (location.hasSpeed()) {
                                cockpit.coordinator.updateNavigationSpeed(
                                    TripDistanceFormat.metersPerSecondToKmh(location.speed),
                                )
                            }
                        }
                        if (locationManager != null) {
                            @SuppressLint("MissingPermission")
                            runCatching {
                                locationManager.requestLocationUpdates(
                                    LocationManager.PASSIVE_PROVIDER,
                                    1_000L,
                                    0f,
                                    listener,
                                    Looper.getMainLooper(),
                                )
                            }
                        }
                        onDispose {
                            if (locationManager != null) {
                                runCatching { locationManager.removeUpdates(listener) }
                            }
                        }
                    }
                }
                var fullOverlayActive by remember { mutableStateOf(slotState.isExpanded) }

                LaunchedEffect(
                    slotState.isExpanded,
                    slotState.inlineReplyOpen,
                    prefs.cutoutOffsetX,
                    prefs.cutoutOffsetY,
                    cutoutTopPx.intValue,
                    cutoutCenterOffsetXPx.intValue,
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
                        cutoutCenterOffsetXPx = cutoutCenterOffsetXPx.intValue,
                    )
                }

                if (screenOn && !isLandscapeFullscreen.value && prefs.masterEnabled) {
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
                                    // The satellite bubble swaps into the primary slot without expanding.
                                    cockpit.coordinator.focusActivity(activity.id)
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
                                        cutoutCenterOffsetXPx = cutoutCenterOffsetXPx.intValue,
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
                                callAudio = callAudio,
                                activityActions = activityActions,
                                modifier = if (fullOverlayActive) {
                                    val density = serviceContext.resources.displayMetrics.density
                                    Modifier.offset {
                                        IntOffset(
                                            x = cutoutCenterOffsetXPx.intValue + (prefs.cutoutOffsetX * density).toInt(),
                                            y = (prefs.cutoutOffsetY * density).toInt(),
                                        )
                                    }
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
            is CockpitLiveActivity.CallActivity -> {
                if (!CockpitNotificationListener.launchNotification(activity.notificationKey) &&
                    !cockpit.callActionsController.openCall(activity.notificationKey)
                ) {
                    if (!cockpit.pinnedAppsRepo.launchApp(activity.packageName)) openDriveLiftApp()
                }
            }
            is CockpitLiveActivity.ScreenRecordingActivity -> {
                if (!CockpitNotificationListener.launchNotification(activity.notificationKey)) {
                    openDriveLiftApp()
                }
            }
            is CockpitLiveActivity.MediaActivity -> {
                if (!CockpitNotificationListener.launchNotification(activity.notificationKey)) {
                    if (!cockpit.pinnedAppsRepo.launchApp(activity.packageName)) openDriveLiftApp()
                }
            }
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

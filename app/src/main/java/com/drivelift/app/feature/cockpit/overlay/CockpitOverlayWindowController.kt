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
import com.drivelift.app.feature.cockpit.CockpitModule
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Pixel anchor derived strictly from `WindowInsets.displayCutout` bounds with zero hardcoded
 * pixel offsets:
 * - [centerOffsetXPx]: signed horizontal delta from screen horizontal center (`Gravity.CENTER_HORIZONTAL`)
 *   to the exact horizontal center of `boundingRectTop`.
 * - [topPx]: vertical window origin so the pill's vertical center aligns with the cutout's vertical center.
 * - [cutoutWidthPx]: measured pixel width of `boundingRectTop` so the idle pill keeps a clear gap.
 * - [hasCutout]: true when derived from a non-empty cutout bounding rect (used for lock-screen fallback).
 */
internal data class CutoutAnchor(
    val centerOffsetXPx: Int = 0,
    val topPx: Int = 0,
    val cutoutWidthPx: Int = 0,
    val hasCutout: Boolean = false,
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

    /**
     * Converts a [DisplayCutout] delivered to a view's [WindowInsets] (offset by Android relative
     * to that view's window frame) back into display coordinates using the view's on-screen origin
     * before computing the horizontal and vertical anchor.
     */
    fun fromViewWindowInsets(
        windowInsets: WindowInsets?,
        windowLeftPx: Int,
        windowTopPx: Int,
        displayWidthPx: Int,
        pillHeightPx: Int,
    ): CutoutAnchor {
        val cutout = windowInsets?.displayCutout ?: return CutoutAnchor()
        val relativeRect = cutout.boundingRectTop.takeIf { !it.isEmpty }
            ?: cutout.boundingRects.firstOrNull { !it.isEmpty }
            ?: return CutoutAnchor()
        val displayRect = Rect(relativeRect).apply {
            offset(windowLeftPx, windowTopPx)
        }
        return fromCutoutBounds(
            cutoutTopRect = displayRect,
            displayWidthPx = displayWidthPx,
            pillHeightPx = pillHeightPx,
        )
    }

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
            return CutoutAnchor(centerOffsetXPx = 0, topPx = 0, cutoutWidthPx = 0, hasCutout = false)
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
            cutoutWidthPx = cutoutTopRect.width().coerceAtLeast(0),
            hasCutout = true,
        )
    }

    /**
     * Keyguard / lock-screen windows can temporarily report empty cutout insets. Preserves the
     * last known non-empty cutout anchor until Android reports a non-empty cutout again.
     */
    fun resolveWithFallback(
        current: CutoutAnchor,
        lastKnownNonEmpty: CutoutAnchor?,
    ): CutoutAnchor = when {
        current.hasCutout -> current
        lastKnownNonEmpty != null && lastKnownNonEmpty.hasCutout -> lastKnownNonEmpty
        else -> current
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

    /** Clears the deduplication signature so the next layout pass always calls updateViewLayout(). */
    fun invalidateLayoutSignature() {
        lastAppliedSignature = null
    }

    /** Marks the cached window signature stale when the display turns off. */
    fun onScreenOff() {
        lastAppliedSignature = null
    }

    /**
     * Forces a fresh WindowManager.updateViewLayout() on ACTION_SCREEN_ON / ACTION_USER_PRESENT so
     * the WRAP_CONTENT pill window is always re-measured and re-centered on the display cutout.
     */
    fun onScreenOn(
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
        lastAppliedSignature = null
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
    private var screenObserverJob: Job? = null
    private val boundsCoordinator = CockpitWindowBoundsCoordinator { params ->
        val wm = windowManager ?: return@CockpitWindowBoundsCoordinator
        val view = overlayView ?: return@CockpitWindowBoundsCoordinator
        runCatching { wm.updateViewLayout(view, params) }
    }

    val isLandscapeFullscreen = mutableStateOf(false)
    val cutoutTopPx = mutableIntStateOf(0)
    val cutoutCenterOffsetXPx = mutableIntStateOf(0)
    val cutoutWidthPx = mutableIntStateOf(0)

    private var lastKnownCutoutAnchor: CutoutAnchor? = null
    private var isScreenInteractive: Boolean = true
    private var isMasterEnabled: Boolean = true

    val isAttached: Boolean
        get() = overlayView != null

    private fun syncOverlayVisibility() {
        overlayView?.visibility = if (isScreenInteractive && !isLandscapeFullscreen.value && isMasterEnabled) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun handleScreenStateChanged(screenOn: Boolean, cockpit: CockpitModule) {
        isScreenInteractive = screenOn
        isMasterEnabled = cockpit.prefsRepo.isMasterEnabledMirrored()
        syncOverlayVisibility()
        val params = layoutParams ?: return
        if (!screenOn) {
            boundsCoordinator.onScreenOff()
        } else {
            updateMetrics()
            val prefs = cockpit.prefsRepo.state.value
            val slot = cockpit.coordinator.slotState.value
            val density = serviceContext.resources.displayMetrics.density
            boundsCoordinator.onScreenOn(
                params = params,
                isExpanded = slot.isExpanded,
                inlineReplyOpen = slot.inlineReplyOpen,
                cutoutTopPx = cutoutTopPx.intValue,
                offsetXPx = (prefs.cutoutOffsetX * density).toInt(),
                offsetYPx = (prefs.cutoutOffsetY * density).toInt(),
                cutoutCenterOffsetXPx = cutoutCenterOffsetXPx.intValue,
            )
            overlayView?.requestLayout()
        }
    }

    private fun applyCutoutAnchor(anchor: CutoutAnchor) {
        if (anchor.hasCutout) {
            lastKnownCutoutAnchor = anchor
        }
        val resolved = CutoutAnchorCalculator.resolveWithFallback(
            current = anchor,
            lastKnownNonEmpty = lastKnownCutoutAnchor,
        )
        cutoutTopPx.intValue = resolved.topPx
        cutoutCenterOffsetXPx.intValue = resolved.centerOffsetXPx
        cutoutWidthPx.intValue = resolved.cutoutWidthPx
    }

    private fun readDisplayCutoutAnchor(view: View?, viewInsets: WindowInsets?): CutoutAnchor {
        val wm = windowManager ?: return CutoutAnchor()
        val density = serviceContext.resources.displayMetrics.density
        val pillHeightPx = (Tokens.Cockpit.PillHeight.value * density).toInt()
        val metricsAnchor = runCatching {
            val metrics = wm.currentWindowMetrics
            CutoutAnchorCalculator.fromWindowInsets(
                windowInsets = metrics.windowInsets,
                displayWidthPx = metrics.bounds.width(),
                pillHeightPx = pillHeightPx,
            )
        }.getOrDefault(CutoutAnchor())
        if (metricsAnchor.hasCutout) {
            return metricsAnchor
        }
        if (view != null && view.isAttachedToWindow && view.isLaidOut && view.width > 0 && viewInsets != null) {
            val displayWidthPx = runCatching { wm.currentWindowMetrics.bounds.width() }
                .getOrDefault(serviceContext.resources.displayMetrics.widthPixels)
            val locationOnScreen = IntArray(2)
            view.getLocationOnScreen(locationOnScreen)
            val viewAnchor = CutoutAnchorCalculator.fromViewWindowInsets(
                windowInsets = viewInsets,
                windowLeftPx = locationOnScreen[0],
                windowTopPx = locationOnScreen[1],
                displayWidthPx = displayWidthPx,
                pillHeightPx = pillHeightPx,
            )
            if (viewAnchor.hasCutout) {
                return viewAnchor
            }
        }
        return metricsAnchor
    }

    fun updateMetrics() {
        isLandscapeFullscreen.value =
            serviceContext.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val anchor = runCatching {
            readDisplayCutoutAnchor(view = overlayView, viewInsets = overlayView?.rootWindowInsets)
        }.getOrDefault(CutoutAnchor())
        applyCutoutAnchor(anchor)
        syncOverlayVisibility()
    }

    fun attachIfNeeded(scope: CoroutineScope) {
        if (overlayView != null) return
        val wm = windowManager ?: return
        val cockpit = Graph.get(serviceContext).cockpit
        if (!cockpit.prefsRepo.isMasterEnabledMirrored()) return

        isScreenInteractive = cockpit.telemetryMonitor.isScreenOn.value
        isMasterEnabled = cockpit.prefsRepo.isMasterEnabledMirrored()
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
            setOnApplyWindowInsetsListener { v, insets ->
                val anchor = runCatching { readDisplayCutoutAnchor(view = v, viewInsets = insets) }
                    .getOrDefault(CutoutAnchor())
                applyCutoutAnchor(anchor)
                v.onApplyWindowInsets(insets)
            }
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
            visibility = if (isScreenInteractive && !isLandscapeFullscreen.value && isMasterEnabled) {
                View.VISIBLE
            } else {
                View.GONE
            }
            setContent {
                val prefs by cockpit.prefsRepo.state.collectAsStateWithLifecycle()
                val slotState by cockpit.coordinator.slotState.collectAsStateWithLifecycle()
                val telemetry by cockpit.telemetryAdapter.summary.collectAsStateWithLifecycle()
                val torchState by cockpit.torchController.state.collectAsStateWithLifecycle()
                val quickControls by cockpit.quickControlsController.state.collectAsStateWithLifecycle()
                val callAudio by cockpit.callActionsController.audioState.collectAsStateWithLifecycle()
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

                LaunchedEffect(prefs.masterEnabled, isLandscapeFullscreen.value) {
                    isMasterEnabled = prefs.masterEnabled
                    syncOverlayVisibility()
                }

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

                // Keep the pill composed while screen is off and hide via overlayView.visibility =
                // View.GONE so the WRAP_CONTENT window never collapses to 0 width across screen-off.
                if (!isLandscapeFullscreen.value && prefs.masterEnabled) {
                    val displayDensity = serviceContext.resources.displayMetrics.density.coerceAtLeast(1f)
                    val measuredCutoutWidthDp = (cutoutWidthPx.intValue / displayDensity).roundToInt()
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
                                cutoutWidthDp = measuredCutoutWidthDp,
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
                                onTapWifiLocationHint = { cockpit.quickControlsController.onWifiLocationHintTap() },
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
            } else if (added && overlayView === view) {
                screenObserverJob?.cancel()
                screenObserverJob = scope.launch {
                    var initialized = false
                    combine(
                        cockpit.telemetryMonitor.isScreenOn,
                        cockpit.telemetryMonitor.screenWakeCount,
                    ) { screenOn, wakeCount -> screenOn to wakeCount }
                        .collect { (screenOn, _) ->
                            if (!initialized) {
                                initialized = true
                                isScreenInteractive = screenOn
                                isMasterEnabled = cockpit.prefsRepo.isMasterEnabledMirrored()
                                syncOverlayVisibility()
                            } else {
                                handleScreenStateChanged(screenOn = screenOn, cockpit = cockpit)
                            }
                        }
                }
            }
        }
    }

    fun detach() {
        screenObserverJob?.cancel()
        screenObserverJob = null
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

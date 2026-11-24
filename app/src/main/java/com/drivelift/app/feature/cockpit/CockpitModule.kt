package com.drivelift.app.feature.cockpit

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.drivelift.app.Graph
import com.drivelift.app.feature.cockpit.activities.CallState
import com.drivelift.app.feature.cockpit.activities.CockpitLiveActivity
import com.drivelift.app.feature.cockpit.activities.CockpitStateCoordinator
import com.drivelift.app.feature.cockpit.alerts.AndroidTorchHardwareAdapter
import com.drivelift.app.feature.cockpit.alerts.CockpitDriveLiftAlertBridge
import com.drivelift.app.feature.cockpit.alerts.CockpitFlashPattern
import com.drivelift.app.feature.cockpit.alerts.CockpitLedFlashAlertController
import com.drivelift.app.feature.cockpit.alerts.CockpitSystemAlert
import com.drivelift.app.feature.cockpit.alerts.CockpitSystemAlertsMonitor
import com.drivelift.app.feature.cockpit.data.CockpitPreferencesRepository
import com.drivelift.app.feature.cockpit.data.PinnedAppsRepository
import com.drivelift.app.feature.cockpit.hardware.AndroidTorchHardwareBackend
import com.drivelift.app.feature.cockpit.hardware.CockpitQuickControlsController
import com.drivelift.app.feature.cockpit.hardware.CockpitTelemetryMonitor
import com.drivelift.app.feature.cockpit.hardware.CockpitTorchController
import com.drivelift.app.feature.cockpit.notifications.CockpitCallActionsController
import com.drivelift.app.feature.cockpit.notifications.CockpitNotificationListener
import com.drivelift.app.feature.cockpit.notifications.DriveLiftTelemetryAdapter
import com.drivelift.app.feature.cockpit.overlay.CockpitAccessibilityService
import com.drivelift.app.feature.cockpit.overlay.CockpitOverlayService
import com.drivelift.app.feature.cockpit.widget.CockpitGlanceWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Self-contained Cockpit module wiring preferences, pinned launcher apps, 2-slot live activity
 * coordinator, read-only DriveLift telemetry, hardware flashlight, overlay services, and widget sync.
 */
class CockpitModule(
    private val app: Application,
    private val graph: Graph,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val prefsRepo: CockpitPreferencesRepository by lazy {
        CockpitPreferencesRepository(app)
    }

    val pinnedAppsRepo: PinnedAppsRepository by lazy {
        PinnedAppsRepository(app)
    }

    val coordinator: CockpitStateCoordinator by lazy {
        CockpitStateCoordinator(scope = scope).also { coord ->
            scope.launch {
                prefsRepo.state.collect { prefs ->
                    coord.applyPrefs(prefs)
                }
            }
        }
    }

    val callActionsController: CockpitCallActionsController by lazy {
        CockpitCallActionsController(app)
    }

    val torchController: CockpitTorchController by lazy {
        CockpitTorchController(AndroidTorchHardwareBackend(app))
    }

    val quickControlsController: CockpitQuickControlsController by lazy {
        CockpitQuickControlsController(
            context = app,
            clock = graph.clock,
            onDefaultNetworkChanged = { caps ->
                telemetryMonitor.onDefaultNetworkCapabilitiesChanged(caps)
            },
        )
    }

    val telemetryMonitor: CockpitTelemetryMonitor by lazy {
        CockpitTelemetryMonitor(context = app)
    }

    val telemetryAdapter: DriveLiftTelemetryAdapter by lazy {
        DriveLiftTelemetryAdapter(
            desktopDropController = graph.desktopDrop.controller,
            transferDao = graph.db.transfers(),
            coordinator = coordinator,
            clock = graph.clock,
        )
    }

    /** Rear LED flash for a ringing call, an urgent alert and a finished transfer. Off until the user opts in. */
    val ledFlashController: CockpitLedFlashAlertController by lazy {
        CockpitLedFlashAlertController(
            adapter = AndroidTorchHardwareAdapter(app),
            isScreenOn = { app.getSystemService(PowerManager::class.java)?.isInteractive ?: true },
        )
    }

    val systemAlertsMonitor: CockpitSystemAlertsMonitor by lazy {
        CockpitSystemAlertsMonitor(
            context = app,
            scope = scope,
            flashController = ledFlashController,
        )
    }

    /** The system alert the island is showing, after the user's switch and call priority are applied. */
    val activeSystemAlert: StateFlow<CockpitSystemAlert?>
        get() = coordinator.activeSystemAlert

    private val alertsLock = Any()
    private val alertsHolders = LinkedHashSet<Any>()
    private val alertJobs = mutableListOf<Job>()

    /**
     * Starts the system alerts monitor with the first [holder], next to the telemetry monitor, and
     * stops it with the last. Acquiring twice with the same holder counts once. Alerts are mirrored
     * into the coordinator, the LED flash follows the user's switches, and Desktop Drop and transfer
     * events are forwarded as read-only hooks.
     */
    fun acquireSystemAlerts(holder: Any) {
        synchronized(alertsLock) {
            val wasIdle = alertsHolders.isEmpty()
            if (!alertsHolders.add(holder) || !wasIdle) return
            val monitor = systemAlertsMonitor
            val flash = ledFlashController
            val bridge = CockpitDriveLiftAlertBridge(monitor)
            monitor.start()
            alertJobs += scope.launch {
                monitor.activeAlert.collect { alert -> coordinator.setSystemAlert(alert) }
            }
            alertJobs += scope.launch {
                prefsRepo.state.collect { prefs ->
                    flash.enabled = prefs.ledFlashEnabled
                    flash.suppressWhenScreenOn = prefs.ledFlashSuppressWhenScreenOn
                    if (!prefs.ledFlashEnabled) flash.cancel()
                }
            }
            alertJobs += scope.launch {
                coordinator.slotState
                    .map { slot ->
                        slot.visibleQueue.any { it is CockpitLiveActivity.CallActivity && it.state == CallState.INCOMING }
                    }
                    .distinctUntilChanged()
                    .collect { ringing ->
                        if (ringing) flash.trigger(CockpitFlashPattern.INCOMING_CALL) else flash.cancel()
                    }
            }
            alertJobs += scope.launch {
                monitor.events
                    .filterIsInstance<CockpitSystemAlert.BatteryLow>()
                    .collect { low -> if (low.isCritical) flash.trigger(CockpitFlashPattern.URGENT_ALERT) }
            }
            alertJobs += scope.launch {
                graph.desktopDrop.controller.snapshot.collect { bridge.onDropSnapshot(it) }
            }
            alertJobs += scope.launch {
                telemetryAdapter.summary.collect { bridge.onTelemetry(it) }
            }
        }
    }

    fun releaseSystemAlerts(holder: Any) {
        synchronized(alertsLock) {
            if (!alertsHolders.remove(holder) || alertsHolders.isNotEmpty()) return
            alertJobs.forEach { it.cancel() }
            alertJobs.clear()
            systemAlertsMonitor.stop()
            ledFlashController.cancel()
            coordinator.setSystemAlert(null)
        }
    }

    private val _openHubPending = MutableStateFlow(false)
    val openHubPending: StateFlow<Boolean> = _openHubPending.asStateFlow()

    fun requestOpenHub() {
        _openHubPending.value = true
    }

    fun consumeOpenHubRequest() {
        _openHubPending.value = false
    }

    fun setDynamicIslandEnabled(enabled: Boolean): Boolean {
        if (enabled && !hasAnyOverlayPermission()) {
            prefsRepo.setMasterEnabledAsync(false)
            coordinator.setExpanded(false)
            syncOverlayService(false)
            refreshWidgetsAsync()
            return false
        }
        prefsRepo.setMasterEnabledAsync(enabled)
        if (!enabled) coordinator.setExpanded(false)
        syncOverlayService(enabled)
        refreshWidgetsAsync()
        return true
    }

    suspend fun setDynamicIslandEnabledAndSync(enabled: Boolean): Boolean {
        if (enabled && !hasAnyOverlayPermission()) {
            prefsRepo.setMasterEnabled(false)
            coordinator.setExpanded(false)
            syncOverlayService(false)
            runCatching { CockpitGlanceWidget.updateAllWidgets(app) }
            return false
        }
        prefsRepo.setMasterEnabled(enabled)
        if (!enabled) coordinator.setExpanded(false)
        syncOverlayService(enabled)
        runCatching { CockpitGlanceWidget.updateAllWidgets(app) }
        return true
    }

    fun syncOverlayService(enabled: Boolean = prefsRepo.isMasterEnabledMirrored()) {
        val effectiveEnabled = enabled && prefsRepo.isMasterEnabledMirrored()
        CockpitAccessibilityService.syncActiveOverlay(effectiveEnabled)

        val shouldFallback = CockpitOverlayService.shouldMountFallbackOverlay(
            masterEnabled = effectiveEnabled,
            hasOverlayPermission = hasOverlayPermission(),
            accessibilityOverlayActive = CockpitAccessibilityService.isConnected,
        )
        val intent = Intent(app, CockpitOverlayService::class.java)
        if (shouldFallback) {
            intent.action = CockpitOverlayService.ACTION_START
            if (Build.VERSION.SDK_INT >= 26) {
                runCatching { app.startForegroundService(intent) }
            } else {
                runCatching { app.startService(intent) }
            }
        } else {
            intent.action = CockpitOverlayService.ACTION_STOP
            runCatching { app.stopService(intent) }
        }
    }

    private fun refreshWidgetsAsync() {
        scope.launch(Dispatchers.Default) {
            runCatching { CockpitGlanceWidget.updateAllWidgets(app) }
        }
    }

    fun hasAccessibilityOverlay(): Boolean = CockpitAccessibilityService.isServiceEnabled(app)

    fun hasOverlayPermission(): Boolean = runCatching {
        Settings.canDrawOverlays(app)
    }.getOrDefault(false)

    fun hasAnyOverlayPermission(): Boolean = hasAccessibilityOverlay() || hasOverlayPermission()

    fun hasNotificationAccess(): Boolean =
        CockpitNotificationListener.isPermissionGranted(app)

    fun isIgnoringBatteryOptimizations(): Boolean = runCatching {
        app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName) == true
    }.getOrDefault(false)
}

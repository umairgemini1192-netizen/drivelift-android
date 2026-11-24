package com.drivelift.app.feature.applock

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.service.quicksettings.TileService
import com.drivelift.app.Graph
import com.drivelift.app.feature.applock.data.AppLockPreferencesRepository
import com.drivelift.app.feature.applock.engine.AppLockClock
import com.drivelift.app.feature.applock.engine.AppLockConfig
import com.drivelift.app.feature.applock.engine.AppLockPolicyEngine
import com.drivelift.app.feature.applock.engine.ForegroundEvent
import com.drivelift.app.feature.applock.engine.ForegroundFilterResult
import com.drivelift.app.feature.applock.engine.ForegroundPackageChangeFilter
import com.drivelift.app.feature.applock.engine.LockDecision
import com.drivelift.app.feature.applock.engine.MonitorFilterConfig
import com.drivelift.app.feature.applock.engine.PinCredentialHasher
import com.drivelift.app.feature.applock.engine.PinLockoutState
import com.drivelift.app.feature.applock.engine.ReLockGracePeriod
import com.drivelift.app.feature.applock.engine.TamperProtectionStatus
import com.drivelift.app.feature.applock.monitor.UsageStatsForegroundFallbackMonitor
import com.drivelift.app.feature.applock.tile.AppLockPauseTileService
import com.drivelift.app.feature.applock.ui.AppLockGateActivity
import com.drivelift.app.feature.cockpit.overlay.CockpitAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Mode in which [AppLockGateActivity] is presented.
 */
enum class AppLockGateMode {
    /** Intercepts a locked foreground app and unlocks it upon authentication. */
    UNLOCK_APP,

    /** Authenticates the user before activating the 15-minute RAM-only pause from Quick Settings. */
    AUTHORIZE_PAUSE,
}

/**
 * Request payload used when launching [AppLockGateActivity].
 */
data class AppLockGateRequest(
    val mode: AppLockGateMode,
    val packageName: String = "",
    val appLabel: String = "",
)

/**
 * Result of verifying a PIN attempt on the gate screen.
 */
sealed class PinVerifyResult {
    data object Success : PinVerifyResult()
    data class WrongPin(val lockoutState: PinLockoutState) : PinVerifyResult()
    data class LockedOut(val remainingMs: Long) : PinVerifyResult()
    data object NoPinConfigured : PinVerifyResult()
}

/**
 * Active foreground detection engine reported to the App Lock Manager UI.
 */
enum class AppLockMonitorMode {
    ACCESSIBILITY_ACTIVE,
    USAGE_STATS_FALLBACK,
    NEEDS_PERMISSION,
}

/**
 * Launchable app entry displayed in the App Lock Manager list.
 */
data class LockableAppEntry(
    val packageName: String,
    val appLabel: String,
    val isLocked: Boolean,
    val isSystemSettings: Boolean = false,
)

/**
 * Snapshot state rendered by AppLockManagerScreen.
 */
data class AppLockManagerUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val config: AppLockConfig = AppLockConfig(),
    val hasPinConfigured: Boolean = false,
    val pinLength: Int = AppLockPreferencesRepository.DEFAULT_PIN_LENGTH,
    val isPaused: Boolean = false,
    val pauseRemainingMs: Long = 0L,
    val monitorMode: AppLockMonitorMode = AppLockMonitorMode.NEEDS_PERMISSION,
    val tamperProtection: TamperProtectionStatus = TamperProtectionStatus(
        isSettingsLocked = false,
        isRecommended = false,
        reason = "App lock is disabled",
    ),
    val searchQuery: String = "",
    val installedApps: List<LockableAppEntry> = emptyList(),
) {
    val filteredApps: List<LockableAppEntry>
        get() {
            val q = searchQuery.trim()
            if (q.isEmpty()) return installedApps
            return installedApps.filter { entry ->
                entry.appLabel.contains(q, ignoreCase = true) ||
                    entry.packageName.contains(q, ignoreCase = true)
            }
        }

    val lockedCount: Int
        get() = config.lockedPackages.size +
            if (config.tamperProtectionEnabled && AppLockConfig.SETTINGS_PACKAGE_NAME !in config.lockedPackages) 1 else 0
}

/**
 * Self-contained Phase 5 App Lock coordinator wiring Phase 4 engine primitives with
 * [CockpitAccessibilityService], [UsageStatsForegroundFallbackMonitor], [AppLockGateActivity],
 * [AppLockPauseTileService], and App Lock Manager UI.
 */
class AppLockModule(
    private val app: Application,
    private val graph: Graph,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val prefs = AppLockPreferencesRepository(app)
    val hasher = PinCredentialHasher()
    val engine = AppLockPolicyEngine(clock = AppLockClock { graph.clock() })

    private val monitorFilter = ForegroundPackageChangeFilter(
        config = MonitorFilterConfig(
            selfPackageName = app.packageName,
            lockActivityClassName = AppLockGateActivity::class.java.name,
            launcherPackages = resolveLauncherPackages(app),
        ),
    )

    /** Replaceable in tests to assert gate launches without starting a real Activity. */
    var gateLauncher: (Context, AppLockGateRequest) -> Unit = { context, request ->
        AppLockGateActivity.launch(context, request)
    }

    /** Replaceable in tests to supply deterministic synthetic installed apps. */
    var installedAppsLoader: () -> List<Pair<String, String>> = {
        loadLaunchableApps(app)
    }

    /** Replaceable in tests to simulate Accessibility connection state. */
    var accessibilityConnectedProvider: () -> Boolean = {
        CockpitAccessibilityService.isConnected
    }

    val fallbackMonitor = UsageStatsForegroundFallbackMonitor(
        context = app,
        scope = scope,
        clockMs = { graph.clock() },
        isAccessibilityConnected = { accessibilityConnectedProvider() },
        onForegroundEvent = { pkg, cls -> onForegroundWindowChanged(pkg, cls) },
    )

    private val unlockLock = Any()
    @Volatile
    private var activeUnlockedPackage: String? = null

    private var cachedRawApps: List<Pair<String, String>> = emptyList()

    private val _openManagerPending = MutableStateFlow(false)
    val openManagerPending: StateFlow<Boolean> = _openManagerPending.asStateFlow()

    private val _uiState = MutableStateFlow(buildUiState())
    val uiState: StateFlow<AppLockManagerUiState> = _uiState.asStateFlow()

    fun requestOpenManager() {
        _openManagerPending.value = true
    }

    fun consumeOpenManagerRequest() {
        _openManagerPending.value = false
    }

    /**
     * Single clean foreground hook called by [CockpitAccessibilityService] (and by
     * [UsageStatsForegroundFallbackMonitor] when Accessibility is off).
     */
    fun onForegroundWindowChanged(packageName: String?, className: String?) {
        if (packageName.isNullOrBlank()) return
        val filtered = monitorFilter.onForegroundEvent(
            ForegroundEvent(
                packageName = packageName,
                className = className,
                timestampMs = graph.clock(),
            ),
        )
        if (filtered is ForegroundFilterResult.Ignored) {
            if (filtered.reason == com.drivelift.app.feature.applock.engine.IgnoreReason.LAUNCHER) {
                synchronized(unlockLock) {
                    activeUnlockedPackage = null
                }
                monitorFilter.reset()
            }
            return
        }
        val accepted = filtered as? ForegroundFilterResult.Accepted ?: return
        val stayUnlocked = synchronized(unlockLock) {
            if (accepted.packageName == activeUnlockedPackage) {
                true
            } else {
                activeUnlockedPackage = null
                false
            }
        }
        if (stayUnlocked) return

        val evaluation = engine.evaluateForegroundPackage(accepted.packageName, prefs.config.value)
        if (evaluation.decision == LockDecision.REQUIRE_AUTH) {
            val label = resolveAppLabel(accepted.packageName)
            gateLauncher(
                app,
                AppLockGateRequest(
                    mode = AppLockGateMode.UNLOCK_APP,
                    packageName = accepted.packageName,
                    appLabel = label,
                ),
            )
        }
    }

    /**
     * Immediately revokes all per-app grace grants and pauses fallback polling when the screen turns off.
     */
    fun onScreenOff() {
        synchronized(unlockLock) {
            activeUnlockedPackage = null
        }
        engine.onScreenOff()
        monitorFilter.reset()
        fallbackMonitor.onScreenOff()
        refreshUiState()
    }

    /**
     * Resumes fallback foreground polling cleanly on screen wake when App Lock is enabled.
     */
    fun onScreenOn() {
        fallbackMonitor.onScreenOn(appLockEnabled = prefs.config.value.enabled)
        refreshUiState()
    }

    /**
     * Starts or stops the UsageStatsManager fallback monitor depending on Accessibility state.
     */
    fun syncMonitoringMode() {
        fallbackMonitor.sync(appLockEnabled = prefs.config.value.enabled)
        refreshUiState()
        requestTileRefresh()
    }

    fun refreshInstalledApps() {
        scope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching { installedAppsLoader() }
                .onSuccess { apps ->
                    cachedRawApps = apps
                    _uiState.value = buildUiState(
                        isLoading = false,
                        errorMessage = null,
                        searchQuery = _uiState.value.searchQuery,
                    )
                }
                .onFailure { err ->
                    _uiState.value = buildUiState(
                        isLoading = false,
                        errorMessage = err.message ?: "Unable to read installed apps.",
                        searchQuery = _uiState.value.searchQuery,
                    )
                }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setEnabled(enabled: Boolean) {
        prefs.setEnabled(enabled)
        if (!enabled) {
            synchronized(unlockLock) {
                activeUnlockedPackage = null
            }
            engine.resumeAppLock()
            monitorFilter.reset()
        }
        syncMonitoringMode()
    }

    fun toggleAppLocked(packageName: String) {
        if (packageName == AppLockConfig.SETTINGS_PACKAGE_NAME) {
            val currentlyLocked = prefs.config.value.tamperProtectionEnabled ||
                AppLockConfig.SETTINGS_PACKAGE_NAME in prefs.config.value.lockedPackages
            prefs.setTamperProtectionEnabled(!currentlyLocked)
        } else {
            prefs.toggleLockedPackage(packageName)
        }
        refreshUiState()
    }

    fun setTamperProtectionEnabled(enabled: Boolean) {
        prefs.setTamperProtectionEnabled(enabled)
        refreshUiState()
    }

    fun setGracePeriod(gracePeriod: ReLockGracePeriod) {
        prefs.setGracePeriod(gracePeriod)
        refreshUiState()
    }

    fun setAllowBiometric(allow: Boolean) {
        prefs.setAllowBiometric(allow)
        refreshUiState()
    }

    fun savePin(pin: String, iterations: Int = PinCredentialHasher.DEFAULT_ITERATIONS) {
        val hashed = hasher.hashPin(pin, iterations = iterations)
        prefs.savePinCredential(hashed, pinLength = pin.length)
        refreshUiState()
    }

    fun verifyPin(pin: String, mode: AppLockGateMode, targetPackage: String): PinVerifyResult {
        if (engine.isPinLockedOut()) {
            return PinVerifyResult.LockedOut(engine.currentLockoutState().remainingLockoutMs(graph.clock()))
        }
        val credential = prefs.readPinCredential() ?: return PinVerifyResult.NoPinConfigured
        val matches = hasher.verifyPin(pin, credential)
        return if (matches) {
            onAuthenticationSucceeded(mode, targetPackage)
            PinVerifyResult.Success
        } else {
            val state = engine.recordFailedPinAttempt(prefs.config.value)
            PinVerifyResult.WrongPin(state)
        }
    }

    fun onAuthenticationSucceeded(mode: AppLockGateMode, targetPackage: String) {
        when (mode) {
            AppLockGateMode.UNLOCK_APP -> {
                if (targetPackage.isNotBlank()) {
                    synchronized(unlockLock) {
                        activeUnlockedPackage = targetPackage
                    }
                    engine.recordUnlock(targetPackage)
                } else {
                    engine.recordPinSuccess()
                }
            }
            AppLockGateMode.AUTHORIZE_PAUSE -> {
                engine.recordPinSuccess()
                engine.pauseAppLock(prefs.config.value)
            }
        }
        refreshUiState()
        requestTileRefresh()
    }

    internal fun currentActiveUnlockedPackage(): String? = activeUnlockedPackage

    fun resumeFromPause() {
        synchronized(unlockLock) {
            activeUnlockedPackage = null
        }
        engine.resumeAppLock()
        monitorFilter.reset()
        refreshUiState()
        requestTileRefresh()
    }

    fun refreshUiState() {
        _uiState.value = buildUiState(
            isLoading = _uiState.value.isLoading,
            errorMessage = _uiState.value.errorMessage,
            searchQuery = _uiState.value.searchQuery,
        )
    }

    private fun buildUiState(
        isLoading: Boolean = false,
        errorMessage: String? = null,
        searchQuery: String = "",
    ): AppLockManagerUiState {
        val cfg = prefs.config.value
        val mode = when {
            accessibilityConnectedProvider() -> AppLockMonitorMode.ACCESSIBILITY_ACTIVE
            fallbackMonitor.hasUsageAccess() -> AppLockMonitorMode.USAGE_STATS_FALLBACK
            else -> AppLockMonitorMode.NEEDS_PERMISSION
        }
        val entries = cachedRawApps.map { (pkg, label) ->
            val isSettings = pkg == AppLockConfig.SETTINGS_PACKAGE_NAME
            val isLocked = pkg in cfg.lockedPackages || (isSettings && cfg.tamperProtectionEnabled)
            LockableAppEntry(
                packageName = pkg,
                appLabel = label,
                isLocked = isLocked,
                isSystemSettings = isSettings,
            )
        }.sortedWith(
            compareByDescending<LockableAppEntry> { it.isLocked }
                .thenBy { it.appLabel.lowercase() },
        )

        return AppLockManagerUiState(
            isLoading = isLoading,
            errorMessage = errorMessage,
            config = cfg,
            hasPinConfigured = prefs.hasPinConfigured.value,
            pinLength = prefs.pinLength.value,
            isPaused = engine.isPaused(),
            pauseRemainingMs = engine.pauseRemainingMs(),
            monitorMode = mode,
            tamperProtection = engine.evaluateTamperProtection(cfg),
            searchQuery = searchQuery,
            installedApps = entries,
        )
    }

    private fun resolveAppLabel(packageName: String): String {
        cachedRawApps.firstOrNull { it.first == packageName }?.second?.let { return it }
        if (packageName == AppLockConfig.SETTINGS_PACKAGE_NAME) return "Settings"
        return runCatching {
            val pm = app.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info)?.toString()?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: packageName
    }

    private fun requestTileRefresh() {
        runCatching {
            TileService.requestListeningState(
                app,
                ComponentName(app, AppLockPauseTileService::class.java),
            )
        }
    }

    companion object {
        const val EXTRA_OPEN_APP_LOCK = "com.drivelift.app.extra.OPEN_APP_LOCK"

        private fun resolveLauncherPackages(context: Context): Set<String> {
            val defaults = MonitorFilterConfig.DEFAULT_LAUNCHER_PACKAGES.toMutableSet()
            runCatching {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                val resolved = context.packageManager.queryIntentActivities(intent, 0)
                for (info in resolved) {
                    val pkg = info.activityInfo?.packageName
                    if (!pkg.isNullOrBlank()) defaults.add(pkg)
                }
            }
            return defaults
        }

        private fun loadLaunchableApps(context: Context): List<Pair<String, String>> {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
            val seen = LinkedHashMap<String, String>()
            for (info in resolved) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (pkg == context.packageName) continue
                if (pkg in seen) continue
                val label = info.loadLabel(pm)?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: pkg
                seen[pkg] = label
            }
            if (AppLockConfig.SETTINGS_PACKAGE_NAME !in seen) {
                seen[AppLockConfig.SETTINGS_PACKAGE_NAME] = "Settings"
            }
            return seen.entries.map { it.key to it.value }.sortedBy { it.second.lowercase() }
        }
    }
}

package com.drivelift.app.feature.cleaner

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import com.drivelift.app.Graph
import com.drivelift.app.feature.automode.AdbPairingNotifier
import com.drivelift.app.feature.automode.AdbSilentExecutor
import com.drivelift.app.feature.automode.AndroidAutoModeSystemGateway
import com.drivelift.app.feature.automode.AndroidKeystoreAdbKeyStorage
import com.drivelift.app.feature.automode.AutoModeAvailability
import com.drivelift.app.feature.automode.AutoModeStatusSnapshot
import com.drivelift.app.feature.automode.AutoModeWizardState
import com.drivelift.app.feature.automode.AutoModeWizardStateMachine
import com.drivelift.app.feature.automode.AutoSessionOutcome
import com.drivelift.app.feature.automode.LibAdbTransport
import com.drivelift.app.feature.automode.SharedPrefsAutoModePreferences
import com.drivelift.app.feature.cleaner.cache.AndroidAppStorageStatsSource
import com.drivelift.app.feature.cleaner.cache.GuidedCleanTarget
import com.drivelift.app.feature.cleaner.cache.GuidedSessionController
import com.drivelift.app.feature.cleaner.engine.AppCacheAndUsageAggregator
import com.drivelift.app.feature.cleaner.engine.CleanerCategory
import com.drivelift.app.feature.cleaner.engine.CleanerCategoryGroup
import com.drivelift.app.feature.cleaner.engine.CleanerItem
import com.drivelift.app.feature.cleaner.engine.DeepJunkScanner
import com.drivelift.app.feature.cleaner.engine.DuplicateFileFinder
import com.drivelift.app.feature.cleaner.engine.DuplicateGroup
import com.drivelift.app.feature.cleaner.engine.SafeStorageCleanupExecutor
import com.drivelift.app.feature.cleaner.ui.CleanerDashboardUiState
import com.drivelift.app.feature.cleaner.ui.DeepCleanCardSummary
import com.drivelift.app.feature.cleaner.ui.DeepCleanDestination
import com.drivelift.app.feature.hibernation.engine.AppDrainCandidate
import com.drivelift.app.feature.hibernation.engine.BackgroundDrainerScorer
import com.drivelift.app.feature.hibernation.engine.HibernationMode
import com.drivelift.app.feature.hibernation.engine.ProtectedAppRule
import com.drivelift.app.feature.hibernation.engine.ProtectionReason
import com.drivelift.app.feature.hibernation.engine.SleepSessionStateMachine
import com.drivelift.app.feature.hibernation.ui.SleepModeUiState
import com.drivelift.app.feature.hibernation.ui.defaultProtectedRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Self-contained coordinator wiring Storage Cleaner, Sleep Mode, Android StorageStatsManager /
 * UsageStatsManager, Built-in Auto Mode (embedded localhost Wireless Debugging ADB client), and
 * Guided Pill fallback.
 */
class CleanerSleepModule(
    private val app: Application,
    private val graph: Graph,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val statsSource = AndroidAppStorageStatsSource(
        context = app,
        nowMs = { graph.clock() },
    )
    private val userWhitelistPackages = MutableStateFlow<List<ProtectedAppRule>>(emptyList())

    private val autoModeKeyStorage = AndroidKeystoreAdbKeyStorage(app)
    private val autoModePrefs = SharedPrefsAutoModePreferences(app)
    val autoModeSystemGateway = AndroidAutoModeSystemGateway(app)
    private val autoModeTransport = LibAdbTransport(app, autoModeKeyStorage)
    private val autoModeWizardMachine = AutoModeWizardStateMachine()

    val adbSilentExecutor = AdbSilentExecutor(
        transport = autoModeTransport,
        systemGateway = autoModeSystemGateway,
        keyStorage = autoModeKeyStorage,
        preferences = autoModePrefs,
        protectedPackagesProvider = {
            userWhitelistPackages.value.map { it.packageName }.toSet()
        },
    )

    private val junkScanner = DeepJunkScanner(nowMs = { graph.clock() })
    private val duplicateFinder = DuplicateFileFinder()
    private val appAggregator = AppCacheAndUsageAggregator(
        statsSource = statsSource,
        nowMs = { graph.clock() },
    )
    private val cleanupExecutor = SafeStorageCleanupExecutor()
    private val drainerScorer = BackgroundDrainerScorer()
    private val sleepStateMachine = SleepSessionStateMachine()
    private val guidedCleanController = GuidedSessionController(sleepStateMachine, nowMs = { graph.clock() })
    private val guidedSleepController = GuidedSessionController(
        sleepStateMachine = sleepStateMachine,
        nowMs = { graph.clock() },
        isAppAlreadyStopped = { pkg -> isPackageStopped(pkg) },
    )

    private var cachedDuplicateGroups: List<DuplicateGroup> = emptyList()
    private var initialScanTriggered = false

    private val _autoModeWizardState = MutableStateFlow(
        autoModeWizardMachine.onEnvironmentUpdated(
            AutoModeWizardState(),
            adbSilentExecutor.environmentSnapshot(),
        ),
    )
    val autoModeWizardState: StateFlow<AutoModeWizardState> = _autoModeWizardState.asStateFlow()

    private val _cleanerState = MutableStateFlow(
        CleanerDashboardUiState(
            autoModeStatus = adbSilentExecutor.inspectStatus(),
        ),
    )
    val cleanerState: StateFlow<CleanerDashboardUiState> = _cleanerState.asStateFlow()

    private val _sleepState = MutableStateFlow(
        SleepModeUiState(
            autoModeStatus = adbSilentExecutor.inspectStatus(),
            protectedRules = defaultProtectedRules(),
        ),
    )
    val sleepState: StateFlow<SleepModeUiState> = _sleepState.asStateFlow()

    fun ensureInitialScan() {
        if (!initialScanTriggered) {
            initialScanTriggered = true
            runFullCleanerScan()
        }
    }

    fun runFullCleanerScan() {
        scope.launch {
            val status = adbSilentExecutor.inspectStatus()
            val hasUsage = statsSource.hasUsageStatsAccess()
            _cleanerState.update {
                it.copy(
                    isLoading = true,
                    errorMessage = null,
                    needsUsageAccess = !hasUsage,
                    autoModeStatus = status,
                    celebrationReclaimedBytes = null,
                )
            }

            runCatching {
                val roots = defaultScanRoots()
                val junkResult = junkScanner.scan(roots)
                val appGroups = if (hasUsage) appAggregator.aggregate() else emptyList()
                val dupGroups = duplicateFinder.findDuplicates(roots)
                cachedDuplicateGroups = dupGroups

                val allGroups = mergeGroups(junkResult.groups, appGroups, dupGroups)
                val deviceStorage = statsSource.queryDeviceStorageSummary()
                val breakdown = appAggregator.buildCapacityBreakdown(
                    totalDeviceBytes = deviceStorage.totalBytes,
                    freeDeviceBytes = deviceStorage.freeBytes,
                    groups = allGroups,
                    duplicateGroups = dupGroups,
                )

                val defaultSelectedIds = allGroups.asSequence()
                    .flatMap { it.items.asSequence() }
                    .filter { it.selectedByDefault }
                    .map { it.id }
                    .toSet()

                val deepCards = buildDeepCleanCards(allGroups, dupGroups)

                _cleanerState.update {
                    it.copy(
                        isLoading = false,
                        needsUsageAccess = !hasUsage,
                        groups = allGroups,
                        selectedItemIds = defaultSelectedIds,
                        breakdown = breakdown,
                        deepCleanCards = deepCards,
                        scannedFileCount = junkResult.scannedFileCount,
                        autoModeStatus = status,
                    )
                }
            }.onFailure { err ->
                _cleanerState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = err.message ?: "Unable to complete storage scan.",
                    )
                }
            }
        }
    }

    fun toggleCategoryExpanded(category: CleanerCategory) {
        _cleanerState.update { state ->
            val next = if (category in state.expandedCategories) {
                state.expandedCategories - category
            } else {
                state.expandedCategories + category
            }
            state.copy(expandedCategories = next)
        }
    }

    fun toggleCategorySelection(category: CleanerCategory) {
        _cleanerState.update { state ->
            val group = state.groups.firstOrNull { it.category == category } ?: return@update state
            val itemIds = group.items.map { it.id }.toSet()
            val allSelected = itemIds.isNotEmpty() && itemIds.all { it in state.selectedItemIds }
            val next = if (allSelected) {
                state.selectedItemIds - itemIds
            } else {
                state.selectedItemIds + itemIds
            }
            state.copy(selectedItemIds = next)
        }
    }

    fun toggleItemSelection(itemId: String) {
        _cleanerState.update { state ->
            val next = if (itemId in state.selectedItemIds) {
                state.selectedItemIds - itemId
            } else {
                state.selectedItemIds + itemId
            }
            state.copy(selectedItemIds = next)
        }
    }

    fun focusDeepCleanCategory(destination: DeepCleanDestination) {
        val targetCategory = when (destination) {
            DeepCleanDestination.WHATSAPP -> CleanerCategory.WHATSAPP_BLOAT
            DeepCleanDestination.LARGE_FILES -> CleanerCategory.LARGE_FILE
            DeepCleanDestination.DUPLICATES -> CleanerCategory.DUPLICATE_FILES
            DeepCleanDestination.RARELY_USED -> CleanerCategory.RARELY_USED_FILES
            DeepCleanDestination.SLEEP_MODE -> return
        }
        _cleanerState.update { state ->
            state.copy(
                celebrationReclaimedBytes = null,
                expandedCategories = state.expandedCategories + targetCategory,
            )
        }
    }

    fun executeSelectedCleanup() {
        scope.launch {
            val state = _cleanerState.value
            val selectedItems = state.groups.flatMap { it.items }.filter { it.id in state.selectedItemIds }
            if (selectedItems.isEmpty()) return@launch

            val cacheItems = selectedItems.filter {
                it.category == CleanerCategory.HIDDEN_CACHE || it.category == CleanerCategory.VISIBLE_CACHE
            }
            val filePaths = selectedItems
                .filterNot { it.category == CleanerCategory.HIDDEN_CACHE || it.category == CleanerCategory.VISIBLE_CACHE }
                .filterNot { it.category == CleanerCategory.RARELY_USED_APPS }
                .map { it.path }

            val fileResult = if (filePaths.isNotEmpty()) {
                cleanupExecutor.execute(
                    targetPaths = filePaths,
                    duplicateGroups = cachedDuplicateGroups,
                    protectedRoots = setOf(app.filesDir.absolutePath, app.noBackupFilesDir.absolutePath),
                    dryRun = false,
                )
            } else {
                null
            }

            var totalReclaimed = fileResult?.reclaimedBytes ?: 0L
            var totalDeleted = fileResult?.deletedPaths?.size ?: 0

            val status = adbSilentExecutor.inspectStatus()
            val guidedTargets = mutableListOf<GuidedCleanTarget>()
            var fallbackReason: String? = null

            if (cacheItems.isNotEmpty()) {
                if (status.effectiveMode == HibernationMode.AUTO_SILENT) {
                    _cleanerState.update {
                        it.copy(
                            autoProgressMessage = "Auto Mode: trimming caches for ${cacheItems.size} apps...",
                            autoModeStatus = status,
                        )
                    }
                    when (val outcome = adbSilentExecutor.runWithAutoSession { executor -> executor.trimCachesSilent() }) {
                        is AutoSessionOutcome.Completed -> {
                            if (outcome.value.isSuccess) {
                                totalReclaimed += cacheItems.sumOf { it.sizeBytes }
                                totalDeleted += cacheItems.size
                            } else {
                                fallbackReason = outcome.value.exceptionOrNull()?.message
                                    ?: "Could not trim caches automatically - using guided steps this time"
                                for (cacheItem in cacheItems) {
                                    val pkg = cacheItem.packageName ?: continue
                                    guidedTargets.add(GuidedCleanTarget(pkg, cacheItem.name, cacheItem.sizeBytes))
                                }
                            }
                        }
                        is AutoSessionOutcome.FallbackToGuided -> {
                            fallbackReason = outcome.reason
                            for (cacheItem in cacheItems) {
                                val pkg = cacheItem.packageName ?: continue
                                guidedTargets.add(GuidedCleanTarget(pkg, cacheItem.name, cacheItem.sizeBytes))
                            }
                        }
                    }
                    _cleanerState.update { it.copy(autoProgressMessage = null) }
                } else {
                    if (status.isConfigured && status.availability == AutoModeAvailability.NEEDS_WIFI) {
                        fallbackReason = AdbSilentExecutor.FALLBACK_REASON_NO_WIFI
                    }
                    for (cacheItem in cacheItems) {
                        val pkg = cacheItem.packageName ?: continue
                        guidedTargets.add(GuidedCleanTarget(pkg, cacheItem.name, cacheItem.sizeBytes))
                    }
                }
            }

            val refreshedStatus = adbSilentExecutor.inspectStatus()
            if (guidedTargets.isNotEmpty()) {
                val pill = guidedCleanController.startGuidedClean(
                    targets = guidedTargets,
                    fallbackReason = fallbackReason,
                )
                _cleanerState.update {
                    it.copy(
                        guidedPill = pill,
                        autoModeStatus = refreshedStatus,
                        autoProgressMessage = null,
                    )
                }
            } else {
                _cleanerState.update {
                    it.copy(
                        celebrationReclaimedBytes = totalReclaimed,
                        celebrationDeletedCount = totalDeleted,
                        selectedItemIds = emptySet(),
                        autoModeStatus = refreshedStatus,
                        autoProgressMessage = null,
                    )
                }
            }
        }
    }

    fun confirmGuidedCleanStep() {
        val nextPill = guidedCleanController.confirmCurrentStep()
        if (nextPill == null) {
            val prev = _cleanerState.value.guidedPill
            _cleanerState.update {
                it.copy(
                    guidedPill = null,
                    celebrationReclaimedBytes = (prev?.reclaimedBytes ?: 0L),
                    celebrationDeletedCount = (prev?.completedCount ?: 0) + 1,
                )
            }
        } else {
            _cleanerState.update { it.copy(guidedPill = nextPill) }
        }
    }

    fun skipGuidedCleanStep() {
        val nextPill = guidedCleanController.skipCurrentStep()
        _cleanerState.update { it.copy(guidedPill = nextPill) }
    }

    fun cancelGuidedCleanSession() {
        guidedCleanController.cancelActiveSession()
        _cleanerState.update { it.copy(guidedPill = null) }
    }

    // -------------------------------------------------------------------------
    // Built-in Auto Mode setup wizard & notification pairing flow
    // -------------------------------------------------------------------------

    fun refreshAutoModeStatus(): AutoModeStatusSnapshot {
        val status = adbSilentExecutor.inspectStatus()
        val env = adbSilentExecutor.environmentSnapshot()
        _autoModeWizardState.update { current ->
            autoModeWizardMachine.onEnvironmentUpdated(current, env)
        }
        _cleanerState.update { it.copy(autoModeStatus = status) }
        _sleepState.update { it.copy(autoModeStatus = status) }
        return status
    }

    fun postPairingNotification() {
        refreshAutoModeStatus()
        val current = _autoModeWizardState.value
        val next = autoModeWizardMachine.onPairingNotificationPosted(current)
        _autoModeWizardState.value = next
        if (next.pairingNotificationPosted) {
            AdbPairingNotifier.showPairingPrompt(app)
        }
    }

    fun submitPairingCodeFromWizard(rawCode: String) {
        scope.launch {
            performPairingAndBootstrap(rawCode, fromNotification = false)
        }
    }

    suspend fun handlePairingCodeFromNotification(rawCode: String) {
        performPairingAndBootstrap(rawCode, fromNotification = true)
    }

    private suspend fun performPairingAndBootstrap(rawCode: String, fromNotification: Boolean) {
        val sanitized = autoModeWizardMachine.sanitizePairingCode(rawCode)
        if (sanitized == null) {
            val msg = "Enter the 6-digit pairing code shown in Wireless debugging."
            _autoModeWizardState.update { autoModeWizardMachine.onPairingFailed(it, msg) }
            if (fromNotification) {
                AdbPairingNotifier.showPairingPrompt(app, errorHint = msg)
            }
            return
        }

        _autoModeWizardState.update { autoModeWizardMachine.onPairingStarted(it) }
        if (fromNotification) {
            AdbPairingNotifier.showProgress(app, "Pairing with local Wireless debugging...")
        }

        val pairResult = adbSilentExecutor.pairWithCode(sanitized)
        if (pairResult.isFailure) {
            val errMsg = pairResult.exceptionOrNull()?.message
                ?: "Pairing failed. Check the 6-digit code and try again."
            _autoModeWizardState.update { autoModeWizardMachine.onPairingFailed(it, errMsg) }
            if (fromNotification) {
                AdbPairingNotifier.showPairingPrompt(app, errorHint = errMsg)
            }
            return
        }

        _autoModeWizardState.update { autoModeWizardMachine.onPairingSucceeded(it) }
        if (fromNotification) {
            AdbPairingNotifier.showProgress(app, "Paired. Finishing one-time permission setup...")
        }

        val bootstrapResult = adbSilentExecutor.runOneTimeBootstrap()
        if (bootstrapResult.isSuccess) {
            _autoModeWizardState.update { autoModeWizardMachine.onBootstrapSucceeded(it) }
            refreshAutoModeStatus()
            if (fromNotification) {
                AdbPairingNotifier.showReady(app)
            } else {
                AdbPairingNotifier.dismiss(app)
            }
        } else {
            val errMsg = bootstrapResult.exceptionOrNull()?.message
                ?: "Paired, but could not finish permission grant. Open DriveLift to tap Finish setup."
            _autoModeWizardState.update { autoModeWizardMachine.onBootstrapFailed(it, errMsg) }
            if (fromNotification) {
                AdbPairingNotifier.showPairingPrompt(app, errorHint = errMsg)
            }
        }
    }

    fun retryOneTimeBootstrap() {
        scope.launch {
            _autoModeWizardState.update { autoModeWizardMachine.onBootstrapStarted(it) }
            val result = adbSilentExecutor.runOneTimeBootstrap()
            if (result.isSuccess) {
                _autoModeWizardState.update { autoModeWizardMachine.onBootstrapSucceeded(it) }
                AdbPairingNotifier.dismiss(app)
                refreshAutoModeStatus()
            } else {
                val errMsg = result.exceptionOrNull()?.message
                    ?: "Could not finish one-time setup. Make sure Wireless debugging is on and try again."
                _autoModeWizardState.update { autoModeWizardMachine.onBootstrapFailed(it, errMsg) }
            }
        }
    }

    fun turnOffAutoMode() {
        scope.launch {
            adbSilentExecutor.turnOffAutoMode()
            AdbPairingNotifier.dismiss(app)
            val envAfter = adbSilentExecutor.environmentSnapshot()
            _autoModeWizardState.update { current ->
                autoModeWizardMachine.onTurnedOff(current, envAfter)
            }
            val status = adbSilentExecutor.inspectStatus()
            _cleanerState.update { it.copy(autoModeStatus = status) }
            _sleepState.update { it.copy(autoModeStatus = status) }
        }
    }

    fun advanceGuidedCleanToNextApp(): String? {
        confirmGuidedCleanStep()
        return _cleanerState.value.guidedPill?.currentPackageName
    }

    fun advanceGuidedSleepToNextApp(): String? {
        confirmGuidedSleepStep()
        return _sleepState.value.guidedPill?.currentPackageName
    }

    private fun isPackageStopped(packageName: String): Boolean = runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            app.packageManager.getApplicationInfo(packageName, 0)
        }
        (info.flags and ApplicationInfo.FLAG_STOPPED) != 0
    }.getOrDefault(false)

    fun dismissCelebration() {
        _cleanerState.update { it.copy(celebrationReclaimedBytes = null, celebrationDeletedCount = 0) }
        runFullCleanerScan()
    }

    // -------------------------------------------------------------------------
    // Sleep Mode
    // -------------------------------------------------------------------------

    fun refreshSleepCandidates() {
        scope.launch {
            val status = adbSilentExecutor.inspectStatus()
            val hasUsage = statsSource.hasUsageStatsAccess()
            val allRules = defaultProtectedRules() + userWhitelistPackages.value
            _sleepState.update {
                it.copy(
                    isLoading = true,
                    needsUsageAccess = !hasUsage,
                    errorMessage = null,
                    autoModeStatus = status,
                    protectedRules = allRules,
                )
            }

            if (!hasUsage) {
                _sleepState.update {
                    it.copy(
                        isLoading = false,
                        candidates = emptyList(),
                        selectedPackages = emptySet(),
                    )
                }
                return@launch
            }

            runCatching {
                val usageSnapshots = statsSource.queryAppUsageSnapshots()
                val ranked = drainerScorer.rankCandidates(
                    snapshots = usageSnapshots,
                    userRules = allRules,
                    nowMs = graph.clock(),
                )
                _sleepState.update {
                    it.copy(
                        isLoading = false,
                        candidates = ranked,
                        selectedPackages = ranked.map { c -> c.packageName }.toSet(),
                        protectedRules = allRules,
                        autoModeStatus = status,
                    )
                }
            }.onFailure { err ->
                _sleepState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = err.message ?: "Unable to read background usage.",
                    )
                }
            }
        }
    }

    fun toggleSleepCandidateSelection(packageName: String) {
        _sleepState.update { state ->
            val next = if (packageName in state.selectedPackages) {
                state.selectedPackages - packageName
            } else {
                state.selectedPackages + packageName
            }
            state.copy(selectedPackages = next)
        }
    }

    fun protectApp(candidate: AppDrainCandidate) {
        val rule = ProtectedAppRule(
            packageName = candidate.packageName,
            reason = ProtectionReason.USER_WHITELIST,
            label = candidate.appLabel,
            immutable = false,
        )
        userWhitelistPackages.update { existing ->
            if (existing.any { it.packageName == rule.packageName }) existing else existing + rule
        }
        _sleepState.update { state ->
            state.copy(
                candidates = state.candidates.filterNot { it.packageName == candidate.packageName },
                selectedPackages = state.selectedPackages - candidate.packageName,
                protectedRules = defaultProtectedRules() + userWhitelistPackages.value,
            )
        }
    }

    fun unprotectApp(packageName: String) {
        userWhitelistPackages.update { existing ->
            existing.filterNot { it.packageName == packageName }
        }
        refreshSleepCandidates()
    }

    fun startSleepForSelected() {
        val current = _sleepState.value
        val targets = current.selectedCandidates
        if (targets.isEmpty()) return

        val status = adbSilentExecutor.inspectStatus()
        val sessionId = "sleep-${graph.clock()}"
        if (status.effectiveMode == HibernationMode.AUTO_SILENT) {
            scope.launch {
                _sleepState.update {
                    it.copy(
                        isSleepingBatch = true,
                        autoProgressMessage = "Auto Mode: connecting to sleep ${targets.size} apps...",
                        lastReport = null,
                        autoModeStatus = status,
                    )
                }
                val initial = sleepStateMachine.startSession(
                    sessionId = sessionId,
                    mode = HibernationMode.AUTO_SILENT,
                    candidates = targets,
                    nowMs = graph.clock(),
                )
                val outcome = adbSilentExecutor.runWithAutoSession { executor ->
                    sleepStateMachine.runSilentBatch(
                        initialState = initial,
                        executor = executor,
                        clockMs = { graph.clock() },
                        onStateChange = { stepState ->
                            val currentApp = stepState.currentCandidate?.appLabel
                            if (currentApp != null) {
                                _sleepState.update {
                                    it.copy(
                                        autoProgressMessage = "Auto Mode: sleeping $currentApp (${stepState.results.size + 1} of ${stepState.queue.size})",
                                    )
                                }
                            }
                        },
                    )
                }
                val refreshed = adbSilentExecutor.inspectStatus()
                when (outcome) {
                    is AutoSessionOutcome.Completed -> {
                        val report = sleepStateMachine.buildReport(outcome.value, graph.clock())
                        _sleepState.update {
                            it.copy(
                                isSleepingBatch = false,
                                autoProgressMessage = null,
                                lastReport = report,
                                autoModeStatus = refreshed,
                            )
                        }
                    }
                    is AutoSessionOutcome.FallbackToGuided -> {
                        val pill = guidedSleepController.startGuidedSleep(
                            sessionId = sessionId,
                            candidates = targets,
                            fallbackReason = outcome.reason,
                        )
                        _sleepState.update {
                            it.copy(
                                isSleepingBatch = false,
                                autoProgressMessage = null,
                                guidedPill = pill,
                                lastReport = guidedSleepController.lastSleepReport,
                                autoModeStatus = refreshed,
                            )
                        }
                    }
                }
            }
        } else {
            val fallbackReason = if (status.isConfigured && status.availability == AutoModeAvailability.NEEDS_WIFI) {
                AdbSilentExecutor.FALLBACK_REASON_NO_WIFI
            } else {
                null
            }
            val pill = guidedSleepController.startGuidedSleep(
                sessionId = sessionId,
                candidates = targets,
                fallbackReason = fallbackReason,
            )
            _sleepState.update {
                it.copy(
                    guidedPill = pill,
                    lastReport = guidedSleepController.lastSleepReport,
                    autoModeStatus = status,
                )
            }
        }
    }

    fun confirmGuidedSleepStep() {
        val nextPill = guidedSleepController.confirmCurrentStep()
        _sleepState.update {
            it.copy(
                guidedPill = nextPill,
                lastReport = if (nextPill == null) guidedSleepController.lastSleepReport else it.lastReport,
            )
        }
    }

    fun skipGuidedSleepStep() {
        val nextPill = guidedSleepController.skipCurrentStep()
        _sleepState.update {
            it.copy(
                guidedPill = nextPill,
                lastReport = if (nextPill == null) guidedSleepController.lastSleepReport else it.lastReport,
            )
        }
    }

    fun cancelGuidedSleepSession() {
        guidedSleepController.cancelActiveSession()
        _sleepState.update {
            it.copy(
                guidedPill = null,
                lastReport = guidedSleepController.lastSleepReport,
            )
        }
    }

    fun dismissSleepReport() {
        _sleepState.update { it.copy(lastReport = null) }
    }

    private fun defaultScanRoots(): List<File> = buildList {
        app.cacheDir?.let { add(it) }
        app.externalCacheDir?.let { add(it) }
        runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }
            .getOrNull()
            ?.takeIf { it.exists() && it.isDirectory }
            ?.let { add(it) }
    }

    private fun mergeGroups(
        junkGroups: List<CleanerCategoryGroup>,
        appGroups: List<CleanerCategoryGroup>,
        duplicateGroups: List<DuplicateGroup>,
    ): List<CleanerCategoryGroup> {
        val duplicateCategoryGroup = if (duplicateGroups.isNotEmpty()) {
            val dupItems = duplicateGroups.flatMap { group ->
                group.files
                    .filter { it.path != group.recommendedKeepPath }
                    .map { file ->
                        CleanerItem(
                            id = "${CleanerCategory.DUPLICATE_FILES.name}:${file.path}",
                            path = file.path,
                            name = file.name,
                            category = CleanerCategory.DUPLICATE_FILES,
                            sizeBytes = file.sizeBytes,
                            lastModifiedMs = file.lastModifiedMs,
                            adviceLabel = "Clean up carefully",
                            selectedByDefault = false,
                        )
                    }
            }
            if (dupItems.isNotEmpty()) {
                CleanerCategoryGroup(
                    category = CleanerCategory.DUPLICATE_FILES,
                    items = dupItems,
                    totalBytes = dupItems.sumOf { it.sizeBytes },
                    selectedBytes = 0L,
                    selectedCount = 0,
                )
            } else {
                null
            }
        } else {
            null
        }

        return (junkGroups + appGroups + listOfNotNull(duplicateCategoryGroup))
            .sortedWith(
                compareBy<CleanerCategoryGroup> { it.category == CleanerCategory.EMPTY_FOLDERS }
                    .thenByDescending { it.totalBytes },
            )
    }

    private fun buildDeepCleanCards(
        groups: List<CleanerCategoryGroup>,
        duplicateGroups: List<DuplicateGroup>,
    ): List<DeepCleanCardSummary> {
        fun groupFor(cat: CleanerCategory): CleanerCategoryGroup? = groups.firstOrNull { it.category == cat }

        val whatsapp = groupFor(CleanerCategory.WHATSAPP_BLOAT)
        val large = groupFor(CleanerCategory.LARGE_FILE)
        val dupBytes = duplicateGroups.sumOf { it.reclaimableBytes }
        val dupCount = duplicateGroups.sumOf { (it.files.size - 1).coerceAtLeast(0) }
        val rarelyFiles = groupFor(CleanerCategory.RARELY_USED_FILES)
        val rarelyApps = groupFor(CleanerCategory.RARELY_USED_APPS)
        val rarelyBytes = (rarelyFiles?.totalBytes ?: 0L) + (rarelyApps?.totalBytes ?: 0L)
        val rarelyCount = (rarelyFiles?.items?.size ?: 0) + (rarelyApps?.items?.size ?: 0)

        return listOf(
            DeepCleanCardSummary(
                destination = DeepCleanDestination.WHATSAPP,
                title = "WhatsApp",
                subtitle = if ((whatsapp?.items?.size ?: 0) > 0) "${whatsapp?.items?.size} items" else "Clean",
                bytes = whatsapp?.totalBytes ?: 0L,
                itemCount = whatsapp?.items?.size ?: 0,
            ),
            DeepCleanCardSummary(
                destination = DeepCleanDestination.LARGE_FILES,
                title = "Large Files",
                subtitle = if ((large?.items?.size ?: 0) > 0) "${large?.items?.size} files" else "Clean",
                bytes = large?.totalBytes ?: 0L,
                itemCount = large?.items?.size ?: 0,
            ),
            DeepCleanCardSummary(
                destination = DeepCleanDestination.DUPLICATES,
                title = "Duplicates",
                subtitle = if (dupCount > 0) "$dupCount extra copies" else "Clean",
                bytes = dupBytes,
                itemCount = dupCount,
            ),
            DeepCleanCardSummary(
                destination = DeepCleanDestination.SLEEP_MODE,
                title = "Sleep Mode",
                subtitle = "Hibernate drainers",
                bytes = 0L,
                itemCount = _sleepState.value.candidates.size,
            ),
            DeepCleanCardSummary(
                destination = DeepCleanDestination.RARELY_USED,
                title = "Rarely Used",
                subtitle = if (rarelyCount > 0) "$rarelyCount items" else "Clean",
                bytes = rarelyBytes,
                itemCount = rarelyCount,
            ),
        )
    }
}

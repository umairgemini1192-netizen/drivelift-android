package com.drivelift.app.feature.cleaner

import android.app.Application
import android.os.Environment
import com.drivelift.app.Graph
import com.drivelift.app.feature.cleaner.cache.AndroidAppStorageStatsSource
import com.drivelift.app.feature.cleaner.cache.GuidedCleanTarget
import com.drivelift.app.feature.cleaner.cache.GuidedSessionController
import com.drivelift.app.feature.cleaner.cache.ReflectionShizukuRuntimeBridge
import com.drivelift.app.feature.cleaner.cache.ShizukuSilentAdapter
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
 * Self-contained Phase 3 coordinator wiring Phase 1 (cleaner engine) and Phase 2 (hibernation engine)
 * with Android StorageStatsManager/UsageStatsManager, optional Shizuku silent execution, and
 * Android 16/17 Guided Pill fallback.
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

    val shizukuAdapter = ShizukuSilentAdapter(
        bridge = ReflectionShizukuRuntimeBridge(app),
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
    private val guidedSleepController = GuidedSessionController(sleepStateMachine, nowMs = { graph.clock() })

    private var cachedDuplicateGroups: List<DuplicateGroup> = emptyList()
    private var initialScanTriggered = false

    private val _cleanerState = MutableStateFlow(
        CleanerDashboardUiState(
            shizukuStatus = shizukuAdapter.inspectStatus(),
        ),
    )
    val cleanerState: StateFlow<CleanerDashboardUiState> = _cleanerState.asStateFlow()

    private val _sleepState = MutableStateFlow(
        SleepModeUiState(
            shizukuStatus = shizukuAdapter.inspectStatus(),
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
            val status = shizukuAdapter.inspectStatus()
            val hasUsage = statsSource.hasUsageStatsAccess()
            _cleanerState.update {
                it.copy(
                    isLoading = true,
                    errorMessage = null,
                    needsUsageAccess = !hasUsage,
                    shizukuStatus = status,
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
                        shizukuStatus = status,
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

            val status = shizukuAdapter.inspectStatus()
            val guidedTargets = mutableListOf<GuidedCleanTarget>()
            for (cacheItem in cacheItems) {
                val pkg = cacheItem.packageName ?: continue
                if (status.effectiveMode == HibernationMode.SHIZUKU_SILENT) {
                    val cleared = shizukuAdapter.clearAppCacheSilent(pkg)
                    if (cleared.isSuccess) {
                        totalReclaimed += cacheItem.sizeBytes
                        totalDeleted++
                    } else {
                        guidedTargets.add(GuidedCleanTarget(pkg, cacheItem.name, cacheItem.sizeBytes))
                    }
                } else {
                    guidedTargets.add(GuidedCleanTarget(pkg, cacheItem.name, cacheItem.sizeBytes))
                }
            }

            if (guidedTargets.isNotEmpty()) {
                val pill = guidedCleanController.startGuidedClean(guidedTargets)
                _cleanerState.update {
                    it.copy(
                        guidedPill = pill,
                        shizukuStatus = status,
                    )
                }
            } else {
                _cleanerState.update {
                    it.copy(
                        celebrationReclaimedBytes = totalReclaimed,
                        celebrationDeletedCount = totalDeleted,
                        selectedItemIds = emptySet(),
                        shizukuStatus = status,
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

    fun dismissCelebration() {
        _cleanerState.update { it.copy(celebrationReclaimedBytes = null, celebrationDeletedCount = 0) }
        runFullCleanerScan()
    }

    // -------------------------------------------------------------------------
    // Sleep Mode (Phase 2 wiring)
    // -------------------------------------------------------------------------

    fun refreshSleepCandidates() {
        scope.launch {
            val status = shizukuAdapter.inspectStatus()
            val hasUsage = statsSource.hasUsageStatsAccess()
            val allRules = defaultProtectedRules() + userWhitelistPackages.value
            _sleepState.update {
                it.copy(
                    isLoading = true,
                    needsUsageAccess = !hasUsage,
                    errorMessage = null,
                    shizukuStatus = status,
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
                        shizukuStatus = status,
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

        val status = shizukuAdapter.inspectStatus()
        val sessionId = "sleep-${graph.clock()}"
        if (status.effectiveMode == HibernationMode.SHIZUKU_SILENT) {
            scope.launch {
                _sleepState.update { it.copy(isSleepingBatch = true, lastReport = null) }
                val initial = sleepStateMachine.startSession(
                    sessionId = sessionId,
                    mode = HibernationMode.SHIZUKU_SILENT,
                    candidates = targets,
                    nowMs = graph.clock(),
                )
                val finalState = sleepStateMachine.runSilentBatch(
                    initialState = initial,
                    executor = shizukuAdapter,
                    clockMs = { graph.clock() },
                )
                val report = sleepStateMachine.buildReport(finalState, graph.clock())
                _sleepState.update {
                    it.copy(
                        isSleepingBatch = false,
                        lastReport = report,
                        shizukuStatus = status,
                    )
                }
            }
        } else {
            val pill = guidedSleepController.startGuidedSleep(sessionId, targets)
            _sleepState.update {
                it.copy(
                    guidedPill = pill,
                    lastReport = guidedSleepController.lastSleepReport,
                    shizukuStatus = status,
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

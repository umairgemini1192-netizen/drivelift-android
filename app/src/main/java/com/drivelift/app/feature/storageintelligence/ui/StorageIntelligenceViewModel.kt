package com.drivelift.app.feature.storageintelligence.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drivelift.app.Graph
import com.drivelift.app.data.TrashState
import com.drivelift.app.feature.storageintelligence.db.StorageScanSessionEntity
import com.drivelift.app.feature.storageintelligence.db.decodeScanErrors
import com.drivelift.app.feature.storageintelligence.model.AutoSelectPreset
import com.drivelift.app.feature.storageintelligence.model.BatchCleanupPlan
import com.drivelift.app.feature.storageintelligence.model.DEFAULT_LARGE_THRESHOLD_BYTES
import com.drivelift.app.feature.storageintelligence.model.DEFAULT_LARGE_TOP_N
import com.drivelift.app.feature.storageintelligence.model.DuplicateGroupModel
import com.drivelift.app.feature.storageintelligence.model.LargeFileCategory
import com.drivelift.app.feature.storageintelligence.model.OrphanArtifactCandidate
import com.drivelift.app.feature.storageintelligence.model.ScanPhase
import com.drivelift.app.feature.storageintelligence.model.ScanProgress
import com.drivelift.app.feature.storageintelligence.model.ScanScope
import com.drivelift.app.feature.storageintelligence.model.SourceScanError
import com.drivelift.app.feature.storageintelligence.model.UnifiedFileNode
import com.drivelift.app.feature.storageintelligence.scanner.LargeFileRadarEngine
import com.drivelift.app.feature.storageintelligence.scanner.SmartAutoSelectEngine
import com.drivelift.app.ui.AppViewModel
import com.drivelift.app.ui.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class IntelligenceTab(val label: String) {
    DUPLICATES("Duplicates"),
    LARGE_FILES("Large files"),
    ORPHANS("Orphans"),
}

data class SourceFilterOption(
    val sourceId: String?,
    val label: String,
)

data class StorageIntelligenceUiState(
    val activeTab: IntelligenceTab = IntelligenceTab.DUPLICATES,
    val scope: ScanScope = ScanScope.CROSS_SOURCE,
    val sourceFilterId: String? = null,
    val availableSources: List<SourceFilterOption> = listOf(SourceFilterOption(null, "All")),
    val thresholdBytes: Long = DEFAULT_LARGE_THRESHOLD_BYTES,
    val categoryFilter: LargeFileCategory = LargeFileCategory.ALL,
    val session: StorageScanSessionEntity? = null,
    val sessionErrors: List<SourceScanError> = emptyList(),
    val scanProgress: ScanProgress = ScanProgress(),
    val duplicateGroups: List<DuplicateGroupModel> = emptyList(),
    val largeFiles: List<UnifiedFileNode> = emptyList(),
    val orphans: List<OrphanArtifactCandidate> = emptyList(),
    val protectedNodeIds: Set<String> = emptySet(),
    val activeAutoPreset: AutoSelectPreset? = null,
    val selectedDuplicateNodeIds: Set<String> = emptySet(),
    val selectedLargeFileNodeIds: Set<String> = emptySet(),
    val selectedOrphanArtifactIds: Set<String> = emptySet(),
    val pendingCleanupPlan: BatchCleanupPlan? = null,
    val pendingPlanSourceTab: IntelligenceTab? = null,
    val cleanupBusy: Boolean = false,
    val showCustomThresholdDialog: Boolean = false,
) {
    val totalReclaimableBytes: Long
        get() = duplicateGroups.sumOf { it.reclaimableBytes } +
            orphans.filter { it.canClean }.sumOf { it.sizeBytes }

    val selectedCount: Int
        get() = when (activeTab) {
            IntelligenceTab.DUPLICATES -> selectedDuplicateNodeIds.size
            IntelligenceTab.LARGE_FILES -> selectedLargeFileNodeIds.size
            IntelligenceTab.ORPHANS -> selectedOrphanArtifactIds.size
        }

    val selectedBytes: Long
        get() = when (activeTab) {
            IntelligenceTab.DUPLICATES -> duplicateGroups
                .flatMap { it.items }
                .filter { it.id in selectedDuplicateNodeIds }
                .sumOf { it.sizeBytes }
            IntelligenceTab.LARGE_FILES -> largeFiles
                .filter { it.id in selectedLargeFileNodeIds }
                .sumOf { it.sizeBytes }
            IntelligenceTab.ORPHANS -> orphans
                .filter { it.artifactId in selectedOrphanArtifactIds }
                .sumOf { it.sizeBytes }
        }

    val hasSelectedLocalLargeFiles: Boolean
        get() = activeTab == IntelligenceTab.LARGE_FILES && largeFiles.any {
            it.id in selectedLargeFileNodeIds && it.isLocal
        }
}

class StorageIntelligenceViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = Graph.get(app)
    private val module = graph.storageIntelligence
    private val repository = module.repository
    private val autoSelect = SmartAutoSelectEngine()
    private val largeRadarEngine = LargeFileRadarEngine()

    private val activeTab = MutableStateFlow(IntelligenceTab.DUPLICATES)
    private val scope = MutableStateFlow(ScanScope.CROSS_SOURCE)
    private val sourceFilterId = MutableStateFlow<String?>(null)
    private val thresholdBytes = MutableStateFlow(DEFAULT_LARGE_THRESHOLD_BYTES)
    private val categoryFilter = MutableStateFlow(LargeFileCategory.ALL)
    private val activeAutoPreset = MutableStateFlow<AutoSelectPreset?>(null)
    private val selectedDuplicateNodeIds = MutableStateFlow<Set<String>>(emptySet())
    private val selectedLargeFileNodeIds = MutableStateFlow<Set<String>>(emptySet())
    private val selectedOrphanArtifactIds = MutableStateFlow<Set<String>>(emptySet())
    private val pendingCleanupPlan = MutableStateFlow<BatchCleanupPlan?>(null)
    private val pendingPlanSourceTab = MutableStateFlow<IntelligenceTab?>(null)
    private val cleanupBusy = MutableStateFlow(false)
    private val showCustomThresholdDialog = MutableStateFlow(false)
    private val protectedNodeIds = MutableStateFlow<Set<String>>(emptySet())
    private val availableSources = MutableStateFlow(listOf(SourceFilterOption(null, "All")))

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    private val filteredDuplicates = combine(
        repository.duplicateGroups,
        sourceFilterId,
    ) { groups, filter ->
        if (filter == null) groups
        else groups.filter { g -> g.items.any { it.sourceId == filter } }
    }

    private val filteredLargeFiles = combine(
        repository.largeFiles,
        thresholdBytes,
        sourceFilterId,
        categoryFilter,
    ) { items, threshold, srcFilter, category ->
        largeRadarEngine.filter(
            items = items,
            minSizeBytes = threshold,
            sourceFilterId = srcFilter,
            categoryFilter = category,
            topN = DEFAULT_LARGE_TOP_N,
        )
    }

    private val filteredOrphans = combine(
        repository.orphans,
        sourceFilterId,
    ) { items, filter ->
        if (filter == null) items else items.filter { it.sourceId == filter }
    }

    private data class CoreUiInputs(
        val tab: IntelligenceTab,
        val scope: ScanScope,
        val sourceFilter: String?,
        val threshold: Long,
        val category: LargeFileCategory,
        val sources: List<SourceFilterOption>,
    )

    private data class ScanDataInputs(
        val session: StorageScanSessionEntity?,
        val progress: ScanProgress,
        val groups: List<DuplicateGroupModel>,
        val large: List<UnifiedFileNode>,
        val orphans: List<OrphanArtifactCandidate>,
        val protectedIds: Set<String>,
    )

    private data class SelectionInputs(
        val preset: AutoSelectPreset?,
        val dupSel: Set<String>,
        val largeSel: Set<String>,
        val orphanSel: Set<String>,
        val plan: BatchCleanupPlan?,
        val planTab: IntelligenceTab?,
        val busy: Boolean,
        val customThresholdDialog: Boolean,
    )

    private val coreInputs = combine(
        activeTab,
        scope,
        sourceFilterId,
        thresholdBytes,
        categoryFilter,
    ) { tab, sc, src, thresh, cat ->
        CoreUiInputs(tab, sc, src, thresh, cat, availableSources.value)
    }.combine(availableSources) { core, sources ->
        core.copy(sources = sources)
    }

    private val scanDataInputs = combine(
        repository.latestSession,
        repository.scanProgress,
        filteredDuplicates,
        filteredLargeFiles,
        filteredOrphans,
    ) { session, prog, groups, large, orphans ->
        ScanDataInputs(session, prog, groups, large, orphans, protectedNodeIds.value)
    }.combine(protectedNodeIds) { data, prot ->
        data.copy(protectedIds = prot)
    }

    private val selectionInputs = combine(
        activeAutoPreset,
        selectedDuplicateNodeIds,
        selectedLargeFileNodeIds,
        selectedOrphanArtifactIds,
        pendingCleanupPlan,
    ) { preset, dupSel, largeSel, orphanSel, plan ->
        SelectionInputs(
            preset = preset,
            dupSel = dupSel,
            largeSel = largeSel,
            orphanSel = orphanSel,
            plan = plan,
            planTab = pendingPlanSourceTab.value,
            busy = cleanupBusy.value,
            customThresholdDialog = showCustomThresholdDialog.value,
        )
    }.combine(combine(pendingPlanSourceTab, cleanupBusy, showCustomThresholdDialog, ::Triple)) { sel, extra ->
        sel.copy(planTab = extra.first, busy = extra.second, customThresholdDialog = extra.third)
    }

    val uiState: StateFlow<StorageIntelligenceUiState> = combine(
        coreInputs,
        scanDataInputs,
        selectionInputs,
    ) { core, data, sel ->
        val errors = if (data.progress.isRunning) {
            data.progress.errors
        } else {
            data.session?.partialErrorsJson?.let(::decodeScanErrors).orEmpty()
        }
        StorageIntelligenceUiState(
            activeTab = core.tab,
            scope = core.scope,
            sourceFilterId = core.sourceFilter,
            availableSources = core.sources,
            thresholdBytes = core.threshold,
            categoryFilter = core.category,
            session = data.session,
            sessionErrors = errors,
            scanProgress = data.progress,
            duplicateGroups = data.groups,
            largeFiles = data.large,
            orphans = data.orphans,
            protectedNodeIds = data.protectedIds,
            activeAutoPreset = sel.preset,
            selectedDuplicateNodeIds = sel.dupSel,
            selectedLargeFileNodeIds = sel.largeSel,
            selectedOrphanArtifactIds = sel.orphanSel,
            pendingCleanupPlan = sel.plan,
            pendingPlanSourceTab = sel.planTab,
            cleanupBusy = sel.busy,
            showCustomThresholdDialog = sel.customThresholdDialog,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, StorageIntelligenceUiState())

    init {
        refreshSourcesAndLocks()
        viewModelScope.launch {
            repository.scanProgress.collect { prog ->
                if (prog.phase == ScanPhase.COMPLETED) {
                    refreshSourcesAndLocks()
                }
            }
        }
        viewModelScope.launch {
            graph.prefs.accountEmailFlow.collect {
                refreshSourcesAndLocks()
            }
        }
        viewModelScope.launch {
            graph.db.transfers().observeCounts().collect {
                protectedNodeIds.value = repository.activeProtectedNodeIds()
            }
        }
        viewModelScope.launch {
            repository.duplicateGroups.collect { groups ->
                val valid = groups.flatMapTo(HashSet()) { g -> g.items.map { it.id } }
                val pruned = selectedDuplicateNodeIds.value.filterTo(HashSet()) { it in valid }
                if (pruned.size != selectedDuplicateNodeIds.value.size) {
                    selectedDuplicateNodeIds.value = pruned
                }
            }
        }
        viewModelScope.launch {
            repository.largeFiles.collect { items ->
                val valid = items.mapTo(HashSet()) { it.id }
                val pruned = selectedLargeFileNodeIds.value.filterTo(HashSet()) { it in valid }
                if (pruned.size != selectedLargeFileNodeIds.value.size) {
                    selectedLargeFileNodeIds.value = pruned
                }
            }
        }
        viewModelScope.launch {
            repository.orphans.collect { items ->
                val valid = items.filter { it.canClean }.mapTo(HashSet()) { it.artifactId }
                val pruned = selectedOrphanArtifactIds.value.filterTo(HashSet()) { it in valid }
                if (pruned.size != selectedOrphanArtifactIds.value.size) {
                    selectedOrphanArtifactIds.value = pruned
                }
            }
        }
    }

    fun refreshSourcesAndLocks() {
        viewModelScope.launch {
            val sources = repository.availableSources().map { SourceFilterOption(it.first, it.second) }
            availableSources.value = listOf(SourceFilterOption(null, "All")) + sources
            protectedNodeIds.value = repository.activeProtectedNodeIds()
        }
    }

    fun selectTab(tab: IntelligenceTab) {
        activeTab.value = tab
    }

    fun selectScope(newScope: ScanScope) {
        scope.value = newScope
    }

    fun selectSourceFilter(sourceId: String?) {
        sourceFilterId.value = sourceId
    }

    fun selectCategoryFilter(category: LargeFileCategory) {
        categoryFilter.value = category
    }

    fun setThresholdBytes(bytes: Long) {
        thresholdBytes.value = bytes.coerceAtLeast(1L * 1024L * 1024L)
        viewModelScope.launch {
            val currentSession = uiState.value.session ?: return@launch
            if (bytes < currentSession.thresholdBytes) {
                repository.refreshLargeFiles(
                    scope = scope.value,
                    thresholdBytes = thresholdBytes.value,
                    topN = DEFAULT_LARGE_TOP_N,
                )
            }
        }
    }

    fun openCustomThresholdDialog() {
        showCustomThresholdDialog.value = true
    }

    fun dismissCustomThresholdDialog() {
        showCustomThresholdDialog.value = false
    }

    fun startScan() {
        val current = uiState.value
        if (current.scanProgress.isRunning) return
        val singleCloudId = if (current.scope == ScanScope.SINGLE_CLOUD) {
            current.sourceFilterId?.takeIf { it != com.drivelift.app.feature.storageintelligence.model.LOCAL_SOURCE_ID }
                ?: current.availableSources.firstOrNull {
                    it.sourceId != null && it.sourceId != com.drivelift.app.feature.storageintelligence.model.LOCAL_SOURCE_ID
                }?.sourceId
        } else {
            null
        }
        repository.enqueueScan(
            scope = current.scope,
            singleCloudSourceId = singleCloudId,
            thresholdBytes = current.thresholdBytes,
            topN = DEFAULT_LARGE_TOP_N,
        )
    }

    fun cancelScan() {
        repository.cancelScan()
        _messages.tryEmit("Scan stopped. Partial results were not saved.")
    }

    fun applyAutoSelectPreset(preset: AutoSelectPreset) {
        val state = uiState.value
        val result = autoSelect.selectForPreset(
            groups = state.duplicateGroups,
            preset = preset,
            protectedNodeIds = state.protectedNodeIds,
        )
        activeAutoPreset.value = preset
        selectedDuplicateNodeIds.value = result.selectedNodeIds
        val count = result.selectedNodeIds.size
        _messages.tryEmit(
            when (count) {
                0 -> "No copies selected under ${preset.label}"
                1 -> "1 copy selected (${preset.label}); at least 1 copy per group stays"
                else -> "$count copies selected (${preset.label}); at least 1 copy per group stays"
            },
        )
    }

    fun toggleDuplicateSelection(nodeId: String) {
        val state = uiState.value
        val group = state.duplicateGroups.firstOrNull { g -> g.items.any { it.id == nodeId } } ?: return
        val current = selectedDuplicateNodeIds.value
        if (!autoSelect.canToggleSelect(group, current, nodeId, state.protectedNodeIds)) {
            val reason = if (nodeId in state.protectedNodeIds) {
                "This file is protected by an active transfer"
            } else {
                "At least 1 original copy must stay in each duplicate group"
            }
            _messages.tryEmit(reason)
            return
        }
        activeAutoPreset.value = null
        selectedDuplicateNodeIds.value = if (nodeId in current) current - nodeId else current + nodeId
    }

    fun toggleLargeFileSelection(nodeId: String) {
        val current = selectedLargeFileNodeIds.value
        selectedLargeFileNodeIds.value = if (nodeId in current) current - nodeId else current + nodeId
    }

    fun toggleOrphanSelection(artifactId: String) {
        val candidate = uiState.value.orphans.firstOrNull { it.artifactId == artifactId } ?: return
        if (!candidate.canClean) return
        val current = selectedOrphanArtifactIds.value
        selectedOrphanArtifactIds.value = if (artifactId in current) current - artifactId else current + artifactId
    }

    fun clearSelection() {
        when (activeTab.value) {
            IntelligenceTab.DUPLICATES -> {
                activeAutoPreset.value = null
                selectedDuplicateNodeIds.value = emptySet()
            }
            IntelligenceTab.LARGE_FILES -> selectedLargeFileNodeIds.value = emptySet()
            IntelligenceTab.ORPHANS -> selectedOrphanArtifactIds.value = emptySet()
        }
    }

    fun prepareCleanup() {
        val state = uiState.value
        if (cleanupBusy.value) return
        viewModelScope.launch {
            val tab = state.activeTab
            val plan = when (tab) {
                IntelligenceTab.DUPLICATES -> repository.buildDuplicateCleanupPlan(
                    selectedNodeIds = state.selectedDuplicateNodeIds,
                    groups = state.duplicateGroups,
                )
                IntelligenceTab.LARGE_FILES -> repository.buildLargeFileCleanupPlan(
                    selectedNodes = state.largeFiles.filter { it.id in state.selectedLargeFileNodeIds },
                )
                IntelligenceTab.ORPHANS -> repository.buildOrphanCleanupPlan(
                    selectedOrphans = state.orphans.filter { it.artifactId in state.selectedOrphanArtifactIds },
                )
            }
            if (plan.totalEligibleCount == 0) {
                _messages.emit("Nothing eligible to clean right now.")
                return@launch
            }
            pendingPlanSourceTab.value = tab
            pendingCleanupPlan.value = plan
        }
    }

    fun prepareSingleLargeFileTrash(node: UnifiedFileNode) {
        if (cleanupBusy.value) return
        viewModelScope.launch {
            val plan = repository.buildLargeFileCleanupPlan(listOf(node))
            if (plan.totalEligibleCount == 0) {
                _messages.emit("${node.name} is locked by an active transfer.")
                return@launch
            }
            pendingPlanSourceTab.value = IntelligenceTab.LARGE_FILES
            pendingCleanupPlan.value = plan
        }
    }

    fun dismissCleanupConfirmation() {
        pendingCleanupPlan.value = null
        pendingPlanSourceTab.value = null
    }

    fun confirmCleanup(appViewModel: AppViewModel) {
        val plan = pendingCleanupPlan.value ?: return
        val tab = pendingPlanSourceTab.value ?: activeTab.value
        pendingCleanupPlan.value = null
        pendingPlanSourceTab.value = null
        cleanupBusy.value = true

        viewModelScope.launch {
            try {
                when (tab) {
                    IntelligenceTab.ORPHANS -> {
                        val result = repository.cleanOrphans(plan)
                        selectedOrphanArtifactIds.value = emptySet()
                        val freedLabel = formatBytes(getApplication(), result.freedBytes)
                        val msg = if (result.freedBytes > 0L) "${result.message()} ($freedLabel freed)" else result.message()
                        _messages.emit(msg)
                    }
                    IntelligenceTab.DUPLICATES,
                    IntelligenceTab.LARGE_FILES,
                    -> {
                        val cleanedNodeIds = ArrayList<String>()
                        var localOutcomeText: String? = null
                        var cloudOutcomeText: String? = null

                        if (plan.cloudEligibleFiles.isNotEmpty()) {
                            val pinnedAccount = graph.prefs.accountEmail
                            val outcome = appViewModel.trashDriveFiles(plan.cloudEligibleFiles, pinnedAccount)
                            val trashedIds = outcome.trashed.mapTo(HashSet()) { it.id }
                            val nodeByNative = plan.cloudEligibleFiles.zip(plan.cloudEligibleNodeIds)
                                .associate { (f, nId) -> f.id to nId }
                            trashedIds.mapNotNullTo(cleanedNodeIds) { nodeByNative[it] }
                            cloudOutcomeText = outcome.message()
                        }

                        if (plan.localEligibleMediaIds.isNotEmpty()) {
                            val idToUriAndNode = buildLocalLookup(plan.localEligibleMediaIds)
                            val started = appViewModel.requestPhoneTrash(plan.localEligibleMediaIds)
                            if (!started) {
                                localOutcomeText = "Another phone trash dialog is open"
                            } else {
                                appViewModel.pendingTrash.first { it == null }
                                val resolver = getApplication<Application>().contentResolver
                                val confirmedLocalNodes = withContext(Dispatchers.IO) {
                                    idToUriAndNode.mapNotNull { (_, pair) ->
                                        val (uri, nodeId) = pair
                                        if (graph.trashState(resolver, Uri.parse(uri)) == TrashState.TRASHED) nodeId else null
                                    }
                                }
                                cleanedNodeIds += confirmedLocalNodes
                                if (confirmedLocalNodes.isEmpty()) {
                                    localOutcomeText = "Phone trash request was cancelled"
                                } else {
                                    val count = confirmedLocalNodes.size
                                    localOutcomeText = if (count == 1) "1 phone file moved to trash" else "$count phone files moved to trash"
                                }
                            }
                        }

                        if (cleanedNodeIds.isNotEmpty()) {
                            repository.markNodesCleaned(cleanedNodeIds)
                        }
                        if (tab == IntelligenceTab.DUPLICATES) {
                            selectedDuplicateNodeIds.value = emptySet()
                            activeAutoPreset.value = null
                        } else {
                            selectedLargeFileNodeIds.value = emptySet()
                        }

                        val summary = listOfNotNull(localOutcomeText, cloudOutcomeText)
                            .joinToString(". ")
                            .ifBlank { "Cleanup finished." }
                        _messages.emit(summary)
                    }
                }
            } finally {
                cleanupBusy.value = false
            }
        }
    }

    private fun buildLocalLookup(mediaIds: List<Long>): Map<Long, Pair<String, String>> {
        val state = uiState.value
        val allNodes = state.duplicateGroups.flatMap { it.items } + state.largeFiles
        val byMediaId = HashMap<Long, Pair<String, String>>()
        for (node in allNodes) {
            if (!node.isLocal) continue
            val mId = node.nativeId.toLongOrNull() ?: continue
            val uri = node.localUri ?: continue
            if (mId in mediaIds) byMediaId[mId] = uri to node.id
        }
        return byMediaId
    }

    fun offloadSelectedLargeFiles() {
        val state = uiState.value
        val mediaIds = state.largeFiles
            .filter { it.id in state.selectedLargeFileNodeIds && it.isLocal }
            .mapNotNull { it.nativeId.toLongOrNull() }
        if (mediaIds.isEmpty()) {
            _messages.tryEmit("Select at least 1 local file to offload to Google Drive")
            return
        }
        offloadLocalMediaIds(mediaIds)
        selectedLargeFileNodeIds.value = emptySet()
    }

    fun offloadSingleLocalFile(node: UnifiedFileNode) {
        val mediaId = node.nativeId.toLongOrNull() ?: return
        offloadLocalMediaIds(listOf(mediaId))
    }

    private fun offloadLocalMediaIds(mediaIds: List<Long>) {
        viewModelScope.launch {
            val result = repository.offloadLocalFilesToCloud(mediaIds)
            protectedNodeIds.value = repository.activeProtectedNodeIds()
            val msg = when {
                result.queuedCount == 0 && result.skippedCount > 0 ->
                    "Already queued or held by an active transfer"
                result.skippedCount == 0 ->
                    "${result.queuedCount} queued in Transfers for verified cloud upload"
                else ->
                    "${result.queuedCount} queued for upload; ${result.skippedCount} already in Transfers"
            }
            _messages.emit(msg)
        }
    }

    fun findLocalMediaItem(node: UnifiedFileNode) =
        node.nativeId.toLongOrNull()?.let { mediaId ->
            graph.media.items.value.firstOrNull { it.id == mediaId }
        }
}

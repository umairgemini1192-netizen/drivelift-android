package com.drivelift.app.feature.storageintelligence.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.drivelift.app.drive.DriveFile
import com.drivelift.app.feature.storageintelligence.model.AutoSelectPreset
import com.drivelift.app.feature.storageintelligence.model.DuplicateGroupModel
import com.drivelift.app.feature.storageintelligence.model.DuplicateMatchKind
import com.drivelift.app.feature.storageintelligence.model.LARGE_THRESHOLD_PRESETS_MB
import com.drivelift.app.feature.storageintelligence.model.LargeFileCategory
import com.drivelift.app.feature.storageintelligence.model.OrphanArtifactCandidate
import com.drivelift.app.feature.storageintelligence.model.OrphanCategory
import com.drivelift.app.feature.storageintelligence.model.ScanPhase
import com.drivelift.app.feature.storageintelligence.model.ScanProgress
import com.drivelift.app.feature.storageintelligence.model.ScanScope
import com.drivelift.app.feature.storageintelligence.model.SourceScanError
import com.drivelift.app.feature.storageintelligence.model.UnifiedFileNode
import com.drivelift.app.feature.storageintelligence.model.classifyLargeFileCategory
import com.drivelift.app.feature.storageintelligence.scanner.SmartAutoSelectEngine
import com.drivelift.app.ui.AppViewModel
import com.drivelift.app.ui.FullScreenOverlay
import com.drivelift.app.ui.GlassPalette
import com.drivelift.app.ui.GlassPanel
import com.drivelift.app.ui.LocalMediaViewer
import com.drivelift.app.ui.RisingBar
import com.drivelift.app.ui.Tokens
import com.drivelift.app.ui.formatBytes
import com.drivelift.app.ui.glassBar
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val SI_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm")
private const val MB_BYTES = 1024L * 1024L

private fun formatTimestamp(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(SI_TIME_FORMAT)

/**
 * Unified Local + Multi-Cloud Storage Intelligence Hub following DriveLift's Dark Tactile Glass
 * design system.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageIntelligenceHubScreen(
    appViewModel: AppViewModel,
    onBack: () -> Unit,
    onRevealLocalFolder: (Long) -> Unit,
    onRevealDriveFolder: (DriveFile) -> Unit,
    vm: StorageIntelligenceViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by vm.uiState.collectAsStateWithLifecycle()
    val mediaList by appViewModel.media.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var viewingMediaId by rememberSaveable { mutableStateOf<Long?>(null) }
    val viewingItem = remember(viewingMediaId, mediaList) {
        viewingMediaId?.let { id -> mediaList.firstOrNull { it.id == id } }
    }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("si-hub")
            .then(if (viewingItem != null) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Column(Modifier.fillMaxWidth()) {
                RisingBar(visible = state.selectedCount > 0) {
                    StickyActionBar(
                        selectedCount = state.selectedCount,
                        selectedBytes = state.selectedBytes,
                        busy = state.cleanupBusy,
                        showOffloadCta = state.hasSelectedLocalLargeFiles,
                        onClear = vm::clearSelection,
                        onOffload = vm::offloadSelectedLargeFiles,
                        onClean = vm::prepareCleanup,
                    )
                }
            }
        },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                title = { Text("Storage intelligence", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to storage")
                    }
                },
                actions = {
                    if (state.scanProgress.isRunning) {
                        IconButton(
                            onClick = vm::cancelScan,
                            enabled = state.scanProgress.isCancellable,
                            modifier = Modifier.testTag("si-scan-cta"),
                        ) {
                            Icon(Icons.Outlined.Stop, contentDescription = "Stop scan")
                        }
                    } else {
                        IconButton(
                            onClick = vm::startScan,
                            modifier = Modifier.testTag("si-scan-cta"),
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Run unified scan")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("si-list"),
                contentPadding = PaddingValues(bottom = Tokens.Section),
            ) {
                item(key = "telemetry") {
                    TelemetryHeader(
                        state = state,
                        onSelectScope = vm::selectScope,
                        onSelectSource = vm::selectSourceFilter,
                        onStartScan = vm::startScan,
                        onStopScan = vm::cancelScan,
                    )
                }

                if (state.scanProgress.isRunning) {
                    item(key = "live-progress") {
                        LiveScanProgressPanel(
                            progress = state.scanProgress,
                            onCancel = vm::cancelScan,
                        )
                    }
                }

                if (state.sessionErrors.isNotEmpty()) {
                    item(key = "partial-errors") {
                        PartialFailureBanner(
                            errors = state.sessionErrors,
                            onReconnect = { appViewModel.connectDrive(interactive = true) },
                        )
                    }
                }

                item(key = "tabs") {
                    IntelligenceTabSelector(
                        activeTab = state.activeTab,
                        duplicateCount = state.duplicateGroups.size,
                        largeCount = state.largeFiles.size,
                        orphanCount = state.orphans.size,
                        onSelect = vm::selectTab,
                    )
                }

                when (state.activeTab) {
                    IntelligenceTab.DUPLICATES -> {
                        item(key = "dup-presets") {
                            AutoSelectPresetRow(
                                activePreset = state.activeAutoPreset,
                                enabled = state.duplicateGroups.isNotEmpty() && !state.cleanupBusy,
                                onSelectPreset = vm::applyAutoSelectPreset,
                            )
                        }
                        if (state.duplicateGroups.isEmpty()) {
                            item(key = "dup-empty") {
                                EmptyHubPanel(
                                    title = if (state.session == null) "No scan results yet" else "No duplicate candidates found",
                                    detail = if (state.session == null) {
                                        "Run a unified scan to inspect exact hash and probable matches across local and cloud storage."
                                    } else {
                                        "Files sharing identical size and content or matching names will appear here."
                                    },
                                    actionLabel = if (state.scanProgress.isRunning) null else "Scan now",
                                    onAction = vm::startScan,
                                )
                            }
                        } else {
                            items(state.duplicateGroups, key = { "dup-${it.groupKey}" }) { group ->
                                DuplicateGroupCard(
                                    group = group,
                                    selectedIds = state.selectedDuplicateNodeIds,
                                    protectedIds = state.protectedNodeIds,
                                    onToggle = vm::toggleDuplicateSelection,
                                    onReveal = { node ->
                                        revealNode(
                                            node = node,
                                            vm = vm,
                                            onOpenMedia = { viewingMediaId = it },
                                            onRevealLocal = onRevealLocalFolder,
                                            onRevealDrive = onRevealDriveFolder,
                                        )
                                    },
                                )
                            }
                        }
                    }

                    IntelligenceTab.LARGE_FILES -> {
                        item(key = "large-filters") {
                            LargeFileFilterBar(
                                thresholdBytes = state.thresholdBytes,
                                categoryFilter = state.categoryFilter,
                                onSelectThreshold = vm::setThresholdBytes,
                                onOpenCustomThreshold = vm::openCustomThresholdDialog,
                                onSelectCategory = vm::selectCategoryFilter,
                            )
                        }
                        if (state.largeFiles.isEmpty()) {
                            item(key = "large-empty") {
                                EmptyHubPanel(
                                    title = if (state.session == null) "No large-file index yet" else "No files match these filters",
                                    detail = "Adjust the size threshold or category filter, or run a scan to index local and cloud files.",
                                    actionLabel = if (state.scanProgress.isRunning) null else "Scan now",
                                    onAction = vm::startScan,
                                )
                            }
                        } else {
                            items(state.largeFiles, key = { "large-${it.id}" }) { node ->
                                LargeFileCard(
                                    node = node,
                                    selected = node.id in state.selectedLargeFileNodeIds,
                                    protected = node.id in state.protectedNodeIds,
                                    onToggle = { vm.toggleLargeFileSelection(node.id) },
                                    onOffload = { vm.offloadSingleLocalFile(node) },
                                    onTrash = { vm.prepareSingleLargeFileTrash(node) },
                                    onReveal = {
                                        revealNode(
                                            node = node,
                                            vm = vm,
                                            onOpenMedia = { viewingMediaId = it },
                                            onRevealLocal = onRevealLocalFolder,
                                            onRevealDrive = onRevealDriveFolder,
                                        )
                                    },
                                )
                            }
                        }
                    }

                    IntelligenceTab.ORPHANS -> {
                        item(key = "orphan-guard") {
                            OrphanSafetyNotePanel()
                        }
                        if (state.orphans.isEmpty()) {
                            item(key = "orphan-empty") {
                                EmptyHubPanel(
                                    title = if (state.session == null) "No orphan scan yet" else "No orphaned caches or stubs found",
                                    detail = "Stale caches, abandoned temp files outside the 24-hour grace period, and cloud 0-byte stubs appear here.",
                                    actionLabel = if (state.scanProgress.isRunning) null else "Scan now",
                                    onAction = vm::startScan,
                                )
                            }
                        } else {
                            val grouped = OrphanCategory.entries.mapNotNull { cat ->
                                val list = state.orphans.filter { it.category == cat }
                                if (list.isEmpty()) null else cat to list
                            }
                            for ((category, entries) in grouped) {
                                item(key = "orphan-header-${category.name}") {
                                    Text(
                                        category.label,
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.padding(
                                            start = Tokens.Edge,
                                            end = Tokens.Edge,
                                            top = Tokens.Medium,
                                            bottom = Tokens.Small,
                                        ),
                                    )
                                }
                                items(entries, key = { "orphan-${it.artifactId}" }) { item ->
                                    OrphanArtifactCard(
                                        candidate = item,
                                        selected = item.artifactId in state.selectedOrphanArtifactIds,
                                        onToggle = { vm.toggleOrphanSelection(item.artifactId) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    viewingItem?.let { item ->
        FullScreenOverlay {
            LocalMediaViewer(
                vm = appViewModel,
                item = item,
                onClose = { viewingMediaId = null },
            )
        }
    }

    state.pendingCleanupPlan?.let { plan ->
        BatchCleanupConfirmDialog(
            plan = plan,
            onConfirm = { vm.confirmCleanup(appViewModel) },
            onDismiss = vm::dismissCleanupConfirmation,
        )
    }

    if (state.showCustomThresholdDialog) {
        CustomThresholdDialog(
            currentMb = (state.thresholdBytes / MB_BYTES).coerceAtLeast(1L),
            onConfirmMb = { mb ->
                vm.setThresholdBytes(mb * MB_BYTES)
                vm.dismissCustomThresholdDialog()
            },
            onDismiss = vm::dismissCustomThresholdDialog,
        )
    }
}

private fun revealNode(
    node: UnifiedFileNode,
    vm: StorageIntelligenceViewModel,
    onOpenMedia: (Long) -> Unit,
    onRevealLocal: (Long) -> Unit,
    onRevealDrive: (DriveFile) -> Unit,
) {
    if (node.isLocal) {
        val localItem = vm.findLocalMediaItem(node)
        if (localItem != null) {
            onOpenMedia(localItem.id)
        } else {
            node.bucketId?.let(onRevealLocal)
        }
    } else {
        val folderId = node.parentFolderId ?: node.nativeId
        onRevealDrive(
            DriveFile(
                id = folderId,
                name = node.path.substringBeforeLast('/', node.sourceLabel),
                mimeType = "application/vnd.google-apps.folder",
                size = 0L,
                md5 = null,
                trashed = false,
            ),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TelemetryHeader(
    state: StorageIntelligenceUiState,
    onSelectScope: (ScanScope) -> Unit,
    onSelectSource: (String?) -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val compact = LocalConfiguration.current.screenWidthDp < Tokens.COMPACT_WIDTH_DP ||
        density.fontScale >= Tokens.STACK_AT_FONT_SCALE
    val leadStyle = if (compact) MaterialTheme.typography.displaySmall else MaterialTheme.typography.displayLarge

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Edge, vertical = Tokens.Small),
    ) {
        Text(
            formatBytes(context, state.totalReclaimableBytes),
            style = leadStyle,
        )
        val statusText = when {
            state.scanProgress.isRunning -> "Scanning ${state.scanProgress.activeSourceLabel ?: "storage"}"
            state.session != null -> "Reclaimable space · Scanned ${formatTimestamp(state.session.completedAtMs)}"
            else -> "Reclaimable space · Ready to scan local and cloud storage"
        }
        Text(
            statusText,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Tokens.Tiny),
        )

        Spacer(Modifier.height(Tokens.Medium))

        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                ScanScopeRow(state.scope, !state.scanProgress.isRunning, onSelectScope)
                PrimaryScanButton(state.scanProgress.isRunning, onStartScan, onStopScan)
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
            ) {
                Box(Modifier.weight(1f)) {
                    ScanScopeRow(state.scope, !state.scanProgress.isRunning, onSelectScope)
                }
                PrimaryScanButton(state.scanProgress.isRunning, onStartScan, onStopScan)
            }
        }

        if (state.availableSources.size > 1) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = Tokens.Small),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                verticalArrangement = Arrangement.spacedBy(Tokens.Tiny),
            ) {
                for (option in state.availableSources) {
                    FilterChip(
                        selected = state.sourceFilterId == option.sourceId,
                        onClick = { onSelectSource(option.sourceId) },
                        label = { Text(option.label) },
                        modifier = Modifier.heightIn(min = Tokens.TouchTarget),
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanScopeRow(
    selected: ScanScope,
    enabled: Boolean,
    onSelect: (ScanScope) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
    ) {
        for (scope in ScanScope.entries) {
            FilterChip(
                selected = scope == selected,
                onClick = { onSelect(scope) },
                enabled = enabled,
                label = { Text(scope.label) },
                modifier = Modifier.heightIn(min = Tokens.TouchTarget),
            )
        }
    }
}

@Composable
private fun PrimaryScanButton(
    running: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    if (running) {
        OutlinedButton(
            onClick = onStop,
            modifier = Modifier.heightIn(min = Tokens.TouchTarget),
        ) {
            Text("Stop")
        }
    } else {
        Button(
            onClick = onStart,
            modifier = Modifier.heightIn(min = Tokens.TouchTarget),
        ) {
            Text("Scan")
        }
    }
}

@Composable
private fun LiveScanProgressPanel(
    progress: ScanProgress,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    GlassPanel(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Edge, vertical = Tokens.Small),
    ) {
        Column(Modifier.padding(horizontal = Tokens.Gutter, vertical = Tokens.Medium)) {
            Text(
                "${progress.phase.label}: ${progress.activeSourceLabel ?: "Working"}",
                style = MaterialTheme.typography.titleSmall,
            )
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Tokens.Small)
                    .height(Tokens.ProgressHeight),
                color = MaterialTheme.colorScheme.tertiary,
                trackColor = GlassPalette.Dim,
            )
            val detail = "${progress.scannedCount} files · ${formatBytes(context, progress.bytesAnalyzed)} analyzed"
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (progress.isCancellable) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.padding(top = Tokens.Tiny).heightIn(min = Tokens.TouchTarget),
                ) {
                    Text("Cancel scan")
                }
            }
        }
    }
}

@Composable
private fun PartialFailureBanner(
    errors: List<SourceScanError>,
    onReconnect: () -> Unit,
) {
    val needsReconnect = errors.any { it.authExpired }
    GlassPanel(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Edge, vertical = Tokens.Small),
    ) {
        Column(Modifier.padding(horizontal = Tokens.Gutter, vertical = Tokens.Medium)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(Tokens.Icon),
                )
                Spacer(Modifier.width(Tokens.Small))
                Text(
                    "Some sources could not finish scanning",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            for (err in errors) {
                Text(
                    "${err.sourceLabel}: ${err.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Tokens.Tiny),
                )
            }
            if (needsReconnect) {
                OutlinedButton(
                    onClick = onReconnect,
                    modifier = Modifier.padding(top = Tokens.Small).heightIn(min = Tokens.TouchTarget),
                ) {
                    Text("Reconnect Google Drive")
                }
            }
        }
    }
}

@Composable
private fun IntelligenceTabSelector(
    activeTab: IntelligenceTab,
    duplicateCount: Int,
    largeCount: Int,
    orphanCount: Int,
    onSelect: (IntelligenceTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Edge, vertical = Tokens.Small)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
    ) {
        for (tab in IntelligenceTab.entries) {
            val count = when (tab) {
                IntelligenceTab.DUPLICATES -> duplicateCount
                IntelligenceTab.LARGE_FILES -> largeCount
                IntelligenceTab.ORPHANS -> orphanCount
            }
            val tag = when (tab) {
                IntelligenceTab.DUPLICATES -> "si-tab-duplicates"
                IntelligenceTab.LARGE_FILES -> "si-tab-large"
                IntelligenceTab.ORPHANS -> "si-tab-orphans"
            }
            val selected = tab == activeTab
            val wellModifier = if (selected) {
                Modifier
                    .clip(Tokens.PillShape)
                    .background(MaterialTheme.colorScheme.primaryContainer, Tokens.PillShape)
                    .border(Tokens.Hairline, MaterialTheme.colorScheme.primary, Tokens.PillShape)
            } else {
                Modifier
                    .clip(Tokens.PillShape)
                    .background(GlassPalette.Well, Tokens.PillShape)
                    .border(Tokens.Hairline, GlassPalette.WellEdge, Tokens.PillShape)
            }
            Box(
                modifier = wellModifier
                    .heightIn(min = Tokens.TouchTarget)
                    .clickable(onClick = { onSelect(tab) })
                    .padding(horizontal = Tokens.Gutter, vertical = Tokens.Small)
                    .testTag(tag),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${tab.label} ($count)",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun AutoSelectPresetRow(
    activePreset: AutoSelectPreset?,
    enabled: Boolean,
    onSelectPreset: (AutoSelectPreset) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = Tokens.Small)) {
        Text(
            "Smart auto-select",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Tokens.Edge, vertical = Tokens.Tiny),
        )
        LazyRow(
            modifier = Modifier.testTag("si-presets-row"),
            contentPadding = PaddingValues(horizontal = Tokens.Edge),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
        ) {
            items(AutoSelectPreset.entries) { preset ->
                FilterChip(
                    selected = activePreset == preset,
                    onClick = { onSelectPreset(preset) },
                    enabled = enabled,
                    label = { Text(preset.label) },
                    modifier = Modifier.heightIn(min = Tokens.TouchTarget),
                )
            }
        }
    }
}

@Composable
private fun DuplicateGroupCard(
    group: DuplicateGroupModel,
    selectedIds: Set<String>,
    protectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onReveal: (UnifiedFileNode) -> Unit,
) {
    val context = LocalContext.current
    val autoSelect = remember { SmartAutoSelectEngine() }
    GlassPanel(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Edge, vertical = Tokens.Tiny),
    ) {
        Column(Modifier.padding(vertical = Tokens.Small)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Tokens.Gutter, vertical = Tokens.Tiny),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${group.items.size} copies · ${formatBytes(context, group.sizeBytes)} each",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    val subtitle = buildString {
                        append(group.matchKind.label)
                        if (group.isCrossSource) append(" · Cross-source")
                        if (group.matchKind == DuplicateMatchKind.EXACT_HASH && !group.hashValue.isNullOrBlank()) {
                            append(" · ${group.hashAlgorithm.label} ${group.hashValue.take(10)}")
                        } else if (!group.normalizedName.isNullOrBlank()) {
                            append(" · Matching name")
                        }
                    }
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "${formatBytes(context, group.reclaimableBytes)} freeable",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            for (item in group.items) {
                val checked = item.id in selectedIds
                val isProtected = item.id in protectedIds
                val enabled = !isProtected && autoSelect.canToggleSelect(group, selectedIds, item.id, protectedIds)
                val note = when {
                    isProtected -> "Locked by active transfer"
                    !enabled -> "Kept: at least 1 copy stays"
                    else -> null
                }
                DuplicateNodeRow(
                    node = item,
                    checked = checked,
                    enabled = enabled,
                    note = note,
                    onToggle = { onToggle(item.id) },
                    onReveal = { onReveal(item) },
                )
            }
        }
    }
}

@Composable
private fun DuplicateNodeRow(
    node: UnifiedFileNode,
    checked: Boolean,
    enabled: Boolean,
    note: String?,
    onToggle: () -> Unit,
    onReveal: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(end = Tokens.Tiny),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
                .padding(start = Tokens.Small, top = Tokens.Small, bottom = Tokens.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                modifier = Modifier.size(Tokens.TouchTarget),
            )
            NodeThumbnail(node)
            Column(Modifier.weight(1f).padding(start = Tokens.Medium)) {
                Text(node.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${node.sourceLabel} · ${node.path}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (note != null) {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        IconButton(onClick = onReveal) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Inspect ${node.name}")
        }
    }
}

@Composable
private fun LargeFileFilterBar(
    thresholdBytes: Long,
    categoryFilter: LargeFileCategory,
    onSelectThreshold: (Long) -> Unit,
    onOpenCustomThreshold: () -> Unit,
    onSelectCategory: (LargeFileCategory) -> Unit,
) {
    val thresholdMb = thresholdBytes / MB_BYTES
    val isPreset = thresholdMb in LARGE_THRESHOLD_PRESETS_MB
    Column(Modifier.fillMaxWidth().padding(vertical = Tokens.Small)) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = Tokens.Edge),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
        ) {
            items(LARGE_THRESHOLD_PRESETS_MB) { mb ->
                val label = if (mb >= 1024L) "${mb / 1024L} GB" else "$mb MB"
                FilterChip(
                    selected = thresholdMb == mb,
                    onClick = { onSelectThreshold(mb * MB_BYTES) },
                    label = { Text(label) },
                    modifier = Modifier.heightIn(min = Tokens.TouchTarget),
                )
            }
            item(key = "custom-threshold") {
                FilterChip(
                    selected = !isPreset,
                    onClick = onOpenCustomThreshold,
                    label = { Text(if (isPreset) "Custom" else "$thresholdMb MB") },
                    modifier = Modifier.heightIn(min = Tokens.TouchTarget),
                )
            }
        }
        Spacer(Modifier.height(Tokens.Tiny))
        LazyRow(
            contentPadding = PaddingValues(horizontal = Tokens.Edge),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
        ) {
            items(LargeFileCategory.entries) { cat ->
                FilterChip(
                    selected = categoryFilter == cat,
                    onClick = { onSelectCategory(cat) },
                    label = { Text(cat.label) },
                    modifier = Modifier.heightIn(min = Tokens.TouchTarget),
                )
            }
        }
    }
}

@Composable
private fun LargeFileCard(
    node: UnifiedFileNode,
    selected: Boolean,
    protected: Boolean,
    onToggle: () -> Unit,
    onOffload: () -> Unit,
    onTrash: () -> Unit,
    onReveal: () -> Unit,
) {
    val context = LocalContext.current
    val category = classifyLargeFileCategory(node.name, node.mimeType)
    val compact = LocalConfiguration.current.screenWidthDp < Tokens.COMPACT_WIDTH_DP ||
        LocalDensity.current.fontScale >= Tokens.STACK_AT_FONT_SCALE

    GlassPanel(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Edge, vertical = Tokens.Tiny),
    ) {
        Column(Modifier.padding(Tokens.Medium)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = null,
                    modifier = Modifier.size(Tokens.TouchTarget),
                )
                NodeThumbnail(node)
                Column(Modifier.weight(1f).padding(start = Tokens.Medium)) {
                    Text(node.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${formatBytes(context, node.sizeBytes)} · ${category.label} · ${node.sourceLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        node.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val actionButtons: @Composable () -> Unit = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (node.isLocal) {
                        OutlinedButton(
                            onClick = onOffload,
                            enabled = !protected,
                            modifier = Modifier.heightIn(min = Tokens.TouchTarget),
                        ) {
                            Icon(Icons.Outlined.CloudUpload, contentDescription = null, modifier = Modifier.size(Tokens.IconSmall))
                            Spacer(Modifier.width(Tokens.Tiny))
                            Text(if (protected) "In transfer" else "Offload")
                        }
                    }
                    IconButton(onClick = onReveal) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = "Inspect or reveal ${node.name}")
                    }
                    IconButton(onClick = onTrash, enabled = !protected) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "Move ${node.name} to trash")
                    }
                }
            }
            if (compact) {
                Box(Modifier.fillMaxWidth().padding(top = Tokens.Small)) { actionButtons() }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(top = Tokens.Tiny),
                    horizontalArrangement = Arrangement.End,
                ) { actionButtons() }
            }
        }
    }
}

@Composable
private fun OrphanSafetyNotePanel() {
    GlassPanel(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Edge, vertical = Tokens.Small),
    ) {
        Row(
            Modifier.padding(horizontal = Tokens.Gutter, vertical = Tokens.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(Tokens.Icon),
            )
            Spacer(Modifier.width(Tokens.Medium))
            Text(
                "Safety lock active: files queued or running in Transfers and items modified within 24 hours are protected unless explicitly failed or cancelled.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OrphanArtifactCard(
    candidate: OrphanArtifactCandidate,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    GlassPanel(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Edge, vertical = Tokens.Tiny),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .let { mod ->
                    if (candidate.canClean) {
                        mod.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
                    } else {
                        mod
                    }
                }
                .padding(horizontal = Tokens.Small, vertical = Tokens.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (candidate.canClean) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = null,
                    modifier = Modifier.size(Tokens.TouchTarget),
                )
            } else {
                Box(Modifier.size(Tokens.TouchTarget), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.Lock,
                        contentDescription = "Summary only",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(Modifier.weight(1f).padding(start = Tokens.Small)) {
                Text(candidate.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    candidate.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${candidate.sourceLabel} · ${candidate.path}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(Tokens.Small))
            Text(
                formatBytes(context, candidate.sizeBytes),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun NodeThumbnail(node: UnifiedFileNode) {
    val context = LocalContext.current
    val modelData: Any? = node.localUri ?: node.thumbnailRef
    if (modelData != null) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(modelData).size(192).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(Tokens.FolderThumb)
                .clip(Tokens.ThumbShape)
                .background(GlassPalette.Thumb),
        )
    } else {
        Box(
            modifier = Modifier
                .size(Tokens.FolderThumb)
                .clip(Tokens.ThumbShape)
                .background(GlassPalette.Thumb),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Tokens.Icon),
            )
        }
    }
}

@Composable
private fun EmptyHubPanel(
    title: String,
    detail: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    GlassPanel(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Edge, vertical = Tokens.Small),
    ) {
        Column(Modifier.padding(horizontal = Tokens.Gutter, vertical = Tokens.Medium)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Tokens.Tiny).widthIn(max = Tokens.ReadableWidth),
            )
            if (actionLabel != null) {
                Button(
                    onClick = onAction,
                    modifier = Modifier.padding(top = Tokens.Medium).heightIn(min = Tokens.TouchTarget),
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun StickyActionBar(
    selectedCount: Int,
    selectedBytes: Long,
    busy: Boolean,
    showOffloadCta: Boolean,
    onClear: () -> Unit,
    onOffload: () -> Unit,
    onClean: () -> Unit,
) {
    val context = LocalContext.current
    val compact = LocalConfiguration.current.screenWidthDp < Tokens.COMPACT_WIDTH_DP ||
        LocalDensity.current.fontScale >= Tokens.STACK_AT_FONT_SCALE
    Box(Modifier.fillMaxWidth().glassBar()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Tokens.Edge, vertical = Tokens.Medium)) {
            Text(
                if (selectedCount == 1) "1 item selected" else "$selectedCount items selected",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                formatBytes(context, selectedBytes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Tokens.Small))
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                    if (showOffloadCta) {
                        Button(
                            onClick = onOffload,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.TouchTarget),
                        ) {
                            Text("Offload selected")
                        }
                    }
                    Button(
                        onClick = onClean,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.TouchTarget),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Text("Clean selected")
                    }
                    OutlinedButton(
                        onClick = onClear,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.TouchTarget),
                    ) {
                        Text("Clear")
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onClear,
                        enabled = !busy,
                        modifier = Modifier.heightIn(min = Tokens.TouchTarget),
                    ) {
                        Text("Clear")
                    }
                    if (showOffloadCta) {
                        Button(
                            onClick = onOffload,
                            enabled = !busy,
                            modifier = Modifier.weight(1f).heightIn(min = Tokens.TouchTarget),
                        ) {
                            Text("Offload selected", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Button(
                        onClick = onClean,
                        enabled = !busy,
                        modifier = Modifier.weight(1f).heightIn(min = Tokens.TouchTarget),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Text("Clean selected", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

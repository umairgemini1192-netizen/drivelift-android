package com.drivelift.app.feature.cleaner.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivelift.app.Graph
import com.drivelift.app.feature.cleaner.cache.GuidedPillState
import com.drivelift.app.feature.cleaner.cache.ShizukuAvailability
import com.drivelift.app.feature.cleaner.cache.ShizukuStatusSnapshot
import com.drivelift.app.feature.cleaner.engine.CleanerCategory
import com.drivelift.app.feature.cleaner.engine.CleanerCategoryGroup
import com.drivelift.app.feature.cleaner.engine.CleanerItem
import com.drivelift.app.feature.cleaner.engine.CleanerSafetyTier
import com.drivelift.app.feature.cleaner.engine.StorageCapacityBreakdown
import com.drivelift.app.feature.hibernation.engine.HibernationMode
import com.drivelift.app.ui.AppMotion
import com.drivelift.app.ui.GlassPalette
import com.drivelift.app.ui.GlassPanel
import com.drivelift.app.ui.Tokens
import com.drivelift.app.ui.formatBytes
import com.drivelift.app.ui.glassBar

/**
 * Target destination for each deep-clean card on the Cleaner Dashboard and post-clean celebration.
 */
enum class DeepCleanDestination {
    WHATSAPP,
    LARGE_FILES,
    DUPLICATES,
    SLEEP_MODE,
    RARELY_USED,
}

/**
 * Summary model for a single deep-clean quick card.
 */
data class DeepCleanCardSummary(
    val destination: DeepCleanDestination,
    val title: String,
    val subtitle: String,
    val bytes: Long,
    val itemCount: Int,
)

/**
 * Complete UI state rendered by [CleanerDashboardContent].
 */
data class CleanerDashboardUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val needsUsageAccess: Boolean = false,
    val groups: List<CleanerCategoryGroup> = emptyList(),
    val selectedItemIds: Set<String> = emptySet(),
    val expandedCategories: Set<CleanerCategory> = emptySet(),
    val breakdown: StorageCapacityBreakdown = StorageCapacityBreakdown(
        totalDeviceBytes = 0L,
        freeDeviceBytes = 0L,
        safeUnneededBytes = 0L,
        hiddenCacheBytes = 0L,
        filesToReviewBytes = 0L,
    ),
    val shizukuStatus: ShizukuStatusSnapshot = ShizukuStatusSnapshot(
        availability = ShizukuAvailability.NOT_INSTALLED,
        effectiveMode = HibernationMode.GUIDED_PILL,
        detail = "Shizuku not installed. Guided Pill fallback is ready.",
    ),
    val deepCleanCards: List<DeepCleanCardSummary> = defaultDeepCleanCards(),
    val guidedPill: GuidedPillState? = null,
    val celebrationReclaimedBytes: Long? = null,
    val celebrationDeletedCount: Int = 0,
    val scannedFileCount: Int = 0,
) {
    val selectedBytes: Long
        get() = groups.asSequence()
            .flatMap { it.items.asSequence() }
            .filter { it.id in selectedItemIds }
            .sumOf { it.sizeBytes }

    val selectedCount: Int
        get() = groups.asSequence()
            .flatMap { it.items.asSequence() }
            .count { it.id in selectedItemIds }

    val isCelebration: Boolean
        get() = celebrationReclaimedBytes != null
}

/**
 * Stateful Cleaner Dashboard entry point connected to [Graph.cleanerAndSleep].
 */
@Composable
fun CleanerDashboardScreen(
    onBack: () -> Unit,
    onOpenSleepMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val module = remember(context) { Graph.get(context).cleanerAndSleep }
    val state by module.cleanerState.collectAsStateWithLifecycle()

    LaunchedEffect(module) {
        module.ensureInitialScan()
    }

    CleanerDashboardContent(
        state = state,
        onBack = onBack,
        onRescan = { module.runFullCleanerScan() },
        onGrantUsageAccess = {
            runCatching {
                context.startActivity(module.statsSource.createUsageAccessSettingsIntent())
            }
        },
        onToggleCategoryExpand = { module.toggleCategoryExpanded(it) },
        onToggleCategorySelection = { module.toggleCategorySelection(it) },
        onToggleItemSelection = { module.toggleItemSelection(it) },
        onExecuteClean = { module.executeSelectedCleanup() },
        onDismissCelebration = { module.dismissCelebration() },
        onOpenDeepClean = { dest ->
            if (dest == DeepCleanDestination.SLEEP_MODE) {
                onOpenSleepMode()
            } else {
                module.focusDeepCleanCategory(dest)
            }
        },
        onOpenGuidedAppInfo = { pkg ->
            runCatching {
                context.startActivity(com.drivelift.app.feature.cleaner.cache.ShizukuSilentAdapter.createAppInfoIntent(pkg))
            }
        },
        onConfirmGuidedStep = { module.confirmGuidedCleanStep() },
        onNextGuidedApp = {
            val nextPkg = module.advanceGuidedCleanToNextApp()
            if (nextPkg != null) {
                runCatching {
                    context.startActivity(com.drivelift.app.feature.cleaner.cache.ShizukuSilentAdapter.createAppInfoIntent(nextPkg))
                }
            }
        },
        onSkipGuidedStep = { module.skipGuidedCleanStep() },
        onCancelGuidedSession = { module.cancelGuidedCleanSession() },
        onShizukuAction = {
            module.handleShizukuBannerAction { intent ->
                runCatching { context.startActivity(intent) }
            }
        },
        modifier = modifier,
    )
}

/**
 * Stateless flagship Material 3 Expressive + OLED Tactile Glass Cleaner Dashboard.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CleanerDashboardContent(
    state: CleanerDashboardUiState,
    onBack: () -> Unit,
    onRescan: () -> Unit,
    onGrantUsageAccess: () -> Unit,
    onToggleCategoryExpand: (CleanerCategory) -> Unit,
    onToggleCategorySelection: (CleanerCategory) -> Unit,
    onToggleItemSelection: (String) -> Unit,
    onExecuteClean: () -> Unit,
    onDismissCelebration: () -> Unit,
    onOpenDeepClean: (DeepCleanDestination) -> Unit,
    onOpenGuidedAppInfo: (String) -> Unit,
    onConfirmGuidedStep: () -> Unit,
    onSkipGuidedStep: () -> Unit,
    onCancelGuidedSession: () -> Unit,
    modifier: Modifier = Modifier,
    onShizukuAction: () -> Unit = {},
    onNextGuidedApp: () -> Unit = onConfirmGuidedStep,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var showCleanConfirm by remember { mutableStateOf(false) }

    // Trigger tactile confirmation haptics when entering post-clean celebration.
    LaunchedEffect(state.isCelebration) {
        if (state.isCelebration) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    Box(modifier = modifier.fillMaxSize().testTag("cleaner-dashboard-screen")) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                title = { Text("Storage Cleaner") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("cleaner-back-button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onRescan,
                        modifier = Modifier.testTag("cleaner-rescan-button"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Rescan storage",
                        )
                    }
                },
            )

            if (state.guidedPill != null && state.guidedPill.isActive) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Tokens.Edge, vertical = Tokens.Small),
                ) {
                    GuidedDynamicIslandPill(
                        state = state.guidedPill,
                        onOpenAppInfo = onOpenGuidedAppInfo,
                        onConfirmStep = onConfirmGuidedStep,
                        onNextApp = onNextGuidedApp,
                        onSkipStep = onSkipGuidedStep,
                        onCancel = onCancelGuidedSession,
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("cleaner-dashboard-list"),
                contentPadding = PaddingValues(
                    start = Tokens.Edge,
                    end = Tokens.Edge,
                    top = Tokens.Small,
                    bottom = Tokens.Section,
                ),
                verticalArrangement = Arrangement.spacedBy(Tokens.Medium),
            ) {
                if (state.isLoading) {
                    item(key = "loading") {
                        CleanerLoadingPanel(scannedFileCount = state.scannedFileCount)
                    }
                } else {
                    if (state.errorMessage != null || state.needsUsageAccess) {
                        item(key = "error-banner") {
                            CleanerErrorAndPermissionBanner(
                                errorMessage = state.errorMessage,
                                needsUsageAccess = state.needsUsageAccess,
                                onGrantUsageAccess = onGrantUsageAccess,
                                onRetry = onRescan,
                            )
                        }
                    }

                    if (!state.isCelebration) {
                        item(key = "shizuku-status-banner") {
                            ShizukuSilentModeBanner(
                                status = state.shizukuStatus,
                                onAction = onShizukuAction,
                            )
                        }
                    }

                    item(key = "hero") {
                        val heroBytes = if (state.isCelebration) {
                            state.celebrationReclaimedBytes ?: 0L
                        } else {
                            state.breakdown.totalReclaimableBytes
                        }
                        val heroHeadline = when {
                            state.isCelebration -> "Space reclaimed"
                            state.breakdown.totalReclaimableBytes == 0L -> "Storage is clean"
                            else -> "Reclaimable storage"
                        }
                        val heroSubtitle = when {
                            state.isCelebration ->
                                "Cleaned ${state.celebrationDeletedCount} items. Safe unneeded categories are clear."
                            state.breakdown.totalReclaimableBytes == 0L ->
                                "No junk files or app caches need attention right now."
                            else ->
                                "${state.selectedCount} items selected (${formatBytes(context, state.selectedBytes)}) across ${state.groups.size} categories."
                        }
                        CleanerMoodHeroCard(
                            reclaimableBytes = heroBytes,
                            breakdown = state.breakdown,
                            headlineLabel = heroHeadline,
                            subtitle = heroSubtitle,
                            isCelebration = state.isCelebration || state.breakdown.totalReclaimableBytes == 0L,
                            badgeContent = {
                                ExecutionModeBadge(status = state.shizukuStatus)
                            },
                        )
                    }

                    if (state.isCelebration) {
                        item(key = "celebration-card") {
                            PostCleanCelebrationPanel(
                                reclaimedBytes = state.celebrationReclaimedBytes ?: 0L,
                                deletedCount = state.celebrationDeletedCount,
                                onDone = onDismissCelebration,
                            )
                        }
                    }

                    if (!state.isCelebration && state.groups.isEmpty() && state.errorMessage == null) {
                        item(key = "empty-state") {
                            CleanerEmptyStatePanel(onRescan = onRescan)
                        }
                    }

                    if (!state.isCelebration && state.groups.isNotEmpty()) {
                        item(key = "categories-header") {
                            Text(
                                text = "Categories",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(top = Tokens.Small),
                            )
                        }

                        items(
                            items = state.groups,
                            key = { it.category.name },
                        ) { group ->
                            CleanerCategoryAccordion(
                                group = group,
                                selectedItemIds = state.selectedItemIds,
                                expanded = group.category in state.expandedCategories,
                                onToggleExpand = { onToggleCategoryExpand(group.category) },
                                onToggleGroupSelection = { onToggleCategorySelection(group.category) },
                                onToggleItemSelection = onToggleItemSelection,
                            )
                        }
                    }

                    item(key = "deep-clean-header") {
                        Text(
                            text = if (state.isCelebration) "Next deep-clean steps" else "Deep clean tools",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = Tokens.Small),
                        )
                    }

                    item(key = "deep-clean-grid") {
                        DeepCleanCardsSection(
                            cards = state.deepCleanCards,
                            onOpenDeepClean = onOpenDeepClean,
                        )
                    }
                }
            }

            if (!state.isLoading && !state.isCelebration && state.groups.isNotEmpty()) {
                CleanerBottomActionBar(
                    selectedCount = state.selectedCount,
                    selectedBytes = state.selectedBytes,
                    onCleanClick = { showCleanConfirm = true },
                )
            }
        }
    }

    if (showCleanConfirm) {
        AlertDialog(
            onDismissRequest = { showCleanConfirm = false },
            title = { Text("Clean selected items?") },
            text = {
                Text(
                    "Remove ${state.selectedCount} selected items (${formatBytes(context, state.selectedBytes)}). " +
                        "Protected roots and one copy of every duplicate group are always kept.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCleanConfirm = false
                        onExecuteClean()
                    },
                    modifier = Modifier
                        .heightIn(min = Tokens.TouchTarget)
                        .testTag("cleaner-confirm-execute"),
                ) {
                    Text("Clean now")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCleanConfirm = false },
                    modifier = Modifier.heightIn(min = Tokens.TouchTarget),
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

/**
 * Clear top status banner for enabling or binding Shizuku Silent Mode in 1 tap across Cleaner
 * and Sleep Mode so users skip manual App Info screens whenever Shizuku is active.
 */
@Composable
fun ShizukuSilentModeBanner(
    status: ShizukuStatusSnapshot,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val compact = LocalDensity.current.fontScale >= Tokens.STACK_AT_FONT_SCALE ||
        LocalConfiguration.current.screenWidthDp < Tokens.Cleaner.CompactStackWidthDp
    val isSilent = status.effectiveMode == HibernationMode.SHIZUKU_SILENT
    val accent = when (status.availability) {
        ShizukuAvailability.READY -> GlassPalette.HudEmerald
        ShizukuAvailability.PERMISSION_REQUIRED -> MaterialTheme.colorScheme.primary
        ShizukuAvailability.NOT_RUNNING -> GlassPalette.HudAmber
        ShizukuAvailability.NOT_INSTALLED,
        ShizukuAvailability.UNSUPPORTED_ANDROID_VERSION,
        -> GlassPalette.HudElectricCyan
    }
    val headline = when (status.availability) {
        ShizukuAvailability.READY -> "Shizuku Silent Mode active"
        ShizukuAvailability.PERMISSION_REQUIRED -> "Enable Shizuku Silent Mode"
        ShizukuAvailability.NOT_RUNNING -> "Start Shizuku for Silent Mode"
        ShizukuAvailability.NOT_INSTALLED -> "Silent Mode via Shizuku"
        ShizukuAvailability.UNSUPPORTED_ANDROID_VERSION -> "Guided Pill Mode active"
    }
    val subtitle = when (status.availability) {
        ShizukuAvailability.READY -> "Apps clean and sleep silently without App Info screens."
        ShizukuAvailability.PERMISSION_REQUIRED -> "Bind Shizuku in 1 tap to skip manual App Info screens."
        ShizukuAvailability.NOT_RUNNING -> "Shizuku is installed but stopped. Start it to avoid App Info screens."
        ShizukuAvailability.NOT_INSTALLED -> "Install Shizuku for 1-tap silent clean and sleep, or use Guided Pill."
        ShizukuAvailability.UNSUPPORTED_ANDROID_VERSION -> status.detail
    }
    val actionLabel = when (status.availability) {
        ShizukuAvailability.READY -> "Refresh"
        ShizukuAvailability.PERMISSION_REQUIRED -> "Bind Shizuku"
        ShizukuAvailability.NOT_RUNNING -> "Open Shizuku"
        ShizukuAvailability.NOT_INSTALLED -> "Enable Shizuku"
        ShizukuAvailability.UNSUPPORTED_ANDROID_VERSION -> null
    }

    GlassPanel(
        shape = Tokens.Cleaner.CardShape,
        modifier = modifier
            .fillMaxWidth()
            .testTag("shizuku-status-banner"),
    ) {
        if (compact) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Tokens.Gutter),
                verticalArrangement = Arrangement.spacedBy(Tokens.Small),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Shield,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(Tokens.IconSmall),
                    )
                    Text(
                        text = headline,
                        style = MaterialTheme.typography.titleSmall,
                        color = accent,
                    )
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (actionLabel != null) {
                    if (isSilent) {
                        OutlinedButton(
                            onClick = onAction,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = Tokens.TouchTarget)
                                .testTag("shizuku-banner-action"),
                        ) {
                            Text(actionLabel)
                        }
                    } else {
                        Button(
                            onClick = onAction,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = Tokens.TouchTarget)
                                .testTag("shizuku-banner-action"),
                        ) {
                            Text(actionLabel)
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Tokens.Gutter),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Medium),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Shield,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(Tokens.Icon),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Tiny),
                ) {
                    Text(
                        text = headline,
                        style = MaterialTheme.typography.titleSmall,
                        color = accent,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (actionLabel != null) {
                    if (isSilent) {
                        OutlinedButton(
                            onClick = onAction,
                            modifier = Modifier
                                .heightIn(min = Tokens.TouchTarget)
                                .testTag("shizuku-banner-action"),
                        ) {
                            Text(actionLabel)
                        }
                    } else {
                        Button(
                            onClick = onAction,
                            modifier = Modifier
                                .heightIn(min = Tokens.TouchTarget)
                                .testTag("shizuku-banner-action"),
                        ) {
                            Text(actionLabel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExecutionModeBadge(status: ShizukuStatusSnapshot) {
    val isSilent = status.effectiveMode == HibernationMode.SHIZUKU_SILENT
    val accent = if (isSilent) GlassPalette.HudEmerald else GlassPalette.HudElectricCyan
    val label = if (isSilent) "Shizuku silent" else "Guided Pill"

    Row(
        modifier = Modifier
            .clip(Tokens.Cleaner.BadgeShape)
            .background(GlassPalette.Well)
            .border(Tokens.Hairline, accent.copy(alpha = 0.5f), Tokens.Cleaner.BadgeShape)
            .padding(horizontal = Tokens.Row, vertical = Tokens.Tiny)
            .testTag("cleaner-mode-badge"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Snug),
    ) {
        Icon(
            imageVector = Icons.Outlined.Shield,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(Tokens.IconSmall),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = accent,
        )
    }
}

@Composable
private fun CleanerLoadingPanel(scannedFileCount: Int) {
    GlassPanel(
        shape = Tokens.Cleaner.CardShape,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cleaner-loading-panel"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Tokens.Edge),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Gutter),
        ) {
            CircularProgressIndicator(
                color = GlassPalette.HudElectricCyan,
                modifier = Modifier.size(Tokens.Icon),
            )
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Tiny)) {
                Text(
                    text = "Scanning storage and app caches",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (scannedFileCount > 0) {
                        "$scannedFileCount files inspected so far."
                    } else {
                        "Checking folders, caches, and duplicate candidates."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CleanerErrorAndPermissionBanner(
    errorMessage: String?,
    needsUsageAccess: Boolean,
    onGrantUsageAccess: () -> Unit,
    onRetry: () -> Unit,
) {
    val compact = LocalDensity.current.fontScale >= Tokens.STACK_AT_FONT_SCALE ||
        LocalConfiguration.current.screenWidthDp < Tokens.Cleaner.CompactStackWidthDp

    GlassPanel(
        shape = Tokens.Cleaner.CardShape,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cleaner-error-banner"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Tokens.Gutter),
            verticalArrangement = Arrangement.spacedBy(Tokens.Medium),
        ) {
            Text(
                text = if (needsUsageAccess) "Usage access needed for app caches" else "Scan incomplete",
                style = MaterialTheme.typography.titleMedium,
                color = GlassPalette.HudAmber,
            )
            Text(
                text = errorMessage
                    ?: "Grant Usage Access in Android Settings to measure hidden app caches and rarely used apps.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                    if (needsUsageAccess) {
                        Button(
                            onClick = onGrantUsageAccess,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = Tokens.TouchTarget)
                                .testTag("cleaner-grant-usage-button"),
                        ) {
                            Text("Grant Usage Access")
                        }
                    }
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Tokens.TouchTarget)
                            .testTag("cleaner-retry-button"),
                    ) {
                        Text("Retry scan")
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                    if (needsUsageAccess) {
                        Button(
                            onClick = onGrantUsageAccess,
                            modifier = Modifier
                                .heightIn(min = Tokens.TouchTarget)
                                .testTag("cleaner-grant-usage-button"),
                        ) {
                            Text("Grant Usage Access")
                        }
                    }
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier
                            .heightIn(min = Tokens.TouchTarget)
                            .testTag("cleaner-retry-button"),
                    ) {
                        Text("Retry scan")
                    }
                }
            }
        }
    }
}

@Composable
private fun CleanerEmptyStatePanel(onRescan: () -> Unit) {
    GlassPanel(
        shape = Tokens.Cleaner.CardShape,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cleaner-empty-panel"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Tokens.Edge),
            verticalArrangement = Arrangement.spacedBy(Tokens.Medium),
        ) {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = GlassPalette.HudEmerald,
                modifier = Modifier.size(Tokens.Icon),
            )
            Text(
                text = "Nothing to clean right now",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Temporary files, thumbnails, empty folders, and caches are clear.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onRescan,
                modifier = Modifier
                    .heightIn(min = Tokens.TouchTarget)
                    .testTag("cleaner-empty-rescan-button"),
            ) {
                Text("Scan again")
            }
        }
    }
}

@Composable
private fun PostCleanCelebrationPanel(
    reclaimedBytes: Long,
    deletedCount: Int,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    GlassPanel(
        shape = Tokens.Cleaner.CardShape,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cleaner-celebration-panel"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Tokens.Edge),
            verticalArrangement = Arrangement.spacedBy(Tokens.Medium),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Row),
            ) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = GlassPalette.HudEmerald,
                    modifier = Modifier.size(Tokens.Icon),
                )
                Text(
                    text = "Cleanup complete",
                    style = MaterialTheme.typography.titleLarge,
                    color = GlassPalette.HudEmerald,
                )
            }
            Text(
                text = "Reclaimed ${formatBytes(context, reclaimedBytes)} across $deletedCount items. " +
                    "Explore deep-clean tools below for more savings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onDone,
                modifier = Modifier
                    .heightIn(min = Tokens.TouchTarget)
                    .testTag("cleaner-celebration-done"),
            ) {
                Text("Back to scanner")
            }
        }
    }
}

@Composable
private fun CleanerCategoryAccordion(
    group: CleanerCategoryGroup,
    selectedItemIds: Set<String>,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleGroupSelection: () -> Unit,
    onToggleItemSelection: (String) -> Unit,
) {
    val context = LocalContext.current
    val isSafe = group.category.safetyTier == CleanerSafetyTier.SAFE_UNNEEDED
    val railColor = if (isSafe) GlassPalette.HudEmerald else GlassPalette.HudAmber
    val adviceText = if (isSafe) "Advice: clean" else "Clean up carefully"
    val selectedInGroup = group.items.count { it.id in selectedItemIds }
    val allSelected = group.items.isNotEmpty() && selectedInGroup == group.items.size
    val stackHeader = LocalDensity.current.fontScale >= Tokens.STACK_AT_FONT_SCALE ||
        LocalConfiguration.current.screenWidthDp < Tokens.Cleaner.CompactStackWidthDp

    GlassPanel(
        shape = Tokens.Cleaner.AccordionShape,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cleaner-accordion-${group.category.name.lowercase()}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            Box(
                modifier = Modifier
                    .width(Tokens.Cleaner.SafetyRailWidth)
                    .fillMaxHeight()
                    .background(railColor),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Tokens.TouchTarget)
                        .clickable(
                            onClickLabel = if (expanded) "Collapse category" else "Expand category",
                            onClick = onToggleExpand,
                        )
                        .padding(
                            start = Tokens.Small,
                            end = Tokens.Medium,
                            top = Tokens.Row,
                            bottom = Tokens.Row,
                        )
                        .testTag("cleaner-accordion-header-${group.category.name.lowercase()}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = allSelected,
                        onCheckedChange = { onToggleGroupSelection() },
                        modifier = Modifier.testTag("cleaner-accordion-check-${group.category.name.lowercase()}"),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = Tokens.Small),
                        verticalArrangement = Arrangement.spacedBy(Tokens.Tiny),
                    ) {
                        Text(
                            text = categoryDisplayName(group.category),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (stackHeader) {
                            Text(
                                text = adviceText,
                                style = MaterialTheme.typography.labelMedium,
                                color = railColor,
                            )
                            Text(
                                text = "${group.items.size} items, ${formatBytes(context, group.totalBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                            ) {
                                Text(
                                    text = adviceText,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = railColor,
                                )
                                Text(
                                    text = "${group.items.size} items",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (!stackHeader) {
                        Text(
                            text = formatBytes(context, group.totalBytes),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(end = Tokens.Small),
                        )
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(AppMotion.spec(Tokens.Cleaner.ACCORDION_EXPAND_MS)) +
                        fadeIn(AppMotion.spec(Tokens.Cleaner.ACCORDION_EXPAND_MS)),
                    exit = shrinkVertically(AppMotion.spec(Tokens.Cleaner.ACCORDION_EXPAND_MS)) +
                        fadeOut(AppMotion.spec(Tokens.Cleaner.ACCORDION_EXPAND_MS)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = Tokens.Medium,
                                end = Tokens.Medium,
                                bottom = Tokens.Medium,
                            ),
                        verticalArrangement = Arrangement.spacedBy(Tokens.Small),
                    ) {
                        for (item in group.items) {
                            CleanerItemRow(
                                item = item,
                                checked = item.id in selectedItemIds,
                                onToggle = { onToggleItemSelection(item.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CleanerItemRow(
    item: CleanerItem,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.TouchTarget)
            .clip(Tokens.PanelShape)
            .background(GlassPalette.Well)
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            .padding(horizontal = Tokens.Row, vertical = Tokens.Small)
            .testTag("cleaner-item-${item.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Tokens.Small),
            verticalArrangement = Arrangement.spacedBy(Tokens.Tiny),
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.packageName ?: item.path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = formatBytes(context, item.sizeBytes),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeepCleanCardsSection(
    cards: List<DeepCleanCardSummary>,
    onOpenDeepClean: (DeepCleanDestination) -> Unit,
) {
    val context = LocalContext.current
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cleaner-deep-clean-cards"),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Medium),
        verticalArrangement = Arrangement.spacedBy(Tokens.Medium),
    ) {
        for (card in cards) {
            val icon = deepCleanIcon(card.destination)
            val accent = deepCleanAccent(card.destination)
            GlassPanel(
                shape = Tokens.Cleaner.DeepCleanCardShape,
                onClick = { onOpenDeepClean(card.destination) },
                onClickLabel = "Open ${card.title}",
                modifier = Modifier
                    .widthIn(min = Tokens.Cleaner.DeepCleanCardMinWidth)
                    .weight(1f)
                    .heightIn(min = Tokens.TouchTarget)
                    .testTag("deep-clean-card-${card.destination.name.lowercase()}"),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Tokens.Gutter),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Small),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(Tokens.Icon),
                    )
                    Text(
                        text = card.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (card.bytes > 0L) {
                            formatBytes(context, card.bytes)
                        } else {
                            card.subtitle
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun CleanerBottomActionBar(
    selectedCount: Int,
    selectedBytes: Long,
    onCleanClick: () -> Unit,
) {
    val context = LocalContext.current
    val compact = LocalDensity.current.fontScale >= Tokens.STACK_AT_FONT_SCALE ||
        LocalConfiguration.current.screenWidthDp < Tokens.Cleaner.CompactStackWidthDp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .glassBar()
            .padding(horizontal = Tokens.Edge, vertical = Tokens.Medium),
    ) {
        if (compact) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Tokens.Small),
            ) {
                Text(
                    text = if (selectedCount > 0) {
                        "$selectedCount selected (${formatBytes(context, selectedBytes)})"
                    } else {
                        "Select items to clean"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onCleanClick,
                    enabled = selectedCount > 0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Tokens.TouchTarget)
                        .testTag("cleaner-execute-button"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CleaningServices,
                        contentDescription = null,
                        modifier = Modifier.size(Tokens.IconSmall),
                    )
                    Spacer(modifier = Modifier.width(Tokens.Small))
                    Text("Clean ${formatBytes(context, selectedBytes)}")
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Tokens.Tiny)) {
                    Text(
                        text = formatBytes(context, selectedBytes),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "$selectedCount items selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = onCleanClick,
                    enabled = selectedCount > 0,
                    modifier = Modifier
                        .heightIn(min = Tokens.TouchTarget)
                        .testTag("cleaner-execute-button"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CleaningServices,
                        contentDescription = null,
                        modifier = Modifier.size(Tokens.IconSmall),
                    )
                    Spacer(modifier = Modifier.width(Tokens.Small))
                    Text("Clean now")
                }
            }
        }
    }
}

private fun deepCleanIcon(destination: DeepCleanDestination): ImageVector = when (destination) {
    DeepCleanDestination.WHATSAPP -> Icons.Outlined.Forum
    DeepCleanDestination.LARGE_FILES -> Icons.Outlined.InsertDriveFile
    DeepCleanDestination.DUPLICATES -> Icons.Outlined.ContentCopy
    DeepCleanDestination.SLEEP_MODE -> Icons.Outlined.Bedtime
    DeepCleanDestination.RARELY_USED -> Icons.Outlined.History
}

private fun deepCleanAccent(destination: DeepCleanDestination): Color = when (destination) {
    DeepCleanDestination.WHATSAPP -> GlassPalette.HudEmerald
    DeepCleanDestination.LARGE_FILES -> GlassPalette.HudAmber
    DeepCleanDestination.DUPLICATES -> GlassPalette.HudElectricCyan
    DeepCleanDestination.SLEEP_MODE -> GlassPalette.HudVioletBlue
    DeepCleanDestination.RARELY_USED -> GlassPalette.SecondaryViolet
}

fun categoryDisplayName(category: CleanerCategory): String = when (category) {
    CleanerCategory.HIDDEN_CACHE -> "Hidden app caches"
    CleanerCategory.VISIBLE_CACHE -> "Visible app caches"
    CleanerCategory.THUMBNAILS -> "Thumbnails"
    CleanerCategory.TEMP_AND_RESIDUAL -> "Temporary and residual files"
    CleanerCategory.RECYCLE_BIN -> "Recycle bin"
    CleanerCategory.EMPTY_FOLDERS -> "Empty folders"
    CleanerCategory.STALE_APK -> "Stale APK installers"
    CleanerCategory.COMPRESSED_ARCHIVE -> "Compressed archives"
    CleanerCategory.LARGE_FILE -> "Large files"
    CleanerCategory.WHATSAPP_BLOAT -> "WhatsApp media"
    CleanerCategory.DUPLICATE_FILES -> "Duplicate files"
    CleanerCategory.RARELY_USED_FILES -> "Rarely used files"
    CleanerCategory.RARELY_USED_APPS -> "Rarely used apps"
}

fun defaultDeepCleanCards(): List<DeepCleanCardSummary> = listOf(
    DeepCleanCardSummary(DeepCleanDestination.WHATSAPP, "WhatsApp", "Media and voice notes", 0L, 0),
    DeepCleanCardSummary(DeepCleanDestination.LARGE_FILES, "Large Files", "Over 100 MB", 0L, 0),
    DeepCleanCardSummary(DeepCleanDestination.DUPLICATES, "Duplicates", "SHA-256 verified", 0L, 0),
    DeepCleanCardSummary(DeepCleanDestination.SLEEP_MODE, "Sleep Mode", "Hibernate drainers", 0L, 0),
    DeepCleanCardSummary(DeepCleanDestination.RARELY_USED, "Rarely Used", "Unused apps and files", 0L, 0),
)

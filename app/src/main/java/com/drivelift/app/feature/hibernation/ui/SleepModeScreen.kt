package com.drivelift.app.feature.hibernation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Shield
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivelift.app.Graph
import com.drivelift.app.feature.automode.AdbSilentExecutor
import com.drivelift.app.feature.automode.AutoModeAvailability
import com.drivelift.app.feature.automode.AutoModeStatusSnapshot
import com.drivelift.app.feature.cleaner.cache.GuidedPillState
import com.drivelift.app.feature.cleaner.ui.AutoModeProgressPill
import com.drivelift.app.feature.cleaner.ui.GuidedDynamicIslandPill
import com.drivelift.app.feature.hibernation.engine.AppDrainCandidate
import com.drivelift.app.feature.hibernation.engine.HibernationMode
import com.drivelift.app.feature.hibernation.engine.ProtectedAppRule
import com.drivelift.app.feature.hibernation.engine.ProtectionReason
import com.drivelift.app.feature.hibernation.engine.SleepExecutionReport
import com.drivelift.app.ui.GlassPalette
import com.drivelift.app.ui.GlassPanel
import com.drivelift.app.ui.Tokens
import com.drivelift.app.ui.glassBar

/**
 * Immutable UI state for [SleepModeContent].
 */
data class SleepModeUiState(
    val isLoading: Boolean = false,
    val isSleepingBatch: Boolean = false,
    val needsUsageAccess: Boolean = false,
    val errorMessage: String? = null,
    val candidates: List<AppDrainCandidate> = emptyList(),
    val selectedPackages: Set<String> = emptySet(),
    val protectedRules: List<ProtectedAppRule> = defaultProtectedRules(),
    val autoModeStatus: AutoModeStatusSnapshot = AutoModeStatusSnapshot(
        availability = AutoModeAvailability.SETUP_REQUIRED,
        effectiveMode = HibernationMode.GUIDED_PILL,
        detail = "Turn on built-in Auto Mode once to sleep apps without prompts.",
        isConfigured = false,
    ),
    val autoProgressMessage: String? = null,
    val guidedPill: GuidedPillState? = null,
    val lastReport: SleepExecutionReport? = null,
) {
    val selectedCandidates: List<AppDrainCandidate>
        get() = candidates.filter { it.packageName in selectedPackages }
}

/**
 * Stateful Sleep Mode screen wired to [Graph.cleanerAndSleep].
 */
@Composable
fun SleepModeScreen(
    onBack: () -> Unit,
    onOpenAutoMode: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val module = remember(context) { Graph.get(context).cleanerAndSleep }
    val state by module.sleepState.collectAsStateWithLifecycle()

    LaunchedEffect(module) {
        module.refreshSleepCandidates()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        module.refreshAutoModeStatus()
    }

    SleepModeContent(
        state = state,
        onBack = onBack,
        onRefresh = { module.refreshSleepCandidates() },
        onGrantUsageAccess = {
            runCatching {
                context.startActivity(module.statsSource.createUsageAccessSettingsIntent())
            }
        },
        onToggleSelectCandidate = { module.toggleSleepCandidateSelection(it) },
        onProtectCandidate = { module.protectApp(it) },
        onRemoveProtectedRule = { module.unprotectApp(it) },
        onStartSleep = { module.startSleepForSelected() },
        onDismissReport = { module.dismissSleepReport() },
        onOpenGuidedAppInfo = { pkg ->
            runCatching {
                context.startActivity(AdbSilentExecutor.createAppInfoIntent(pkg))
            }
        },
        onConfirmGuidedStep = { module.confirmGuidedSleepStep() },
        onNextGuidedApp = {
            val nextPkg = module.advanceGuidedSleepToNextApp()
            if (nextPkg != null) {
                runCatching {
                    context.startActivity(AdbSilentExecutor.createAppInfoIntent(nextPkg))
                }
            }
        },
        onSkipGuidedStep = { module.skipGuidedSleepStep() },
        onCancelGuidedSession = { module.cancelGuidedSleepSession() },
        onAutoModeAction = onOpenAutoMode,
        modifier = modifier,
    )
}

/**
 * Stateless Sleep Mode UI featuring ranked background drainers, Protected whitelist management,
 * 1-tap Sleep CTA, and Android 16/17 Guided Sleep Dynamic Island pill fallback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepModeContent(
    state: SleepModeUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onGrantUsageAccess: () -> Unit,
    onToggleSelectCandidate: (String) -> Unit,
    onProtectCandidate: (AppDrainCandidate) -> Unit,
    onRemoveProtectedRule: (String) -> Unit,
    onStartSleep: () -> Unit,
    onDismissReport: () -> Unit,
    onOpenGuidedAppInfo: (String) -> Unit,
    onConfirmGuidedStep: () -> Unit,
    onSkipGuidedStep: () -> Unit,
    onCancelGuidedSession: () -> Unit,
    modifier: Modifier = Modifier,
    onAutoModeAction: () -> Unit = {},
    onNextGuidedApp: () -> Unit = onConfirmGuidedStep,
) {
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(state.lastReport?.sessionId) {
        if (state.lastReport != null) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("sleep-mode-screen"),
    ) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
            ),
            title = { Text("Sleep Mode") },
            navigationIcon = {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("sleep-back-button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            },
            actions = {
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.testTag("sleep-refresh-button"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = "Refresh drainers",
                    )
                }
            },
        )

        if (state.autoProgressMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Tokens.Edge, vertical = Tokens.Small),
            ) {
                AutoModeProgressPill(message = state.autoProgressMessage)
            }
        }

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
                .testTag("sleep-mode-list"),
            contentPadding = PaddingValues(
                start = Tokens.Edge,
                end = Tokens.Edge,
                top = Tokens.Small,
                bottom = Tokens.Section,
            ),
            verticalArrangement = Arrangement.spacedBy(Tokens.Medium),
        ) {
            item(key = "mode-card") {
                SleepModeStatusCard(
                    autoModeStatus = state.autoModeStatus,
                    candidateCount = state.candidates.size,
                    onAutoModeAction = onAutoModeAction,
                )
            }

            if (state.isLoading || state.isSleepingBatch) {
                item(key = "loading") {
                    GlassPanel(
                        shape = Tokens.Cleaner.CardShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sleep-loading-panel"),
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
                            Text(
                                text = if (state.isSleepingBatch) {
                                    "Putting selected apps to sleep"
                                } else {
                                    "Measuring background drainers"
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            } else {
                if (state.needsUsageAccess || state.errorMessage != null) {
                    item(key = "error") {
                        SleepPermissionErrorPanel(
                            needsUsageAccess = state.needsUsageAccess,
                            errorMessage = state.errorMessage,
                            onGrantUsageAccess = onGrantUsageAccess,
                            onRetry = onRefresh,
                        )
                    }
                }

                if (state.lastReport != null) {
                    item(key = "report") {
                        SleepReportPanel(
                            report = state.lastReport,
                            onDismiss = onDismissReport,
                        )
                    }
                }

                item(key = "drainers-header") {
                    Text(
                        text = "Background drainers (${state.candidates.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = Tokens.Small),
                    )
                }

                if (state.candidates.isEmpty()) {
                    item(key = "empty-drainers") {
                        GlassPanel(
                            shape = Tokens.Cleaner.CardShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("sleep-empty-panel"),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(Tokens.Edge),
                                verticalArrangement = Arrangement.spacedBy(Tokens.Small),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.CheckCircle,
                                    contentDescription = null,
                                    tint = GlassPalette.HudEmerald,
                                    modifier = Modifier.size(Tokens.Icon),
                                )
                                Text(
                                    text = "No background drainers found",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = "Eligible third-party apps are quiet right now.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    items(
                        items = state.candidates,
                        key = { it.packageName },
                    ) { candidate ->
                        DrainerCandidateRow(
                            candidate = candidate,
                            selected = candidate.packageName in state.selectedPackages,
                            onToggleSelect = { onToggleSelectCandidate(candidate.packageName) },
                            onProtect = { onProtectCandidate(candidate) },
                        )
                    }
                }

                item(key = "protected-header") {
                    Text(
                        text = "Protected whitelist (${state.protectedRules.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = Tokens.Small),
                    )
                }

                items(
                    items = state.protectedRules,
                    key = { "protected:${it.packageName}" },
                ) { rule ->
                    ProtectedWhitelistRow(
                        rule = rule,
                        onRemove = { onRemoveProtectedRule(rule.packageName) },
                    )
                }
            }
        }

        if (!state.isLoading && state.candidates.isNotEmpty() && state.guidedPill == null) {
            SleepBottomBar(
                selectedCount = state.selectedCandidates.size,
                mode = state.autoModeStatus.effectiveMode,
                isSleepingBatch = state.isSleepingBatch,
                onStartSleep = onStartSleep,
            )
        }
    }
}

@Composable
private fun SleepModeStatusCard(
    autoModeStatus: AutoModeStatusSnapshot,
    candidateCount: Int,
    onAutoModeAction: () -> Unit,
) {
    val isSilent = autoModeStatus.effectiveMode == HibernationMode.AUTO_SILENT
    val accent = when (autoModeStatus.availability) {
        AutoModeAvailability.READY -> GlassPalette.HudEmerald
        AutoModeAvailability.NEEDS_WIFI -> GlassPalette.HudAmber
        AutoModeAvailability.BOOTSTRAP_INCOMPLETE -> MaterialTheme.colorScheme.primary
        AutoModeAvailability.SETUP_REQUIRED -> GlassPalette.HudElectricCyan
    }
    val headline = when (autoModeStatus.availability) {
        AutoModeAvailability.READY -> "Auto Mode is on"
        AutoModeAvailability.NEEDS_WIFI -> "Auto Mode needs Wi-Fi"
        AutoModeAvailability.BOOTSTRAP_INCOMPLETE -> "Finish Auto Mode setup"
        AutoModeAvailability.SETUP_REQUIRED -> "Guided Sleep pill ready"
    }
    val actionLabel = when (autoModeStatus.availability) {
        AutoModeAvailability.READY,
        AutoModeAvailability.NEEDS_WIFI,
        -> "Manage Auto Mode"
        AutoModeAvailability.BOOTSTRAP_INCOMPLETE -> "Finish Auto Mode setup"
        AutoModeAvailability.SETUP_REQUIRED -> "Turn on Auto Mode"
    }

    GlassPanel(
        shape = Tokens.Cleaner.CardShape,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sleep-mode-status-card"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Tokens.Edge),
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
                    modifier = Modifier.size(Tokens.Icon),
                )
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleMedium,
                    color = accent,
                )
            }
            Text(
                text = autoModeStatus.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "$candidateCount apps ranked by background time, standby bucket, and recency.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isSilent) {
                OutlinedButton(
                    onClick = onAutoModeAction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Tokens.TouchTarget)
                        .testTag("automode-banner-action"),
                ) {
                    Text(actionLabel)
                }
            } else {
                Button(
                    onClick = onAutoModeAction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Tokens.TouchTarget)
                        .testTag("automode-banner-action"),
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun SleepPermissionErrorPanel(
    needsUsageAccess: Boolean,
    errorMessage: String?,
    onGrantUsageAccess: () -> Unit,
    onRetry: () -> Unit,
) {
    GlassPanel(
        shape = Tokens.Cleaner.CardShape,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sleep-error-panel"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Tokens.Gutter),
            verticalArrangement = Arrangement.spacedBy(Tokens.Medium),
        ) {
            Text(
                text = if (needsUsageAccess) "Usage access required" else "Unable to read app activity",
                style = MaterialTheme.typography.titleMedium,
                color = GlassPalette.HudAmber,
            )
            Text(
                text = errorMessage
                    ?: "Grant Usage Access so Sleep Mode can rank background drainers.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                if (needsUsageAccess) {
                    Button(
                        onClick = onGrantUsageAccess,
                        modifier = Modifier
                            .heightIn(min = Tokens.TouchTarget)
                            .testTag("sleep-grant-usage-button"),
                    ) {
                        Text("Grant Usage Access")
                    }
                }
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier
                        .heightIn(min = Tokens.TouchTarget)
                        .testTag("sleep-retry-button"),
                ) {
                    Text("Retry")
                }
            }
        }
    }
}

@Composable
private fun SleepReportPanel(
    report: SleepExecutionReport,
    onDismiss: () -> Unit,
) {
    GlassPanel(
        shape = Tokens.Cleaner.CardShape,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sleep-report-panel"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Tokens.Edge),
            verticalArrangement = Arrangement.spacedBy(Tokens.Small),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
            ) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = GlassPalette.HudEmerald,
                    modifier = Modifier.size(Tokens.Icon),
                )
                Text(
                    text = "Sleep session finished",
                    style = MaterialTheme.typography.titleMedium,
                    color = GlassPalette.HudEmerald,
                )
            }
            Text(
                text = "Slept ${report.sleptPackages.size}, skipped ${report.skippedPackages.size}, " +
                    "failed ${report.failedPackages.size}, cancelled ${report.cancelledPackages.size}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .heightIn(min = Tokens.TouchTarget)
                    .testTag("sleep-report-dismiss"),
            ) {
                Text("Dismiss")
            }
        }
    }
}

@Composable
private fun DrainerCandidateRow(
    candidate: AppDrainCandidate,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onProtect: () -> Unit,
) {
    val compact = LocalDensity.current.fontScale >= Tokens.STACK_AT_FONT_SCALE ||
        LocalConfiguration.current.screenWidthDp < Tokens.Cleaner.CompactStackWidthDp
    val scoreColor = when {
        candidate.drainScore >= 70 -> GlassPalette.HudCoral
        candidate.drainScore >= 40 -> GlassPalette.HudAmber
        else -> GlassPalette.HudElectricCyan
    }

    GlassPanel(
        shape = Tokens.Cleaner.CardShape,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sleep-drainer-${candidate.packageName}"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Tokens.Medium),
            verticalArrangement = Arrangement.spacedBy(Tokens.Small),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Tokens.TouchTarget)
                    .toggleable(
                        value = selected,
                        role = Role.Checkbox,
                        onValueChange = { onToggleSelect() },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = null,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = Tokens.Small),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Tiny),
                ) {
                    Text(
                        text = candidate.appLabel,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = candidate.reasons.joinToString(" / ").ifBlank { candidate.packageName },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    modifier = Modifier
                        .widthIn(min = Tokens.Cleaner.ScoreBadgeMinWidth)
                        .clip(Tokens.Cleaner.BadgeShape)
                        .background(GlassPalette.Well)
                        .border(Tokens.Hairline, scoreColor.copy(alpha = 0.6f), Tokens.Cleaner.BadgeShape)
                        .padding(horizontal = Tokens.Row, vertical = Tokens.Tiny),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${candidate.drainScore}",
                        style = MaterialTheme.typography.labelLarge,
                        color = scoreColor,
                    )
                }
            }

            if (compact) {
                OutlinedButton(
                    onClick = onProtect,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Tokens.TouchTarget)
                        .testTag("sleep-protect-${candidate.packageName}"),
                ) {
                    Text("Protect")
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = candidate.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onProtect,
                        modifier = Modifier
                            .heightIn(min = Tokens.TouchTarget)
                            .testTag("sleep-protect-${candidate.packageName}"),
                    ) {
                        Text("Protect")
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtectedWhitelistRow(
    rule: ProtectedAppRule,
    onRemove: () -> Unit,
) {
    val compact = LocalDensity.current.fontScale >= Tokens.STACK_AT_FONT_SCALE ||
        LocalConfiguration.current.screenWidthDp < Tokens.Cleaner.CompactStackWidthDp

    GlassPanel(
        shape = Tokens.Cleaner.CardShape,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sleep-protected-${rule.packageName}"),
    ) {
        val reasonText = when (rule.reason) {
            ProtectionReason.SELF_APP -> "Always protected (DriveLift)"
            ProtectionReason.DIALER -> "Always protected (Phone)"
            ProtectionReason.CLOCK_OR_ALARM -> "Always protected (Clock and alarms)"
            ProtectionReason.SYSTEM_APP -> "Always protected (System)"
            ProtectionReason.USER_WHITELIST -> "Protected by you"
        }

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
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = GlassPalette.HudEmerald,
                        modifier = Modifier.size(Tokens.IconSmall),
                    )
                    Text(
                        text = rule.label.ifBlank { rule.packageName },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Text(
                    text = reasonText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!rule.immutable) {
                    OutlinedButton(
                        onClick = onRemove,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Tokens.TouchTarget)
                            .testTag("sleep-unprotect-${rule.packageName}"),
                    ) {
                        Text("Remove protection")
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Tokens.TouchTarget)
                    .padding(horizontal = Tokens.Gutter, vertical = Tokens.Row),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Medium),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = GlassPalette.HudEmerald,
                        modifier = Modifier.size(Tokens.IconSmall),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Tiny)) {
                        Text(
                            text = rule.label.ifBlank { rule.packageName },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = reasonText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!rule.immutable) {
                    TextButton(
                        onClick = onRemove,
                        modifier = Modifier
                            .heightIn(min = Tokens.TouchTarget)
                            .testTag("sleep-unprotect-${rule.packageName}"),
                    ) {
                        Text("Remove")
                    }
                }
            }
        }
    }
}

@Composable
private fun SleepBottomBar(
    selectedCount: Int,
    mode: HibernationMode,
    isSleepingBatch: Boolean,
    onStartSleep: () -> Unit,
) {
    val label = when {
        isSleepingBatch -> "Sleeping apps"
        mode == HibernationMode.AUTO_SILENT -> "Sleep $selectedCount apps"
        else -> "Start Guided Sleep ($selectedCount)"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .glassBar()
            .padding(horizontal = Tokens.Edge, vertical = Tokens.Medium),
    ) {
        Button(
            onClick = onStartSleep,
            enabled = selectedCount > 0 && !isSleepingBatch,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Tokens.TouchTarget)
                .testTag("sleep-one-tap-button"),
        ) {
            Icon(
                imageVector = Icons.Outlined.Bedtime,
                contentDescription = null,
                modifier = Modifier.size(Tokens.IconSmall),
            )
            Spacer(modifier = Modifier.width(Tokens.Small))
            Text(label)
        }
    }
}

fun defaultProtectedRules(): List<ProtectedAppRule> = listOf(
    ProtectedAppRule(
        packageName = "com.drivelift.app",
        reason = ProtectionReason.SELF_APP,
        label = "DriveLift",
        immutable = true,
    ),
    ProtectedAppRule(
        packageName = "com.google.android.dialer",
        reason = ProtectionReason.DIALER,
        label = "Phone",
        immutable = true,
    ),
    ProtectedAppRule(
        packageName = "com.google.android.deskclock",
        reason = ProtectionReason.CLOCK_OR_ALARM,
        label = "Clock",
        immutable = true,
    ),
)

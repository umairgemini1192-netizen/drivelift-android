package com.drivelift.app.feature.applock.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivelift.app.Graph
import com.drivelift.app.feature.applock.AppLockManagerUiState
import com.drivelift.app.feature.applock.AppLockMonitorMode
import com.drivelift.app.feature.applock.LockableAppEntry
import com.drivelift.app.feature.applock.data.AppLockPreferencesRepository
import com.drivelift.app.feature.applock.engine.ReLockGracePeriod
import com.drivelift.app.ui.GlassPalette
import com.drivelift.app.ui.GlassPanel
import com.drivelift.app.ui.Tokens

/**
 * Stateful App Lock Manager screen wired to [Graph.appLock].
 */
@Composable
fun AppLockManagerScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val appLock = remember(context) { Graph.get(context).appLock }
    val state by appLock.uiState.collectAsStateWithLifecycle()
    var showPinDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        appLock.refreshInstalledApps()
        appLock.syncMonitoringMode()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        appLock.syncMonitoringMode()
    }

    AppLockManagerContent(
        state = state,
        onBack = onBack,
        onToggleEnabled = { desired ->
            if (desired && !state.hasPinConfigured) {
                showPinDialog = true
            } else {
                appLock.setEnabled(desired)
            }
        },
        onOpenPinSetup = { showPinDialog = true },
        onResumeFromPause = { appLock.resumeFromPause() },
        onToggleTamperProtection = { appLock.setTamperProtectionEnabled(!state.tamperProtection.isSettingsLocked) },
        onSelectGracePeriod = { appLock.setGracePeriod(it) },
        onToggleBiometric = { appLock.setAllowBiometric(it) },
        onSearchQueryChange = { appLock.setSearchQuery(it) },
        onToggleAppLocked = { appLock.toggleAppLocked(it) },
        onRetryLoadApps = { appLock.refreshInstalledApps() },
        onOpenAccessibilitySettings = {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        },
        onOpenUsageAccessSettings = {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        },
    )

    if (showPinDialog) {
        SetAppLockPinDialog(
            hasExistingPin = state.hasPinConfigured,
            onDismiss = { showPinDialog = false },
            onConfirmPin = { newPin ->
                appLock.savePin(newPin)
                if (!state.config.enabled) {
                    appLock.setEnabled(true)
                }
                showPinDialog = false
            },
        )
    }
}

/**
 * Stateless App Lock Manager content suitable for production rendering and Roborazzi tests.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppLockManagerContent(
    state: AppLockManagerUiState,
    onBack: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onOpenPinSetup: () -> Unit,
    onResumeFromPause: () -> Unit,
    onToggleTamperProtection: () -> Unit,
    onSelectGracePeriod: (ReLockGracePeriod) -> Unit,
    onToggleBiometric: (Boolean) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggleAppLocked: (String) -> Unit,
    onRetryLoadApps: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenUsageAccessSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val stackRows = density.fontScale >= Tokens.STACK_AT_FONT_SCALE ||
        configuration.screenWidthDp < Tokens.COMPACT_WIDTH_DP
    val filteredApps = state.filteredApps

    Column(modifier = modifier.fillMaxSize().testTag("applock-manager-screen")) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
            ),
            title = { Text("App Lock") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back to settings",
                    )
                }
            },
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Tokens.Edge)
                .widthIn(max = Tokens.ReadableWidth),
            verticalArrangement = Arrangement.spacedBy(Tokens.Medium),
        ) {
            // 1. Master status + RAM-only pause banner + PIN setup
            item(key = "master") {
                MasterStatusPanel(
                    state = state,
                    stackRows = stackRows,
                    onToggleEnabled = onToggleEnabled,
                    onOpenPinSetup = onOpenPinSetup,
                    onResumeFromPause = onResumeFromPause,
                )
            }

            // 2. Monitoring engine card (Accessibility vs UsageStats fallback)
            item(key = "monitor") {
                MonitoringEngineCard(
                    mode = state.monitorMode,
                    stackRows = stackRows,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    onOpenUsageAccessSettings = onOpenUsageAccessSettings,
                )
            }

            // 3. One-tap Protect Settings (com.android.settings) card
            item(key = "tamper") {
                ProtectSettingsCard(
                    isProtected = state.tamperProtection.isSettingsLocked,
                    isRecommended = state.tamperProtection.isRecommended,
                    reason = state.tamperProtection.reason,
                    stackRows = stackRows,
                    onToggle = onToggleTamperProtection,
                )
            }

            // 4. Grace-period selector + Biometric Class 3 toggle
            item(key = "policy") {
                PolicyOptionsPanel(
                    gracePeriod = state.config.gracePeriod,
                    allowBiometric = state.config.allowBiometric,
                    onSelectGracePeriod = onSelectGracePeriod,
                    onToggleBiometric = onToggleBiometric,
                )
            }

            // 5. Searchable installed apps section header + search input
            item(key = "apps-header") {
                Column(verticalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = Tokens.Small),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Protected apps",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "${state.lockedCount} locked",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("applock-locked-count"),
                        )
                    }

                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = onSearchQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("applock-search-input"),
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = null,
                            )
                        },
                        placeholder = { Text("Search installed apps") },
                    )
                }
            }

            // 6. Loading / error / empty / populated app rows
            when {
                state.isLoading -> {
                    item(key = "loading") {
                        GlassPanel(modifier = Modifier.fillMaxWidth().testTag("applock-loading")) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(Tokens.Gutter),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Tokens.Medium),
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(Tokens.Icon))
                                Text(
                                    text = "Loading installed apps...",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
                !state.errorMessage.isNullOrBlank() -> {
                    item(key = "error") {
                        GlassPanel(modifier = Modifier.fillMaxWidth().testTag("applock-error-banner")) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(Tokens.Gutter),
                                verticalArrangement = Arrangement.spacedBy(Tokens.Small),
                            ) {
                                Text(
                                    text = state.errorMessage,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                OutlinedButton(
                                    onClick = onRetryLoadApps,
                                    modifier = Modifier
                                        .heightIn(min = Tokens.TouchTarget)
                                        .testTag("applock-retry-button"),
                                ) {
                                    Text("Retry")
                                }
                            }
                        }
                    }
                }
                filteredApps.isEmpty() -> {
                    item(key = "empty") {
                        GlassPanel(modifier = Modifier.fillMaxWidth().testTag("applock-empty-state")) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(Tokens.Gutter),
                                verticalArrangement = Arrangement.spacedBy(Tokens.Tiny),
                            ) {
                                Text(
                                    text = if (state.searchQuery.isBlank()) {
                                        "No launchable apps found"
                                    } else {
                                        "No apps match \"${state.searchQuery.trim()}\""
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = if (state.searchQuery.isBlank()) {
                                        "Installed launchable apps appear here so you can toggle App Lock per app."
                                    } else {
                                        "Try searching by another app name or package identifier."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                else -> {
                    items(
                        items = filteredApps,
                        key = { it.packageName },
                    ) { entry ->
                        InstalledAppToggleRow(
                            entry = entry,
                            onToggle = { onToggleAppLocked(entry.packageName) },
                        )
                    }
                }
            }

            item(key = "bottom-spacer") {
                Spacer(Modifier.height(Tokens.Section))
            }
        }
    }
}

@Composable
private fun MasterStatusPanel(
    state: AppLockManagerUiState,
    stackRows: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onOpenPinSetup: () -> Unit,
    onResumeFromPause: () -> Unit,
) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth().testTag("applock-master-card"),
        shape = AppLockTokens.StatusCardShape,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Tokens.Gutter),
            verticalArrangement = Arrangement.spacedBy(Tokens.Medium),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Tokens.TouchTarget)
                    .toggleable(
                        value = state.config.enabled,
                        role = Role.Switch,
                        onValueChange = onToggleEnabled,
                    )
                    .testTag("applock-master-toggle"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = Tokens.Medium)) {
                    Text(
                        text = "Protect locked apps",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = when {
                            !state.config.enabled -> "Off. Locked apps open without prompting."
                            state.isPaused -> "Paused in RAM only. Resumes automatically after timer or restart."
                            else -> "Active. Locked apps require Class 3 biometric or PIN."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.config.enabled, onCheckedChange = null)
            }

            if (state.isPaused) {
                val minsLeft = ((state.pauseRemainingMs + 59_999L) / 60_000L).coerceAtLeast(1L)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(Tokens.PanelShape)
                        .background(GlassPalette.Well)
                        .border(Tokens.Hairline, MaterialTheme.colorScheme.tertiary, Tokens.PanelShape)
                        .padding(Tokens.Medium)
                        .testTag("applock-paused-banner"),
                ) {
                    if (stackRows) {
                        Column(verticalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                            PausedSummaryText(minsLeft)
                            Button(
                                onClick = onResumeFromPause,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = Tokens.TouchTarget)
                                    .testTag("applock-resume-now"),
                            ) {
                                Text("Resume now")
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Box(Modifier.weight(1f).padding(end = Tokens.Small)) {
                                PausedSummaryText(minsLeft)
                            }
                            Button(
                                onClick = onResumeFromPause,
                                modifier = Modifier
                                    .heightIn(min = Tokens.TouchTarget)
                                    .testTag("applock-resume-now"),
                            ) {
                                Text("Resume")
                            }
                        }
                    }
                }
            }

            if (stackRows) {
                Column(verticalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                    PinSummaryText(hasPin = state.hasPinConfigured, pinLength = state.pinLength)
                    OutlinedButton(
                        onClick = onOpenPinSetup,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Tokens.TouchTarget)
                            .testTag("applock-pin-button"),
                    ) {
                        Text(if (state.hasPinConfigured) "Change PIN" else "Set backup PIN")
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Box(Modifier.weight(1f).padding(end = Tokens.Small)) {
                        PinSummaryText(hasPin = state.hasPinConfigured, pinLength = state.pinLength)
                    }
                    OutlinedButton(
                        onClick = onOpenPinSetup,
                        modifier = Modifier
                            .heightIn(min = Tokens.TouchTarget)
                            .testTag("applock-pin-button"),
                    ) {
                        Text(if (state.hasPinConfigured) "Change PIN" else "Set PIN")
                    }
                }
            }
        }
    }
}

@Composable
private fun PausedSummaryText(minsLeft: Long) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
    ) {
        Icon(
            imageVector = Icons.Outlined.PauseCircleOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(Tokens.IconSmall),
        )
        Column {
            Text(
                text = "App Lock paused ($minsLeft min left)",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = "RAM-only pause from Quick Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PinSummaryText(hasPin: Boolean, pinLength: Int) {
    Column {
        Text(
            text = if (hasPin) "Backup PIN configured ($pinLength digits)" else "Backup PIN required",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "Stored only as a salted PBKDF2-HMAC-SHA256 hash.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MonitoringEngineCard(
    mode: AppLockMonitorMode,
    stackRows: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenUsageAccessSettings: () -> Unit,
) {
    val statusLabel = when (mode) {
        AppLockMonitorMode.ACCESSIBILITY_ACTIVE -> "Accessibility monitor connected"
        AppLockMonitorMode.USAGE_STATS_FALLBACK -> "Usage Access fallback active"
        AppLockMonitorMode.NEEDS_PERMISSION -> "Foreground detection needs setup"
    }
    val statusDetail = when (mode) {
        AppLockMonitorMode.ACCESSIBILITY_ACTIVE ->
            "Instant lock via Cockpit AccessibilityService. Reads package transitions only, zero window content."
        AppLockMonitorMode.USAGE_STATS_FALLBACK ->
            "Accessibility is off. Polling foreground transitions via UsageStatsManager."
        AppLockMonitorMode.NEEDS_PERMISSION ->
            "Enable DriveLift Accessibility Service for instant locking, or grant Usage Access as fallback."
    }
    val dotColor = when (mode) {
        AppLockMonitorMode.ACCESSIBILITY_ACTIVE -> GlassPalette.HudEmerald
        AppLockMonitorMode.USAGE_STATS_FALLBACK -> GlassPalette.HudAmber
        AppLockMonitorMode.NEEDS_PERMISSION -> MaterialTheme.colorScheme.error
    }

    GlassPanel(modifier = Modifier.fillMaxWidth().testTag("applock-monitor-card")) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Tokens.Gutter),
            verticalArrangement = Arrangement.spacedBy(Tokens.Small),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
            ) {
                Box(
                    modifier = Modifier
                        .size(AppLockTokens.StatusDotSize)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                Text(text = statusLabel, style = MaterialTheme.typography.titleSmall)
            }
            Text(
                text = statusDetail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (mode != AppLockMonitorMode.ACCESSIBILITY_ACTIVE) {
                if (stackRows) {
                    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                        OutlinedButton(
                            onClick = onOpenAccessibilitySettings,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = Tokens.TouchTarget)
                                .testTag("applock-open-accessibility"),
                        ) {
                            Text("Enable Accessibility")
                        }
                        if (mode == AppLockMonitorMode.NEEDS_PERMISSION) {
                            OutlinedButton(
                                onClick = onOpenUsageAccessSettings,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = Tokens.TouchTarget)
                                    .testTag("applock-open-usage"),
                            ) {
                                Text("Grant Usage Access")
                            }
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                        OutlinedButton(
                            onClick = onOpenAccessibilitySettings,
                            modifier = Modifier
                                .heightIn(min = Tokens.TouchTarget)
                                .testTag("applock-open-accessibility"),
                        ) {
                            Text("Enable Accessibility")
                        }
                        if (mode == AppLockMonitorMode.NEEDS_PERMISSION) {
                            OutlinedButton(
                                onClick = onOpenUsageAccessSettings,
                                modifier = Modifier
                                    .heightIn(min = Tokens.TouchTarget)
                                    .testTag("applock-open-usage"),
                            ) {
                                Text("Usage Access")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtectSettingsCard(
    isProtected: Boolean,
    isRecommended: Boolean,
    reason: String,
    stackRows: Boolean,
    onToggle: () -> Unit,
) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth().testTag("applock-protect-settings-card"),
        shape = AppLockTokens.StatusCardShape,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Tokens.Gutter),
            verticalArrangement = Arrangement.spacedBy(Tokens.Small),
        ) {
            val content: @Composable () -> Unit = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Medium),
                ) {
                    Box(
                        modifier = Modifier
                            .size(AppLockTokens.AppIconBadgeSize)
                            .clip(CircleShape)
                            .background(
                                if (isProtected) MaterialTheme.colorScheme.tertiaryContainer
                                else MaterialTheme.colorScheme.primaryContainer,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (isProtected) Icons.Outlined.Shield else Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            tint = if (isProtected) MaterialTheme.colorScheme.onTertiaryContainer
                            else MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(Tokens.IconSmall),
                        )
                    }
                    Column {
                        Text(
                            text = if (isProtected) "Settings protected" else "Protect Android Settings",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (stackRows) {
                content()
                if (isProtected) {
                    OutlinedButton(
                        onClick = onToggle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Tokens.TouchTarget)
                            .testTag("applock-protect-settings-button"),
                    ) {
                        Text("Remove Settings lock")
                    }
                } else {
                    Button(
                        onClick = onToggle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Tokens.TouchTarget)
                            .testTag("applock-protect-settings-button"),
                    ) {
                        Text(if (isRecommended) "Protect Settings" else "Lock Settings")
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Box(Modifier.weight(1f).padding(end = Tokens.Medium)) {
                        content()
                    }
                    if (isProtected) {
                        OutlinedButton(
                            onClick = onToggle,
                            modifier = Modifier
                                .heightIn(min = Tokens.TouchTarget)
                                .testTag("applock-protect-settings-button"),
                        ) {
                            Text("Protected")
                        }
                    } else {
                        Button(
                            onClick = onToggle,
                            modifier = Modifier
                                .heightIn(min = Tokens.TouchTarget)
                                .testTag("applock-protect-settings-button"),
                        ) {
                            Text("Protect")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PolicyOptionsPanel(
    gracePeriod: ReLockGracePeriod,
    allowBiometric: Boolean,
    onSelectGracePeriod: (ReLockGracePeriod) -> Unit,
    onToggleBiometric: (Boolean) -> Unit,
) {
    GlassPanel(modifier = Modifier.fillMaxWidth().testTag("applock-policy-card")) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Tokens.Gutter),
            verticalArrangement = Arrangement.spacedBy(Tokens.Medium),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                Text(
                    text = "Re-lock grace period",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "Screen off always re-locks immediately.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Small),
                ) {
                    GracePeriodPill(
                        label = "Immediate",
                        selected = gracePeriod == ReLockGracePeriod.IMMEDIATE,
                        tag = "applock-grace-immediate",
                        onClick = { onSelectGracePeriod(ReLockGracePeriod.IMMEDIATE) },
                    )
                    GracePeriodPill(
                        label = "1 min",
                        selected = gracePeriod == ReLockGracePeriod.ONE_MIN,
                        tag = "applock-grace-1min",
                        onClick = { onSelectGracePeriod(ReLockGracePeriod.ONE_MIN) },
                    )
                    GracePeriodPill(
                        label = "5 min",
                        selected = gracePeriod == ReLockGracePeriod.FIVE_MIN,
                        tag = "applock-grace-5min",
                        onClick = { onSelectGracePeriod(ReLockGracePeriod.FIVE_MIN) },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Tokens.TouchTarget)
                    .toggleable(
                        value = allowBiometric,
                        role = Role.Switch,
                        onValueChange = onToggleBiometric,
                    )
                    .testTag("applock-biometric-toggle"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Fingerprint,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Tokens.Icon),
                )
                Spacer(Modifier.width(Tokens.Medium))
                Column(Modifier.weight(1f).padding(end = Tokens.Medium)) {
                    Text(
                        text = "Biometric unlock (Class 3 Strong)",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Allow strong fingerprint or face biometric before PIN.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = allowBiometric, onCheckedChange = null)
            }
        }
    }
}

@Composable
private fun GracePeriodPill(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else GlassPalette.Well
    val border = if (selected) MaterialTheme.colorScheme.primary else GlassPalette.WellEdge
    val textColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .heightIn(min = Tokens.TouchTarget)
            .clip(AppLockTokens.ChipShape)
            .background(bg)
            .border(Tokens.Hairline, border, AppLockTokens.ChipShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Tokens.Gutter, vertical = Tokens.Row)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = textColor,
        )
    }
}

@Composable
private fun InstalledAppToggleRow(
    entry: LockableAppEntry,
    onToggle: () -> Unit,
) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Tokens.TouchTarget)
                .toggleable(
                    value = entry.isLocked,
                    role = Role.Switch,
                    onValueChange = { onToggle() },
                )
                .padding(horizontal = Tokens.Gutter, vertical = Tokens.Row)
                .testTag("applock-app-row-${entry.packageName}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(AppLockTokens.AppIconBadgeSize)
                    .clip(CircleShape)
                    .background(
                        if (entry.isLocked) MaterialTheme.colorScheme.primaryContainer
                        else GlassPalette.Well,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (entry.isLocked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                    contentDescription = null,
                    tint = if (entry.isLocked) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Tokens.IconSmall),
                )
            }
            Spacer(Modifier.width(Tokens.Medium))
            Column(Modifier.weight(1f).padding(end = Tokens.Medium)) {
                Text(
                    text = entry.appLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(checked = entry.isLocked, onCheckedChange = null)
        }
    }
}

@Composable
private fun SetAppLockPinDialog(
    hasExistingPin: Boolean,
    onDismiss: () -> Unit,
    onConfirmPin: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = {
            Text(if (hasExistingPin) "Change App Lock PIN" else "Set App Lock PIN")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Medium)) {
                Text(
                    text = "Choose a ${AppLockPreferencesRepository.MIN_PIN_LENGTH} to ${AppLockPreferencesRepository.MAX_PIN_LENGTH} digit PIN. Only a salted PBKDF2-HMAC-SHA256 hash is saved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { raw ->
                        val digits = raw.filter { it.isDigit() }.take(AppLockPreferencesRepository.MAX_PIN_LENGTH)
                        pin = digits
                        validationError = null
                    },
                    label = { Text("New PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth().testTag("applock-pin-dialog-input"),
                )
                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { raw ->
                        val digits = raw.filter { it.isDigit() }.take(AppLockPreferencesRepository.MAX_PIN_LENGTH)
                        confirmPin = digits
                        validationError = null
                    },
                    label = { Text("Confirm PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth().testTag("applock-pin-dialog-confirm"),
                )
                if (validationError != null) {
                    Text(
                        text = validationError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when {
                        pin.length < AppLockPreferencesRepository.MIN_PIN_LENGTH ->
                            validationError = "PIN must be at least ${AppLockPreferencesRepository.MIN_PIN_LENGTH} digits."
                        pin != confirmPin ->
                            validationError = "PIN entries do not match."
                        else -> onConfirmPin(pin)
                    }
                },
                modifier = Modifier.heightIn(min = Tokens.TouchTarget).testTag("applock-pin-dialog-save"),
            ) {
                Text("Save PIN")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = Tokens.TouchTarget),
            ) {
                Text("Cancel")
            }
        },
    )
}

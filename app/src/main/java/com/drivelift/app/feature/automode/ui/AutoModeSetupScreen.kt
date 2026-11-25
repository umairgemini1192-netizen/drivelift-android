package com.drivelift.app.feature.automode.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivelift.app.Graph
import com.drivelift.app.feature.automode.AdbPairingNotifier
import com.drivelift.app.feature.automode.AndroidAutoModeSystemGateway
import com.drivelift.app.feature.automode.AutoModeWizardState
import com.drivelift.app.feature.automode.AutoModeWizardStep
import com.drivelift.app.feature.automode.DeveloperOptionsObserver
import com.drivelift.app.feature.automode.NotificationAccessState
import com.drivelift.app.ui.GlassLevel
import com.drivelift.app.ui.GlassPalette
import com.drivelift.app.ui.GlassPanel
import com.drivelift.app.ui.GlassSurface
import com.drivelift.app.ui.Tokens

/**
 * Stateful Built-in Auto Mode one-time setup wizard connected to [Graph.cleanerAndSleep].
 * Automatically re-checks Developer options, Wi-Fi, and permissions on resume and via a live
 * [DeveloperOptionsObserver] while visible.
 */
@Composable
fun AutoModeSetupScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val module = remember(context) { Graph.get(context).cleanerAndSleep }
    val state by module.autoModeWizardState.collectAsStateWithLifecycle()

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ ->
        module.refreshAutoModeStatus()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        module.refreshAutoModeStatus()
    }

    DisposableEffect(context, module) {
        val observer = DeveloperOptionsObserver(
            context = context,
            onChanged = { module.refreshAutoModeStatus() },
        )
        observer.register()
        onDispose {
            observer.unregister()
            module.stopPairingServiceDiscovery()
        }
    }

    LaunchedEffect(state.step, state.isOnWifi) {
        if (state.step == AutoModeWizardStep.PAIR_WIRELESS_DEBUGGING && state.isOnWifi) {
            module.startPairingServiceDiscovery()
        } else {
            module.stopPairingServiceDiscovery()
        }
    }

    fun openDeveloperOptions() {
        runCatching {
            context.startActivity(AndroidAutoModeSystemGateway.createDeveloperOptionsIntent())
        }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    AutoModeSetupContent(
        state = state,
        onBack = onBack,
        onRefresh = { module.refreshAutoModeStatus() },
        onOpenAboutPhone = {
            runCatching {
                context.startActivity(AndroidAutoModeSystemGateway.createAboutPhoneIntent())
            }.onFailure {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        },
        onOpenDeveloperOptions = { openDeveloperOptions() },
        onOpenWifiSettings = {
            runCatching {
                context.startActivity(AndroidAutoModeSystemGateway.createWifiSettingsIntent())
            }
        },
        onRequestNotificationPermission = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                runCatching {
                    context.startActivity(AdbPairingNotifier.createNotificationSettingsIntent(context))
                }
            }
        },
        onOpenNotificationSettings = {
            runCatching {
                context.startActivity(AdbPairingNotifier.createNotificationSettingsIntent(context))
            }
        },
        onPostNotificationAndOpenWirelessDebugging = {
            val access = module.autoModeSystemGateway.notificationAccessState()
            if (access == NotificationAccessState.NEEDS_RUNTIME_PERMISSION &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ) {
                module.postPairingNotification()
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                module.postPairingNotification()
                openDeveloperOptions()
            }
        },
        onRetryDiscovery = { module.startPairingServiceDiscovery() },
        onSubmitPairingCode = { code ->
            module.submitPairingCodeFromWizard(code)
        },
        onSubmitPairingCodeWithPort = { code, port ->
            module.submitPairingCodeFromWizard(code, port)
        },
        onRetryBootstrap = { module.retryOneTimeBootstrap() },
        onToggleKeepWirelessDebuggingOn = { enabled ->
            module.setKeepWirelessDebuggingOn(enabled)
        },
        onTurnOffAutoMode = { module.turnOffAutoMode() },
        modifier = modifier,
    )
}

/**
 * Stateless Built-in Auto Mode setup wizard UI adhering to DriveLift's unified GlassSurface and
 * Tokens design system.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoModeSetupContent(
    state: AutoModeWizardState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenAboutPhone: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onPostNotificationAndOpenWirelessDebugging: () -> Unit,
    onSubmitPairingCode: (String) -> Unit,
    onRetryBootstrap: () -> Unit,
    onTurnOffAutoMode: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDeveloperOptions: () -> Unit = {},
    onRequestNotificationPermission: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {},
    onRetryDiscovery: () -> Unit = {},
    onSubmitPairingCodeWithPort: ((code: String, port: String) -> Unit)? = null,
    onToggleKeepWirelessDebuggingOn: (Boolean) -> Unit = {},
) {
    var inlineCode by remember { mutableStateOf("") }
    var inlinePort by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("automode-setup-screen"),
    ) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
            ),
            title = { Text("Turn on Auto Mode") },
            navigationIcon = {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("automode-back-button"),
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
                    modifier = Modifier.testTag("automode-refresh-button"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = "Refresh Auto Mode status",
                    )
                }
            },
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("automode-wizard-list"),
            contentPadding = PaddingValues(
                start = Tokens.Edge,
                end = Tokens.Edge,
                top = Tokens.Small,
                bottom = Tokens.Section,
            ),
            verticalArrangement = Arrangement.spacedBy(Tokens.Medium),
        ) {
            if (state.isReady) {
                item(key = "ready-card") {
                    AutoModeReadyCard(
                        isOnWifi = state.isOnWifi,
                        keepWirelessDebuggingOn = state.keepWirelessDebuggingOn,
                        onToggleKeepWirelessDebuggingOn = onToggleKeepWirelessDebuggingOn,
                        onTurnOff = onTurnOffAutoMode,
                    )
                }
            } else {
                item(key = "intro-step-progress") {
                    AutoModeStepHeaderCard(currentStep = state.step)
                }

                if (state.turnOffNotice != null) {
                    item(key = "turnoff-notice") {
                        AutoModeInfoPanel(
                            title = "Auto Mode turned off",
                            body = state.turnOffNotice,
                            accent = MaterialTheme.colorScheme.secondary,
                            tag = "automode-turnoff-notice",
                        )
                    }
                }

                if (state.errorMessage != null) {
                    item(key = "error-banner") {
                        AutoModeInfoPanel(
                            title = "Setup needs attention",
                            body = state.errorMessage,
                            accent = GlassPalette.HudAmber,
                            tag = "automode-error-panel",
                        )
                    }
                }

                if (state.isWorking && state.statusMessage != null) {
                    item(key = "working-banner") {
                        GlassPanel(
                            shape = Tokens.Cleaner.CardShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("automode-working-panel"),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(Tokens.Edge),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Tokens.Gutter),
                            ) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(Tokens.Icon),
                                )
                                Text(
                                    text = state.statusMessage,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }

                when (state.step) {
                    AutoModeWizardStep.ENABLE_DEVELOPER_OPTIONS -> {
                        item(key = "step-1") {
                            Step1DeveloperOptionsCard(
                                onOpenAboutPhone = onOpenAboutPhone,
                                onOpenDeveloperOptions = onOpenDeveloperOptions,
                            )
                        }
                    }
                    AutoModeWizardStep.PAIR_WIRELESS_DEBUGGING -> {
                        item(key = "step-2") {
                            Step2WirelessPairingCard(
                                isOnWifi = state.isOnWifi,
                                isWorking = state.isWorking,
                                notificationPosted = state.pairingNotificationPosted,
                                notificationAccess = state.notificationAccess,
                                isDiscoveringPairingService = state.isDiscoveringPairingService,
                                discoveredPairingPort = state.discoveredPairingPort,
                                discoveryErrorMessage = state.discoveryErrorMessage,
                                inlineCode = inlineCode,
                                inlinePort = inlinePort,
                                onInlineCodeChange = { next ->
                                    inlineCode = next.filter { it in '0'..'9' }.take(6)
                                },
                                onInlinePortChange = { next ->
                                    inlinePort = next.filter { it in '0'..'9' }.take(5)
                                },
                                onOpenWifiSettings = onOpenWifiSettings,
                                onRequestNotificationPermission = onRequestNotificationPermission,
                                onOpenNotificationSettings = onOpenNotificationSettings,
                                onPostNotificationAndOpenWirelessDebugging = onPostNotificationAndOpenWirelessDebugging,
                                onRetryDiscovery = onRetryDiscovery,
                                onSubmitInlineCode = {
                                    val codeToSubmit = inlineCode
                                    val portToSubmit = inlinePort
                                    inlineCode = ""
                                    inlinePort = ""
                                    if (onSubmitPairingCodeWithPort != null) {
                                        onSubmitPairingCodeWithPort(codeToSubmit, portToSubmit)
                                    } else {
                                        onSubmitPairingCode(codeToSubmit)
                                    }
                                },
                            )
                        }
                    }
                    AutoModeWizardStep.GRANT_PERMISSIONS_AND_VERIFY -> {
                        item(key = "step-3") {
                            Step3GrantPermissionsCard(
                                isOnWifi = state.isOnWifi,
                                isWorking = state.isWorking,
                                onRetryBootstrap = onRetryBootstrap,
                                onOpenWifiSettings = onOpenWifiSettings,
                            )
                        }
                    }
                    AutoModeWizardStep.READY -> Unit
                }
            }
        }
    }
}

@Composable
private fun AutoModeStepHeaderCard(currentStep: AutoModeWizardStep) {
    val stepNumber = when (currentStep) {
        AutoModeWizardStep.ENABLE_DEVELOPER_OPTIONS -> 1
        AutoModeWizardStep.PAIR_WIRELESS_DEBUGGING -> 2
        AutoModeWizardStep.GRANT_PERMISSIONS_AND_VERIFY -> 3
        AutoModeWizardStep.READY -> 3
    }

    GlassSurface(
        level = GlassLevel.Hero,
        shape = Tokens.HeroShape,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("automode-step-header"),
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
                    imageVector = Icons.Outlined.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Tokens.Icon),
                )
                Text(
                    text = "Step $stepNumber of 3",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = "One-time setup inside DriveLift",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "No extra app needed. Once paired, Clean and Sleep run automatically on Wi-Fi and survive reboot.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Step1DeveloperOptionsCard(
    onOpenAboutPhone: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
) {
    val wellShape = Tokens.concentricShape(Tokens.CardRadius, Tokens.Medium)
    GlassPanel(
        shape = Tokens.Cleaner.CardShape,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("automode-step1-card"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Tokens.Edge),
            verticalArrangement = Arrangement.spacedBy(Tokens.Medium),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
            ) {
                Icon(
                    imageVector = Icons.Outlined.DeveloperMode,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Tokens.Icon),
                )
                Text(
                    text = "Turn on Developer options",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = "Enable Developer options once so DriveLift can pair with Android's built-in Wireless debugging.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(wellShape)
                    .background(GlassPalette.Well)
                    .border(Tokens.Hairline, GlassPalette.WellEdge, wellShape)
                    .padding(Tokens.Gutter)
                    .testTag("automode-build-number-hint"),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Tokens.Tiny)) {
                    Text(
                        text = "Not unlocked yet?",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Tap Build number 7 times",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Scroll to the bottom of About phone, tap Build number 7 times, enter your screen lock, then come back to DriveLift.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(
                onClick = onOpenAboutPhone,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Tokens.TouchTarget)
                    .testTag("automode-open-about-phone"),
            ) {
                Text("Open About phone")
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(wellShape)
                    .background(GlassPalette.Well)
                    .border(Tokens.Hairline, GlassPalette.WellEdge, wellShape)
                    .padding(Tokens.Gutter)
                    .testTag("automode-developer-options-hint"),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Tokens.Tiny)) {
                    Text(
                        text = "Already a developer?",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Switch on Use developer options at the top",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "If Android says you are already a developer, open Developer options directly and turn on the master switch. This screen advances automatically once it is on.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(
                onClick = onOpenDeveloperOptions,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Tokens.TouchTarget)
                    .testTag("automode-open-developer-options"),
            ) {
                Text("Open Developer options")
            }
        }
    }
}

@Composable
private fun Step2WirelessPairingCard(
    isOnWifi: Boolean,
    isWorking: Boolean,
    notificationPosted: Boolean,
    notificationAccess: NotificationAccessState,
    isDiscoveringPairingService: Boolean,
    discoveredPairingPort: Int?,
    discoveryErrorMessage: String?,
    inlineCode: String,
    inlinePort: String,
    onInlineCodeChange: (String) -> Unit,
    onInlinePortChange: (String) -> Unit,
    onOpenWifiSettings: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onPostNotificationAndOpenWirelessDebugging: () -> Unit,
    onRetryDiscovery: () -> Unit,
    onSubmitInlineCode: () -> Unit,
) {
    val wellShape = Tokens.concentricShape(Tokens.CardRadius, Tokens.Medium)
    GlassPanel(
        shape = Tokens.Cleaner.CardShape,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("automode-step2-card"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Tokens.Edge),
            verticalArrangement = Arrangement.spacedBy(Tokens.Medium),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
            ) {
                Icon(
                    imageVector = if (isOnWifi) Icons.Outlined.Wifi else Icons.Outlined.WifiOff,
                    contentDescription = null,
                    tint = if (isOnWifi) GlassPalette.HudEmerald else GlassPalette.HudAmber,
                    modifier = Modifier.size(Tokens.Icon),
                )
                Text(
                    text = "Pair with Wireless debugging",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            if (!isOnWifi) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(wellShape)
                        .background(GlassPalette.Well)
                        .border(Tokens.Hairline, GlassPalette.HudAmber.copy(alpha = 0.6f), wellShape)
                        .padding(Tokens.Medium)
                        .testTag("automode-wifi-required-banner"),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                        Text(
                            text = "Wi-Fi is required for Wireless debugging",
                            style = MaterialTheme.typography.titleSmall,
                            color = GlassPalette.HudAmber,
                        )
                        Text(
                            text = "Connect to Wi-Fi, then come back here to pair DriveLift.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = onOpenWifiSettings,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = Tokens.TouchTarget)
                                .testTag("automode-open-wifi-settings"),
                        ) {
                            Text("Open Wi-Fi settings")
                        }
                    }
                }
            }

            if (notificationAccess != NotificationAccessState.ALLOWED) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(wellShape)
                        .background(GlassPalette.Well)
                        .border(Tokens.Hairline, GlassPalette.HudAmber.copy(alpha = 0.6f), wellShape)
                        .padding(Tokens.Medium)
                        .testTag("automode-notification-permission-banner"),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                        Text(
                            text = "Notifications are blocked for DriveLift",
                            style = MaterialTheme.typography.titleSmall,
                            color = GlassPalette.HudAmber,
                        )
                        Text(
                            text = AdbPairingNotifier.BLOCKED_EXPLANATION,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (notificationAccess == NotificationAccessState.NEEDS_RUNTIME_PERMISSION) {
                            Button(
                                onClick = onRequestNotificationPermission,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = Tokens.TouchTarget)
                                    .testTag("automode-request-notification-permission"),
                            ) {
                                Text("Allow notifications")
                            }
                        } else {
                            OutlinedButton(
                                onClick = onOpenNotificationSettings,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = Tokens.TouchTarget)
                                    .testTag("automode-open-notification-settings"),
                            ) {
                                Text("Open notification settings")
                            }
                        }
                    }
                }
            }

            Text(
                text = "1. Open Wireless debugging (tap the row text, not just the switch), turn it on, and allow on this network.\n" +
                    "2. Tap \"Pair device with pairing code\".\n" +
                    "3. Type the 6-digit code into the DriveLift notification (or the in-app field below).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("automode-step2-instructions"),
            )

            if (isOnWifi) {
                when {
                    isDiscoveringPairingService -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(wellShape)
                                .background(GlassPalette.Well)
                                .border(Tokens.Hairline, GlassPalette.WellEdge, wellShape)
                                .padding(Tokens.Medium)
                                .testTag("automode-pairing-discovery-indicator"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Tokens.Medium),
                        ) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = Tokens.Stroke,
                                modifier = Modifier.size(Tokens.IconSmall),
                            )
                            Text(
                                text = "Waiting for pairing service on this phone...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    discoveredPairingPort != null -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(wellShape)
                                .background(GlassPalette.Well)
                                .border(Tokens.Hairline, GlassPalette.HudEmerald.copy(alpha = 0.6f), wellShape)
                                .padding(Tokens.Medium)
                                .testTag("automode-pairing-discovery-found"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = GlassPalette.HudEmerald,
                                modifier = Modifier.size(Tokens.IconSmall),
                            )
                            Text(
                                text = "Pairing service found on port $discoveredPairingPort",
                                style = MaterialTheme.typography.bodySmall,
                                color = GlassPalette.HudEmerald,
                            )
                        }
                    }
                    discoveryErrorMessage != null -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(wellShape)
                                .background(GlassPalette.Well)
                                .border(Tokens.Hairline, GlassPalette.HudAmber.copy(alpha = 0.6f), wellShape)
                                .padding(Tokens.Medium)
                                .testTag("automode-pairing-discovery-error"),
                            verticalArrangement = Arrangement.spacedBy(Tokens.Small),
                        ) {
                            Text(
                                text = discoveryErrorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = GlassPalette.HudAmber,
                            )
                            OutlinedButton(
                                onClick = onRetryDiscovery,
                                enabled = !isWorking,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = Tokens.TouchTarget)
                                    .testTag("automode-pairing-discovery-retry"),
                            ) {
                                Text("Search again")
                            }
                        }
                    }
                }
            }

            Button(
                onClick = onPostNotificationAndOpenWirelessDebugging,
                enabled = isOnWifi && !isWorking,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Tokens.TouchTarget)
                    .testTag("automode-open-wireless-debugging"),
            ) {
                Icon(
                    imageVector = Icons.Outlined.NotificationsActive,
                    contentDescription = null,
                    modifier = Modifier.size(Tokens.IconSmall),
                )
                Spacer(modifier = Modifier.width(Tokens.Small))
                Text(
                    if (notificationPosted) {
                        "Re-open Wireless debugging"
                    } else {
                        "Open Wireless debugging"
                    },
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(wellShape)
                    .background(GlassPalette.Well)
                    .border(Tokens.Hairline, GlassPalette.WellEdge, wellShape)
                    .padding(Tokens.Medium)
                    .testTag("automode-inapp-pairing-fallback"),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                    Text(
                        text = "Enter pairing code in DriveLift",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Use split-screen or quickly switch back from the Wireless debugging pairing dialog so pairing works even without the notification.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = inlineCode,
                        onValueChange = onInlineCodeChange,
                        singleLine = true,
                        enabled = isOnWifi && !isWorking,
                        label = { Text("6-digit pairing code") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("automode-pairing-code-input"),
                    )
                    OutlinedTextField(
                        value = inlinePort,
                        onValueChange = onInlinePortChange,
                        singleLine = true,
                        enabled = isOnWifi && !isWorking,
                        label = { Text("Pairing port (optional, e.g. 37111)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("automode-pairing-port-input"),
                    )
                    OutlinedButton(
                        onClick = onSubmitInlineCode,
                        enabled = isOnWifi && !isWorking && inlineCode.length == 6,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Tokens.TouchTarget)
                            .testTag("automode-submit-pairing-code"),
                    ) {
                        Text("Pair now")
                    }
                }
            }
        }
    }
}

@Composable
private fun Step3GrantPermissionsCard(
    isOnWifi: Boolean,
    isWorking: Boolean,
    onRetryBootstrap: () -> Unit,
    onOpenWifiSettings: () -> Unit,
) {
    GlassPanel(
        shape = Tokens.Cleaner.CardShape,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("automode-step3-card"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Tokens.Edge),
            verticalArrangement = Arrangement.spacedBy(Tokens.Medium),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Tokens.Icon),
                )
                Text(
                    text = "Finish one-time permissions",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = "DriveLift connects once over localhost to grant WRITE_SECURE_SETTINGS and disable the ADB authorization timeout so setup survives reboots.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!isOnWifi) {
                OutlinedButton(
                    onClick = onOpenWifiSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Tokens.TouchTarget)
                        .testTag("automode-step3-wifi-button"),
                ) {
                    Text("Connect to Wi-Fi")
                }
            } else {
                Button(
                    onClick = onRetryBootstrap,
                    enabled = !isWorking,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Tokens.TouchTarget)
                        .testTag("automode-finish-setup-button"),
                ) {
                    Text(if (isWorking) "Finishing setup..." else "Finish setup")
                }
            }
        }
    }
}

@Composable
private fun AutoModeReadyCard(
    isOnWifi: Boolean,
    keepWirelessDebuggingOn: Boolean,
    onToggleKeepWirelessDebuggingOn: (Boolean) -> Unit,
    onTurnOff: () -> Unit,
) {
    val wellShape = Tokens.concentricShape(Tokens.HeroRadius, Tokens.Medium)
    GlassSurface(
        level = GlassLevel.Hero,
        shape = Tokens.HeroShape,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("automode-ready-card"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Tokens.Edge),
            verticalArrangement = Arrangement.spacedBy(Tokens.Medium),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
            ) {
                Box(
                    modifier = Modifier
                        .size(Tokens.StatusDot)
                        .clip(CircleShape)
                        .background(GlassPalette.HudEmerald),
                )
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = GlassPalette.HudEmerald,
                    modifier = Modifier.size(Tokens.Icon),
                )
                Text(
                    text = "Auto Mode is on",
                    style = MaterialTheme.typography.headlineSmall,
                    color = GlassPalette.HudEmerald,
                    modifier = Modifier.testTag("automode-ready-title"),
                )
            }

            Text(
                text = "Clean now and Sleep Mode run silently on Wi-Fi with zero prompts, even after a reboot. By default, DriveLift turns Wireless debugging back off after every run so the debug port is never left open.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Tokens.TouchTarget)
                    .clip(wellShape)
                    .background(GlassPalette.Well)
                    .border(Tokens.Hairline, GlassPalette.WellEdge, wellShape)
                    .toggleable(
                        value = keepWirelessDebuggingOn,
                        role = Role.Switch,
                        onValueChange = onToggleKeepWirelessDebuggingOn,
                    )
                    .padding(horizontal = Tokens.Gutter, vertical = Tokens.Medium)
                    .testTag("automode-keep-wireless-debugging-switch"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Medium),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Tiny),
                ) {
                    Text(
                        text = "Keep Wireless debugging on after Auto Mode",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Off by default so DriveLift closes the Wireless debugging port after every Clean and Sleep run.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = keepWirelessDebuggingOn,
                    onCheckedChange = null,
                )
            }

            if (!isOnWifi) {
                Text(
                    text = "Wi-Fi is currently off. If you run Clean or Sleep right now, DriveLift will use guided steps until Wi-Fi reconnects.",
                    style = MaterialTheme.typography.bodySmall,
                    color = GlassPalette.HudAmber,
                )
            }

            OutlinedButton(
                onClick = onTurnOff,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Tokens.TouchTarget)
                    .testTag("automode-turn-off-button"),
            ) {
                Icon(
                    imageVector = Icons.Outlined.PowerSettingsNew,
                    contentDescription = null,
                    modifier = Modifier.size(Tokens.IconSmall),
                )
                Spacer(modifier = Modifier.width(Tokens.Small))
                Text("Turn off Auto Mode")
            }
        }
    }
}

@Composable
private fun AutoModeInfoPanel(
    title: String,
    body: String,
    accent: Color,
    tag: String,
) {
    GlassPanel(
        shape = Tokens.Cleaner.CardShape,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Tokens.Gutter),
            verticalArrangement = Arrangement.spacedBy(Tokens.Small),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = accent,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

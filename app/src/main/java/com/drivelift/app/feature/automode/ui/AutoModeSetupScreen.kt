package com.drivelift.app.feature.automode.ui

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivelift.app.Graph
import com.drivelift.app.feature.automode.AndroidAutoModeSystemGateway
import com.drivelift.app.feature.automode.AutoModeWizardState
import com.drivelift.app.feature.automode.AutoModeWizardStep
import com.drivelift.app.ui.GlassLevel
import com.drivelift.app.ui.GlassPalette
import com.drivelift.app.ui.GlassPanel
import com.drivelift.app.ui.GlassSurface
import com.drivelift.app.ui.Tokens

/**
 * Stateful Built-in Auto Mode one-time setup wizard connected to [Graph.cleanerAndSleep].
 * Automatically re-checks Developer options, Wi-Fi, and permissions on resume.
 */
@Composable
fun AutoModeSetupScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val module = remember(context) { Graph.get(context).cleanerAndSleep }
    val state by module.autoModeWizardState.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        module.refreshAutoModeStatus()
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
        onOpenWifiSettings = {
            runCatching {
                context.startActivity(AndroidAutoModeSystemGateway.createWifiSettingsIntent())
            }
        },
        onPostNotificationAndOpenWirelessDebugging = {
            module.postPairingNotification()
            runCatching {
                context.startActivity(AndroidAutoModeSystemGateway.createDeveloperOptionsIntent())
            }.onFailure {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        },
        onSubmitPairingCode = { code ->
            module.submitPairingCodeFromWizard(code)
        },
        onRetryBootstrap = { module.retryOneTimeBootstrap() },
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
) {
    var inlineCode by remember { mutableStateOf("") }

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
                            Step1DeveloperOptionsCard(onOpenAboutPhone = onOpenAboutPhone)
                        }
                    }
                    AutoModeWizardStep.PAIR_WIRELESS_DEBUGGING -> {
                        item(key = "step-2") {
                            Step2WirelessPairingCard(
                                isOnWifi = state.isOnWifi,
                                isWorking = state.isWorking,
                                notificationPosted = state.pairingNotificationPosted,
                                inlineCode = inlineCode,
                                onInlineCodeChange = { next ->
                                    inlineCode = next.filter { it in '0'..'9' }.take(6)
                                },
                                onOpenWifiSettings = onOpenWifiSettings,
                                onPostNotificationAndOpenWirelessDebugging = onPostNotificationAndOpenWirelessDebugging,
                                onSubmitInlineCode = {
                                    val codeToSubmit = inlineCode
                                    inlineCode = ""
                                    onSubmitPairingCode(codeToSubmit)
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
                Text(
                    text = "Tap Build number 7 times",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = "Scroll to the bottom of About phone, tap Build number 7 times, enter your screen lock, then come back to DriveLift.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onOpenAboutPhone,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Tokens.TouchTarget)
                    .testTag("automode-open-about-phone"),
            ) {
                Text("Open About phone")
            }
        }
    }
}

@Composable
private fun Step2WirelessPairingCard(
    isOnWifi: Boolean,
    isWorking: Boolean,
    notificationPosted: Boolean,
    inlineCode: String,
    onInlineCodeChange: (String) -> Unit,
    onOpenWifiSettings: () -> Unit,
    onPostNotificationAndOpenWirelessDebugging: () -> Unit,
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

            Text(
                text = "1. Tap below to post DriveLift's reply notification and open Developer options.\n" +
                    "2. Turn on Wireless debugging and tap Pair device with pairing code.\n" +
                    "3. Pull down the notification shade and type the 6-digit code into Enter pairing code without leaving Settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

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
                    .padding(Tokens.Medium),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                    Text(
                        text = "Or enter the 6-digit pairing code here (split-screen)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
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
    onTurnOff: () -> Unit,
) {
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
                text = "Clean now and Sleep Mode run silently on Wi-Fi with zero prompts, even after a reboot. DriveLift turns Wireless debugging back off after each run so the debug port is never left open.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

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

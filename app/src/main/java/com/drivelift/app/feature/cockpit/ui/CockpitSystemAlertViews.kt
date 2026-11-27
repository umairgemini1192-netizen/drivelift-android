package com.drivelift.app.feature.cockpit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AirplanemodeActive
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Headset
import androidx.compose.material.icons.outlined.LaptopMac
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.drivelift.app.feature.cockpit.alerts.CockpitAlertAccent
import com.drivelift.app.feature.cockpit.alerts.CockpitBiometricStage
import com.drivelift.app.feature.cockpit.alerts.CockpitRingerMode
import com.drivelift.app.feature.cockpit.alerts.CockpitSystemAlert
import com.drivelift.app.feature.cockpit.overlay.toWideGamutP3
import com.drivelift.app.ui.CockpitLivePalette
import com.drivelift.app.ui.GlassPalette
import com.drivelift.app.ui.Tokens

/** What a transient system alert says on the collapsed island: an icon, a short title and one detail. */
internal data class SystemAlertPresentation(
    val icon: ImageVector,
    val title: String,
    val detail: String,
    /** Spoken description for accessibility, with the longer facts the pill has no room for. */
    val description: String,
)

/** Copy and icon for every [CockpitSystemAlert]. Pure, so tests can check each alert without rendering. */
internal fun CockpitSystemAlert.presentation(): SystemAlertPresentation = when (this) {
    is CockpitSystemAlert.BatteryCharging -> {
        val title = if (isFastCharging) "Fast charging" else "Charging"
        val remaining = estimatedFullMinutes?.let { ", full in $it minutes" }.orEmpty()
        SystemAlertPresentation(
            icon = Icons.Outlined.BatteryChargingFull,
            title = title,
            detail = "$percent%",
            description = "$title, battery $percent percent$remaining",
        )
    }
    is CockpitSystemAlert.BatteryFull -> SystemAlertPresentation(
        icon = Icons.Outlined.BatteryFull,
        title = "Battery full",
        detail = "$percent%",
        description = "Battery full, $percent percent",
    )
    is CockpitSystemAlert.BatteryLow -> {
        val title = if (isCritical) "Battery critical" else "Battery low"
        SystemAlertPresentation(
            icon = Icons.Outlined.BatteryAlert,
            title = title,
            detail = "$percent%",
            description = "$title, $percent percent left",
        )
    }
    is CockpitSystemAlert.EarbudsConnected -> {
        val name = deviceName.ifBlank { "Earbuds" }
        SystemAlertPresentation(
            icon = Icons.Outlined.Headset,
            title = name,
            detail = batteryPercent?.let { "$it%" } ?: "Connected",
            description = "$name connected" + batteryPercent?.let { ", battery $it percent" }.orEmpty(),
        )
    }
    is CockpitSystemAlert.EarbudsLowBattery -> {
        val name = deviceName.ifBlank { "Earbuds" }
        SystemAlertPresentation(
            icon = Icons.Outlined.Headset,
            title = name,
            detail = "$batteryPercent% low",
            description = "$name battery low, $batteryPercent percent",
        )
    }
    is CockpitSystemAlert.RingerModeChanged -> when (mode) {
        CockpitRingerMode.SILENT -> SystemAlertPresentation(
            icon = Icons.Outlined.NotificationsOff,
            title = "Silent",
            detail = "",
            description = "Ringer silent",
        )
        CockpitRingerMode.VIBRATE -> SystemAlertPresentation(
            icon = Icons.Outlined.Vibration,
            title = "Vibrate",
            detail = "",
            description = "Ringer vibrate",
        )
        CockpitRingerMode.NORMAL -> SystemAlertPresentation(
            icon = Icons.Outlined.NotificationsActive,
            title = "Ringer on",
            detail = "",
            description = "Ringer on",
        )
    }
    is CockpitSystemAlert.AirplaneModeChanged -> SystemAlertPresentation(
        icon = Icons.Outlined.AirplanemodeActive,
        title = "Airplane mode",
        detail = if (enabled) "On" else "Off",
        description = if (enabled) "Airplane mode on" else "Airplane mode off",
    )
    is CockpitSystemAlert.BiometricFeedback -> when (stage) {
        CockpitBiometricStage.SCANNING -> SystemAlertPresentation(
            icon = Icons.Outlined.Fingerprint,
            title = "Approve laptop",
            detail = "Scanning",
            description = "Approve the laptop connection with your fingerprint or screen lock",
        )
        CockpitBiometricStage.SUCCESS -> SystemAlertPresentation(
            icon = Icons.Outlined.Fingerprint,
            title = "Approved",
            detail = "Verified",
            description = "Laptop connection approved",
        )
        CockpitBiometricStage.ERROR -> SystemAlertPresentation(
            icon = Icons.Outlined.Fingerprint,
            title = "Not approved",
            detail = "Try again",
            description = "Laptop connection was not approved",
        )
        CockpitBiometricStage.DEVICE_UNLOCKED -> SystemAlertPresentation(
            icon = Icons.Outlined.LockOpen,
            title = "Unlocked",
            detail = "",
            description = "Phone unlocked",
        )
    }
    is CockpitSystemAlert.DriveLiftTransfer -> {
        val name = fileName.ifBlank { "Transfer" }
        SystemAlertPresentation(
            icon = Icons.Outlined.CloudDone,
            title = name,
            detail = if (isCompleted) "Done" else "$progressPercent%",
            description = if (isCompleted) {
                "$name transferred"
            } else {
                "$name transferring, $progressPercent percent"
            },
        )
    }
    is CockpitSystemAlert.DriveLiftRadarConnected -> {
        val name = peerName.ifBlank { "Laptop" }
        SystemAlertPresentation(
            icon = Icons.Outlined.LaptopMac,
            title = name,
            detail = "Connected",
            description = "$name connected to Desktop Drop",
        )
    }
}

/** Rim colours for an alert: the accent role resolved against the one theme and the Cockpit palette. */
@Composable
internal fun systemAlertAccentPair(accent: CockpitAlertAccent): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (accent) {
        CockpitAlertAccent.PRIMARY -> scheme.primary.toWideGamutP3() to scheme.tertiary.toWideGamutP3()
        CockpitAlertAccent.INFO -> scheme.secondary.toWideGamutP3() to scheme.tertiary.toWideGamutP3()
        CockpitAlertAccent.POSITIVE ->
            CockpitLivePalette.Emerald.toWideGamutP3() to CockpitLivePalette.Cyan.toWideGamutP3()
        CockpitAlertAccent.WARNING ->
            GlassPalette.HudAmber.toWideGamutP3() to scheme.primary.toWideGamutP3()
        CockpitAlertAccent.ERROR -> scheme.error.toWideGamutP3() to scheme.primary.toWideGamutP3()
    }
}

/**
 * Body of the collapsed pill while a transient alert shows: icon and title on the left, one detail
 * (a percentage or a state) on the right. The title shrinks first, so the detail is never cut off.
 */
@Composable
internal fun RowScope.CollapsedSystemAlertContent(alert: CockpitSystemAlert, accent: Color) {
    val presentation = alert.presentation()
    Row(
        modifier = Modifier
            .weight(1f, fill = false)
            .semantics(mergeDescendants = true) { contentDescription = presentation.description }
            .testTag("cockpit-system-alert"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Snug),
    ) {
        Icon(
            imageVector = presentation.icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(Tokens.IconSmall),
        )
        Text(
            text = presentation.title,
            style = MaterialTheme.typography.labelMedium,
            color = CockpitLivePalette.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .testTag("cockpit-system-alert-title"),
        )
    }
    if (presentation.detail.isNotEmpty()) {
        Spacer(Modifier.width(Tokens.Small))
        Text(
            text = presentation.detail,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = accent,
            maxLines = 1,
            modifier = Modifier.testTag("cockpit-system-alert-detail"),
        )
    }
}

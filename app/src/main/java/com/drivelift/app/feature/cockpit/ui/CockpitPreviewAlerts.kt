package com.drivelift.app.feature.cockpit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.drivelift.app.feature.cockpit.alerts.CockpitBiometricStage
import com.drivelift.app.feature.cockpit.alerts.CockpitRingerMode
import com.drivelift.app.feature.cockpit.alerts.CockpitSystemAlert
import com.drivelift.app.ui.Tokens

/** Simulated transient system alerts the Cockpit Hub can show over the island preview. */
enum class CockpitPreviewAlert(val label: String, val tag: String) {
    CHARGING("Charging", "cockpit-preview-alert-charging"),
    BATTERY_FULL("Battery Full", "cockpit-preview-alert-battery-full"),
    BATTERY_LOW("Battery Low", "cockpit-preview-alert-battery-low"),
    EARBUDS("Earbuds", "cockpit-preview-alert-earbuds"),
    EARBUDS_LOW("Earbuds Low", "cockpit-preview-alert-earbuds-low"),
    RINGER("Ringer", "cockpit-preview-alert-ringer"),
    AIRPLANE("Airplane", "cockpit-preview-alert-airplane"),
    BIOMETRIC("Biometric", "cockpit-preview-alert-biometric"),
    TRANSFER("Transfer", "cockpit-preview-alert-transfer"),
    RADAR("Radar", "cockpit-preview-alert-radar"),
}

/** Synthetic alerts for the preview chips. Nothing here reads the battery, Bluetooth or ringer. */
object CockpitPreviewAlerts {
    private const val CHARGING_PERCENT = 82
    private const val FULL_PERCENT = 100
    private const val LOW_PERCENT = 15
    private const val EARBUDS_PERCENT = 76
    private const val EARBUDS_LOW_PERCENT = 12
    private const val FULL_IN_MINUTES = 40L

    fun alert(preview: CockpitPreviewAlert): CockpitSystemAlert = when (preview) {
        CockpitPreviewAlert.CHARGING -> CockpitSystemAlert.BatteryCharging(
            percent = CHARGING_PERCENT,
            isFastCharging = true,
            estimatedFullMinutes = FULL_IN_MINUTES,
        )
        CockpitPreviewAlert.BATTERY_FULL -> CockpitSystemAlert.BatteryFull(percent = FULL_PERCENT)
        CockpitPreviewAlert.BATTERY_LOW -> CockpitSystemAlert.BatteryLow(percent = LOW_PERCENT, isCritical = false)
        CockpitPreviewAlert.EARBUDS -> CockpitSystemAlert.EarbudsConnected(
            deviceName = "Pixel Buds Pro",
            batteryPercent = EARBUDS_PERCENT,
        )
        CockpitPreviewAlert.EARBUDS_LOW -> CockpitSystemAlert.EarbudsLowBattery(
            deviceName = "Pixel Buds Pro",
            batteryPercent = EARBUDS_LOW_PERCENT,
        )
        CockpitPreviewAlert.RINGER -> CockpitSystemAlert.RingerModeChanged(mode = CockpitRingerMode.SILENT)
        CockpitPreviewAlert.AIRPLANE -> CockpitSystemAlert.AirplaneModeChanged(enabled = true)
        CockpitPreviewAlert.BIOMETRIC -> CockpitSystemAlert.BiometricFeedback(stage = CockpitBiometricStage.SCANNING)
        CockpitPreviewAlert.TRANSFER -> CockpitSystemAlert.DriveLiftTransfer(
            fileName = "IMG_0412.jpg",
            progressPercent = FULL_PERCENT,
            isCompleted = true,
        )
        CockpitPreviewAlert.RADAR -> CockpitSystemAlert.DriveLiftRadarConnected(peerName = "Work laptop")
    }
}

/** One chip per simulated alert. Tapping the selected chip clears the alert again. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CockpitPreviewAlertChips(
    selected: CockpitPreviewAlert?,
    onSelect: (CockpitPreviewAlert?) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .testTag("cockpit-preview-alert-chips"),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
        verticalArrangement = Arrangement.spacedBy(Tokens.Small),
    ) {
        CockpitPreviewAlert.entries.forEach { preview ->
            val isSelected = preview == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(if (isSelected) null else preview) },
                label = { Text(preview.label, maxLines = 1) },
                modifier = Modifier
                    .defaultMinSize(minHeight = Tokens.TouchTarget)
                    .testTag(preview.tag),
            )
        }
    }
}

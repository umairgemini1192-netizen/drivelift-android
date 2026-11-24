package com.drivelift.app.feature.cockpit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.drivelift.app.Graph
import com.drivelift.app.feature.cockpit.hardware.CockpitNetworkFormat
import com.drivelift.app.feature.cockpit.hardware.IdleNetworkKind
import com.drivelift.app.feature.cockpit.hardware.IdleNetworkSnapshot
import com.drivelift.app.ui.CockpitLivePalette
import com.drivelift.app.ui.TABULAR_DIGITS
import com.drivelift.app.ui.Tokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Which speed figures the right zone of the collapsed idle pill displays. */
internal enum class IdleSpeedDisplayMode {
    NONE,
    BOTH,
    UPLOAD_ONLY,
    DOWNLOAD_ONLY,
}

/**
 * Pure zone geometry for the collapsed idle pill:
 * - [leftZoneWidthDp] holds the connection icon and label (start-aligned, ellipsized).
 * - [centerGapDp] stays completely empty over the camera punch-hole cutout plus [Tokens.Small]
 *   padding on each side.
 * - [rightZoneWidthDp] holds the speed figures (end-aligned) and never crosses into [centerGapDp].
 */
internal data class IdlePillZoneLayout(
    val centerGapDp: Float,
    val leftZoneWidthDp: Float,
    val rightZoneWidthDp: Float,
    val totalContentWidthDp: Float,
    val speedDisplayMode: IdleSpeedDisplayMode,
)

/**
 * Pure layout-math helper keeping the idle pill's connection label and live network speeds clear
 * of the camera punch-hole cutout. Nothing here touches Android views.
 */
internal object IdlePillLayoutMath {
    /** Compact rate formatting (`0K`, `999K`, `1.4M`, `999M`) never exceeds 4 characters. */
    const val MAX_RATE_CHARS = 4

    /** Computes the centre cutout gap: `max(0, measuredCutoutWidthDp + cutoutWidthDeltaDp) + 2 * sidePaddingDp`. */
    fun computeCenterGapDp(
        measuredCutoutWidthDp: Float,
        cutoutWidthDeltaDp: Float = 0f,
        sidePaddingDp: Float = Tokens.Small.value,
    ): Float {
        val effectiveCutout = (measuredCutoutWidthDp + cutoutWidthDeltaDp).coerceAtLeast(0f)
        val safePadding = sidePaddingDp.coerceAtLeast(0f)
        return effectiveCutout + (safePadding * 2f)
    }

    /** Estimated width in dp of one 4-char max speed figure (`999K` + gap + triangle). */
    fun singleSpeedFigureWidthDp(
        fontScale: Float = 1f,
        rateChars: Int = MAX_RATE_CHARS,
    ): Float {
        val safeScale = fontScale.coerceAtLeast(0.5f)
        val chars = rateChars.coerceIn(1, MAX_RATE_CHARS)
        val textWidth = chars * Tokens.Cockpit.IdleRateCharWidth.value * safeScale
        return textWidth + Tokens.GridGap.value + Tokens.Cockpit.IdleSpeedArrow.value
    }

    /** Estimated width in dp of two 4-char max speed figures side by side. */
    fun twoSpeedFiguresWidthDp(
        fontScale: Float = 1f,
        figureGapDp: Float = Tokens.Tiny.value,
    ): Float = (singleSpeedFigureWidthDp(fontScale) * 2f) + figureGapDp.coerceAtLeast(0f)

    /**
     * Splits [maxContentWidthDp] into a left zone, a fixed centre cutout gap, and a right zone.
     *
     * When a physical cutout is present (`measuredCutoutWidthDp + cutoutWidthDeltaDp > 0`), the
     * left and right zones are kept strictly equal so the centre gap aligns with the horizontal
     * centre of the pill (and therefore with the camera punch-hole). If two speed figures do not
     * fit inside the right zone, only the larger direction is shown so speeds never slide under
     * the cutout.
     */
    fun compute(
        maxContentWidthDp: Float,
        minContentWidthDp: Float = 0f,
        measuredCutoutWidthDp: Float = 0f,
        cutoutWidthDeltaDp: Float = 0f,
        hasUploadTraffic: Boolean = false,
        hasDownloadTraffic: Boolean = false,
        uploadBytesPerSec: Long = 0L,
        downloadBytesPerSec: Long = 0L,
        fontScale: Float = 1f,
        sidePaddingDp: Float = Tokens.Small.value,
    ): IdlePillZoneLayout {
        val safeMax = maxContentWidthDp.coerceAtLeast(0f)
        val safeMin = minContentWidthDp.coerceIn(0f, safeMax)
        val safeScale = fontScale.coerceAtLeast(0.5f)
        val effectiveCutout = (measuredCutoutWidthDp + cutoutWidthDeltaDp).coerceAtLeast(0f)
        val centerGap = computeCenterGapDp(
            measuredCutoutWidthDp = measuredCutoutWidthDp,
            cutoutWidthDeltaDp = cutoutWidthDeltaDp,
            sidePaddingDp = sidePaddingDp,
        ).coerceAtMost(safeMax)

        val remaining = (safeMax - centerGap).coerceAtLeast(0f)
        val maxSymmetricZone = remaining / 2f
        val minSymmetricZone = ((safeMin - centerGap) / 2f).coerceAtLeast(0f)

        val singleFigureWidth = singleSpeedFigureWidthDp(safeScale)
        val twoFiguresWidth = twoSpeedFiguresWidthDp(safeScale)
        val minLabelZone = Tokens.IconSmall.value + Tokens.Snug.value + (Tokens.Cockpit.IdleCompactLabelWidth.value * safeScale)
        val idleLabelZone = Tokens.IconSmall.value + Tokens.Snug.value + (Tokens.Cockpit.IdleLabelWidth.value * safeScale)

        val activeCount = (if (hasUploadTraffic) 1 else 0) + (if (hasDownloadTraffic) 1 else 0)
        val largerSingleMode = when {
            hasUploadTraffic && !hasDownloadTraffic -> IdleSpeedDisplayMode.UPLOAD_ONLY
            !hasUploadTraffic && hasDownloadTraffic -> IdleSpeedDisplayMode.DOWNLOAD_ONLY
            hasUploadTraffic && hasDownloadTraffic ->
                if (uploadBytesPerSec > downloadBytesPerSec) {
                    IdleSpeedDisplayMode.UPLOAD_ONLY
                } else {
                    IdleSpeedDisplayMode.DOWNLOAD_ONLY
                }
            else -> IdleSpeedDisplayMode.NONE
        }

        if (activeCount == 0) {
            val zone = maxOf(minSymmetricZone, idleLabelZone).coerceAtMost(maxSymmetricZone)
            return IdlePillZoneLayout(
                centerGapDp = centerGap,
                leftZoneWidthDp = zone,
                rightZoneWidthDp = zone,
                totalContentWidthDp = zone + centerGap + zone,
                speedDisplayMode = IdleSpeedDisplayMode.NONE,
            )
        }

        if (effectiveCutout > 0f) {
            // Physical punch-hole present: strictly symmetric zones keep centerGap dead-centre over the camera.
            val (mode, neededRight) = when {
                activeCount == 2 && twoFiguresWidth <= maxSymmetricZone ->
                    IdleSpeedDisplayMode.BOTH to twoFiguresWidth
                singleFigureWidth <= maxSymmetricZone ->
                    largerSingleMode to singleFigureWidth
                else ->
                    IdleSpeedDisplayMode.NONE to 0f
            }
            val zone = maxOf(minSymmetricZone, minLabelZone, neededRight).coerceAtMost(maxSymmetricZone)
            return IdlePillZoneLayout(
                centerGapDp = centerGap,
                leftZoneWidthDp = zone,
                rightZoneWidthDp = zone,
                totalContentWidthDp = zone + centerGap + zone,
                speedDisplayMode = mode,
            )
        }

        // No physical cutout (e.g. non-cutout screen or JVM preview): stay symmetric when both
        // figures fit in half the pill, and only borrow unused left margin at large font scales
        // while keeping the fixed centre gap completely clear.
        val symmetricFitsTwo = activeCount == 2 && twoFiguresWidth <= maxSymmetricZone
        if (symmetricFitsTwo) {
            val zone = maxOf(minSymmetricZone, minLabelZone, twoFiguresWidth).coerceAtMost(maxSymmetricZone)
            return IdlePillZoneLayout(
                centerGapDp = centerGap,
                leftZoneWidthDp = zone,
                rightZoneWidthDp = zone,
                totalContentWidthDp = zone + centerGap + zone,
                speedDisplayMode = IdleSpeedDisplayMode.BOTH,
            )
        }

        val maxRightWithMinLeft = (remaining - minLabelZone).coerceAtLeast(0f)
        if (activeCount == 2 && twoFiguresWidth <= maxRightWithMinLeft) {
            val rightZone = twoFiguresWidth
            val leftZone = (remaining - rightZone).coerceAtLeast(0f)
            return IdlePillZoneLayout(
                centerGapDp = centerGap,
                leftZoneWidthDp = leftZone,
                rightZoneWidthDp = rightZone,
                totalContentWidthDp = leftZone + centerGap + rightZone,
                speedDisplayMode = IdleSpeedDisplayMode.BOTH,
            )
        }

        val mode = if (singleFigureWidth <= maxSymmetricZone) {
            largerSingleMode
        } else if (singleFigureWidth <= maxRightWithMinLeft) {
            largerSingleMode
        } else {
            IdleSpeedDisplayMode.NONE
        }
        if (mode == IdleSpeedDisplayMode.NONE) {
            val zone = maxOf(minSymmetricZone, minLabelZone).coerceAtMost(maxSymmetricZone)
            return IdlePillZoneLayout(
                centerGapDp = centerGap,
                leftZoneWidthDp = zone,
                rightZoneWidthDp = zone,
                totalContentWidthDp = zone + centerGap + zone,
                speedDisplayMode = IdleSpeedDisplayMode.NONE,
            )
        }
        return if (singleFigureWidth <= maxSymmetricZone) {
            val zone = maxOf(minSymmetricZone, minLabelZone, singleFigureWidth).coerceAtMost(maxSymmetricZone)
            IdlePillZoneLayout(
                centerGapDp = centerGap,
                leftZoneWidthDp = zone,
                rightZoneWidthDp = zone,
                totalContentWidthDp = zone + centerGap + zone,
                speedDisplayMode = mode,
            )
        } else {
            val rightZone = singleFigureWidth.coerceAtMost(maxRightWithMinLeft)
            val leftZone = (remaining - rightZone).coerceAtLeast(0f)
            IdlePillZoneLayout(
                centerGapDp = centerGap,
                leftZoneWidthDp = leftZone,
                rightZoneWidthDp = rightZone,
                totalContentWidthDp = leftZone + centerGap + rightZone,
                speedDisplayMode = mode,
            )
        }
    }
}

/**
 * Previews and tests provide a fixed [IdleNetworkSnapshot] here, so the idle pill needs neither the
 * monitor nor a real connection. In the app this stays null and the pill reads the live monitor.
 */
val LocalIdleNetworkOverride = compositionLocalOf<IdleNetworkSnapshot?> { null }

/**
 * The live snapshot for the idle pill. While this is composed the monitor samples throughput once a
 * second and only with the screen on; when the pill leaves the screen the sampling stops.
 */
@Composable
private fun rememberIdleNetworkSnapshot(): IdleNetworkSnapshot {
    val override = LocalIdleNetworkOverride.current
    val context = LocalContext.current
    val monitor = remember(context) { runCatching { Graph.get(context).cockpit.telemetryMonitor }.getOrNull() }
    DisposableEffect(monitor, override) {
        val live = override == null && monitor != null
        if (live) monitor?.acquireIdleNetwork()
        onDispose { if (live) monitor?.releaseIdleNetwork() }
    }
    val fallback = remember { MutableStateFlow(IdleNetworkSnapshot()) }
    val flow: StateFlow<IdleNetworkSnapshot> = monitor?.network ?: fallback
    val live by flow.collectAsState()
    return override ?: live
}

/**
 * The collapsed pill when no live activity is on screen. Splits the pill into a left zone
 * (connection icon and label, start-aligned with end ellipsis), a fixed empty centre gap sized
 * to the measured camera cutout + calibration delta + [Tokens.Small] padding on each side, and a
 * right zone (end-aligned tabular speeds, falling back to the larger direction if both do not fit).
 */
@Composable
internal fun RowScope.CollapsedIdleNetworkContent(
    cutoutWidthDp: Int = 0,
    widthDeltaDp: Int = 0,
    minContentWidth: Dp = Tokens.Cockpit.PillMinWidth - (Tokens.Medium * 2),
    maxContentWidth: Dp = Tokens.Cockpit.PillMaxWidth - (Tokens.Medium * 2),
) {
    val net = rememberIdleNetworkSnapshot()
    val fontScale = LocalDensity.current.fontScale
    val layout = remember(
        cutoutWidthDp,
        widthDeltaDp,
        minContentWidth,
        maxContentWidth,
        net.hasUploadTraffic,
        net.hasDownloadTraffic,
        net.uploadBytesPerSec,
        net.downloadBytesPerSec,
        fontScale,
    ) {
        IdlePillLayoutMath.compute(
            maxContentWidthDp = maxContentWidth.value,
            minContentWidthDp = minContentWidth.value,
            measuredCutoutWidthDp = cutoutWidthDp.toFloat(),
            cutoutWidthDeltaDp = widthDeltaDp.toFloat(),
            hasUploadTraffic = net.hasUploadTraffic,
            hasDownloadTraffic = net.hasDownloadTraffic,
            uploadBytesPerSec = net.uploadBytesPerSec,
            downloadBytesPerSec = net.downloadBytesPerSec,
            fontScale = fontScale,
        )
    }

    val connectionIcon = when (net.kind) {
        IdleNetworkKind.WIFI -> Icons.Outlined.Wifi
        IdleNetworkKind.CELLULAR -> Icons.Outlined.SignalCellularAlt
        IdleNetworkKind.OFFLINE -> Icons.Outlined.WifiOff
    }
    val connectionTint = if (net.kind == IdleNetworkKind.OFFLINE) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.primary
    }

    Box(
        modifier = Modifier
            .width(layout.leftZoneWidthDp.dp)
            .clipToBounds(),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier
                .testTag("cockpit-idle-network")
                .semantics(mergeDescendants = true) { contentDescription = net.label },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Snug),
        ) {
            Icon(
                imageVector = connectionIcon,
                contentDescription = null,
                tint = connectionTint,
                modifier = Modifier.size(Tokens.IconSmall),
            )
            Text(
                text = net.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Start,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    Spacer(
        modifier = Modifier
            .width(layout.centerGapDp.dp)
            .height(Tokens.IconSmall)
            .testTag("cockpit-idle-cutout-gap"),
    )

    Box(
        modifier = Modifier
            .width(layout.rightZoneWidthDp.dp)
            .clipToBounds(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        val showUpload = layout.speedDisplayMode == IdleSpeedDisplayMode.BOTH ||
            layout.speedDisplayMode == IdleSpeedDisplayMode.UPLOAD_ONLY
        val showDownload = layout.speedDisplayMode == IdleSpeedDisplayMode.BOTH ||
            layout.speedDisplayMode == IdleSpeedDisplayMode.DOWNLOAD_ONLY

        if (showUpload || showDownload) {
            Row(
                modifier = Modifier
                    .testTag("cockpit-idle-speed")
                    .semantics(mergeDescendants = true) {
                        val parts = buildList {
                            if (showUpload) {
                                add("Uploading ${CockpitNetworkFormat.spokenRate(net.uploadBytesPerSec)}")
                            }
                            if (showDownload) {
                                add("downloading ${CockpitNetworkFormat.spokenRate(net.downloadBytesPerSec)}")
                            }
                        }
                        contentDescription = parts.joinToString(", ")
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Tiny),
            ) {
                if (showUpload) {
                    SpeedFigure(text = net.uploadText, up = true, tint = CockpitLivePalette.Cyan)
                }
                if (showDownload) {
                    SpeedFigure(text = net.downloadText, up = false, tint = CockpitLivePalette.Emerald)
                }
            }
        }
    }
}

@Composable
private fun SpeedFigure(text: String, up: Boolean, tint: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.GridGap),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = TABULAR_DIGITS,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
        )
        SpeedTriangle(up = up, tint = tint)
    }
}

/** A small filled triangle. Drawn rather than typed so it renders the same on every font. */
@Composable
private fun SpeedTriangle(up: Boolean, tint: Color) {
    Canvas(Modifier.size(Tokens.Cockpit.IdleSpeedArrow)) {
        val w = size.width
        val h = size.height
        val inset = w * 0.12f
        val path = Path().apply {
            if (up) {
                moveTo(w / 2f, inset)
                lineTo(w - inset, h - inset)
                lineTo(inset, h - inset)
            } else {
                moveTo(inset, inset)
                lineTo(w - inset, inset)
                lineTo(w / 2f, h - inset)
            }
            close()
        }
        drawPath(path = path, color = tint)
    }
}

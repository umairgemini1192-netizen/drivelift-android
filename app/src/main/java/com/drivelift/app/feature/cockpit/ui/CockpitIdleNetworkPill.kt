package com.drivelift.app.feature.cockpit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
 * Pure zone geometry for the collapsed idle (and active) pill:
 * - [leftZoneWidthDp] holds the connection/activity icon and label (start-aligned, ellipsized).
 * - [centerGapDp] stays completely empty over the camera punch-hole cutout plus [Tokens.Small]
 *   padding on each side.
 * - [rightZoneWidthDp] holds the speed figures (stacked vertically when both upload and download
 *   are active, or a single centred figure) and never crosses into [centerGapDp].
 * - Priority when space is short: (1) speeds always visible whenever traffic >= 1K (never NONE),
 *   (2) connection icon, (3) connection text label dropped first.
 */
internal data class IdlePillZoneLayout(
    val centerGapDp: Float,
    val leftZoneWidthDp: Float,
    val rightZoneWidthDp: Float,
    val totalContentWidthDp: Float,
    val speedDisplayMode: IdleSpeedDisplayMode,
    val showConnectionIcon: Boolean = true,
    val showConnectionLabel: Boolean = true,
    val isStacked: Boolean = false,
    val useCompactSpeedStyle: Boolean = false,
)

/**
 * Pure layout-math helper keeping the pill's left cluster and live network speeds clear of the
 * camera punch-hole cutout while always keeping speeds visible when traffic >= 1K.
 * Nothing here touches Android views.
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

    /** Estimated width in dp of one 4-char standard single-line speed figure (`999K` + gap + triangle). */
    fun singleSpeedFigureWidthDp(
        fontScale: Float = 1f,
        rateChars: Int = MAX_RATE_CHARS,
    ): Float {
        val safeScale = fontScale.coerceAtLeast(0.5f)
        val chars = rateChars.coerceIn(1, MAX_RATE_CHARS)
        val textWidth = chars * Tokens.Cockpit.IdleRateCharWidth.value * safeScale
        return textWidth + Tokens.GridGap.value + Tokens.Cockpit.IdleSpeedArrow.value
    }

    /**
     * Estimated width in dp of one compact stacked-line speed figure (`999K` + gap + small triangle).
     * Because upload and download stack vertically, two speeds require only a single figure's width.
     */
    fun stackedSpeedFigureWidthDp(
        fontScale: Float = 1f,
        rateChars: Int = MAX_RATE_CHARS,
    ): Float {
        val safeScale = fontScale.coerceAtLeast(0.5f)
        val chars = rateChars.coerceIn(1, MAX_RATE_CHARS)
        val textWidth = chars * Tokens.Cockpit.IdleStackedRateCharWidth.value * safeScale
        return textWidth + Tokens.GridGap.value + Tokens.Cockpit.IdleStackedSpeedArrow.value
    }

    /** True when two stacked speed lines fit vertically inside [Tokens.Cockpit.PillHeight]. */
    fun stackedFitsVertically(
        fontScale: Float = 1f,
        pillHeightDp: Float = Tokens.Cockpit.PillHeight.value,
    ): Boolean {
        val safeScale = fontScale.coerceAtLeast(0.5f)
        val twoLineHeight = (Tokens.Cockpit.IdleStackedRateLineHeight.value * safeScale * 2f) +
            Tokens.GridGap.value
        return twoLineHeight <= pillHeightDp
    }

    /** Estimated width in dp of two 4-char max speed figures side by side (retained for reference). */
    fun twoSpeedFiguresWidthDp(
        fontScale: Float = 1f,
        figureGapDp: Float = Tokens.Tiny.value,
    ): Float = (singleSpeedFigureWidthDp(fontScale) * 2f) + figureGapDp.coerceAtLeast(0f)

    /**
     * Splits [maxContentWidthDp] into a left zone, a fixed centre cutout gap, and a right zone.
     *
     * Rules:
     * 1. When both upload and download have traffic (`>= 1K`), stack them vertically (upload top,
     *    download bottom) inside [Tokens.Cockpit.PillHeight]. Stacked width equals one compact
     *    figure, so both directions fit beside the Pixel 8 Pro cutout at font scales 1.0 and 1.3
     *    (and 1.5). At extreme font scales where two lines would clip vertically or horizontally,
     *    degrade cleanly to the larger single direction rather than overlapping.
     * 2. Priority when space is short: (1) speeds ALWAYS visible while traffic exists (never
     *    returns [IdleSpeedDisplayMode.NONE] while traffic >= 1K), (2) connection icon, (3) text
     *    label dropped first.
     * 3. Symmetric widening: both zones grow equally up to [maxContentWidthDp] (the status-bar
     *    safe cap) before dropping the text label, keeping [centerGapDp] centred over the cutout.
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

        val iconOnlyWidth = Tokens.IconSmall.value
        val minLabelZone = iconOnlyWidth + Tokens.Snug.value + (Tokens.Cockpit.IdleCompactLabelWidth.value * safeScale)
        val idleLabelZone = iconOnlyWidth + Tokens.Snug.value + (Tokens.Cockpit.IdleLabelWidth.value * safeScale)
        val stackedFigureWidth = stackedSpeedFigureWidthDp(safeScale)
        val singleFigureWidth = singleSpeedFigureWidthDp(safeScale)
        val canStackVertically = stackedFitsVertically(safeScale)

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
                showConnectionIcon = zone >= iconOnlyWidth,
                showConnectionLabel = zone >= minLabelZone,
                isStacked = false,
                useCompactSpeedStyle = false,
            )
        }

        // Traffic exists: speeds are Priority 1 and must NEVER return NONE.
        val bothFitSymmetric = activeCount == 2 && canStackVertically && stackedFigureWidth <= maxSymmetricZone
        val bothFitAsymmetric = effectiveCutout <= 0f &&
            activeCount == 2 &&
            canStackVertically &&
            stackedFigureWidth <= (remaining - iconOnlyWidth).coerceAtLeast(0f)

        if (effectiveCutout > 0f) {
            // Physical punch-hole present: keep left & right zones strictly symmetric so the
            // centre cutout gap stays dead-centre over the camera. Widen symmetrically up to
            // maxSymmetricZone (status-bar safe cap) so full labels like "Wi-Fi" fit before
            // truncating or dropping the text label.
            // If a tight width budget beside the cutout would starve a compact speed figure,
            // symmetrically reclaim excess side safety padding down to Tokens.Tiny (4dp per side)
            // while never entering the physical cutout itself.
            val minSafeGap = (effectiveCutout + (Tokens.Tiny.value * 2f)).coerceAtMost(safeMax)
            val effectiveGap = if (stackedFigureWidth > maxSymmetricZone && centerGap > minSafeGap) {
                val deficitPerZone = stackedFigureWidth - maxSymmetricZone
                (centerGap - (deficitPerZone * 2f)).coerceAtLeast(minSafeGap)
            } else {
                centerGap
            }
            val effectiveMaxZone = ((safeMax - effectiveGap).coerceAtLeast(0f)) / 2f
            val effectiveMinZone = ((safeMin - effectiveGap) / 2f).coerceAtLeast(0f)
            val bothFitEffective = activeCount == 2 && canStackVertically && stackedFigureWidth <= effectiveMaxZone

            val (mode, stacked, compact, neededRight) = when {
                bothFitEffective -> Quad(IdleSpeedDisplayMode.BOTH, true, true, stackedFigureWidth)
                singleFigureWidth <= effectiveMaxZone -> Quad(largerSingleMode, false, false, singleFigureWidth)
                else -> Quad(largerSingleMode, false, true, minOf(stackedFigureWidth, effectiveMaxZone))
            }
            val desiredLeft = when {
                idleLabelZone <= effectiveMaxZone -> idleLabelZone
                minLabelZone <= effectiveMaxZone -> minLabelZone
                else -> iconOnlyWidth
            }
            val zone = maxOf(effectiveMinZone, desiredLeft, neededRight).coerceAtMost(effectiveMaxZone)
            return IdlePillZoneLayout(
                centerGapDp = effectiveGap,
                leftZoneWidthDp = zone,
                rightZoneWidthDp = zone,
                totalContentWidthDp = zone + effectiveGap + zone,
                speedDisplayMode = mode,
                showConnectionIcon = zone >= iconOnlyWidth,
                showConnectionLabel = zone >= minLabelZone,
                isStacked = stacked,
                useCompactSpeedStyle = compact,
            )
        }

        // No physical cutout: prefer symmetric zones, widening up to maxSymmetricZone first,
        // and only borrowing from the left zone if extreme font/compact width requires it
        // (dropping the connection text label before the icon, and never dropping speeds).
        if (bothFitSymmetric) {
            val desiredLeft = when {
                idleLabelZone <= maxSymmetricZone -> idleLabelZone
                minLabelZone <= maxSymmetricZone -> minLabelZone
                else -> iconOnlyWidth
            }
            val zone = maxOf(minSymmetricZone, desiredLeft, stackedFigureWidth).coerceAtMost(maxSymmetricZone)
            return IdlePillZoneLayout(
                centerGapDp = centerGap,
                leftZoneWidthDp = zone,
                rightZoneWidthDp = zone,
                totalContentWidthDp = zone + centerGap + zone,
                speedDisplayMode = IdleSpeedDisplayMode.BOTH,
                showConnectionIcon = zone >= iconOnlyWidth,
                showConnectionLabel = zone >= minLabelZone,
                isStacked = true,
                useCompactSpeedStyle = true,
            )
        }

        if (bothFitAsymmetric) {
            val rightZone = stackedFigureWidth.coerceAtMost(remaining)
            val leftZone = (remaining - rightZone).coerceAtLeast(0f)
            return IdlePillZoneLayout(
                centerGapDp = centerGap,
                leftZoneWidthDp = leftZone,
                rightZoneWidthDp = rightZone,
                totalContentWidthDp = leftZone + centerGap + rightZone,
                speedDisplayMode = IdleSpeedDisplayMode.BOTH,
                showConnectionIcon = leftZone >= iconOnlyWidth,
                showConnectionLabel = leftZone >= minLabelZone,
                isStacked = true,
                useCompactSpeedStyle = true,
            )
        }

        // Single direction (either only 1 active direction, or degraded from BOTH at extreme scale).
        if (singleFigureWidth <= maxSymmetricZone) {
            val desiredLeft = if (minLabelZone <= maxSymmetricZone) minLabelZone else iconOnlyWidth
            val zone = maxOf(minSymmetricZone, desiredLeft, singleFigureWidth).coerceAtMost(maxSymmetricZone)
            return IdlePillZoneLayout(
                centerGapDp = centerGap,
                leftZoneWidthDp = zone,
                rightZoneWidthDp = zone,
                totalContentWidthDp = zone + centerGap + zone,
                speedDisplayMode = largerSingleMode,
                showConnectionIcon = zone >= iconOnlyWidth,
                showConnectionLabel = zone >= minLabelZone,
                isStacked = false,
                useCompactSpeedStyle = false,
            )
        }

        val compactNeeded = stackedFigureWidth.coerceAtMost(remaining)
        val rightZone = if (singleFigureWidth <= (remaining - iconOnlyWidth).coerceAtLeast(0f)) {
            singleFigureWidth
        } else {
            compactNeeded
        }
        val compact = rightZone < singleFigureWidth
        val leftZone = (remaining - rightZone).coerceAtLeast(0f)
        return IdlePillZoneLayout(
            centerGapDp = centerGap,
            leftZoneWidthDp = leftZone,
            rightZoneWidthDp = rightZone,
            totalContentWidthDp = leftZone + centerGap + rightZone,
            speedDisplayMode = largerSingleMode,
            showConnectionIcon = leftZone >= iconOnlyWidth,
            showConnectionLabel = leftZone >= minLabelZone,
            isStacked = false,
            useCompactSpeedStyle = compact,
        )
    }

    private data class Quad(
        val mode: IdleSpeedDisplayMode,
        val stacked: Boolean,
        val compact: Boolean,
        val neededRight: Float,
    )
}

/**
 * Previews and tests provide a fixed [IdleNetworkSnapshot] here, so the idle pill needs neither the
 * monitor nor a real connection. In the app this stays null and the pill reads the live monitor.
 */
val LocalIdleNetworkOverride = compositionLocalOf<IdleNetworkSnapshot?> { null }

/**
 * The live snapshot for the collapsed pill and expanded info strip. While this is composed the
 * monitor samples throughput once a second and only with the screen on; when it leaves the screen
 * the sampling stops.
 */
@Composable
internal fun rememberIdleNetworkSnapshot(): IdleNetworkSnapshot {
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
 * (connection icon and optional label, start-aligned with end ellipsis), a fixed empty centre gap
 * sized to the measured camera cutout + calibration delta + [Tokens.Small] padding on each side,
 * and a right zone (stacked up+down tabular speeds when both directions have traffic, or a single
 * centred-vertical figure when only one direction is active or at extreme font scales).
 */
@Composable
internal fun RowScope.CollapsedIdleNetworkContent(
    cutoutWidthDp: Int = 0,
    widthDeltaDp: Int = 0,
    minContentWidth: Dp = Tokens.Cockpit.PillMinWidth - (Tokens.Medium * 2),
    maxContentWidth: Dp = Tokens.Cockpit.PillMaxWidth - (Tokens.Medium * 2),
    networkSnapshot: IdleNetworkSnapshot? = null,
) {
    val net = networkSnapshot ?: rememberIdleNetworkSnapshot()
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
            if (layout.showConnectionIcon) {
                Icon(
                    imageVector = connectionIcon,
                    contentDescription = null,
                    tint = connectionTint,
                    modifier = Modifier.size(Tokens.IconSmall),
                )
            }
            if (layout.showConnectionLabel) {
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
        CollapsedLiveSpeedCluster(net = net, layout = layout)
    }
}

/**
 * Renders live upload/download speeds in the right zone of any collapsed pill:
 * - Both active & fits: stacked vertically (upload top in cyan, download bottom in emerald).
 * - Single active or extreme font scale: single centred-vertical figure.
 * - Idle: draws nothing.
 * Spoken contentDescription always lists every active direction.
 */
@Composable
internal fun CollapsedLiveSpeedCluster(
    net: IdleNetworkSnapshot,
    layout: IdlePillZoneLayout,
    modifier: Modifier = Modifier,
) {
    val showUpload = layout.speedDisplayMode == IdleSpeedDisplayMode.BOTH ||
        layout.speedDisplayMode == IdleSpeedDisplayMode.UPLOAD_ONLY
    val showDownload = layout.speedDisplayMode == IdleSpeedDisplayMode.BOTH ||
        layout.speedDisplayMode == IdleSpeedDisplayMode.DOWNLOAD_ONLY

    if (!showUpload && !showDownload) return

    val spokenDescription = remember(
        net.hasUploadTraffic,
        net.hasDownloadTraffic,
        net.uploadBytesPerSec,
        net.downloadBytesPerSec,
    ) {
        val parts = buildList {
            if (net.hasUploadTraffic) {
                add("Uploading ${CockpitNetworkFormat.spokenRate(net.uploadBytesPerSec)}")
            }
            if (net.hasDownloadTraffic) {
                add("downloading ${CockpitNetworkFormat.spokenRate(net.downloadBytesPerSec)}")
            }
        }
        parts.joinToString(", ")
    }

    if (layout.isStacked && showUpload && showDownload) {
        Column(
            modifier = modifier
                .testTag("cockpit-idle-speed")
                .semantics(mergeDescendants = true) { contentDescription = spokenDescription },
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(Tokens.GridGap, Alignment.CenterVertically),
        ) {
            SpeedFigure(
                text = net.uploadText,
                up = true,
                tint = CockpitLivePalette.Cyan,
                compact = true,
            )
            SpeedFigure(
                text = net.downloadText,
                up = false,
                tint = CockpitLivePalette.Emerald,
                compact = true,
            )
        }
    } else {
        Row(
            modifier = modifier
                .testTag("cockpit-idle-speed")
                .semantics(mergeDescendants = true) { contentDescription = spokenDescription },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Tiny),
        ) {
            if (showUpload) {
                SpeedFigure(
                    text = net.uploadText,
                    up = true,
                    tint = CockpitLivePalette.Cyan,
                    compact = layout.useCompactSpeedStyle,
                )
            }
            if (showDownload) {
                SpeedFigure(
                    text = net.downloadText,
                    up = false,
                    tint = CockpitLivePalette.Emerald,
                    compact = layout.useCompactSpeedStyle,
                )
            }
        }
    }
}

@Composable
internal fun SpeedFigure(
    text: String,
    up: Boolean,
    tint: Color,
    compact: Boolean = false,
) {
    val textStyle = if (compact) {
        MaterialTheme.typography.labelSmall.copy(
            fontSize = Tokens.Cockpit.IdleStackedRateTextSize,
            lineHeight = Tokens.Cockpit.IdleStackedRateLineHeight,
            fontWeight = FontWeight.SemiBold,
            fontFeatureSettings = TABULAR_DIGITS,
        )
    } else {
        MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.SemiBold,
            fontFeatureSettings = TABULAR_DIGITS,
        )
    }
    val arrowSize = if (compact) Tokens.Cockpit.IdleStackedSpeedArrow else Tokens.Cockpit.IdleSpeedArrow
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.GridGap),
    ) {
        Text(
            text = text,
            style = textStyle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
        )
        SpeedTriangle(up = up, tint = tint, size = arrowSize)
    }
}

/** A small filled triangle. Drawn rather than typed so it renders the same on every font. */
@Composable
private fun SpeedTriangle(up: Boolean, tint: Color, size: Dp = Tokens.Cockpit.IdleSpeedArrow) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
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

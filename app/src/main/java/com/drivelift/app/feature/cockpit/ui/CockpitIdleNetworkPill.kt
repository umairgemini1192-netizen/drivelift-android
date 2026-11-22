package com.drivelift.app.feature.cockpit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.drivelift.app.Graph
import com.drivelift.app.feature.cockpit.hardware.CockpitNetworkFormat
import com.drivelift.app.feature.cockpit.hardware.IdleNetworkKind
import com.drivelift.app.feature.cockpit.hardware.IdleNetworkSnapshot
import com.drivelift.app.ui.CockpitLivePalette
import com.drivelift.app.ui.Tokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
 * The collapsed pill when no live activity is on screen. Left of the camera cutout: the active
 * connection. Right of it: upload and download rate, for example `12K` with a triangle up and
 * `1.4M` with a triangle down.
 */
@Composable
internal fun RowScope.CollapsedIdleNetworkContent() {
    val net = rememberIdleNetworkSnapshot()
    val connectionIcon = when (net.kind) {
        IdleNetworkKind.WIFI -> Icons.Outlined.Wifi
        IdleNetworkKind.CELLULAR -> Icons.Outlined.SignalCellularAlt
        IdleNetworkKind.OFFLINE -> Icons.Outlined.WifiOff
    }
    val connectionTint = if (net.kind == IdleNetworkKind.OFFLINE) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.tertiary
    }
    Row(
        modifier = Modifier
            .weight(1f, fill = false)
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Spacer(Modifier.width(Tokens.Small))
    if (net.kind != IdleNetworkKind.OFFLINE) {
        Row(
            modifier = Modifier
                .testTag("cockpit-idle-speed")
                .semantics(mergeDescendants = true) {
                    contentDescription = "Uploading ${CockpitNetworkFormat.spokenRate(net.uploadBytesPerSec)}, " +
                        "downloading ${CockpitNetworkFormat.spokenRate(net.downloadBytesPerSec)}"
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Tiny),
        ) {
            SpeedFigure(text = net.uploadText, up = true, tint = CockpitLivePalette.Cyan)
            Spacer(Modifier.width(Tokens.Tiny))
            SpeedFigure(text = net.downloadText, up = false, tint = CockpitLivePalette.Emerald)
        }
    }
}

@Composable
private fun SpeedFigure(text: String, up: Boolean, tint: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Tiny),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum",
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        SpeedTriangle(up = up, tint = tint)
    }
}

/** A small filled triangle. Drawn rather than typed so it renders the same on every font. */
@Composable
private fun SpeedTriangle(up: Boolean, tint: Color) {
    Canvas(Modifier.size(CockpitTokens.IdleSpeedArrow)) {
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

package com.drivelift.app.feature.cockpit.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Dimensions and animation constants dedicated to the DriveLift Cockpit Dynamic Island overlay
 * and Home Screen Master Widget preview, keeping composable files free of raw dp literals.
 */
object CockpitTokens {
    val PillHeight = 36.dp
    val BubbleSize = 36.dp
    val PillMinWidth = 176.dp
    val PillMaxWidth = 272.dp
    val SplitGap = 8.dp
    val ExpandedMaxWidth = 348.dp
    val PreviewBackdropHeight = 96.dp
    val StatusDot = 8.dp
    val AppShortcutTile = 44.dp
    val IslandBadgeShape = RoundedCornerShape(18.dp)
    val ExpandedCardShape = RoundedCornerShape(28.dp)
    val WidgetCardShape = RoundedCornerShape(24.dp)

    const val RIM_PULSE_MS = 3_600
    const val EXPAND_SWIPE_COLLAPSE_PX = -44f
    const val CYCLE_SWIPE_THRESHOLD_PX = 48f
    const val OVERLAY_BLUR_RADIUS_PX = 32
}

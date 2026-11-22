package com.drivelift.app.feature.cockpit.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Dimensions and animation constants dedicated to the DriveLift Cockpit Dynamic Island overlay
 * and Home Screen Master Widget preview, keeping composable files free of raw dp literals.
 */
object CockpitTokens {
    val PillHeight = 36.dp
    val PillTouchTargetHeight = 52.dp
    val BubbleSize = 36.dp
    val PillMinWidth = 176.dp
    val PillMaxWidth = 272.dp
    val SplitGap = 8.dp
    val ExpandedMaxWidth = 348.dp
    val ExpandedDefaultHeight = 360.dp
    val PreviewBackdropHeight = 96.dp
    val StatusDot = 8.dp
    val AppShortcutTile = 44.dp
    val QuickTileMinHeight = 52.dp
    val LiquidSliderHeight = 48.dp
    val SosPillMinWidth = 68.dp
    val IconStrokeWidth = 2.dp
    val MorphBackdropHorizontalPad = 12.dp
    val MorphCollapsedCornerRadius = 22.dp
    val MorphExpandedCornerRadius = 28.dp
    val WidgetBadgeIconSize = 14.dp
    val WidgetAppIconSize = 16.dp
    val HudOuterRadius = 26.dp
    val HudInnerRadius = 18.dp
    val HudTileRadius = 16.dp
    val HudControlHeight = 48.dp
    val HudGaugeSize = 58.dp
    val HudDockIconHaloSize = 28.dp
    val HudStatusDotSize = 8.dp
    val HudWallpaperPad = 10.dp
    val HudPillSpacing = 6.dp
    val IslandBadgeShape = RoundedCornerShape(18.dp)
    val QuickDeckShape = RoundedCornerShape(16.dp)
    val ExpandedCardShape = RoundedCornerShape(28.dp)
    val WidgetCardShape = RoundedCornerShape(24.dp)
    val HudOuterShape = RoundedCornerShape(26.dp)
    val HudInnerShape = RoundedCornerShape(18.dp)
    val HudTileShape = RoundedCornerShape(16.dp)

    const val RIM_PULSE_MS = 3_600
    const val EXPAND_SWIPE_COLLAPSE_PX = -44f
    const val CYCLE_SWIPE_THRESHOLD_PX = 48f
    const val OVERLAY_BLUR_RADIUS_PX = 32
    const val MORPH_SPRING_DAMPING = 0.80f
    const val MORPH_SPRING_STIFFNESS = 420f
    const val TELEMETRY_TICK_INTERVAL_MS = 20_000L
    const val RAM_DELTA_THRESHOLD_GB = 0.1
    const val SHIMMER_DURATION_MS = 1_100
}

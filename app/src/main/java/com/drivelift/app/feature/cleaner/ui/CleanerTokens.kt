package com.drivelift.app.feature.cleaner.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Dimensions, shapes, and motion constants for the Phase 3 Cleaner Dashboard,
 * Sleep Mode screen, and Guided Progress Dynamic Island pill.
 */
object CleanerTokens {
    val HeroShape = RoundedCornerShape(28.dp)
    val CardShape = RoundedCornerShape(22.dp)
    val AccordionShape = RoundedCornerShape(20.dp)
    val DeepCleanCardShape = RoundedCornerShape(20.dp)
    val PillShape = RoundedCornerShape(28.dp)
    val CapacityBarShape = RoundedCornerShape(percent = 50)
    val BadgeShape = RoundedCornerShape(percent = 50)

    val SafetyRailWidth = 4.dp
    val CapacityBarHeight = 12.dp
    val CapacitySegmentGap = 2.dp
    val LegendDotSize = 10.dp
    val StatusDotSize = 8.dp
    val PillProgressHeight = 6.dp
    val HeroMinHeight = 196.dp
    val DeepCleanCardMinWidth = 148.dp
    val ScoreBadgeMinWidth = 44.dp
    val CompactStackWidthDp = 360

    const val ODOMETER_SPRING_DAMPING = 0.86f
    const val ODOMETER_SPRING_STIFFNESS = 180f
    const val MOOD_TRANSITION_MS = 450
    const val ACCORDION_EXPAND_MS = 220
}

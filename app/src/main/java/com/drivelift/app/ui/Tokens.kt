package com.drivelift.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Every size, gap, shape and duration the screens use. Screens never write a raw dp or
 * millisecond value: they name one of these, so a change here changes the whole app at once.
 * Spacing follows a 4 dp rhythm; the few values off it are named for the one job they do.
 */
object Tokens {
    // Spacing
    val Tiny = 4.dp
    val Snug = 6.dp
    val Small = 8.dp
    val Row = 10.dp
    val Medium = 12.dp
    val RowLoose = 14.dp
    val Gutter = 16.dp
    val Edge = 20.dp
    val Section = 24.dp
    val Header = 28.dp
    val EmptyState = 40.dp

    // Sizes
    val TouchTarget = 48.dp
    val Icon = 24.dp
    val IconSmall = 18.dp
    val Badge = 20.dp
    val BadgePadding = 3.dp
    val FolderThumb = 60.dp
    val GridCell = 104.dp
    val DriveGridCell = 132.dp
    val GridGap = 2.dp
    val DriveGridGap = 8.dp
    val ViewerControl = 48.dp
    val ViewerProgressWidth = 220.dp
    val ViewerTopClearance = 92.dp
    val ReadableWidth = 480.dp
    val GaugeWidth = 320.dp
    val GaugeHeight = 172.dp
    val ProgressHeight = 6.dp

    /** Video player: the lead Play/Pause disc (rule 6) and the smaller wells around it (rule 7). */
    val PlayerLead = 56.dp
    val PlayerWell = 44.dp

    /** Video scrubber thumb, round and joined to the track. */
    val ScrubberThumb = 20.dp
    val AutoScrollEdge = 72.dp

    // Strokes
    val Hairline = 1.dp
    val Stroke = 2.dp
    val SelectionStroke = 3.dp
    val SelectedInset = 10.dp
    val NoInset = 0.dp
    val TagPaddingHorizontal = 5.dp

    // Shapes
    val PanelShape = RoundedCornerShape(20.dp)
    val BarShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val ThumbShape = RoundedCornerShape(14.dp)
    val TagShape = RoundedCornerShape(4.dp)

    /** Capsule pills for compact controls (speed, Fit, Step). */
    val PillShape = RoundedCornerShape(percent = 50)
    val SelectedCorner = 8.dp

    /** At this font scale and above, a row with text beside a button stacks them instead. */
    const val STACK_AT_FONT_SCALE = 1.3f

    /** Below this screen width in dp, the same stacking applies. */
    const val COMPACT_WIDTH_DP = 360

    /** Durations in milliseconds. */
    object Motion {
        const val SELECTION = 160
        const val PROGRESS = 300

        /** Video player controls step aside after this long of playback without a touch. */
        const val VIDEO_CONTROLS_HIDE = 3_500

        /** How often the video scrubber and times are refreshed (state feedback only). */
        const val VIDEO_POSITION_REFRESH = 250

        /**
         * A step seek that has not shown its frame after this long stops holding back the next
         * step (for example a seek the player resolved without drawing a new frame).
         */
        const val VIDEO_STEP_TIMEOUT = 2_000

        /**
         * Media the player keeps behind the playing position, so stepping or skipping back a
         * little reuses what was already streamed instead of fetching it from Drive again.
         */
        const val VIDEO_BACK_BUFFER = 15_000
    }
}

/** User-selected thumbnail size / column density shared across Phone and Drive grids. */
enum class GridDensity(
    val phoneMinCell: Dp,
    val driveMinCell: Dp,
    val label: String,
) {
    LARGE(160.dp, 220.dp, "Large thumbnails"),
    MEDIUM(104.dp, 132.dp, "Medium thumbnails"),
    SMALL(78.dp, 96.dp, "Small thumbnails"),
    COMPACT(60.dp, 76.dp, "Compact thumbnails"),
    ;

    fun zoomOutward(): GridDensity = when (this) {
        COMPACT -> SMALL
        SMALL -> MEDIUM
        MEDIUM, LARGE -> LARGE
    }

    fun zoomInward(): GridDensity = when (this) {
        LARGE -> MEDIUM
        MEDIUM -> SMALL
        SMALL, COMPACT -> COMPACT
    }

    fun nextAccessible(): GridDensity = when (this) {
        LARGE -> COMPACT
        MEDIUM -> LARGE
        SMALL -> MEDIUM
        COMPACT -> SMALL
    }

    companion object {
        fun fromStored(name: String?): GridDensity =
            entries.firstOrNull { it.name == name } ?: MEDIUM
    }
}

/** True when animations should be skipped: values and layout change at once. */
val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * One place for motion: every animation uses a [Tokens.Motion] duration with a standard ease,
 * and snaps instead when the phone has animations turned off.
 */
object AppMotion {
    @Composable
    @ReadOnlyComposable
    fun <T> spec(durationMillis: Int): FiniteAnimationSpec<T> =
        if (LocalReducedMotion.current) snap() else tween(durationMillis, easing = FastOutSlowInEasing)
}

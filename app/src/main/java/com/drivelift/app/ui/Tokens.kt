package com.drivelift.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Single consolidated source of spacing, sizes, concentric shapes, and durations across the entire
 * app (including Cockpit, Cleaner/Sleep, and App Lock). Screens never write raw dp or millisecond
 * values: they reference these tokens so design updates stay uniform everywhere.
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
    val BentoIconWell = 38.dp
    val DriveFolderIconWell = 40.dp
    val DropGridIconWell = 44.dp
    val DropRemoveBadge = 28.dp
    val StatusDot = 8.dp
    val GridCell = 104.dp
    val DriveGridCell = 132.dp
    val GridGap = 2.dp
    val DriveGridGap = 8.dp
    val ViewerControl = 48.dp
    val ViewerProgressWidth = 220.dp
    val ViewerTopClearance = 92.dp
    val SwipeDismissThreshold = 120.dp
    val VideoHeaderScrimPadding = 24.dp
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

    /** How far a folder slides toward the swipe edge at the end of a predictive back gesture. */
    val BackPreviewShift = 24.dp

    /** How far a new folder or screen slides in from the side as it arrives. */
    val NavigateShift = 48.dp

    /** Height of the electric cyan glow around a selected thumbnail. */
    val SelectGlow = 10.dp

    // Strokes & Glass Borders
    val Hairline = 1.dp
    val GlassBorder = 1.dp
    val GlassBorderIsland = 1.25.dp
    val GlassBorderHero = 1.5.dp
    val Stroke = 2.dp
    val SelectionStroke = 3.dp
    val SelectedInset = 10.dp
    val NoInset = 0.dp
    val TagPaddingHorizontal = 5.dp

    // Glass Blur (API 31+ RenderEffect radii in pixels)
    const val GLASS_BLUR_RADIUS_PX = 24f
    const val BACKDROP_BLUR_RADIUS_PX = 32f

    // Concentric Radii & Shapes
    val PanelRadius = 20.dp
    val BarRadius = 28.dp
    val IslandRadius = 28.dp
    val HeroRadius = 28.dp
    val CardRadius = 22.dp

    /** Computes a mathematically concentric inner corner radius (`outer - inset`). */
    fun concentricRadius(outer: Dp, inset: Dp): Dp = (outer - inset).coerceAtLeast(Tiny)

    /** Builds a rounded shape concentric with an outer container of [outer] padded by [inset]. */
    fun concentricShape(outer: Dp, inset: Dp): RoundedCornerShape =
        RoundedCornerShape(concentricRadius(outer, inset))

    val PanelShape = RoundedCornerShape(PanelRadius)
    val BarShape = RoundedCornerShape(topStart = BarRadius, topEnd = BarRadius)
    val IslandShape = RoundedCornerShape(IslandRadius)
    val HeroShape = RoundedCornerShape(HeroRadius)
    val ThumbShape = RoundedCornerShape(14.dp)
    val TagShape = RoundedCornerShape(4.dp)

    /** Capsule pills for compact controls (speed, Fit, Step). */
    val PillShape = RoundedCornerShape(percent = 50)
    val SelectedCorner = 8.dp

    /** At this font scale and above, a row with text beside a button stacks them instead. */
    const val STACK_AT_FONT_SCALE = 1.3f

    /** Below this screen width in dp, the same stacking applies. */
    const val COMPACT_WIDTH_DP = 360

    /** At or above this screen width in dp (when not at large font scale), Desktop Drop's Share Queue grid uses 3 columns. */
    const val DROP_GRID_THREE_COL_WIDTH_DP = 420

    /** Durations in milliseconds. */
    object Motion {
        const val SELECTION = 160
        const val PROGRESS = 300

        /** A new level (folder, Settings, another tab) sliding in, and a bottom bar rising. */
        const val NAVIGATE = 260

        /** A thumbnail fading and growing in once its picture has loaded. */
        const val THUMB_IN = 220

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

    /** Consolidated tokens for Cockpit Dynamic Island overlay and Home Screen Master Widget. */
    object Cockpit {
        val PillHeight = 36.dp
        val PillTouchTargetHeight = 52.dp
        val BubbleSize = 36.dp
        val PillMinWidth = 148.dp
        val PillMaxWidth = 224.dp
        val PillHardMaxWidth = 224.dp
        val StatusBarSafeSideMargin = 68.dp
        val StatusBarCompactSideMargin = 48.dp
        val WidgetStatusPillMaxWidth = 124.dp
        val SplitGap = 8.dp
        val ExpandedMaxWidth = 348.dp
        val ExpandedDefaultHeight = 360.dp
        val PreviewBackdropHeight = 96.dp
        val StatusDot = 8.dp
        val AppShortcutTile = 44.dp
        val QuickTileMinHeight = 48.dp
        val LiquidSliderHeight = 48.dp
        val SosPillMinWidth = 68.dp
        val IconStrokeWidth = 2.dp
        val MorphBackdropHorizontalPad = 12.dp
        val MorphCollapsedCornerRadius = 22.dp
        val MorphExpandedCornerRadius = IslandRadius
        val WidgetBadgeIconSize = 14.dp
        val WidgetAppIconSize = 16.dp
        val HudOuterRadius = 26.dp
        val HudInnerRadius = concentricRadius(HudOuterRadius, Small) // 18.dp concentric
        val HudTileRadius = concentricRadius(HudOuterRadius, Row) // 16.dp concentric
        val HudControlHeight = 48.dp
        val HudGaugeSize = 58.dp
        val HudDockIconHaloSize = 28.dp
        val HudStatusDotSize = 8.dp
        val HudWallpaperPad = 10.dp
        val HudPillSpacing = 6.dp
        val HudPagerArrowWidth = 26.dp
        val HudPagerDotGap = 3.dp
        val IdleSpeedArrow = 12.dp
        val IdleStackedSpeedArrow = 8.dp
        val IdleRateCharWidth = 7.dp
        val IdleStackedRateCharWidth = 6.dp
        val IdleStackedRateTextSize = 10.sp
        val IdleStackedRateLineHeight = 11.sp
        val IdleCompactLabelWidth = 29.dp
        val IdleLabelWidth = 42.dp
        val WifiSsidMarqueeVelocity = 24.dp
        val PinnedScrollFadeWidth = 28.dp
        val NavProgressHairline = 2.dp
        val NavTripTextSize = 11.sp
        val WidgetClockTextSize = 34.sp
        val CallAvatar = 44.dp
        val CallActionButton = 48.dp
        val CallControlStandard = 52.dp
        val CallControlCompact = 44.dp
        val CallInfoButton = 36.dp
        val SatelliteIcon = 16.dp
        val WaveformHeight = 18.dp
        val WaveformBarWidth = 3.dp
        val WaveformGap = 3.dp
        val RecordingDot = 8.dp
        val RecordingStopBox = 14.dp
        val StopRingWidth = 2.dp
        val HeaderActionWell = 36.dp
        val NavTurnBadgeSize = 48.dp
        val NavHudCellRadius = 12.dp
        val PagerDot = 5.dp
        val SpecularRimWidth = GlassBorderIsland
        val IslandBadgeShape = RoundedCornerShape(18.dp)
        val QuickDeckShape = concentricShape(IslandRadius, Medium) // 16.dp concentric
        val ExpandedCardShape = IslandShape
        val WidgetCardShape = RoundedCornerShape(24.dp)
        val HudOuterShape = RoundedCornerShape(HudOuterRadius)
        val HudInnerShape = RoundedCornerShape(HudInnerRadius)
        val HudTileShape = RoundedCornerShape(HudTileRadius)

        const val RIM_PULSE_MS = 3_600
        const val EXPAND_SWIPE_COLLAPSE_PX = -44f
        const val COLLAPSED_SWIPE_DISMISS_PX = -24f
        const val CYCLE_SWIPE_THRESHOLD_PX = 48f
        const val OVERLAY_BLUR_RADIUS_PX = 32
        const val MORPH_SPRING_DAMPING = 0.80f
        const val MORPH_SPRING_STIFFNESS = 420f
        const val TELEMETRY_TICK_INTERVAL_MS = 2_000L
        const val WIDGET_PUSH_DEBOUNCE_MS = 2_000L
        const val RAM_DELTA_THRESHOLD_GB = 0.1
        const val SHIMMER_DURATION_MS = 1_100
        const val WAVEFORM_PERIOD_MS = 1_800
        const val ANSWER_PULSE_MS = 1_100
        const val RECORDING_PULSE_MS = 1_000
        const val SWAP_SLIDE_MS = 240
        const val SWAP_DRAG_DAMPING = 0.35f
        const val COMPACT_CALL_WIDTH_DP = 300
        const val CALL_AVATAR_PX = 128
        const val TICK_INTERVAL_MS = 1_000L
        const val AUDIO_REFRESH_MS = 1_500L
    }

    /** Consolidated tokens for Cleaner Dashboard, Sleep Mode, and Guided Island Pill. */
    object Cleaner {
        val HeroShape = Tokens.HeroShape
        val CardShape = RoundedCornerShape(CardRadius)
        val AccordionShape = PanelShape
        val DeepCleanCardShape = PanelShape
        val PillShape = IslandShape
        val CapacityBarShape = Tokens.PillShape
        val BadgeShape = Tokens.PillShape

        val SafetyRailWidth = 4.dp
        val CapacityBarHeight = 12.dp
        val CapacitySegmentGap = 2.dp
        val LegendDotSize = 10.dp
        val StatusDotSize = 8.dp
        val PillProgressHeight = 6.dp
        val HeroMinHeight = 196.dp
        val DeepCleanCardMinWidth = 148.dp
        val ScoreBadgeMinWidth = 44.dp
        const val CompactStackWidthDp = COMPACT_WIDTH_DP

        const val ODOMETER_SPRING_DAMPING = 0.86f
        const val ODOMETER_SPRING_STIFFNESS = 180f
        const val MOOD_TRANSITION_MS = 450
        const val ACCORDION_EXPAND_MS = 220
    }

    /** Consolidated tokens for App Lock Gate and App Lock Manager. */
    object AppLock {
        val GateCardShape = HeroShape
        val StatusCardShape = RoundedCornerShape(CardRadius)
        val KeyShape = CircleShape
        val ChipShape = PillShape

        val GateMaxWidth = 380.dp
        val GateShieldSize = 56.dp
        val GateShieldIconSize = 28.dp
        val PinDotSize = 14.dp
        val PinDotGap = 14.dp
        val KeypadButtonSize = 68.dp
        val KeypadButtonCompactSize = 58.dp
        val KeypadHorizontalGap = 18.dp
        val KeypadVerticalGap = 12.dp
        val KeypadCompactGap = 10.dp
        val AppIconBadgeSize = 40.dp
        val StatusDotSize = 8.dp
        val ShakeDistance = 14.dp
        const val BackdropBlurRadiusPx = BACKDROP_BLUR_RADIUS_PX

        const val KEY_PRESS_SCALE = 0.91f
        const val KEY_SPRING_DAMPING = 0.62f
        const val KEY_SPRING_STIFFNESS = 520f
        const val SHAKE_STEP_MS = 55
        const val LOCKOUT_TICK_MS = 1_000L
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

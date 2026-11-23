package com.drivelift.app.ui

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The four elevation/opacity tiers of DriveLift's unified [GlassSurface].
 */
enum class GlassLevel {
    /** Standard cards and list panels (~72-78% deep navy/charcoal, 1dp top-left lit border). */
    Panel,

    /** Pinned navigation and action bars (~80-85% deep navy/charcoal, 1dp top-left lit border). */
    Bar,

    /** Dynamic Island and floating status pills (~84-85% deep charcoal/navy, 1.25dp top-left lit border). */
    Island,

    /** Hero cards such as Cleaner mood hero and App Lock gate (~75-82% deep navy, 1.5dp cyan-lit border). */
    Hero,
}

/** Tracks whether composition is already inside a [GlassSurface] so glass is never nested inside glass. */
val LocalInsideGlassSurface = compositionLocalOf { false }

internal data class GlassLevelSpec(
    val fillTop: Color,
    val fillBottom: Color,
    val fallbackTop: Color,
    val fallbackBottom: Color,
    val borderTopLeft: Color,
    val borderBottomRight: Color,
    val borderWidth: Dp,
    val highlightTint: Color,
    val defaultShape: Shape,
)

internal fun supportsGlassBlur(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    sdkInt >= Build.VERSION_CODES.S

internal fun glassLevelSpec(level: GlassLevel): GlassLevelSpec = when (level) {
    GlassLevel.Panel -> GlassLevelSpec(
        fillTop = GlassPalette.PanelFillTop,
        fillBottom = GlassPalette.PanelFillBottom,
        fallbackTop = GlassPalette.PanelFallbackTop,
        fallbackBottom = GlassPalette.PanelFallbackBottom,
        borderTopLeft = GlassPalette.BorderTopLeft,
        borderBottomRight = GlassPalette.BorderBottomRight,
        borderWidth = Tokens.GlassBorder,
        highlightTint = GlassPalette.TopLeftHighlight,
        defaultShape = Tokens.PanelShape,
    )
    GlassLevel.Bar -> GlassLevelSpec(
        fillTop = GlassPalette.BarFillTop,
        fillBottom = GlassPalette.BarFillBottom,
        fallbackTop = GlassPalette.BarFallbackTop,
        fallbackBottom = GlassPalette.BarFallbackBottom,
        borderTopLeft = GlassPalette.BorderTopLeft,
        borderBottomRight = Color.Transparent,
        borderWidth = Tokens.GlassBorder,
        highlightTint = GlassPalette.TopLeftHighlight,
        defaultShape = Tokens.BarShape,
    )
    GlassLevel.Island -> GlassLevelSpec(
        fillTop = GlassPalette.IslandFillTop,
        fillBottom = GlassPalette.IslandFillBottom,
        fallbackTop = GlassPalette.IslandFallbackTop,
        fallbackBottom = GlassPalette.IslandFallbackBottom,
        borderTopLeft = GlassPalette.BorderTopLeft,
        borderBottomRight = GlassPalette.BorderBottomRight,
        borderWidth = Tokens.GlassBorderIsland,
        highlightTint = GlassPalette.TopLeftCyanHighlight,
        defaultShape = Tokens.IslandShape,
    )
    GlassLevel.Hero -> GlassLevelSpec(
        fillTop = GlassPalette.HeroFillTop,
        fillBottom = GlassPalette.HeroFillBottom,
        fallbackTop = GlassPalette.HeroFallbackTop,
        fallbackBottom = GlassPalette.HeroFallbackBottom,
        borderTopLeft = GlassPalette.HeroBorderTopLeft,
        borderBottomRight = GlassPalette.BorderBottomRight,
        borderWidth = Tokens.GlassBorderHero,
        highlightTint = GlassPalette.TopLeftCyanHighlight,
        defaultShape = Tokens.HeroShape,
    )
}

internal fun topLeftLitBorderBrush(spec: GlassLevelSpec): Brush = Brush.linearGradient(
    0f to spec.borderTopLeft,
    0.45f to GlassPalette.BorderMid,
    1f to spec.borderBottomRight,
    start = Offset.Zero,
    end = Offset.Infinite,
)

internal fun glassFillBrush(
    spec: GlassLevelSpec,
    useBlurTier: Boolean = supportsGlassBlur(),
): Brush = Brush.linearGradient(
    colors = if (useBlurTier) {
        listOf(spec.fillTop, spec.fillBottom)
    } else {
        listOf(spec.fallbackTop, spec.fallbackBottom)
    },
    start = Offset.Zero,
    end = Offset.Infinite,
)

/**
 * The night-sky backdrop every screen floats on. It is drawn once, at the root; screens and
 * scaffolds on top of it stay transparent, otherwise the glass has nothing to show through to.
 * Pass [blurred] = true for full-screen security gates (API 31+ `RenderEffect` blur with API 30
 * denser scrim fallback).
 */
@Composable
fun GlassBackdrop(
    modifier: Modifier = Modifier,
    blurred: Boolean = false,
    content: @Composable () -> Unit,
) {
    val blurLayerModifier = remember(blurred) {
        if (blurred && supportsGlassBlur()) {
            val effect = runCatching {
                RenderEffect.createBlurEffect(
                    Tokens.BACKDROP_BLUR_RADIUS_PX,
                    Tokens.BACKDROP_BLUR_RADIUS_PX,
                    Shader.TileMode.CLAMP,
                ).asComposeRenderEffect()
            }.getOrNull()
            if (effect != null) Modifier.graphicsLayer { renderEffect = effect } else Modifier
        } else {
            Modifier
        }
    }

    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .then(blurLayerModifier)
                .background(
                    Brush.verticalGradient(
                        0f to GlassPalette.SkyTop,
                        0.55f to GlassPalette.SkyMid,
                        1f to GlassPalette.SkyBottom,
                    ),
                )
                .drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            listOf(GlassPalette.GlowBlue, Color.Transparent),
                            center = Offset(size.width * 0.05f, size.height * 0.18f),
                            radius = size.width * 0.9f,
                        ),
                    )
                    drawRect(
                        Brush.radialGradient(
                            listOf(GlassPalette.GlowViolet, Color.Transparent),
                            center = Offset(size.width * 0.95f, size.height * 0.98f),
                            radius = size.width * 1.1f,
                        ),
                    )
                },
        )
        if (blurred) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(if (supportsGlassBlur()) GlassPalette.ThumbScrim else GlassPalette.IslandFallbackBottom),
            )
        }
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            content()
        }
    }
}

/**
 * Single shared glass surface across DriveLift with four tiers ([GlassLevel.Panel],
 * [GlassLevel.Bar], [GlassLevel.Island], [GlassLevel.Hero]).
 *
 * - Deep navy/charcoal 70-85% translucent gradient with top-left specular highlight
 * - 1-1.5 dp top-left lit border
 * - Real frosted backdrop layer using [RenderEffect.createBlurEffect] on API 31+ (applied strictly
 *   behind foreground content so text stays sharp), falling back to denser glass on API 30
 * - Never nests glass inside glass: nested calls degrade cleanly to a flat semantic control well
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GlassSurface(
    level: GlassLevel = GlassLevel.Panel,
    modifier: Modifier = Modifier,
    shape: Shape = glassLevelSpec(level).defaultShape,
    borderBrush: Brush? = null,
    onClick: (() -> Unit)? = null,
    /** What a tap does, read out by accessibility services when it is not obvious from the content. */
    onClickLabel: String? = null,
    /** A long press, with its accessibility label (for example selecting the item). */
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    content: @Composable () -> Unit,
) {
    val isNested = LocalInsideGlassSurface.current
    val spec = glassLevelSpec(level)
    val haptics = LocalHapticFeedback.current
    val blurSupported = supportsGlassBlur()

    val clickModifier = when {
        onClick != null && onLongClick != null -> Modifier.combinedClickable(
            onClickLabel = onClickLabel,
            onLongClickLabel = onLongClickLabel,
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongClick()
            },
            onClick = onClick,
        )
        onClick != null -> Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick)
        else -> Modifier
    }

    if (isNested) {
        // Guardrail: never nest glass inside glass; degrade to a flat semantic control well.
        Box(
            modifier = modifier
                .clip(shape)
                .background(GlassPalette.Well, shape)
                .border(Tokens.Hairline, GlassPalette.WellEdge, shape)
                .then(clickModifier),
        ) {
            content()
        }
        return
    }

    val frostedLayerModifier = remember(blurSupported) {
        if (blurSupported) {
            val effect = runCatching {
                RenderEffect.createBlurEffect(
                    Tokens.GLASS_BLUR_RADIUS_PX,
                    Tokens.GLASS_BLUR_RADIUS_PX,
                    Shader.TileMode.CLAMP,
                ).asComposeRenderEffect()
            }.getOrNull()
            if (effect != null) Modifier.graphicsLayer { renderEffect = effect } else Modifier
        } else {
            Modifier
        }
    }

    val activeBorderBrush = borderBrush ?: topLeftLitBorderBrush(spec)
    val fillBrush = glassFillBrush(spec, useBlurTier = blurSupported)

    Box(
        modifier = modifier
            .clip(shape)
            .background(fillBrush, shape)
            .border(spec.borderWidth, activeBorderBrush, shape)
            .then(clickModifier),
    ) {
        // Frosted top-left ambient highlight layer behind foreground content (API 31+ RenderEffect).
        Box(
            modifier = Modifier
                .matchParentSize()
                .then(frostedLayerModifier)
                .drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(spec.highlightTint, Color.Transparent),
                            center = Offset(size.width * 0.08f, size.height * 0.08f),
                            radius = maxOf(size.width, size.height).coerceAtLeast(1f) * 0.72f,
                        ),
                    )
                },
        )
        CompositionLocalProvider(LocalInsideGlassSurface provides true) {
            content()
        }
    }
}

/**
 * Shared modifier for glass decoration backed by the exact same [GlassLevel] tokens as [GlassSurface].
 */
fun Modifier.glass(
    shape: Shape = Tokens.PanelShape,
    level: GlassLevel = GlassLevel.Panel,
): Modifier {
    val spec = glassLevelSpec(level)
    return this
        .background(glassFillBrush(spec), shape)
        .border(spec.borderWidth, topLeftLitBorderBrush(spec), shape)
}

/** Denser glass bar modifier for bars pinned over scrolling content. */
fun Modifier.glassBar(shape: Shape = Tokens.BarShape): Modifier =
    glass(shape = shape, level = GlassLevel.Bar)

/** Convenience alias for [GlassSurface] at [GlassLevel.Panel]. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = Tokens.PanelShape,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    content: @Composable () -> Unit,
) {
    GlassSurface(
        level = GlassLevel.Panel,
        modifier = modifier,
        shape = shape,
        onClick = onClick,
        onClickLabel = onClickLabel,
        onLongClick = onLongClick,
        onLongClickLabel = onLongClickLabel,
        content = content,
    )
}

/** Red outline + glow modifier reserved strictly for stop and destructive actions. */
fun Modifier.destructiveGlowBorder(
    shape: Shape = Tokens.PillShape,
    borderWidth: Dp = Tokens.GlassBorderHero,
): Modifier = this
    .background(GlassPalette.DestructiveGlow, shape)
    .border(borderWidth, GlassPalette.DestructiveRed, shape)

// Four rings leave the middle of the dome clear for the figure, up to "100%".
private const val GAUGE_RINGS = 4
private const val GAUGE_OUTER_DOTS = 31

/**
 * The lead element of the Transfers screen: a dome of dots that lights up from left to right as
 * the batch completes, with the percentage inside it. Lit dots run from neon green (success)
 * through soft violet to electric cyan (primary); unlit dots stay as faint glass.
 */
@Composable
fun DotArcGauge(fraction: Float, label: String, modifier: Modifier = Modifier) {
    val shown by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = AppMotion.spec(Tokens.Motion.PROGRESS),
        label = "gauge",
    )
    val start = MaterialTheme.colorScheme.tertiary
    val middle = MaterialTheme.colorScheme.secondary
    val end = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .fillMaxWidth()
            .widthIn(max = Tokens.GaugeWidth)
            .height(Tokens.GaugeHeight)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f) },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = minOf(size.width / 2f, size.height) * 0.96f
            val centre = Offset(size.width / 2f, size.height)
            for (ring in 0 until GAUGE_RINGS) {
                val ringRadius = radius * (1f - ring * 0.105f)
                val dots = GAUGE_OUTER_DOTS - ring * 4
                val dotRadius = radius * (0.034f - ring * 0.004f)
                for (i in 0 until dots) {
                    val along = i / (dots - 1f)
                    val angle = PI + along * PI
                    val lit = shown > 0f && along <= shown
                    val colour = if (!lit) GlassPalette.Dim
                    else if (along < 0.5f) lerp(start, middle, along * 2f)
                    else lerp(middle, end, (along - 0.5f) * 2f)
                    drawCircle(
                        color = colour,
                        radius = dotRadius,
                        center = Offset(
                            centre.x + ringRadius * cos(angle).toFloat(),
                            centre.y + ringRadius * sin(angle).toFloat() - dotRadius,
                        ),
                    )
                }
            }
        }
        // With large text the smaller display style is used, so the figure stays inside the dome
        // instead of covering the dots. It is still the largest text on the screen.
        val large = LocalDensity.current.fontScale >= Tokens.STACK_AT_FONT_SCALE
        Text(
            label,
            style = if (large) MaterialTheme.typography.displaySmall else MaterialTheme.typography.displayLarge,
        )
    }
}

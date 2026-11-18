package com.drivelift.app.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The night-sky backdrop every screen floats on. It is drawn once, at the root; screens and
 * scaffolds on top of it stay transparent, otherwise the glass has nothing to show through to.
 */
@Composable
fun GlassBackdrop(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to GlassPalette.SkyTop,
                    0.55f to GlassPalette.SkyMid,
                    1f to GlassPalette.SkyBottom,
                )
            )
            .drawBehind {
                // Two soft glows give the flat gradient some depth.
                drawRect(
                    Brush.radialGradient(
                        listOf(GlassPalette.GlowBlue, Color.Transparent),
                        center = Offset(size.width * 0.05f, size.height * 0.18f),
                        radius = size.width * 0.9f,
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(GlassPalette.GlowViolet, Color.Transparent),
                        center = Offset(size.width * 0.95f, size.height * 0.98f),
                        radius = size.width * 1.1f,
                    )
                )
            },
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            content()
        }
    }
}

/** A translucent film with a lit top edge. For panels and rows that float on the backdrop. */
fun Modifier.glass(shape: Shape): Modifier = this
    .background(Brush.verticalGradient(listOf(GlassPalette.FillTop, GlassPalette.FillBottom)), shape)
    .border(Tokens.Hairline, Brush.verticalGradient(listOf(GlassPalette.EdgeTop, GlassPalette.EdgeBottom)), shape)

/** Denser glass for bars that are pinned over scrolling content. */
fun Modifier.glassBar(shape: Shape = Tokens.BarShape): Modifier = this
    .background(Brush.verticalGradient(listOf(GlassPalette.BarTop, GlassPalette.BarBottom)), shape)
    .border(Tokens.Hairline, Brush.verticalGradient(listOf(GlassPalette.EdgeTop, Color.Transparent)), shape)

/** A glass panel. Pass [onClick] to make the whole panel one touch target. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = Tokens.PanelShape,
    onClick: (() -> Unit)? = null,
    /** What a tap does, read out by accessibility services when it is not obvious from the content. */
    onClickLabel: String? = null,
    /** A long press, with its accessibility label (for example selecting the item). */
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .clip(shape)
            .glass(shape)
            .then(
                when {
                    onClick != null && onLongClick != null -> Modifier.combinedClickable(
                        onClickLabel = onClickLabel,
                        onLongClickLabel = onLongClickLabel,
                        onLongClick = onLongClick,
                        onClick = onClick,
                    )
                    onClick != null -> Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick)
                    else -> Modifier
                },
            ),
    ) { content() }
}

// Four rings leave the middle of the dome clear for the figure, up to "100%".
private const val GAUGE_RINGS = 4
private const val GAUGE_OUTER_DOTS = 31

/**
 * The lead element of the Transfers screen: a dome of dots that lights up from left to right as
 * the batch completes, with the percentage inside it. Lit dots run from sky blue through violet
 * to the coral accent; unlit dots stay as faint glass.
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

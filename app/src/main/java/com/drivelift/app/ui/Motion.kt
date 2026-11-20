package com.drivelift.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.launch

/** Which way a new level arrives: deeper slides in from the end, up slides in from the start. */
enum class NavStep { DEEPER, UP, ACROSS }

/**
 * Plays the arrival of a new level whenever [key] changes (a folder opened, Back to its parent,
 * another tab): it slides in by [Tokens.NavigateShift] from the side [step] names and fades in.
 * Across tabs it only fades. Nothing plays the first time, and nothing moves when the phone has
 * animations turned off (AppMotion).
 */
@Composable
fun Modifier.arriveOnChange(key: Any?, step: NavStep): Modifier {
    val reduced = LocalReducedMotion.current
    val offset = remember { Animatable(0f) }
    val alpha = remember { Animatable(1f) }
    var seen by remember { mutableStateOf(false) }
    val slide = AppMotion.spec<Float>(Tokens.Motion.NAVIGATE)
    val fade = AppMotion.spec<Float>(Tokens.Motion.NAVIGATE)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    LaunchedEffect(key) {
        if (!seen) {
            seen = true
            return@LaunchedEffect
        }
        if (reduced) return@LaunchedEffect
        val from = when (step) {
            NavStep.DEEPER -> 1f
            NavStep.UP -> -1f
            NavStep.ACROSS -> 0f
        } * (if (rtl) -1f else 1f)
        offset.snapTo(from)
        alpha.snapTo(0f)
        launch { offset.animateTo(0f, slide) }
        alpha.animateTo(1f, fade)
    }
    val shift = with(LocalDensity.current) { Tokens.NavigateShift.toPx() }
    return graphicsLayer {
        translationX = offset.value * shift
        this.alpha = alpha.value
    }
}

/**
 * A bar pinned at the bottom of a screen (selection bar, move pill) that rises into place and
 * sinks away again, instead of appearing at once.
 */
@Composable
fun ColumnScope.RisingBar(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(AppMotion.spec<IntOffset>(Tokens.Motion.NAVIGATE)) { it } +
            fadeIn(AppMotion.spec(Tokens.Motion.NAVIGATE)),
        exit = slideOutVertically(AppMotion.spec<IntOffset>(Tokens.Motion.SELECTION)) { it } +
            fadeOut(AppMotion.spec(Tokens.Motion.SELECTION)),
    ) { content() }
}

/** How small a thumbnail starts before it grows into place, and how far a selected card shrinks. */
private const val THUMB_START_SCALE = 0.92f
private const val SELECTED_CARD_SCALE = 0.94f

/**
 * A thumbnail that fades and grows in once its picture has [loaded]. A picture that was already
 * at hand ([animate] false, for example from the memory cache while scrolling back) just shows.
 */
@Composable
fun Modifier.revealWhen(loaded: Boolean, animate: Boolean = true): Modifier {
    val shown = remember { Animatable(if (loaded && !animate) 1f else 0f) }
    val spec = AppMotion.spec<Float>(Tokens.Motion.THUMB_IN)
    LaunchedEffect(loaded, animate) {
        when {
            !loaded -> shown.snapTo(0f)
            animate -> shown.animateTo(1f, spec)
            else -> shown.snapTo(1f)
        }
    }
    return graphicsLayer {
        val p = shown.value
        alpha = p
        val scale = THUMB_START_SCALE + (1f - THUMB_START_SCALE) * p
        scaleX = scale
        scaleY = scale
    }
}

/**
 * A selected thumbnail's coral glow: a soft coloured shadow around [shape] that rises with the
 * selection and falls away when it is cleared.
 */
@Composable
fun Modifier.selectionGlow(selected: Boolean, shape: Shape): Modifier {
    val lift by animateDpAsState(
        targetValue = if (selected) Tokens.SelectGlow else Tokens.NoInset,
        animationSpec = AppMotion.spec(Tokens.Motion.SELECTION),
        label = "glow",
    )
    val glow = MaterialTheme.colorScheme.primary
    return if (lift == Tokens.NoInset) this
    else shadow(lift, shape, clip = false, ambientColor = glow, spotColor = glow)
}

/** A selected card in a Drive grid shrinks a little, so the glow and ring have room around it. */
@Composable
fun Modifier.selectedShrink(selected: Boolean): Modifier {
    val scale by animateFloatAsState(
        targetValue = if (selected) SELECTED_CARD_SCALE else 1f,
        animationSpec = AppMotion.spec(Tokens.Motion.SELECTION),
        label = "shrink",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

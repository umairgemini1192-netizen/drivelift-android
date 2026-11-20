package com.drivelift.app.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.coroutines.cancellation.CancellationException

/** How far the current level shrinks at the end of a back swipe (Material's predictive back). */
private const val BACK_PREVIEW_SCALE = 0.9f

/** Where a back swipe is, from 0 (not started) to 1, and which screen edge it came from. */
@Stable
class BackPreviewState {
    var progress by mutableFloatStateOf(0f)
        internal set
    var edge by mutableIntStateOf(BackEventCompat.EDGE_LEFT)
        internal set
}

/**
 * Back for a step up one level (a folder to its parent, Settings or Storage to their tab). With
 * Android's predictive back gesture the returned state follows the finger, so [backPreview] can
 * show the step before it happens; letting go runs [onBack], cancelling leaves everything as it
 * was. The back button and older phones run [onBack] at once.
 */
@Composable
fun rememberUpBack(enabled: Boolean, onBack: () -> Unit): BackPreviewState {
    val state = remember { BackPreviewState() }
    val current by rememberUpdatedState(onBack)
    PredictiveBackHandler(enabled) { events ->
        try {
            events.collect { event ->
                state.edge = event.swipeEdge
                state.progress = event.progress
            }
            current()
            state.progress = 0f
        } catch (e: CancellationException) {
            state.progress = 0f
            throw e
        }
    }
    return state
}

/**
 * Shrinks the current level and slides it toward the side the swipe started from, as far as
 * [Tokens.BackPreviewShift], so the backdrop shows around it while the user decides. State
 * feedback only (rule 5): with animations turned off nothing moves and Back still works.
 */
@Composable
fun Modifier.backPreview(state: BackPreviewState): Modifier {
    if (LocalReducedMotion.current) return this
    return graphicsLayer {
        val p = state.progress
        if (p == 0f) return@graphicsLayer
        val scale = 1f - (1f - BACK_PREVIEW_SCALE) * p
        scaleX = scale
        scaleY = scale
        val direction = if (state.edge == BackEventCompat.EDGE_RIGHT) -1f else 1f
        translationX = direction * Tokens.BackPreviewShift.toPx() * p
        clip = true
        shape = Tokens.PanelShape
    }
}

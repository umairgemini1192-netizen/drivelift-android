package com.drivelift.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch
import kotlin.math.hypot

private const val PINCH_OUT_RATIO = 1.28f
private const val PINCH_IN_RATIO = 0.78f
private const val MIN_PINCH_SPAN_PX = 8f

internal data class GridItemBounds(
    val index: Int,
    val offset: IntOffset,
    val size: IntSize,
)

internal fun findFocalItemBounds(
    visibleItems: List<GridItemBounds>,
    centroid: Offset,
): GridItemBounds? {
    val directHit = visibleItems.firstOrNull { info ->
        centroid.x >= info.offset.x &&
            centroid.x < info.offset.x + info.size.width &&
            centroid.y >= info.offset.y &&
            centroid.y < info.offset.y + info.size.height
    }
    if (directHit != null) return directHit
    return visibleItems.minByOrNull { info ->
        val dx = centroid.x - (info.offset.x + info.size.width / 2f)
        val dy = centroid.y - (info.offset.y + info.size.height / 2f)
        dx * dx + dy * dy
    }
}

internal fun findFocalGridItem(
    visibleItems: List<LazyGridItemInfo>,
    centroid: Offset,
): LazyGridItemInfo? {
    val target = findFocalItemBounds(
        visibleItems.map { GridItemBounds(it.index, it.offset, it.size) },
        centroid,
    ) ?: return null
    return visibleItems.firstOrNull { it.index == target.index }
}

internal fun stepDensityForPinch(current: GridDensity, spanRatio: Float): GridDensity? = when {
    spanRatio >= PINCH_OUT_RATIO -> current.zoomOutward().takeIf { it != current }
    spanRatio <= PINCH_IN_RATIO -> current.zoomInward().takeIf { it != current }
    else -> null
}

/**
 * Supports two-finger pinch to change thumbnail density on a lazy grid while keeping the item
 * under the pinch centroid anchored and consuming multi-touch events so child clicks or drag
 * selection cannot fire when the gesture finishes.
 */
@Composable
fun Modifier.pinchGridDensity(
    gridState: LazyGridState,
    density: GridDensity,
    onDensityChange: (GridDensity) -> Unit,
    onPinchStateChange: (Boolean) -> Unit = {},
): Modifier {
    val currentDensity by rememberUpdatedState(density)
    val currentOnDensityChange by rememberUpdatedState(onDensityChange)
    val currentOnPinchStateChange by rememberUpdatedState(onPinchStateChange)
    val scope = rememberCoroutineScope()

    return pointerInput(gridState) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var pinching = false
            var baselineSpan = 0f
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) {
                        if (pinching) {
                            event.changes.forEach { it.consume() }
                        }
                        break
                    }
                    if (pressed.size >= 2) {
                        val p0 = pressed[0].position
                        val p1 = pressed[1].position
                        val span = hypot(p1.x - p0.x, p1.y - p0.y)
                        val centroid = Offset((p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f)
                        if (!pinching) {
                            pinching = true
                            currentOnPinchStateChange(true)
                            baselineSpan = span.coerceAtLeast(MIN_PINCH_SPAN_PX)
                        } else if (span >= MIN_PINCH_SPAN_PX && baselineSpan >= MIN_PINCH_SPAN_PX) {
                            val ratio = span / baselineSpan
                            val next = stepDensityForPinch(currentDensity, ratio)
                            if (next != null) {
                                val anchor = findFocalGridItem(
                                    gridState.layoutInfo.visibleItemsInfo,
                                    centroid,
                                )
                                currentOnDensityChange(next)
                                baselineSpan = span
                                if (anchor != null) {
                                    scope.launch {
                                        anchorGridAfterDensityChange(gridState, anchor)
                                    }
                                }
                            }
                        }
                        event.changes.forEach { it.consume() }
                    } else if (pinching) {
                        event.changes.forEach { it.consume() }
                    }
                }
            } finally {
                if (pinching) {
                    currentOnPinchStateChange(false)
                }
            }
        }
    }
}

private suspend fun anchorGridAfterDensityChange(
    gridState: LazyGridState,
    anchor: LazyGridItemInfo,
) {
    // Wait a frame for the new Adaptive column count to re-measure, then align the focal item back
    // to its pre-pinch vertical position without ever passing a negative scrollOffset.
    withFrameNanos { }
    val total = gridState.layoutInfo.totalItemsCount
    if (total <= 0) return
    val idx = anchor.index.coerceIn(0, total - 1)
    val desiredY = anchor.offset.y
    if (desiredY <= 0) {
        gridState.scrollToItem(idx, -desiredY)
        return
    }
    gridState.scrollToItem(idx, 0)
    withFrameNanos { }
    val placed = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == idx }
    if (placed != null && desiredY > placed.offset.y) {
        gridState.scrollBy((placed.offset.y - desiredY).toFloat())
    }
}

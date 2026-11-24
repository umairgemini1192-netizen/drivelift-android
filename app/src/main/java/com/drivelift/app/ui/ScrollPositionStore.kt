package com.drivelift.app.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

/**
 * Saved scroll anchor for a lazy list or grid. Stored per route and folder path in
 * [ScrollPositionStore] so navigating into a child screen or switching tabs and returning via Back
 * restores the exact item index and pixel offset.
 */
data class ScrollPosition(
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0,
)

/**
 * Thread-safe in-memory store hosted on [AppViewModel] that retains scroll positions across tab
 * switches, folder navigation, and back gestures.
 */
class ScrollPositionStore {
    private val positions = HashMap<String, ScrollPosition>()

    fun get(key: String): ScrollPosition = synchronized(positions) {
        positions[key] ?: ScrollPosition()
    }

    fun save(key: String, index: Int, offset: Int) {
        synchronized(positions) {
            positions[key] = ScrollPosition(
                firstVisibleItemIndex = index.coerceAtLeast(0),
                firstVisibleItemScrollOffset = offset.coerceAtLeast(0),
            )
        }
    }

    fun clear() {
        synchronized(positions) {
            positions.clear()
        }
    }

    fun snapshot(): Map<String, ScrollPosition> = synchronized(positions) {
        HashMap(positions)
    }
}

/**
 * Remembers a [LazyListState] bound to [key] in both [rememberSaveable] (surviving process death
 * and configuration changes) and [store] (surviving tab disposal and Back navigation).
 */
@Composable
fun rememberPersistentLazyListState(
    store: ScrollPositionStore,
    key: String,
): LazyListState {
    val saved = remember(store, key) { store.get(key) }
    val state = rememberSaveable(key, saver = LazyListState.Saver) {
        LazyListState(
            firstVisibleItemIndex = saved.firstVisibleItemIndex,
            firstVisibleItemScrollOffset = saved.firstVisibleItemScrollOffset,
        )
    }
    val restored = remember(store, key, state) {
        booleanArrayOf(saved.firstVisibleItemIndex == 0 && saved.firstVisibleItemScrollOffset == 0)
    }
    LaunchedEffect(store, key, state) {
        if (!restored[0]) {
            val total = snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > 0 }
            if (state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0) {
                val targetIndex = saved.firstVisibleItemIndex.coerceAtMost(total - 1)
                val targetOffset = if (targetIndex == saved.firstVisibleItemIndex) {
                    saved.firstVisibleItemScrollOffset
                } else {
                    0
                }
                state.scrollToItem(targetIndex, targetOffset)
            }
            restored[0] = true
        }
        snapshotFlow {
            Triple(
                state.firstVisibleItemIndex,
                state.firstVisibleItemScrollOffset,
                state.layoutInfo.totalItemsCount,
            )
        }.collect { (index, offset, total) ->
            if (total > 0) {
                store.save(key, index, offset)
            }
        }
    }
    DisposableEffect(store, key, state) {
        onDispose {
            if (restored[0] && state.layoutInfo.totalItemsCount > 0) {
                store.save(key, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
            }
        }
    }
    return state
}

/**
 * Remembers a [LazyGridState] bound to [key] in both [rememberSaveable] and [store] so folder
 * grids on Phone and Drive return to their exact scroll offset after Back or tab switches.
 */
@Composable
fun rememberPersistentLazyGridState(
    store: ScrollPositionStore,
    key: String,
): LazyGridState {
    val saved = remember(store, key) { store.get(key) }
    val state = rememberSaveable(key, saver = LazyGridState.Saver) {
        LazyGridState(
            firstVisibleItemIndex = saved.firstVisibleItemIndex,
            firstVisibleItemScrollOffset = saved.firstVisibleItemScrollOffset,
        )
    }
    val restored = remember(store, key, state) {
        booleanArrayOf(saved.firstVisibleItemIndex == 0 && saved.firstVisibleItemScrollOffset == 0)
    }
    LaunchedEffect(store, key, state) {
        if (!restored[0]) {
            val total = snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > 0 }
            if (state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0) {
                val targetIndex = saved.firstVisibleItemIndex.coerceAtMost(total - 1)
                val targetOffset = if (targetIndex == saved.firstVisibleItemIndex) {
                    saved.firstVisibleItemScrollOffset
                } else {
                    0
                }
                state.scrollToItem(targetIndex, targetOffset)
            }
            restored[0] = true
        }
        snapshotFlow {
            Triple(
                state.firstVisibleItemIndex,
                state.firstVisibleItemScrollOffset,
                state.layoutInfo.totalItemsCount,
            )
        }.collect { (index, offset, total) ->
            if (total > 0) {
                store.save(key, index, offset)
            }
        }
    }
    DisposableEffect(store, key, state) {
        onDispose {
            if (restored[0] && state.layoutInfo.totalItemsCount > 0) {
                store.save(key, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
            }
        }
    }
    return state
}

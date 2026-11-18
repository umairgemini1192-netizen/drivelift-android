package com.drivelift.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * A slot at the root of the app, outside the Scaffold's system-bar padding, where a screen can
 * show full-screen content such as the photo and video viewers. Content drawn here reaches the
 * screen edges behind the status and navigation bars, so hiding the bars for a video leaves no
 * empty bands. Content that must stay clear of the bars pads itself with the window insets.
 */
@Stable
class FullScreenOverlayState {
    var content: (@Composable () -> Unit)? by mutableStateOf(null)
        internal set
}

val LocalFullScreenOverlay = staticCompositionLocalOf<FullScreenOverlayState?> { null }

/** Draws whatever a screen placed in [state], filling the whole window. */
@Composable
fun FullScreenOverlayHost(state: FullScreenOverlayState) {
    state.content?.let { content -> Box(Modifier.fillMaxSize()) { content() } }
}

/**
 * Shows [content] full screen through the root slot while this call is in the composition. The
 * calling screen keeps its own state, which the content reads as usual. Without a root slot (a
 * screen composed on its own, as in some tests) the content is drawn in place instead.
 */
@Composable
fun FullScreenOverlay(content: @Composable () -> Unit) {
    val host = LocalFullScreenOverlay.current
    if (host == null) {
        Box(Modifier.fillMaxSize()) { content() }
        return
    }
    val current by rememberUpdatedState(content)
    DisposableEffect(host) {
        val shown: @Composable () -> Unit = { current() }
        host.content = shown
        onDispose { if (host.content === shown) host.content = null }
    }
}

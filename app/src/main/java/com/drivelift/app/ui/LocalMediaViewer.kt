package com.drivelift.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import com.drivelift.app.data.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

private sealed interface LocalImageState {
    data object Loading : LocalImageState
    data class Ready(val bitmap: ImageBitmap) : LocalImageState
    data class Error(val message: String) : LocalImageState
}

/**
 * Full-screen viewer for photos and videos on this phone (issue #29), with swipeable
 * [HorizontalPager] browsing across the current folder's images, swipe-down to dismiss,
 * immersive OLED-black video playback, top gradient scrim, and standard Android Share.
 * Everything is read through the item's content URI: no Google account, token or network
 * is involved, and the original file is never changed.
 */
@Composable
fun LocalMediaViewer(
    vm: AppViewModel,
    item: MediaItem,
    onClose: () -> Unit,
    onDelete: (() -> Unit)? = null,
    folderItems: List<MediaItem> = listOf(item),
    onItemChanged: ((MediaItem) -> Unit)? = null,
) {
    val context = LocalContext.current
    BackHandler(onBack = onClose)
    // For videos the top bar hides together with the player controls, for a clear picture.
    var chromeVisible by remember(item.isVideo) { mutableStateOf(true) }
    val favoriteIds by vm.favoriteIds.collectAsStateWithLifecycle()

    if (item.isVideo) {
        val openElsewhere: () -> Unit = {
            openLocalExternally(context, item)?.let { vm.messages.tryEmit(it) }
        }
        val shareCurrent: () -> Unit = {
            shareLocalMedia(context, item)?.let { vm.messages.tryEmit(it) }
        }
        // Pure #000000 OLED black fills edge-to-edge so no purple/navy bars frame the video.
        Box(
            Modifier
                .fillMaxSize()
                .background(GlassPalette.OledBlack)
                .testTag("local-viewer"),
        ) {
            Box(Modifier.fillMaxSize().viewerPointerBarrier())
            LocalVideoContent(vm, item, openElsewhere, onChromeVisibleChange = { chromeVisible = it })
            if (chromeVisible) {
                ViewerTopBar(
                    title = item.name,
                    subtitle = buildString {
                        append("Video on this phone")
                        if (item.size > 0) append("  ·  ${formatBytes(context, item.size)}")
                    },
                    showResetZoom = false,
                    onResetZoom = {},
                    onClose = onClose,
                    externalLabel = "Open in another app",
                    onOpenExternally = openElsewhere,
                    modifier = Modifier.align(Alignment.TopCenter).zIndex(2f),
                    onShare = shareCurrent,
                    onDelete = onDelete,
                    favorite = com.drivelift.app.data.phoneMediaKey(item.id) in favoriteIds,
                    onToggleFavorite = { vm.toggleFavorite(item) },
                    showTopScrim = true,
                )
            }
        }
        return
    }

    val folderImages = remember(folderItems) { folderItems.filterNot { it.isVideo } }
    val imageItems = remember(folderImages, item.id) {
        if (folderImages.any { it.id == item.id }) folderImages else listOf(item)
    }
    val initialPage = remember(folderImages) {
        imageItems.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { imageItems.size })
    val transforms = remember { mutableStateMapOf<Long, ImageTransformState>() }
    var dismissDragY by remember { mutableFloatStateOf(0f) }

    val activeItem = imageItems.getOrElse(pagerState.currentPage) { item }
    val activeTransform = transforms.getOrPut(activeItem.id) { ImageTransformState() }
    val currentOnItemChanged by rememberUpdatedState(onItemChanged)

    LaunchedEffect(pagerState.currentPage, imageItems) {
        imageItems.getOrNull(pagerState.currentPage)?.let { currentOnItemChanged?.invoke(it) }
    }

    val openElsewhere: () -> Unit = {
        openLocalExternally(context, activeItem)?.let { vm.messages.tryEmit(it) }
    }
    val shareCurrent: () -> Unit = {
        shareLocalMedia(context, activeItem)?.let { vm.messages.tryEmit(it) }
    }
    val backdropAlpha = (1f - (dismissDragY / 900f)).coerceIn(0.35f, 1f)

    Box(
        Modifier
            .fillMaxSize()
            .background(GlassPalette.OledBlack.copy(alpha = backdropAlpha))
            .testTag("local-viewer"),
    ) {
        // Taps on the empty backdrop never reach the folder grid beneath.
        Box(Modifier.fillMaxSize().viewerPointerBarrier())
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = activeTransform.scale <= 1.05f,
            key = { imageItems[it].id },
            modifier = Modifier.fillMaxSize().testTag("local-image-pager"),
        ) { page ->
            val pageItem = imageItems[page]
            val pageTransform = transforms.getOrPut(pageItem.id) { ImageTransformState() }
            val pageOpenElsewhere: () -> Unit = {
                openLocalExternally(context, pageItem)?.let { vm.messages.tryEmit(it) }
            }
            LocalImageContent(
                item = pageItem,
                transform = pageTransform,
                onClose = onClose,
                onOpenExternally = pageOpenElsewhere,
                onDismissDragChange = { dismissDragY = it },
            )
        }
        ViewerTopBar(
            title = activeItem.name,
            subtitle = buildString {
                append("Photo on this phone")
                if (imageItems.size > 1) append("  ·  ${pagerState.currentPage + 1} of ${imageItems.size}")
                if (activeItem.size > 0) append("  ·  ${formatBytes(context, activeItem.size)}")
            },
            showResetZoom = activeTransform.scale > 1.05f,
            onResetZoom = { activeTransform.reset() },
            onClose = onClose,
            externalLabel = "Open in another app",
            onOpenExternally = openElsewhere,
            modifier = Modifier.align(Alignment.TopCenter).zIndex(2f),
            onShare = shareCurrent,
            onDelete = onDelete,
            favorite = com.drivelift.app.data.phoneMediaKey(activeItem.id) in favoriteIds,
            onToggleFavorite = { vm.toggleFavorite(activeItem) },
            showTopScrim = true,
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun LocalVideoContent(
    vm: AppViewModel,
    item: MediaItem,
    onOpenExternally: () -> Unit,
    onChromeVisibleChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var problem by remember(item.id) { mutableStateOf<VideoProblem?>(null) }
    var reload by remember(item.id) { mutableIntStateOf(0) }
    // Checked before the player starts: a video moved or deleted since the folder loaded gets a
    // plain notice at once instead of a player error.
    var readable by remember(item.id) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(item.id, reload) {
        readable = null
        val ok = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(item.uri)?.use { true } ?: false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
        readable = ok
        if (!ok) problem = VideoProblem.Failed(LOCAL_VIDEO_UNREADABLE)
    }
    // A new source on Try again gives a fresh player.
    val source = remember(item.id, reload) {
        VideoSource(
            key = "local:${item.id}",
            name = item.name,
            uri = item.uri.toString(),
            // Reads content:// URIs through the content resolver; no network data source is used.
            dataSourceFactory = DefaultDataSource.Factory(context),
            local = true,
        )
    }
    Box(
        Modifier.fillMaxSize().background(GlassPalette.OledBlack),
        contentAlignment = Alignment.Center,
    ) {
        val p = problem
        when {
            p == null && readable == true ->
                VideoPlayer(vm, source, onProblem = { problem = it }, onChromeVisibleChange = onChromeVisibleChange)
            p == null -> CircularProgressIndicator()
            else -> ViewerNoticeCard(
                title = "Could not play video",
                // A local file never needs Google authorization, so every problem here is a failure.
                body = (p as? VideoProblem.Failed)?.message ?: LOCAL_VIDEO_UNREADABLE,
                primaryLabel = "Try again",
                onPrimary = {
                    problem = null
                    reload++
                },
                secondaryLabel = "Open in another app",
                onSecondary = onOpenExternally,
            )
        }
    }
}

@Composable
private fun LocalImageContent(
    item: MediaItem,
    transform: ImageTransformState,
    onClose: () -> Unit,
    onOpenExternally: () -> Unit,
    onDismissDragChange: (Float) -> Unit = {},
) {
    val context = LocalContext.current
    var state by remember(item.id) { mutableStateOf<LocalImageState>(LocalImageState.Loading) }
    var reload by remember(item.id) { mutableIntStateOf(0) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val currentBoxSize by rememberUpdatedState(boxSize)

    LaunchedEffect(item.id, reload) {
        state = LocalImageState.Loading
        state = try {
            val bytes = withContext(Dispatchers.IO) { readLocalBytes(context, item, MAX_IMAGE_VIEWER_BYTES) }
            val decoded = decodeSampledImageBitmap(bytes, MAX_IMAGE_DECODE_EDGE)
            if (decoded != null) LocalImageState.Ready(decoded)
            else LocalImageState.Error("This phone could not decode this photo's format. You can open it in another app.")
        } catch (e: CancellationException) {
            throw e
        } catch (_: TooLargeForViewerException) {
            LocalImageState.Error("This photo is larger than DriveLift's viewer opens (32 MB). You can open it in another app.")
        } catch (_: Exception) {
            LocalImageState.Error("The photo could not be read from this phone. It may have been moved or deleted.")
        }
    }

    Box(
        Modifier.fillMaxSize().onSizeChanged { boxSize = it },
        contentAlignment = Alignment.Center,
    ) {
        when (val s = state) {
            is LocalImageState.Ready -> ZoomableImage(
                bitmap = s.bitmap,
                contentDescription = item.name,
                key = item.id,
                transform = transform,
                onDismiss = onClose,
                onDismissDragChange = onDismissDragChange,
            ) { currentBoxSize }
            LocalImageState.Loading -> GlassPanel(Modifier.padding(Tokens.Edge), shape = Tokens.PanelShape) {
                Column(Modifier.padding(Tokens.Edge), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(Tokens.Medium))
                    Text("Opening photo", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onClose, modifier = Modifier.padding(top = Tokens.Tiny)) {
                        Text("Cancel")
                    }
                }
            }
            is LocalImageState.Error -> ViewerNoticeCard(
                title = "Could not open photo",
                body = s.message,
                primaryLabel = "Try again",
                onPrimary = { reload++ },
                secondaryLabel = "Open in another app",
                onSecondary = onOpenExternally,
            )
        }
    }
}

private class TooLargeForViewerException : IOException()

internal const val LOCAL_VIDEO_UNREADABLE = "This video could not be read from this phone. It may have been moved or deleted."

/** Reads the whole file, refusing anything over [maxBytes] so a huge file cannot exhaust memory. */
private fun readLocalBytes(context: Context, item: MediaItem, maxBytes: Long): ByteArray {
    if (item.size > maxBytes) throw TooLargeForViewerException()
    val stream = context.contentResolver.openInputStream(item.uri) ?: throw IOException("No stream for ${item.name}")
    return stream.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw TooLargeForViewerException()
            out.write(buffer, 0, read)
        }
        out.toByteArray()
    }
}

/**
 * Opens the file in another app with read access to this one file only. Returns a message to
 * show when nothing can open it.
 */
internal fun openLocalExternally(context: Context, item: MediaItem): String? {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(item.uri, item.mime.ifBlank { if (item.isVideo) "video/*" else "image/*" })
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        context.startActivity(intent)
        null
    } catch (_: ActivityNotFoundException) {
        "No other app on this phone can open this file."
    }
}

/**
 * Shares a local photo or video via Android's standard `ACTION_SEND` chooser with read permission
 * scoped strictly to this file's content URI.
 */
internal fun shareLocalMedia(context: Context, item: MediaItem): String? {
    val mime = item.mime.ifBlank { if (item.isVideo) "video/*" else "image/*" }
    val send = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, item.uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val chooser = Intent.createChooser(send, "Share ${item.name}")
    return try {
        context.startActivity(chooser)
        null
    } catch (_: ActivityNotFoundException) {
        "No app on this phone can share this file."
    }
}

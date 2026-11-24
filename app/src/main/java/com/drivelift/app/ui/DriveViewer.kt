package com.drivelift.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivelift.app.data.LocalFileOpener
import com.drivelift.app.drive.AuthRequiredException
import com.drivelift.app.drive.DriveFile
import com.drivelift.app.drive.DrivePreviewKind
import com.drivelift.app.drive.classifyDriveFile
import com.drivelift.app.drive.classifyDriveVisualKind
import com.drivelift.app.drive.driveDownloadMimeType
import com.drivelift.app.drive.drivePreviewTypeLabel
import com.drivelift.app.transfer.describeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import kotlin.math.abs
import kotlin.math.max

internal const val MAX_IMAGE_VIEWER_BYTES = 32L * 1024L * 1024L
internal const val MAX_IMAGE_DECODE_EDGE = 2048

private sealed interface ImageViewerState {
    data object Loading : ImageViewerState
    data class Ready(val bitmap: ImageBitmap) : ImageViewerState
    data object AuthExpired : ImageViewerState
    data class Error(val message: String) : ImageViewerState
}

/**
 * Decodes compressed image bytes downsampled so the longest edge does not exceed [maxEdgePx],
 * applying any EXIF rotation or mirroring metadata so photos render upright.
 */
suspend fun decodeSampledImageBitmap(bytes: ByteArray, maxEdgePx: Int): ImageBitmap? =
    withContext(Dispatchers.Default) {
        val orientation = readExifOrientation(bytes)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        val longest = max(bounds.outWidth, bounds.outHeight)
        while (longest / sample > maxEdgePx) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@withContext null
        val oriented = applyExifOrientation(decoded, orientation)
        if (oriented !== decoded) decoded.recycle()
        oriented.asImageBitmap()
    }

internal fun readExifOrientation(bytes: ByteArray): Int =
    try {
        ByteArrayInputStream(bytes).use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED,
            )
        }
    } catch (_: Throwable) {
        ExifInterface.ORIENTATION_UNDEFINED
    }

/**
 * Returns [bitmap] transformed according to an EXIF orientation tag, or [bitmap] unchanged when no
 * rotation or mirroring is needed.
 */
internal fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
            matrix.setRotate(180f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(-90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

@Stable
internal class ImageTransformState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)

    fun toggleDoubleTap() {
        if (scale > 1.05f) {
            reset()
        } else {
            scale = 2.5f
            offset = Offset.Zero
        }
    }

    fun applyGesture(pan: Offset, zoom: Float, bounds: IntSize) {
        val nextScale = (scale * zoom).coerceIn(1f, 4f)
        val maxX = (bounds.width * (nextScale - 1f)) / 2f
        val maxY = (bounds.height * (nextScale - 1f)) / 2f
        val nextOffset = if (nextScale <= 1.01f) {
            Offset.Zero
        } else {
            Offset(
                x = (offset.x + pan.x).coerceIn(-maxX, maxX),
                y = (offset.y + pan.y).coerceIn(-maxY, maxY),
            )
        }
        scale = nextScale
        offset = nextOffset
    }

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }
}

/**
 * Full-screen in-app preview for Google Drive files. Images render natively with pinch zoom, pan,
 * and double-tap zoom using DriveLift's connected account. Unsupported formats display metadata
 * and an explicit external-open button.
 */
@Composable
fun DriveFileViewer(
    vm: AppViewModel,
    file: DriveFile,
    cachedThumb: ImageBitmap?,
    onClose: () -> Unit,
    onOpenExternally: () -> Unit,
    /** Moves this file to Google Drive's trash after confirmation (issue #36). */
    onDelete: (() -> Unit)? = null,
    /** The Drive path the file is shown in, recorded when it is starred. */
    location: String = "",
    /** Current folder listing for swipeable image paging; defaults to just [file]. */
    folderFiles: List<DriveFile> = listOf(file),
    /** Resolves cached thumbnails for adjacent pages in the swipeable image viewer. */
    thumbProvider: (DriveFile) -> ImageBitmap? = { if (it.id == file.id) cachedThumb else null },
    /** Notifies the caller when swiping changes the active image page. */
    onFileChanged: ((DriveFile) -> Unit)? = null,
    /** Optional per-file delete callback when paging through multiple Drive images. */
    onDeleteFile: ((DriveFile) -> Unit)? = null,
    /** Optional per-file external-open callback when paging through multiple Drive images. */
    onOpenFileExternally: ((DriveFile) -> Unit)? = null,
) {
    val context = LocalContext.current
    val conn by vm.driveConn.collectAsStateWithLifecycle()
    val favoriteIds by vm.favoriteIds.collectAsStateWithLifecycle()
    val favoriteAccount by vm.connectedAccountHash.collectAsStateWithLifecycle()
    val kind = remember(file.mimeType) { classifyDriveFile(file.mimeType) }

    BackHandler(onBack = onClose)

    // For videos the top bar hides together with the player controls, for a clear picture.
    var chromeVisible by remember(kind) { mutableStateOf(true) }

    when (kind) {
        DrivePreviewKind.VIDEO -> {
            // Pure #000000 OLED black fills edge-to-edge so no purple/navy bars frame the video.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(GlassPalette.OledBlack)
                    .testTag("drive-video-viewer"),
            ) {
                Box(Modifier.fillMaxSize().viewerPointerBarrier())
                VideoPreviewContent(
                    vm = vm,
                    file = file,
                    conn = conn,
                    cachedThumb = cachedThumb,
                    onOpenExternally = onOpenExternally,
                    onChromeVisibleChange = { chromeVisible = it },
                )
                if (chromeVisible) {
                    ViewerTopBar(
                        title = file.name,
                        subtitle = buildString {
                            append(drivePreviewTypeLabel(file))
                            if (file.size > 0) append("  ·  ${formatBytes(context, file.size)}")
                        },
                        showResetZoom = false,
                        onResetZoom = {},
                        onClose = onClose,
                        externalLabel = "Open in Google Drive",
                        onOpenExternally = onOpenExternally,
                        modifier = Modifier.align(Alignment.TopCenter).zIndex(2f),
                        onShare = { shareDriveLink(context, file)?.let { vm.messages.tryEmit(it) } },
                        onDelete = onDelete,
                        deleteLabel = "Move to Google Drive's trash",
                        favorite = favoriteAccount?.let { vm.isDriveFavorite(favoriteIds, it, file.id) },
                        onToggleFavorite = { vm.toggleDriveFavorite(file, location) },
                        showTopScrim = true,
                    )
                }
            }
        }

        DrivePreviewKind.IMAGE -> {
            val folderImages = remember(folderFiles) {
                folderFiles.filter { classifyDriveFile(it.mimeType) == DrivePreviewKind.IMAGE }
            }
            val imageFiles = remember(folderImages, file.id) {
                if (folderImages.any { it.id == file.id }) folderImages else listOf(file)
            }
            val initialPage = remember(folderImages) {
                imageFiles.indexOfFirst { it.id == file.id }.coerceAtLeast(0)
            }
            val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { imageFiles.size })
            val transforms = remember { mutableStateMapOf<String, ImageTransformState>() }
            var dismissDragY by remember { mutableFloatStateOf(0f) }

            val activeFile = imageFiles.getOrElse(pagerState.currentPage) { file }
            val activeTransform = transforms.getOrPut(activeFile.id) { ImageTransformState() }
            val currentOnFileChanged by rememberUpdatedState(onFileChanged)

            LaunchedEffect(pagerState.currentPage, imageFiles) {
                imageFiles.getOrNull(pagerState.currentPage)?.let { currentOnFileChanged?.invoke(it) }
            }

            val openActiveExternally: () -> Unit = {
                if (onOpenFileExternally != null) onOpenFileExternally(activeFile) else onOpenExternally()
            }
            val deleteActive: (() -> Unit)? = when {
                onDeleteFile != null -> { { onDeleteFile(activeFile) } }
                onDelete != null -> onDelete
                else -> null
            }
            val backdropAlpha = (1f - (dismissDragY / 900f)).coerceIn(0.35f, 1f)

            Box(
                Modifier
                    .fillMaxSize()
                    .background(GlassPalette.OledBlack.copy(alpha = backdropAlpha)),
            ) {
                Box(Modifier.fillMaxSize().viewerPointerBarrier())
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = activeTransform.scale <= 1.05f,
                    key = { imageFiles[it].id },
                    modifier = Modifier.fillMaxSize().testTag("drive-image-pager"),
                ) { page ->
                    val pageFile = imageFiles[page]
                    val pageTransform = transforms.getOrPut(pageFile.id) { ImageTransformState() }
                    val pageOpenExternally: () -> Unit = {
                        if (onOpenFileExternally != null) onOpenFileExternally(pageFile) else onOpenExternally()
                    }
                    ImagePreviewContent(
                        vm = vm,
                        file = pageFile,
                        conn = conn,
                        cachedThumb = thumbProvider(pageFile),
                        transform = pageTransform,
                        onClose = onClose,
                        onOpenExternally = pageOpenExternally,
                        onDismissDragChange = { dismissDragY = it },
                    )
                }
                ViewerTopBar(
                    title = activeFile.name,
                    subtitle = buildString {
                        append(drivePreviewTypeLabel(activeFile))
                        if (imageFiles.size > 1) append("  ·  ${pagerState.currentPage + 1} of ${imageFiles.size}")
                        if (activeFile.size > 0) append("  ·  ${formatBytes(context, activeFile.size)}")
                    },
                    showResetZoom = activeTransform.scale > 1.05f,
                    onResetZoom = { activeTransform.reset() },
                    onClose = onClose,
                    externalLabel = "Open in Google Drive",
                    onOpenExternally = openActiveExternally,
                    modifier = Modifier.align(Alignment.TopCenter).zIndex(2f),
                    onShare = { shareDriveLink(context, activeFile)?.let { vm.messages.tryEmit(it) } },
                    onDelete = deleteActive,
                    deleteLabel = "Move to Google Drive's trash",
                    favorite = favoriteAccount?.let { vm.isDriveFavorite(favoriteIds, it, activeFile.id) },
                    onToggleFavorite = { vm.toggleDriveFavorite(activeFile, location) },
                    showTopScrim = true,
                )
            }
        }

        else -> {
            // Draw a dedicated backdrop so the underlying Drive grid does not bleed through the viewer.
            GlassBackdrop {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().viewerPointerBarrier())
                    FileDetailsPreview(
                        vm = vm,
                        file = file,
                        kind = kind,
                        cachedThumb = cachedThumb,
                        onOpenExternally = onOpenExternally,
                    )
                    ViewerTopBar(
                        title = file.name,
                        subtitle = buildString {
                            append(drivePreviewTypeLabel(file))
                            if (file.size > 0) append("  ·  ${formatBytes(context, file.size)}")
                        },
                        showResetZoom = false,
                        onResetZoom = {},
                        onClose = onClose,
                        externalLabel = "Open in Google Drive",
                        onOpenExternally = onOpenExternally,
                        modifier = Modifier.align(Alignment.TopCenter).zIndex(2f),
                        onShare = { shareDriveLink(context, file)?.let { vm.messages.tryEmit(it) } },
                        onDelete = onDelete,
                        deleteLabel = "Move to Google Drive's trash",
                        favorite = favoriteAccount?.let { vm.isDriveFavorite(favoriteIds, it, file.id) },
                        onToggleFavorite = { vm.toggleDriveFavorite(file, location) },
                    )
                }
            }
        }
    }
}

/** Shares a Drive file's view link via Android's standard `ACTION_SEND` chooser. */
internal fun shareDriveLink(context: Context, file: DriveFile): String? {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, file.name)
        .putExtra(Intent.EXTRA_TEXT, driveViewUrl(file.id))
    val chooser = Intent.createChooser(send, "Share ${file.name}")
    return try {
        context.startActivity(chooser)
        null
    } catch (_: ActivityNotFoundException) {
        "No app on this phone can share this link."
    }
}

/**
 * The viewer's top bar, shared by the Drive and Phone viewers: close, name and details, reset
 * zoom while zoomed, share, favorite, delete, and opening the file in another app. Touch targets
 * are at least 48 dp. When [showTopScrim] is true a dark top gradient scrim runs behind the bar
 * so icons stay readable over bright/white videos and photos.
 */
@Composable
internal fun ViewerTopBar(
    title: String,
    subtitle: String,
    showResetZoom: Boolean,
    onResetZoom: () -> Unit,
    onClose: () -> Unit,
    externalLabel: String,
    onOpenExternally: () -> Unit,
    modifier: Modifier = Modifier,
    /** Shares this item via Android's standard ACTION_SEND chooser. */
    onShare: (() -> Unit)? = null,
    /** Moves this file to the trash after confirmation (the phone's or Google Drive's). */
    onDelete: (() -> Unit)? = null,
    deleteLabel: String = "Move to this phone's trash",
    /** Null hides the star; otherwise whether this file is a favorite. */
    favorite: Boolean? = null,
    onToggleFavorite: () -> Unit = {},
    /** Draws a dark top gradient scrim behind the header bar for legibility over bright frames. */
    showTopScrim: Boolean = false,
) {
    val compact = LocalConfiguration.current.screenWidthDp < Tokens.COMPACT_WIDTH_DP
    val outerHorizontalPad = if (compact) Tokens.Small else Tokens.Edge
    val scrimModifier = if (showTopScrim) {
        Modifier
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        GlassPalette.VideoHeaderScrimTop,
                        GlassPalette.VideoHeaderScrimMid,
                        GlassPalette.VideoHeaderScrimBottom,
                    ),
                ),
            )
            .testTag("video-header-scrim")
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(scrimModifier)
            .pointerInput(Unit) {
                detectTapGestures { /* Keep taps on header chrome isolated from tap-to-hide. */ }
            }
            .padding(bottom = if (showTopScrim) Tokens.VideoHeaderScrimPadding else Tokens.NoInset),
    ) {
        GlassPanel(
            modifier = Modifier
                .fillMaxWidth()
                // The viewer fills the screen behind the status bar; the bar stays below it.
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = outerHorizontalPad, vertical = Tokens.Medium),
            shape = Tokens.PanelShape,
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Tokens.Tiny, vertical = Tokens.Tiny),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose, modifier = Modifier.size(Tokens.ViewerControl)) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close viewer")
                }
                Column(Modifier.weight(1f).padding(horizontal = Tokens.Tiny)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (showResetZoom) {
                    IconButton(onClick = onResetZoom, modifier = Modifier.size(Tokens.ViewerControl)) {
                        Icon(Icons.Outlined.CenterFocusWeak, contentDescription = "Reset zoom")
                    }
                }
                if (onShare != null) {
                    IconButton(
                        onClick = onShare,
                        modifier = Modifier.size(Tokens.ViewerControl).testTag("viewer-share"),
                    ) {
                        Icon(Icons.Outlined.Share, contentDescription = "Share")
                    }
                }
                if (favorite != null) {
                    FavoriteStar(favorite = favorite, name = title, onToggle = onToggleFavorite)
                }
                if (onDelete != null) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(Tokens.ViewerControl)) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = deleteLabel)
                    }
                }
                IconButton(onClick = onOpenExternally, modifier = Modifier.size(Tokens.ViewerControl)) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = externalLabel)
                }
            }
        }
    }
}

/** Stops taps and drags on the viewer's empty backdrop from reaching the screen beneath it. */
internal fun Modifier.viewerPointerBarrier(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        while (true) {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
            if (event.changes.none { it.pressed }) break
        }
    }
}

/**
 * A picture fitted to the screen with pinch zoom, zoomed pan, double-tap zoom, unzoomed horizontal
 * passthrough for [HorizontalPager], and vertical swipe-down to dismiss.
 */
@Composable
internal fun ZoomableImage(
    bitmap: ImageBitmap,
    contentDescription: String,
    key: Any,
    transform: ImageTransformState,
    onDismiss: (() -> Unit)? = null,
    onDismissDragChange: ((Float) -> Unit)? = null,
    bounds: () -> IntSize,
) {
    var dismissOffsetY by remember(key) { mutableFloatStateOf(0f) }
    val dismissThresholdPx = with(LocalDensity.current) { Tokens.SwipeDismissThreshold.toPx() }
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnDismissDrag by rememberUpdatedState(onDismissDragChange)
    val dismissScale = if (dismissOffsetY > 0f) {
        (1f - (dismissOffsetY / 1600f)).coerceIn(0.85f, 1f)
    } else {
        1f
    }

    Image(
        bitmap = bitmap,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .testTag("viewer-image")
            .pointerInput(key, transform) {
                detectTapGestures(onDoubleTap = { transform.toggleDoubleTap() })
            }
            .pointerInput(key, transform, dismissThresholdPx) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var verticalDismissActive = false
                    var cumulativePan = Offset.Zero
                    val touchSlop = viewConfiguration.touchSlop
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            if (verticalDismissActive) {
                                val shouldDismiss = dismissOffsetY >= dismissThresholdPx
                                dismissOffsetY = 0f
                                currentOnDismissDrag?.invoke(0f)
                                if (shouldDismiss) currentOnDismiss?.invoke()
                            }
                            break
                        }
                        if (pressed.size >= 2) {
                            if (verticalDismissActive) {
                                verticalDismissActive = false
                                dismissOffsetY = 0f
                                currentOnDismissDrag?.invoke(0f)
                            }
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (zoom != 1f || pan != Offset.Zero) {
                                transform.applyGesture(pan = pan, zoom = zoom, bounds = bounds())
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } else {
                            val change = pressed.first()
                            val delta = change.position - change.previousPosition
                            if (transform.scale > 1.05f) {
                                if (delta != Offset.Zero) {
                                    transform.applyGesture(pan = delta, zoom = 1f, bounds = bounds())
                                    if (change.positionChanged()) change.consume()
                                }
                            } else if (currentOnDismiss != null) {
                                cumulativePan += delta
                                if (!verticalDismissActive) {
                                    if (
                                        cumulativePan.y > touchSlop &&
                                        abs(cumulativePan.y) > abs(cumulativePan.x) * 1.2f
                                    ) {
                                        verticalDismissActive = true
                                    }
                                }
                                if (verticalDismissActive) {
                                    dismissOffsetY = (dismissOffsetY + delta.y).coerceAtLeast(0f)
                                    currentOnDismissDrag?.invoke(dismissOffsetY)
                                    if (change.positionChanged()) change.consume()
                                }
                            }
                        }
                    }
                }
            }
            .graphicsLayer(
                scaleX = transform.scale * dismissScale,
                scaleY = transform.scale * dismissScale,
                translationX = transform.offset.x,
                translationY = transform.offset.y + dismissOffsetY,
            ),
    )
}

@Composable
private fun ImagePreviewContent(
    vm: AppViewModel,
    file: DriveFile,
    conn: DriveConn,
    cachedThumb: ImageBitmap?,
    transform: ImageTransformState,
    onClose: () -> Unit,
    onOpenExternally: () -> Unit,
    onDismissDragChange: (Float) -> Unit = {},
) {
    var state by remember(file.id) { mutableStateOf<ImageViewerState>(ImageViewerState.Loading) }
    var reload by remember(file.id) { mutableIntStateOf(0) }
    var loadedBytes by remember(file.id) { mutableLongStateOf(0L) }
    var totalBytes by remember(file.id) { mutableLongStateOf(file.size) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val currentBoxSize by rememberUpdatedState(boxSize)

    LaunchedEffect(file.id, reload, conn) {
        if (state is ImageViewerState.AuthExpired && conn != DriveConn.CONNECTED) {
            return@LaunchedEffect
        }
        state = ImageViewerState.Loading
        loadedBytes = 0L
        totalBytes = file.size
        try {
            val bytes = withContext(Dispatchers.IO) {
                vm.driveFolders.loadPreviewBytes(file, MAX_IMAGE_VIEWER_BYTES) { read, total ->
                    loadedBytes = read
                    if (total > 0) totalBytes = total
                }
            }
            val decoded = decodeSampledImageBitmap(bytes, MAX_IMAGE_DECODE_EDGE)
            state = if (decoded != null) {
                ImageViewerState.Ready(decoded)
            } else {
                ImageViewerState.Error("Drive returned an image format this device could not decode.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: AuthRequiredException) {
            state = ImageViewerState.AuthExpired
        } catch (e: Exception) {
            state = ImageViewerState.Error(describeError(e))
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it },
        contentAlignment = Alignment.Center,
    ) {
        val displayBitmap = when (val s = state) {
            is ImageViewerState.Ready -> s.bitmap
            else -> cachedThumb
        }

        if (displayBitmap != null) {
            ZoomableImage(
                bitmap = displayBitmap,
                contentDescription = file.name,
                key = file.id,
                transform = transform,
                onDismiss = onClose,
                onDismissDragChange = onDismissDragChange,
            ) { currentBoxSize }
        }

        // Until the full image is ready the cached thumbnail is only context. Dim it with the shared
        // scrim so the translucent glass notice above stays readable over bright pictures.
        if (displayBitmap != null && state !is ImageViewerState.Ready) {
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("viewer-placeholder-scrim")
                    .background(GlassPalette.ThumbScrim),
            )
        }

        when (val s = state) {
            ImageViewerState.Loading -> {
                GlassPanel(
                    modifier = Modifier.padding(Tokens.Edge),
                    shape = Tokens.PanelShape,
                ) {
                    Column(
                        Modifier.padding(Tokens.Edge),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (totalBytes > 0 && loadedBytes > 0) {
                            LinearProgressIndicator(
                                progress = { (loadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) },
                                modifier = Modifier.width(Tokens.ViewerProgressWidth),
                            )
                        } else {
                            CircularProgressIndicator()
                        }
                        Spacer(Modifier.height(Tokens.Medium))
                        Text("Loading image preview", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = onClose, modifier = Modifier.padding(top = Tokens.Tiny)) {
                            Text("Cancel")
                        }
                    }
                }
            }

            ImageViewerState.AuthExpired -> {
                ViewerNoticeCard(
                    title = "Google Drive authorization expired",
                    body = "Sign in to your connected Google Drive account again to load this image.",
                    primaryLabel = "Reconnect",
                    onPrimary = { vm.connectDrive(interactive = true) },
                    secondaryLabel = "Try again",
                    onSecondary = { reload++ },
                )
            }

            is ImageViewerState.Error -> {
                ViewerNoticeCard(
                    title = "Could not load image",
                    body = s.message,
                    primaryLabel = "Try again",
                    onPrimary = { reload++ },
                    secondaryLabel = "Open in Google Drive",
                    onSecondary = onOpenExternally,
                )
            }

            is ImageViewerState.Ready -> Unit
        }
    }
}

@Composable
private fun FileDetailsPreview(
    vm: AppViewModel,
    file: DriveFile,
    kind: DrivePreviewKind,
    cachedThumb: ImageBitmap?,
    onOpenExternally: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember(file.id) { mutableStateOf(false) }
    var downloadError by remember(file.id) { mutableStateOf<String?>(null) }
    val visualKind = remember(file.id, file.mimeType, file.name) { classifyDriveVisualKind(file) }
    val icon = driveVisualIcon(visualKind)
    val iconTint = driveVisualIconTint(visualKind)
    val summary = when (kind) {
        DrivePreviewKind.WORKSPACE_PDF_EXPORT ->
            "Download a PDF copy to open on this phone, or open directly in Google Drive."
        DrivePreviewKind.VIDEO ->
            "Download this video to open on this phone, or open in Google Drive."
        DrivePreviewKind.PDF ->
            "Download this PDF to open in your phone's PDF viewer, or open in Google Drive."
        else ->
            "Download a local copy to open with an app on this phone, or open in Google Drive."
    }

    Box(
        Modifier
            .fillMaxSize()
            .padding(
                top = Tokens.ViewerTopClearance,
                start = Tokens.Edge,
                end = Tokens.Edge,
                bottom = Tokens.Edge,
            ),
        contentAlignment = Alignment.Center,
    ) {
        GlassPanel(modifier = Modifier.fillMaxWidth().widthIn(max = Tokens.ReadableWidth)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .testTag("viewer-details")
                    .verticalScroll(rememberScrollState())
                    .padding(Tokens.Edge),
            ) {
                if (cachedThumb != null) {
                    Image(
                        bitmap = cachedThumb,
                        contentDescription = file.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(Tokens.GaugeHeight)
                            .clip(Tokens.ThumbShape)
                            .padding(bottom = Tokens.Gutter),
                    )
                } else {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.padding(bottom = Tokens.Medium).size(Tokens.EmptyState),
                    )
                }
                Text(file.name, style = MaterialTheme.typography.titleLarge)
                val details = buildString {
                    append(drivePreviewTypeLabel(file))
                    if (file.size > 0) append("  \u00B7  ${formatBytes(context, file.size)}")
                    if (file.durationMillis > 0) append("  \u00B7  ${formatDuration(file.durationMillis)}")
                }
                Text(
                    details,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Tokens.Tiny),
                )
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Tokens.Medium, bottom = Tokens.Gutter),
                )
                if (downloadError != null) {
                    Text(
                        downloadError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = Tokens.Small),
                    )
                }
                OutlinedButton(
                    enabled = !downloading,
                    onClick = {
                        downloading = true
                        downloadError = null
                        scope.launch {
                            try {
                                val cached = withContext(Dispatchers.IO) {
                                    vm.downloadDriveFileToCache(file)
                                }
                                val openErr = LocalFileOpener.openFile(
                                    context,
                                    cached,
                                    driveDownloadMimeType(file),
                                )
                                if (openErr != null) {
                                    downloadError = openErr
                                    vm.messages.tryEmit(openErr)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: AuthRequiredException) {
                                downloadError = "Google Drive authorization expired. Reconnect and try again."
                            } catch (e: Exception) {
                                downloadError = describeError(e)
                            } finally {
                                downloading = false
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = Tokens.TouchTarget)
                        .testTag("viewer-download-open"),
                ) {
                    if (downloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(Tokens.IconSmall),
                            strokeWidth = Tokens.Stroke,
                        )
                    } else {
                        Icon(
                            Icons.Outlined.Download,
                            contentDescription = null,
                            modifier = Modifier.size(Tokens.IconSmall),
                        )
                    }
                    Spacer(Modifier.width(Tokens.Small))
                    Text(if (downloading) "Downloading..." else "Download and open")
                }
                Spacer(Modifier.height(Tokens.Small))
                Button(
                    onClick = onOpenExternally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = Tokens.TouchTarget),
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(Tokens.IconSmall),
                    )
                    Spacer(Modifier.width(Tokens.Small))
                    Text("Open in Google Drive")
                }
            }
        }
    }
}

@Composable
internal fun ViewerNoticeCard(
    title: String,
    body: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
) {
    val stacked = LocalDensity.current.fontScale >= Tokens.STACK_AT_FONT_SCALE ||
        LocalConfiguration.current.screenWidthDp < Tokens.COMPACT_WIDTH_DP
    GlassPanel(
        modifier = Modifier
            .padding(
                top = Tokens.ViewerTopClearance,
                start = Tokens.Edge,
                end = Tokens.Edge,
                bottom = Tokens.Edge,
            )
            .widthIn(max = Tokens.ReadableWidth),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(Tokens.Edge)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Tokens.Tiny, bottom = Tokens.Gutter),
            )
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                    Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth()) { Text(primaryLabel) }
                    OutlinedButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth()) { Text(secondaryLabel) }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                    Button(onClick = onPrimary) { Text(primaryLabel) }
                    OutlinedButton(onClick = onSecondary) { Text(secondaryLabel) }
                }
            }
        }
    }
}

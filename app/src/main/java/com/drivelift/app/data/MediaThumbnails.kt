package com.drivelift.app.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.CancellationSignal
import android.provider.MediaStore
import android.util.Size
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import coil.size.pxOrElse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Loads a phone photo or video thumbnail from Android's own thumbnail store
 * ([ContentResolver.loadThumbnail]) instead of decoding the original file.
 *
 * Without this, every grid cell opened the full original: a photo was decoded from its full
 * size, and a video was opened with a media retriever to pull out a frame, which is slow for
 * long or unusual videos. Android keeps small thumbnails for media and makes them once, so a
 * scroll back to the same files does not pay again.
 *
 * Only MediaStore content addresses are handled. When Android cannot give a thumbnail (a file
 * it cannot read, a format it does not know), [fetch] returns null and Coil falls back to the
 * next fetcher, which decodes the original as before.
 */
class MediaThumbnailFetcher(
    private val uri: Uri,
    private val options: Options,
    private val load: (uri: Uri, size: Size, signal: CancellationSignal) -> Bitmap,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val width = options.size.width.pxOrElse { DEFAULT_EDGE_PX }
        val height = options.size.height.pxOrElse { DEFAULT_EDGE_PX }
        val signal = CancellationSignal()
        // A cell scrolled away cancels its request; that also stops Android's work on it.
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { signal.cancel() }
        val bitmap = try {
            load(uri, Size(width, height), signal)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            handle?.dispose()
        }
        currentCoroutineContext().ensureActive()
        bitmap ?: return null
        return DrawableResult(
            drawable = BitmapDrawable(options.context.resources, bitmap),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(
        private val load: ((uri: Uri, size: Size, signal: CancellationSignal) -> Bitmap)? = null,
    ) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (!isMediaStoreUri(data)) return null
            val loader = load ?: { uri, size, signal -> options.context.contentResolver.loadThumbnail(uri, size, signal) }
            return MediaThumbnailFetcher(data, options, loader)
        }
    }

    companion object {
        /** Used when a request does not say how large the image will be shown. */
        const val DEFAULT_EDGE_PX = 256

        fun isMediaStoreUri(uri: Uri): Boolean =
            uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority == MediaStore.AUTHORITY
    }
}

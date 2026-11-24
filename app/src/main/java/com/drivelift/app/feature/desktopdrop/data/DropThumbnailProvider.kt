package com.drivelift.app.feature.desktopdrop.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Size
import com.drivelift.app.data.MediaThumbnailFetcher
import com.drivelift.app.feature.desktopdrop.model.SharedDropItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** Encoded thumbnail payload served by Desktop Drop's `/thumb/{id}` HTTP endpoint. */
class DropThumbnailData(
    val bytes: ByteArray,
    val contentType: String,
)

/** Abstraction for producing lightweight HTTP thumbnails for shared items. */
interface DropThumbnailProvider {
    suspend fun loadThumbnail(
        item: SharedDropItem,
        maxEdgePx: Int = DropThumbnailSupport.DEFAULT_MAX_EDGE_PX,
    ): DropThumbnailData?
}

/**
 * Pure-JVM fallback thumbnail provider used by default in [com.drivelift.app.feature.desktopdrop.server.DesktopDropServerEngine]
 * when running outside Android (for example pure JUnit server engine tests).
 */
class PassthroughDropThumbnailProvider(
    private val itemSource: SharedItemSource,
) : DropThumbnailProvider {

    override suspend fun loadThumbnail(item: SharedDropItem, maxEdgePx: Int): DropThumbnailData? {
        if (!DropThumbnailSupport.isImage(item)) return null
        if (!DropThumbnailSupport.isBrowserSafeImageMime(item.mimeType)) return null
        if (item.sizeBytes > DropThumbnailSupport.MAX_RAW_PASSTHROUGH_BYTES) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = itemSource.openInputStream(item).use { input ->
                    readCapped(input, DropThumbnailSupport.MAX_RAW_PASSTHROUGH_BYTES.toInt())
                } ?: return@runCatching null
                if (bytes.isEmpty()) null else DropThumbnailData(bytes, normalizedImageMime(item.mimeType))
            }.getOrNull()
        }
    }
}

/**
 * Android thumbnail generator backed by MediaStore's thumbnail cache, downsampled [BitmapFactory]
 * decoding with EXIF orientation handling, and [MediaMetadataRetriever] video frames, with a small
 * bounded in-memory LRU cache to avoid redundant decoding on browser refreshes.
 */
class AndroidDropThumbnailProvider(
    private val context: Context,
    private val itemSource: SharedItemSource,
    private val maxCacheEntries: Int = DEFAULT_CACHE_ENTRIES,
) : DropThumbnailProvider {

    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<String, DropThumbnailData>(maxCacheEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DropThumbnailData>?): Boolean =
            size > maxCacheEntries
    }

    override suspend fun loadThumbnail(item: SharedDropItem, maxEdgePx: Int): DropThumbnailData? {
        if (!DropThumbnailSupport.supportsThumbnail(item)) return null
        val safeEdge = maxEdgePx.coerceIn(64, 512)
        val cacheKey = "${item.id}:${item.sizeBytes}:$safeEdge"
        synchronized(cacheLock) {
            cache[cacheKey]?.let { return it }
        }
        val generated = withContext(Dispatchers.IO) {
            when {
                DropThumbnailSupport.isVideo(item) -> generateVideoThumbnail(item, safeEdge)
                DropThumbnailSupport.isImage(item) -> generateImageThumbnail(item, safeEdge)
                else -> null
            }
        } ?: return null

        synchronized(cacheLock) {
            cache[cacheKey] = generated
        }
        return generated
    }

    private fun generateImageThumbnail(item: SharedDropItem, maxEdgePx: Int): DropThumbnailData? {
        loadMediaStoreThumbnail(item, maxEdgePx)?.let { bmp ->
            return encodeScaledJpeg(bmp, maxEdgePx)
        }
        val decoded = decodeSampledBitmap(item, maxEdgePx)
        if (decoded != null) {
            val oriented = applyExifOrientation(item, decoded)
            return encodeScaledJpeg(oriented, maxEdgePx)
        }
        // Fallback for small browser-safe images when BitmapFactory cannot decode the format.
        if (DropThumbnailSupport.isBrowserSafeImageMime(item.mimeType) &&
            item.sizeBytes in 1L..DropThumbnailSupport.MAX_RAW_PASSTHROUGH_BYTES
        ) {
            val raw = runCatching {
                itemSource.openInputStream(item).use { input ->
                    readCapped(input, DropThumbnailSupport.MAX_RAW_PASSTHROUGH_BYTES.toInt())
                }
            }.getOrNull()
            if (raw != null && raw.isNotEmpty()) {
                return DropThumbnailData(raw, normalizedImageMime(item.mimeType))
            }
        }
        return null
    }

    private fun generateVideoThumbnail(item: SharedDropItem, maxEdgePx: Int): DropThumbnailData? {
        loadMediaStoreThumbnail(item, maxEdgePx)?.let { bmp ->
            return encodeScaledJpeg(bmp, maxEdgePx)
        }
        val retriever = MediaMetadataRetriever()
        return try {
            val localFile = itemSource.localFileOrNull(item)
            when {
                localFile != null -> retriever.setDataSource(localFile.absolutePath)
                !item.sourceUri.isNullOrBlank() -> retriever.setDataSource(context, Uri.parse(item.sourceUri))
                else -> return null
            }
            val frame = runCatching {
                retriever.getScaledFrameAtTime(
                    -1L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    maxEdgePx,
                    maxEdgePx,
                )
            }.getOrNull() ?: runCatching { retriever.frameAtTime }.getOrNull()
            frame?.let { encodeScaledJpeg(it, maxEdgePx) }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun loadMediaStoreThumbnail(item: SharedDropItem, maxEdgePx: Int): Bitmap? {
        val uriStr = item.sourceUri ?: return null
        val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return null
        if (!MediaThumbnailFetcher.isMediaStoreUri(uri)) return null
        return runCatching {
            context.contentResolver.loadThumbnail(uri, Size(maxEdgePx, maxEdgePx), null)
        }.getOrNull()
    }

    private fun decodeSampledBitmap(item: SharedDropItem, maxEdgePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            itemSource.openInputStream(item).use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
        }
        val sampleSize = DropThumbnailSupport.computeInSampleSize(bounds.outWidth, bounds.outHeight, maxEdgePx)
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return runCatching {
            itemSource.openInputStream(item).use { input ->
                BitmapFactory.decodeStream(input, null, decodeOpts)
            }
        }.getOrNull()
    }

    private fun applyExifOrientation(item: SharedDropItem, source: Bitmap): Bitmap {
        val orientation = runCatching {
            itemSource.openInputStream(item).use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return source
        }
        return try {
            val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            if (rotated !== source) source.recycle()
            rotated
        } catch (_: Throwable) {
            source
        }
    }

    private fun encodeScaledJpeg(source: Bitmap, maxEdgePx: Int): DropThumbnailData? {
        var working = source
        try {
            val w = working.width
            val h = working.height
            if (w <= 0 || h <= 0) return null
            val longest = maxOf(w, h)
            if (longest > maxEdgePx) {
                val scale = maxEdgePx.toFloat() / longest.toFloat()
                val dstW = (w * scale).roundToInt().coerceAtLeast(1)
                val dstH = (h * scale).roundToInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(working, dstW, dstH, true)
                if (scaled !== working) {
                    working.recycle()
                    working = scaled
                }
            }
            val out = ByteArrayOutputStream(8 * 1024)
            if (!working.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) return null
            val bytes = out.toByteArray()
            return if (bytes.isEmpty()) null else DropThumbnailData(bytes, MIME_JPEG)
        } catch (_: Throwable) {
            return null
        } finally {
            if (!working.isRecycled) working.recycle()
        }
    }

    companion object {
        const val DEFAULT_CACHE_ENTRIES = 64
        const val JPEG_QUALITY = 78
        const val MIME_JPEG = "image/jpeg"
    }
}

private fun normalizedImageMime(raw: String): String {
    val clean = raw.lowercase(java.util.Locale.US).substringBefore(';').trim()
    return if (clean == "image/jpg") "image/jpeg" else clean
}

private fun readCapped(input: java.io.InputStream, maxBytes: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val read = input.read(buf)
        if (read < 0) break
        total += read
        if (total > maxBytes) return null
        out.write(buf, 0, read)
    }
    return out.toByteArray()
}

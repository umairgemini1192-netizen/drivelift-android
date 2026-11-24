package com.drivelift.app.feature.desktopdrop.data

import com.drivelift.app.data.LocalFileType
import com.drivelift.app.data.classifyLocalFile
import com.drivelift.app.data.localFileTypeLabel
import com.drivelift.app.feature.desktopdrop.model.SharedDropItem
import java.util.Locale

/**
 * Shared file classification and thumbnail eligibility helpers used by both the phone Share Queue
 * visual grid and the local HTTP `/thumb/{id}` server endpoint.
 */
object DropThumbnailSupport {

    const val DEFAULT_MAX_EDGE_PX = 256
    const val MAX_RAW_PASSTHROUGH_BYTES = 512 * 1024L

    private val BROWSER_SAFE_IMAGE_MIMES = setOf(
        "image/jpeg",
        "image/jpg",
        "image/png",
        "image/webp",
        "image/gif",
    )

    fun classifyItem(item: SharedDropItem): LocalFileType {
        val byName = classifyLocalFile(item.name, isDirectory = false)
        if (byName != LocalFileType.OTHER) return byName
        val mime = item.mimeType.lowercase(Locale.US).substringBefore(';').trim()
        return when {
            mime.startsWith("image/") -> LocalFileType.IMAGE
            mime.startsWith("video/") -> LocalFileType.VIDEO
            mime.startsWith("audio/") -> LocalFileType.AUDIO
            mime == "application/pdf" -> LocalFileType.PDF
            mime == "application/vnd.android.package-archive" -> LocalFileType.APK
            mime == "application/zip" ||
                mime == "application/x-zip-compressed" ||
                mime == "application/x-7z-compressed" ||
                mime == "application/vnd.rar" ||
                mime == "application/gzip" -> LocalFileType.ARCHIVE
            mime == "text/csv" ||
                mime == "application/vnd.ms-excel" ||
                mime.contains("spreadsheet") -> LocalFileType.SPREADSHEET
            mime == "application/vnd.ms-powerpoint" ||
                mime.contains("presentation") -> LocalFileType.PRESENTATION
            mime.startsWith("text/") ||
                mime == "application/msword" ||
                mime == "application/rtf" ||
                mime.contains("wordprocessingml") ||
                mime.contains("opendocument.text") -> LocalFileType.DOCUMENT
            else -> LocalFileType.OTHER
        }
    }

    fun isImage(item: SharedDropItem): Boolean = classifyItem(item) == LocalFileType.IMAGE

    fun isVideo(item: SharedDropItem): Boolean = classifyItem(item) == LocalFileType.VIDEO

    fun supportsThumbnail(item: SharedDropItem): Boolean {
        val type = classifyItem(item)
        return type == LocalFileType.IMAGE || type == LocalFileType.VIDEO
    }

    fun isBrowserSafeImageMime(mimeType: String): Boolean =
        mimeType.lowercase(Locale.US).substringBefore(';').trim() in BROWSER_SAFE_IMAGE_MIMES

    fun typeBadgeLabel(item: SharedDropItem): String =
        localFileTypeLabel(classifyItem(item), item.name)

    /** Power-of-two subsampling factor for [android.graphics.BitmapFactory.Options.inSampleSize]. */
    fun computeInSampleSize(width: Int, height: Int, maxEdgePx: Int): Int {
        if (width <= 0 || height <= 0 || maxEdgePx <= 0) return 1
        var sample = 1
        var halfW = width / 2
        var halfH = height / 2
        while (halfW >= maxEdgePx || halfH >= maxEdgePx) {
            sample *= 2
            halfW /= 2
            halfH /= 2
        }
        return sample.coerceAtLeast(1)
    }
}

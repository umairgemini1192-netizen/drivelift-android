package com.drivelift.app.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.util.Locale

/** Folder, under the phone's Pictures, where frames saved from videos go. */
const val FRAME_FOLDER = "DriveLift"

/** "Trip to the coast.mp4" at 83 s becomes "Trip_to_the_coast_01-23.jpg". */
fun frameFileName(videoName: String, positionMs: Long): String {
    val base = videoName.substringBeforeLast('.').ifBlank { "video" }
        .replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(80).ifBlank { "video" }
    val total = (positionMs.coerceAtLeast(0) / 1000)
    val stamp = if (total >= 3600) {
        String.format(Locale.US, "%d-%02d-%02d", total / 3600, (total % 3600) / 60, total % 60)
    } else {
        String.format(Locale.US, "%02d-%02d", total / 60, total % 60)
    }
    return "${base}_$stamp.jpg"
}

/**
 * Saves a JPEG into Pictures/DriveLift through MediaStore. An app may add its own pictures there
 * on Android 10 and later without any storage permission. Returns the new item, or null.
 */
fun saveFrameToPictures(context: Context, bitmap: Bitmap, displayName: String): Uri? {
    val resolver = context.contentResolver
    var pending: Uri? = null
    return try {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FRAME_FOLDER")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return null
        pending = uri
        val written = resolver.openOutputStream(uri)?.use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
        } == true
        if (!written) throw java.io.IOException("The picture could not be written")
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        pending = null
        uri
    } catch (_: Exception) {
        // Storage full, unavailable or refused: remove the half-made item if there is one, and
        // let the caller say the save failed.
        pending?.let { runCatching { resolver.delete(it, null, null) } }
        null
    }
}

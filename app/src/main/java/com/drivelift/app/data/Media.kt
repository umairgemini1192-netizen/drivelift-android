package com.drivelift.app.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val size: Long,
    val mime: String,
    val dateTakenMs: Long,
    val dateModifiedMs: Long,
    val bucketId: Long,
    val bucketName: String,
    val relativePath: String,
    val isVideo: Boolean,
    val durationMs: Long,
)

data class MediaFolder(
    val bucketId: Long,
    val name: String,
    val path: String,
    val count: Int,
    val bytes: Long,
    val cover: Uri,
    val newestMs: Long,
)

fun groupFolders(items: List<MediaItem>): List<MediaFolder> =
    items.groupBy { it.bucketId }.map { (bucketId, list) ->
        val newest = list.maxBy { it.dateTakenMs }
        MediaFolder(
            bucketId = bucketId,
            name = newest.bucketName,
            path = newest.relativePath.trimEnd('/'),
            count = list.size,
            bytes = list.sumOf { it.size },
            cover = newest.uri,
            newestMs = newest.dateTakenMs,
        )
    }.sortedByDescending { it.newestMs }

class SourceMissingException(message: String) : Exception(message)

object MediaAccess {
    /** Everything asked for in one system prompt, including notifications. */
    fun runtimePermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
            add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        add(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }.toTypedArray()

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun hasPermission(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= 33 ->
            granted(context, Manifest.permission.READ_MEDIA_IMAGES) ||
                granted(context, Manifest.permission.READ_MEDIA_VIDEO) ||
                (Build.VERSION.SDK_INT >= 34 &&
                    granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        else -> granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** True when the user picked "selected photos only" on Android 14+. */
    fun isPartial(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 34 &&
            !granted(context, Manifest.permission.READ_MEDIA_IMAGES) &&
            granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    /**
     * Opens the original bytes of a media file. With ACCESS_MEDIA_LOCATION the
     * unredacted original is requested so location EXIF survives the move.
     */
    fun open(context: Context, uri: Uri): ParcelFileDescriptor {
        val resolver = context.contentResolver
        fun plain(): ParcelFileDescriptor = try {
            resolver.openFileDescriptor(uri, "r")
        } catch (e: FileNotFoundException) {
            throw SourceMissingException("The file is no longer on this phone")
        } catch (e: SecurityException) {
            throw SourceMissingException("The app no longer has access to this file")
        } ?: throw SourceMissingException("The file could not be opened")

        if (!granted(context, Manifest.permission.ACCESS_MEDIA_LOCATION)) return plain()
        return try {
            resolver.openFileDescriptor(MediaStore.setRequireOriginal(uri), "r") ?: plain()
        } catch (e: FileNotFoundException) {
            throw SourceMissingException("The file is no longer on this phone")
        } catch (e: Exception) {
            plain()
        }
    }
}

class MediaRepository(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _items = MutableStateFlow<List<MediaItem>>(emptyList())
    val items: StateFlow<List<MediaItem>> = _items.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private var observing = false
    private var debounce: Job? = null

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            debounce?.cancel()
            debounce = scope.launch {
                delay(600)
                refresh()
            }
        }
    }

    suspend fun refresh() {
        if (!MediaAccess.hasPermission(context)) {
            _items.value = emptyList()
            return
        }
        if (!observing) {
            observing = true
            context.contentResolver.registerContentObserver(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), true, observer,
            )
        }
        _items.value = withContext(Dispatchers.IO) { query() }
        _loaded.value = true
    }

    private fun query(): List<MediaItem> {
        val projection = arrayOf(
            FileColumns._ID, FileColumns.DISPLAY_NAME, FileColumns.SIZE, FileColumns.MIME_TYPE,
            FileColumns.DATE_TAKEN, FileColumns.DATE_MODIFIED, FileColumns.BUCKET_ID,
            FileColumns.BUCKET_DISPLAY_NAME, FileColumns.RELATIVE_PATH, FileColumns.MEDIA_TYPE,
            FileColumns.DURATION,
        )
        val selection =
            "${FileColumns.MEDIA_TYPE} IN (${FileColumns.MEDIA_TYPE_IMAGE}, ${FileColumns.MEDIA_TYPE_VIDEO})"
        val out = ArrayList<MediaItem>()
        context.contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            projection, selection, null, "${FileColumns.DATE_MODIFIED} DESC",
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(FileColumns._ID)
            val iName = c.getColumnIndexOrThrow(FileColumns.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(FileColumns.SIZE)
            val iMime = c.getColumnIndexOrThrow(FileColumns.MIME_TYPE)
            val iTaken = c.getColumnIndexOrThrow(FileColumns.DATE_TAKEN)
            val iMod = c.getColumnIndexOrThrow(FileColumns.DATE_MODIFIED)
            val iBucket = c.getColumnIndexOrThrow(FileColumns.BUCKET_ID)
            val iBucketName = c.getColumnIndexOrThrow(FileColumns.BUCKET_DISPLAY_NAME)
            val iPath = c.getColumnIndexOrThrow(FileColumns.RELATIVE_PATH)
            val iType = c.getColumnIndexOrThrow(FileColumns.MEDIA_TYPE)
            val iDuration = c.getColumnIndexOrThrow(FileColumns.DURATION)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val isVideo = c.getInt(iType) == FileColumns.MEDIA_TYPE_VIDEO
                val modifiedMs = c.getLong(iMod) * 1000
                val takenMs = c.getLong(iTaken).takeIf { it > 0 } ?: modifiedMs
                val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                out += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(base, id),
                    name = c.getString(iName) ?: "file_$id",
                    size = c.getLong(iSize),
                    mime = c.getString(iMime) ?: if (isVideo) "video/*" else "image/*",
                    dateTakenMs = takenMs,
                    dateModifiedMs = modifiedMs,
                    bucketId = c.getLong(iBucket),
                    bucketName = c.getString(iBucketName) ?: "Internal storage",
                    relativePath = c.getString(iPath) ?: "",
                    isVideo = isVideo,
                    durationMs = c.getLong(iDuration),
                )
            }
        }
        out.sortByDescending { it.dateTakenMs }
        return out
    }
}

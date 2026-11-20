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
    /**
     * The storage volume MediaStore keeps the file on ("external_primary" for the phone's own
     * storage, another name for an SD card or USB drive). A folder move never changes it.
     */
    val volume: String = MediaStore.VOLUME_EXTERNAL_PRIMARY,
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

/** The file is confirmed gone from the phone. No retry can bring it back. */
class SourceMissingException(message: String) : Exception(message)

/**
 * The file may well still exist, but its original bytes cannot be read right now: access was
 * revoked, is limited to selected photos, or Android refused the unredacted original.
 * Recoverable, so the pipeline retries and never treats it as the file being gone.
 */
class SourceInaccessibleException(message: String) : Exception(message)

/**
 * Decides what a failed open means. "File not found" only proves absence while the app can see
 * every photo and every video; with limited access Android reports unseen files the same way.
 */
fun classifyOpenFailure(error: Exception, visibility: MediaVisibility): Exception = when {
    error is FileNotFoundException && visibility == MediaVisibility.FULL ->
        SourceMissingException("The file is no longer on this phone")
    error is FileNotFoundException ->
        SourceInaccessibleException("This file is not visible with the current access. Allow access to all photos and videos")
    error is SecurityException ->
        SourceInaccessibleException("Photo access was taken away. Allow it in system settings to continue")
    else ->
        SourceInaccessibleException("Android would not open the original of this file (${error.javaClass.simpleName})")
}

/** How much of the phone's photo and video library the granted permissions let the app list. */
enum class MediaVisibility { NONE, PARTIAL, FULL }

/**
 * Classifies the granted media permissions. Kept free of Android types so every combination can
 * be tested. From Android 13 photos and videos are separate grants, and from Android 14 the user
 * can grant a selection only. Anything short of both whole categories is [MediaVisibility.PARTIAL]:
 * photos without videos, videos without photos, or a hand-picked selection. A partial view never
 * proves that a file is gone from the phone, because the file may be one the app cannot see.
 */
fun mediaVisibility(
    sdk: Int,
    images: Boolean,
    video: Boolean,
    userSelected: Boolean,
    legacyStorage: Boolean,
): MediaVisibility = when {
    sdk < 33 -> if (legacyStorage) MediaVisibility.FULL else MediaVisibility.NONE
    images && video -> MediaVisibility.FULL
    images || video || (sdk >= 34 && userSelected) -> MediaVisibility.PARTIAL
    else -> MediaVisibility.NONE
}

object MediaAccess {
    /**
     * True when the owner granted Media management access (Android 12+), so removal can use
     * DriveLift's text-only confirmation and skip Android's picture preview.
     */
    fun canManageMedia(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 31 && android.provider.MediaStore.canManageMedia(context)

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

    fun visibility(context: Context): MediaVisibility {
        val sdk = Build.VERSION.SDK_INT
        return mediaVisibility(
            sdk = sdk,
            images = sdk >= 33 && granted(context, Manifest.permission.READ_MEDIA_IMAGES),
            video = sdk >= 33 && granted(context, Manifest.permission.READ_MEDIA_VIDEO),
            userSelected = sdk >= 34 && granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
            legacyStorage = sdk < 33 && granted(context, Manifest.permission.READ_EXTERNAL_STORAGE),
        )
    }

    /** True when at least some photos or videos can be listed. */
    fun hasPermission(context: Context): Boolean = visibility(context) != MediaVisibility.NONE

    /** True when the user picked "selected photos only" on Android 14+. */
    fun isPartial(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 34 &&
            !granted(context, Manifest.permission.READ_MEDIA_IMAGES) &&
            granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)


    /**
     * Opens the unredacted original of a media file, or throws. There is no fallback to the
     * ordinary, possibly location-stripped stream: uploading that would break the promise that
     * the Drive copy is the original, byte for byte.
     *
     * @throws SourceMissingException the file is confirmed gone
     * @throws SourceInaccessibleException the original cannot be read right now (recoverable)
     */
    fun open(context: Context, uri: Uri): ParcelFileDescriptor {
        if (!hasPermission(context)) {
            throw SourceInaccessibleException("Photo access is off. Allow it in system settings to continue")
        }
        if (!granted(context, Manifest.permission.ACCESS_MEDIA_LOCATION)) {
            throw SourceInaccessibleException(
                "Allow DriveLift to read photo locations so files are moved unchanged, with their location data"
            )
        }
        return try {
            context.contentResolver.openFileDescriptor(MediaStore.setRequireOriginal(uri), "r")
        } catch (e: Exception) {
            throw classifyOpenFailure(e, visibility(context))
        } ?: throw SourceInaccessibleException("Android returned no data for the original of this file")
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
            FileColumns.DURATION, FileColumns.VOLUME_NAME,
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
            val iVolume = c.getColumnIndex(FileColumns.VOLUME_NAME)
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
                    volume = (if (iVolume >= 0) c.getString(iVolume) else null) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY,
                )
            }
        }
        out.sortByDescending { it.dateTakenMs }
        return out
    }
}

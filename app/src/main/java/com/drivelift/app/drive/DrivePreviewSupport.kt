package com.drivelift.app.drive

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.security.MessageDigest

/** Explicit preview capability matrix for Google Drive entries. */
enum class DrivePreviewKind {
    IMAGE,
    VIDEO,
    PDF,
    WORKSPACE_PDF_EXPORT,
    UNSUPPORTED,
}

/** Google Vids. A Google-native type: `alt=media` and `files.export` both refuse it. */
const val GOOGLE_VIDS_MIME = "application/vnd.google-apps.vid"

/**
 * How the in-app player gets bytes for a video. Google-native types cannot use `alt=media`
 * (fileNotDownloadable). Vids are rendered to MP4 by `files.download`, which returns a long-running
 * operation whose response carries the download URI.
 */
enum class DrivePlaybackSource { DIRECT_MEDIA, VIDS_DOWNLOAD_OPERATION }

fun drivePlaybackSource(mimeType: String): DrivePlaybackSource =
    if (mimeType.lowercase().trim() == GOOGLE_VIDS_MIME) {
        DrivePlaybackSource.VIDS_DOWNLOAD_OPERATION
    } else {
        DrivePlaybackSource.DIRECT_MEDIA
    }

/** Wait before polling the download operation again: 2 s, doubling, capped at 15 s. */
fun drivePollDelayMillis(attempt: Int): Long =
    (2_000L shl attempt.coerceIn(0, 3)).coerceAtMost(15_000L)

/** The operation name without a leading `operations/`, for the `operations/{name}` path. */
fun driveOperationName(name: String): String = name.removePrefix("operations/")

/** The parts of a `files.download` operation the player needs. Null when Drive omitted them. */
data class DriveRenderOperation(
    val name: String?,
    val done: Boolean,
    val errorMessage: String?,
    val downloadUri: String?,
)

/**
 * A Google Vids render that Drive could not finish into a playable link. Its message is written
 * for the person watching, so the player shows it as it is. [operationStillValid] is true when
 * Drive may still be preparing the video (the wait ran out), so Try again can go on waiting for
 * the same render instead of starting a new one.
 */
class VidsRenderException(
    message: String,
    val operationStillValid: Boolean = false,
) : java.io.IOException(message)

const val VIDS_STILL_PREPARING = "Drive is still preparing this video. Try again later."
const val VIDS_UNTRUSTED_LINK = "Drive returned a video link to an untrusted address, so DriveLift did not open it."
const val VIDS_RENDER_FAILED = "Drive could not prepare this video."

/**
 * What [DriveClient] remembers about one Vids render, so turning the phone, a reconnect or
 * reopening the video does not make Drive render it again from the start.
 *
 * - [Running] keeps the operation name while Drive renders. Drive keeps an operation for at least
 *   12 hours (long-running operations guide), so polling it again is allowed for that long.
 * - [Ready] keeps the finished link. Drive does not say how long a download link lasts, and
 *   polling a finished operation may be refused (403), so the link itself is kept, for
 *   [VIDS_READY_REUSE_MS]. If it stops working, the player forgets it and Try again renders anew.
 */
sealed interface VidsRenderMemo {
    val at: Long

    data class Running(val operationName: String, override val at: Long) : VidsRenderMemo
    data class Ready(val uri: HttpUrl, override val at: Long) : VidsRenderMemo
}

/** One render per account, file and file version: an edited Vids file is rendered again. */
data class VidsRenderKey(val account: String, val fileId: String, val version: String)

fun vidsRenderKey(account: String, file: DriveFile) =
    VidsRenderKey(account.trim().lowercase(), file.id, file.modifiedTime.orEmpty())

const val VIDS_RUNNING_REUSE_MS = 12L * 60L * 60L * 1000L
const val VIDS_READY_REUSE_MS = 60L * 60L * 1000L

/** [memo] when it may still be used at [now]; null when it is too old, from the future, or absent. */
fun reusableVidsRender(memo: VidsRenderMemo?, now: Long): VidsRenderMemo? {
    if (memo == null) return null
    val age = now - memo.at
    val limit = when (memo) {
        is VidsRenderMemo.Running -> VIDS_RUNNING_REUSE_MS
        is VidsRenderMemo.Ready -> VIDS_READY_REUSE_MS
    }
    return memo.takeIf { age in 0 until limit }
}

/** Poll failures in a row that [awaitVidsRender] asks again after, before it gives up. */
const val VIDS_POLL_RETRIES = 3

/**
 * Starts a Vids render and waits for its download URI. The HTTP calls, the clock and the delay are
 * passed in, so the whole flow is tested with fakes. [poll] receives the operation id, not the
 * full name. Any error, a missing name or link, a timeout, or an untrusted URI stops it. A
 * cancelled coroutine stops it too, because [wait] is suspending.
 *
 * A poll that throws an error [retryable] accepts (a dropped connection or a temporary Drive
 * error) is asked again at the next interval, up to [VIDS_POLL_RETRIES] times in a row and never
 * past the deadline, so one bad response does not throw away a render that is nearly done.
 * Every other error, and any error from [start], stops it at once.
 */
suspend fun awaitVidsRender(
    start: suspend () -> DriveRenderOperation,
    poll: suspend (operationId: String) -> DriveRenderOperation,
    wait: suspend (delayMillis: Long) -> Unit,
    now: () -> Long,
    timeoutMillis: Long,
    isTrusted: (HttpUrl) -> Boolean,
    retryable: (Exception) -> Boolean = { false },
): HttpUrl {
    var operation = start()
    operation.errorMessage?.let { throw VidsRenderException(it) }
    val name = operation.name?.takeIf { it.isNotBlank() }
        ?: throw VidsRenderException("Drive did not start preparing this video.")
    val deadline = now() + timeoutMillis
    var attempt = 0
    var failuresInARow = 0
    while (!operation.done) {
        if (now() > deadline) {
            throw VidsRenderException(VIDS_STILL_PREPARING, operationStillValid = true)
        }
        wait(drivePollDelayMillis(attempt++))
        operation = try {
            poll(driveOperationName(name))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!retryable(e) || ++failuresInARow > VIDS_POLL_RETRIES) throw e
            continue
        }
        failuresInARow = 0
        operation.errorMessage?.let { throw VidsRenderException(it) }
    }
    val uri = operation.downloadUri?.toHttpUrlOrNull()
        ?: throw VidsRenderException("Drive returned no video link")
    if (!isTrusted(uri)) throw VidsRenderException(VIDS_UNTRUSTED_LINK)
    return uri
}

fun classifyDriveFile(mimeType: String): DrivePreviewKind = when (mimeType.lowercase().trim()) {
    "image/jpeg",
    "image/jpg",
    "image/png",
    "image/webp",
    "image/gif",
    "image/bmp",
    "image/heic",
    "image/heif",
    -> DrivePreviewKind.IMAGE

    "video/mp4",
    "video/3gpp",
    "video/webm",
    "video/x-matroska",
    "video/quicktime",
    // Google Vids projects have no bytes of their own. Drive renders them to MP4 on demand, see
    // [drivePlaybackSource].
    GOOGLE_VIDS_MIME,
    -> DrivePreviewKind.VIDEO

    "application/pdf" -> DrivePreviewKind.PDF

    "application/vnd.google-apps.document",
    "application/vnd.google-apps.spreadsheet",
    "application/vnd.google-apps.presentation",
    -> DrivePreviewKind.WORKSPACE_PDF_EXPORT

    else -> DrivePreviewKind.UNSUPPORTED
}

/** Visual icon/badge classification for all non-trashed Google Drive file types. */
enum class DriveFileVisualKind {
    FOLDER,
    IMAGE,
    VIDEO,
    PDF,
    GOOGLE_DOC,
    GOOGLE_SHEET,
    GOOGLE_SLIDES,
    WORD,
    EXCEL,
    POWERPOINT,
    ARCHIVE,
    APK,
    AUDIO,
    TEXT,
    OTHER,
}

fun classifyDriveVisualKind(file: DriveFile): DriveFileVisualKind {
    if (file.isFolder) return DriveFileVisualKind.FOLDER
    val mime = file.mimeType.lowercase().trim()
    val ext = file.name.substringAfterLast('.', "").lowercase().trim()
    return when {
        mime == "application/vnd.google-apps.document" -> DriveFileVisualKind.GOOGLE_DOC
        mime == "application/vnd.google-apps.spreadsheet" -> DriveFileVisualKind.GOOGLE_SHEET
        mime == "application/vnd.google-apps.presentation" -> DriveFileVisualKind.GOOGLE_SLIDES
        mime == "application/pdf" || ext == "pdf" -> DriveFileVisualKind.PDF
        mime == "application/msword" ||
            mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
            mime == "application/vnd.oasis.opendocument.text" ||
            ext in setOf("doc", "docx", "odt", "rtf") -> DriveFileVisualKind.WORD
        mime == "application/vnd.ms-excel" ||
            mime == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ||
            mime == "application/vnd.oasis.opendocument.spreadsheet" ||
            mime == "text/csv" ||
            ext in setOf("xls", "xlsx", "csv", "ods") -> DriveFileVisualKind.EXCEL
        mime == "application/vnd.ms-powerpoint" ||
            mime == "application/vnd.openxmlformats-officedocument.presentationml.presentation" ||
            mime == "application/vnd.oasis.opendocument.presentation" ||
            ext in setOf("ppt", "pptx", "odp") -> DriveFileVisualKind.POWERPOINT
        mime == "application/vnd.android.package-archive" || ext in setOf("apk", "apks", "xapk") -> DriveFileVisualKind.APK
        mime in setOf(
            "application/zip",
            "application/x-zip-compressed",
            "application/vnd.rar",
            "application/x-rar-compressed",
            "application/x-7z-compressed",
            "application/x-tar",
            "application/gzip",
        ) || ext in setOf("zip", "rar", "7z", "tar", "gz") -> DriveFileVisualKind.ARCHIVE
        mime.startsWith("image/") -> DriveFileVisualKind.IMAGE
        mime.startsWith("video/") || mime == GOOGLE_VIDS_MIME -> DriveFileVisualKind.VIDEO
        mime.startsWith("audio/") -> DriveFileVisualKind.AUDIO
        mime.startsWith("text/") || ext in setOf("txt", "md", "json", "xml") -> DriveFileVisualKind.TEXT
        else -> DriveFileVisualKind.OTHER
    }
}

fun drivePreviewTypeLabel(file: DriveFile): String = when (classifyDriveVisualKind(file)) {
    DriveFileVisualKind.FOLDER -> "Folder"
    DriveFileVisualKind.GOOGLE_DOC -> "Google Doc"
    DriveFileVisualKind.GOOGLE_SHEET -> "Google Sheet"
    DriveFileVisualKind.GOOGLE_SLIDES -> "Google Slides"
    DriveFileVisualKind.PDF -> "PDF"
    DriveFileVisualKind.WORD -> "Word"
    DriveFileVisualKind.EXCEL -> "Excel"
    DriveFileVisualKind.POWERPOINT -> "PowerPoint"
    DriveFileVisualKind.ARCHIVE -> {
        val ext = file.name.substringAfterLast('.', "").uppercase()
        if (ext in setOf("ZIP", "RAR", "7Z", "TAR", "GZ")) ext else "ZIP"
    }
    DriveFileVisualKind.APK -> "APK"
    DriveFileVisualKind.IMAGE -> file.mimeType.substringAfter('/', "Photo").uppercase()
    DriveFileVisualKind.VIDEO -> if (file.mimeType.equals(GOOGLE_VIDS_MIME, ignoreCase = true)) {
        "Google Vids"
    } else {
        file.mimeType.substringAfter('/', "Video").uppercase()
    }
    DriveFileVisualKind.AUDIO -> "Audio"
    DriveFileVisualKind.TEXT -> "Text"
    DriveFileVisualKind.OTHER -> {
        val ext = file.name.substringAfterLast('.', "").takeIf { it.isNotBlank() && it != file.name }
        ext?.uppercase() ?: file.mimeType.ifBlank { "File" }
    }
}

/** Local cache filename for a downloaded Drive file (Workspace docs export to PDF). */
fun driveDownloadFileName(file: DriveFile): String {
    val clean = file.name.replace(Regex("[/\\\\:*?\"<>|\\u0000]"), "_").trim().ifBlank { "drive_file_${file.id}" }
    return if (classifyDriveFile(file.mimeType) == DrivePreviewKind.WORKSPACE_PDF_EXPORT) {
        if (clean.endsWith(".pdf", ignoreCase = true)) clean else "$clean.pdf"
    } else {
        clean
    }
}

/** MIME type to use when opening a downloaded Drive file via FileProvider. */
fun driveDownloadMimeType(file: DriveFile): String = when (classifyDriveFile(file.mimeType)) {
    DrivePreviewKind.WORKSPACE_PDF_EXPORT -> "application/pdf"
    else -> file.mimeType.ifBlank { com.drivelift.app.data.resolveLocalMimeType(file.name) }
}

fun driveContentVersion(file: DriveFile): String =
    "${file.md5.orEmpty()}:${file.size}:${file.modifiedTime.orEmpty()}"

fun driveCacheVariant(baseVariant: String, file: DriveFile): String =
    "${baseVariant}_${driveContentVersion(file)}"

fun driveMemoryCacheKey(accountKey: String, file: DriveFile): String =
    "$accountKey|${file.id}|${driveContentVersion(file)}"

/**
 * Validates that a thumbnail or media URL (including any redirect target) is HTTPS on a Google API
 * or Google-hosted media CDN domain before sending an OAuth Bearer token.
 */
fun isTrustedDriveMediaUrl(url: HttpUrl): Boolean {
    if (!url.isHttps) return false
    val host = url.host.lowercase()
    return host == "googleapis.com" ||
        host.endsWith(".googleapis.com") ||
        host == "googleusercontent.com" ||
        host.endsWith(".googleusercontent.com") ||
        host == "usercontent.google.com" ||
        host.endsWith(".usercontent.google.com")
}

private val THUMB_SIZE_SUFFIX = Regex("=s\\d+(?=[&#?]|$)")

fun upgradedThumbnailUrl(rawLink: String, maxEdgePx: Int): HttpUrl? {
    val parsed = rawLink.toHttpUrlOrNull() ?: return null
    if (!isTrustedDriveMediaUrl(parsed)) return null
    val targetEdge = maxEdgePx.coerceIn(120, 1024)
    val upgraded = if (THUMB_SIZE_SUFFIX.containsMatchIn(rawLink)) {
        rawLink.replace(THUMB_SIZE_SUFFIX, "=s$targetEdge")
    } else {
        rawLink
    }
    return upgraded.toHttpUrlOrNull()?.takeIf(::isTrustedDriveMediaUrl)
}

/**
 * Bounded, account-isolated app-private disk cache for Drive preview bytes and thumbnails.
 * Keys combine normalized account email, file id, and variant via SHA-256 so cached entries
 * never cross Google accounts and never leave raw account emails in filenames.
 */
class DrivePreviewCache(
    private val root: File,
    private val maxBytes: Long = 64L * 1024L * 1024L,
) {
    fun cacheKey(accountEmail: String, fileId: String, variant: String): String {
        val raw = "${accountEmail.trim().lowercase()}\u0000$fileId\u0000$variant"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun fileFor(accountEmail: String, fileId: String, variant: String): File =
        File(root, cacheKey(accountEmail, fileId, variant))

    @Synchronized
    fun getBytes(accountEmail: String, fileId: String, variant: String): ByteArray? {
        val file = fileFor(accountEmail, fileId, variant)
        if (!file.isFile) return null
        file.setLastModified(System.currentTimeMillis())
        return runCatching { file.readBytes() }.getOrNull()
    }

    @Synchronized
    fun putBytes(accountEmail: String, fileId: String, variant: String, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > maxBytes) return
        root.mkdirs()
        val target = fileFor(accountEmail, fileId, variant)
        val temp = File(root, "${target.name}.tmp")
        runCatching {
            temp.writeBytes(bytes)
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            target.setLastModified(System.currentTimeMillis())
        }.onFailure {
            temp.delete()
        }
        evictIfNeeded()
    }

    @Synchronized
    fun evictIfNeeded() {
        val files = root.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (oldest in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            val len = oldest.length()
            if (oldest.delete()) total -= len
        }
    }

    @Synchronized
    fun clear() {
        root.listFiles()?.forEach { it.deleteRecursively() }
    }
}

/** What the in-app player needs to stream one Drive video. */
class DriveVideoStream(val url: String, val callFactory: okhttp3.Call.Factory)

/**
 * Adds the Drive token to a video request only when the request goes to a trusted Google media
 * host, and refuses any other host. Run as a network interceptor, it sees every redirect hop
 * before anything is sent, so the token never reaches another host.
 */
fun authorizedMediaRequest(request: okhttp3.Request, token: String): okhttp3.Request {
    if (!isTrustedDriveMediaUrl(request.url)) throw java.io.IOException("Blocked media request to untrusted host")
    return request.newBuilder().header("Authorization", "Bearer $token").build()
}

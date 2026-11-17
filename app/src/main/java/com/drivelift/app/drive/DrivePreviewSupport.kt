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
    -> DrivePreviewKind.VIDEO

    "application/pdf" -> DrivePreviewKind.PDF

    "application/vnd.google-apps.document",
    "application/vnd.google-apps.spreadsheet",
    "application/vnd.google-apps.presentation",
    -> DrivePreviewKind.WORKSPACE_PDF_EXPORT

    else -> DrivePreviewKind.UNSUPPORTED
}

fun drivePreviewTypeLabel(file: DriveFile): String = when (file.mimeType.lowercase().trim()) {
    "application/vnd.google-apps.document" -> "Google Doc"
    "application/vnd.google-apps.spreadsheet" -> "Google Sheet"
    "application/vnd.google-apps.presentation" -> "Google Slides"
    "application/pdf" -> "PDF"
    else -> when {
        file.mimeType.startsWith("image/") -> file.mimeType.substringAfter('/').uppercase()
        file.mimeType.startsWith("video/") -> file.mimeType.substringAfter('/').uppercase()
        file.mimeType.isNotBlank() -> file.mimeType
        else -> "File"
    }
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

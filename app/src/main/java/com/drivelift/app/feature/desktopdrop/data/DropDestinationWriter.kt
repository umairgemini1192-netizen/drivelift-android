package com.drivelift.app.feature.desktopdrop.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale

data class CommittedDropFile(
    val savedName: String,
    val destinationLabel: String,
    val sizeBytes: Long,
    val uriString: String? = null,
)

interface DropDestinationWriter {
    fun commit(partFile: File, filename: String, relativePath: String?, mimeType: String?): CommittedDropFile
}

object DropPathSanitizer {
    private val UNSAFE_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+")

    fun sanitizeFilename(raw: String): String {
        val leaf = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = leaf
            .replace(UNSAFE_NAME_CHARS, "_")
            .trim()
            .trimStart('.')
            .take(160)
        return cleaned.ifBlank { "drop_file" }
    }

    /**
     * Converts a browser relative path (either folder-only like "Work/Docs" or full
     * webkitRelativePath like "Work/Docs/report.pdf") into safe folder segments, dropping ".",
     * "..", and any trailing segment identical to [rawFilename].
     */
    fun sanitizeRelativeFolderSegments(relativePath: String?, rawFilename: String): List<String> {
        if (relativePath.isNullOrBlank()) return emptyList()
        val normalized = relativePath.replace('\\', '/').trim().trim('/')
        if (normalized.isEmpty()) return emptyList()
        val rawSegments = normalized.split('/')
        val cleanLeaf = sanitizeFilename(rawFilename)
        val withoutLeaf = if (rawSegments.isNotEmpty() && sanitizeFilename(rawSegments.last()) == cleanLeaf) {
            rawSegments.dropLast(1)
        } else {
            rawSegments
        }
        return withoutLeaf.mapNotNull { seg ->
            val trimmed = seg.trim()
            if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") null
            else sanitizeFilename(trimmed).take(80)
        }
    }

    fun uniqueName(baseName: String, exists: (String) -> Boolean): String {
        if (!exists(baseName)) return baseName
        val dot = baseName.lastIndexOf('.')
        val stem = if (dot > 0) baseName.substring(0, dot) else baseName
        val ext = if (dot > 0) baseName.substring(dot) else ""
        for (i in 1..999) {
            val candidate = "$stem ($i)$ext"
            if (!exists(candidate)) return candidate
        }
        return "${stem}_${System.currentTimeMillis()}$ext"
    }

    fun resolveMimeType(filename: String, providedMime: String?): String {
        if (!providedMime.isNullOrBlank() && '/' in providedMime) return providedMime
        val ext = filename.substringAfterLast('.', "").lowercase(Locale.US)
        return COMMON_MIMES[ext] ?: "application/octet-stream"
    }

    private val COMMON_MIMES = mapOf(
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "webp" to "image/webp",
        "heic" to "image/heic",
        "gif" to "image/gif",
        "mp4" to "video/mp4",
        "mov" to "video/quicktime",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "flac" to "audio/flac",
        "pdf" to "application/pdf",
        "zip" to "application/zip",
        "txt" to "text/plain",
        "json" to "application/json",
        "apk" to "application/vnd.android.package-archive",
    )
}

/**
 * Pure JVM filesystem writer used by unit tests and direct-directory test harnesses.
 */
class DirectoryDropDestinationWriter(
    private val rootDir: File,
    private val baseLabel: String = rootDir.name,
) : DropDestinationWriter {

    override fun commit(partFile: File, filename: String, relativePath: String?, mimeType: String?): CommittedDropFile {
        if (!partFile.isFile) throw IOException("Missing temporary upload part file")
        val cleanName = DropPathSanitizer.sanitizeFilename(filename)
        val subdirs = DropPathSanitizer.sanitizeRelativeFolderSegments(relativePath, filename)
        var targetDir = rootDir
        for (seg in subdirs) {
            targetDir = File(targetDir, seg)
        }
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("Could not create destination folder: ${targetDir.absolutePath}")
        }
        val finalName = DropPathSanitizer.uniqueName(cleanName) { File(targetDir, it).exists() }
        val finalFile = File(targetDir, finalName)
        val expectedBytes = partFile.length()
        val copied = streamFileToOutput(partFile, FileOutputStream(finalFile))
        if (copied != expectedBytes) {
            finalFile.delete()
            throw IOException("Byte count verification failed: expected $expectedBytes, wrote $copied")
        }
        partFile.delete()
        val label = if (subdirs.isEmpty()) baseLabel else "$baseLabel/${subdirs.joinToString("/")}"
        return CommittedDropFile(
            savedName = finalName,
            destinationLabel = label,
            sizeBytes = expectedBytes,
            uriString = finalFile.toURI().toString(),
        )
    }
}

/**
 * Writes verified incoming desktop uploads either to a user-selected SAF tree URI or to Android's
 * shared Downloads/DriveLift Drop folder via MediaStore.Downloads.
 */
class AndroidDropDestinationWriter(
    private val context: Context,
    private val prefs: DesktopDropPrefs,
) : DropDestinationWriter {

    override fun commit(partFile: File, filename: String, relativePath: String?, mimeType: String?): CommittedDropFile {
        if (!partFile.isFile) throw IOException("Missing temporary upload part file")
        val cleanName = DropPathSanitizer.sanitizeFilename(filename)
        val subdirs = DropPathSanitizer.sanitizeRelativeFolderSegments(relativePath, filename)
        val resolvedMime = DropPathSanitizer.resolveMimeType(cleanName, mimeType)
        val currentPrefs = prefs.current
        val treeUriStr = currentPrefs.destinationTreeUri
        return if (!treeUriStr.isNullOrBlank()) {
            commitToSafTree(partFile, Uri.parse(treeUriStr), currentPrefs.destinationLabel, cleanName, subdirs, resolvedMime)
        } else {
            commitToMediaStoreDownloads(partFile, cleanName, subdirs, resolvedMime)
        }
    }

    private fun commitToSafTree(
        partFile: File,
        treeUri: Uri,
        rootLabel: String,
        cleanName: String,
        subdirs: List<String>,
        mimeType: String,
    ): CommittedDropFile {
        var dir = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("Selected destination folder is unavailable")
        if (!dir.canWrite()) throw IOException("No write permission for $rootLabel")
        for (seg in subdirs) {
            dir = dir.findFile(seg)?.takeIf { it.isDirectory }
                ?: dir.createDirectory(seg)
                ?: throw IOException("Could not create subfolder $seg")
        }
        val finalName = DropPathSanitizer.uniqueName(cleanName) { candidate -> dir.findFile(candidate) != null }
        val doc = dir.createFile(mimeType, finalName)
            ?: throw IOException("Could not create destination file $finalName")
        val expectedBytes = partFile.length()
        return try {
            val out = context.contentResolver.openOutputStream(doc.uri)
                ?: throw IOException("Could not open destination stream")
            val written = streamFileToOutput(partFile, out)
            if (written != expectedBytes) {
                throw IOException("Incomplete write: expected $expectedBytes, wrote $written")
            }
            partFile.delete()
            val label = if (subdirs.isEmpty()) rootLabel else "$rootLabel/${subdirs.joinToString("/")}"
            CommittedDropFile(finalName, label, expectedBytes, doc.uri.toString())
        } catch (e: Exception) {
            runCatching { doc.delete() }
            throw e
        }
    }

    private fun commitToMediaStoreDownloads(
        partFile: File,
        cleanName: String,
        subdirs: List<String>,
        mimeType: String,
    ): CommittedDropFile {
        val resolver = context.contentResolver
        val baseRel = "${Environment.DIRECTORY_DOWNLOADS}/DriveLift Drop"
        val relPath = if (subdirs.isEmpty()) baseRel else "$baseRel/${subdirs.joinToString("/")}"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, cleanName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "$relPath/")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore rejected Downloads insert for $cleanName")
        val expectedBytes = partFile.length()
        return try {
            val out = resolver.openOutputStream(uri)
                ?: throw IOException("Could not open MediaStore output stream")
            val written = streamFileToOutput(partFile, out)
            if (written != expectedBytes) {
                throw IOException("Incomplete write: expected $expectedBytes, wrote $written")
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            partFile.delete()
            CommittedDropFile(cleanName, relPath, expectedBytes, uri.toString())
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }
}

private fun streamFileToOutput(source: File, destination: OutputStream): Long {
    val buffer = ByteArray(512 * 1024)
    var total = 0L
    FileInputStream(source).use { input ->
        destination.use { out ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                total += read
            }
            out.flush()
        }
    }
    return total
}

package com.drivelift.app.data

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Broad local file type used for icons, badges, and category filters. */
enum class LocalFileType {
    FOLDER,
    PDF,
    SPREADSHEET,
    PRESENTATION,
    DOCUMENT,
    ARCHIVE,
    APK,
    IMAGE,
    VIDEO,
    AUDIO,
    OTHER,
}

/** Quick filter categories surfaced in the Local File Explorer and Phone tab. */
enum class LocalFileCategory(val label: String) {
    ALL("All Files"),
    DOCUMENTS("Documents"),
    DOWNLOADS("Downloads"),
    APKS_ZIPS("APKs & ZIPs"),
    PHOTOS("Photos"),
    VIDEOS("Videos"),
}

/** One entry (folder or file) in the local file system explorer. */
data class LocalFsEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val itemCount: Int = 0,
    val fileType: LocalFileType,
    val mimeType: String,
    val relativeParent: String = "",
)

/** One clickable segment in the folder breadcrumb trail. */
data class ExplorerBreadcrumb(
    val label: String,
    val path: String,
)

private val SPREADSHEET_EXTS = setOf("xlsx", "xls", "csv", "ods", "xlsm")
private val PRESENTATION_EXTS = setOf("pptx", "ppt", "odp", "ppsx")
private val DOCUMENT_EXTS = setOf("docx", "doc", "odt", "rtf", "txt", "md")
private val ARCHIVE_EXTS = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")
private val APK_EXTS = setOf("apk", "apks", "xapk")
private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "dng", "avif")
private val VIDEO_EXTS = setOf("mp4", "mkv", "mov", "webm", "3gp", "avi", "m4v", "ts")
private val AUDIO_EXTS = setOf("mp3", "wav", "m4a", "flac", "ogg", "aac", "opus", "wma")

private val SKIPPED_SCAN_DIRS = setOf("android", ".thumbnails", ".trash", ".trashed")

fun fileExtensionLower(name: String): String {
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot >= name.length - 1) return ""
    return name.substring(dot + 1).lowercase(Locale.US)
}

fun classifyLocalFile(name: String, isDirectory: Boolean): LocalFileType {
    if (isDirectory) return LocalFileType.FOLDER
    val ext = fileExtensionLower(name)
    return when {
        ext == "pdf" -> LocalFileType.PDF
        ext in SPREADSHEET_EXTS -> LocalFileType.SPREADSHEET
        ext in PRESENTATION_EXTS -> LocalFileType.PRESENTATION
        ext in DOCUMENT_EXTS -> LocalFileType.DOCUMENT
        ext in APK_EXTS -> LocalFileType.APK
        ext in ARCHIVE_EXTS -> LocalFileType.ARCHIVE
        ext in IMAGE_EXTS -> LocalFileType.IMAGE
        ext in VIDEO_EXTS -> LocalFileType.VIDEO
        ext in AUDIO_EXTS -> LocalFileType.AUDIO
        else -> LocalFileType.OTHER
    }
}

fun localFileTypeLabel(type: LocalFileType, name: String): String {
    if (type == LocalFileType.FOLDER) return "Folder"
    val ext = fileExtensionLower(name)
    return when (type) {
        LocalFileType.FOLDER -> "Folder"
        LocalFileType.PDF -> "PDF"
        LocalFileType.SPREADSHEET -> if (ext.isNotEmpty()) ext.uppercase(Locale.US) else "Excel"
        LocalFileType.PRESENTATION -> if (ext.isNotEmpty()) ext.uppercase(Locale.US) else "PPT"
        LocalFileType.DOCUMENT -> if (ext.isNotEmpty()) ext.uppercase(Locale.US) else "Doc"
        LocalFileType.APK -> "APK"
        LocalFileType.ARCHIVE -> if (ext.isNotEmpty()) ext.uppercase(Locale.US) else "ZIP"
        LocalFileType.IMAGE -> if (ext.isNotEmpty()) ext.uppercase(Locale.US) else "Photo"
        LocalFileType.VIDEO -> if (ext.isNotEmpty()) ext.uppercase(Locale.US) else "Video"
        LocalFileType.AUDIO -> if (ext.isNotEmpty()) ext.uppercase(Locale.US) else "Audio"
        LocalFileType.OTHER -> if (ext.isNotEmpty()) ext.uppercase(Locale.US) else "File"
    }
}

fun resolveLocalMimeType(name: String): String {
    val ext = fileExtensionLower(name)
    val known = when (ext) {
        "pdf" -> "application/pdf"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "xls" -> "application/vnd.ms-excel"
        "csv" -> "text/csv"
        "ods" -> "application/vnd.oasis.opendocument.spreadsheet"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "ppt" -> "application/vnd.ms-powerpoint"
        "odp" -> "application/vnd.oasis.opendocument.presentation"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "doc" -> "application/msword"
        "odt" -> "application/vnd.oasis.opendocument.text"
        "rtf" -> "application/rtf"
        "txt", "md", "log" -> "text/plain"
        "zip" -> "application/zip"
        "rar" -> "application/vnd.rar"
        "7z" -> "application/x-7z-compressed"
        "tar" -> "application/x-tar"
        "gz" -> "application/gzip"
        "apk", "apks", "xapk" -> "application/vnd.android.package-archive"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "bmp" -> "image/bmp"
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "3gp" -> "video/3gpp"
        "avi" -> "video/x-msvideo"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "m4a" -> "audio/mp4"
        "flac" -> "audio/flac"
        "ogg", "opus" -> "audio/ogg"
        "aac" -> "audio/aac"
        else -> null
    }
    if (known != null) return known
    val fromMap = if (ext.isNotEmpty()) {
        runCatching { MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) }.getOrNull()
    } else null
    return fromMap ?: "application/octet-stream"
}

fun matchesCategory(type: LocalFileType, category: LocalFileCategory): Boolean = when (category) {
    LocalFileCategory.ALL -> true
    LocalFileCategory.DOCUMENTS -> type == LocalFileType.PDF ||
        type == LocalFileType.SPREADSHEET ||
        type == LocalFileType.PRESENTATION ||
        type == LocalFileType.DOCUMENT
    LocalFileCategory.DOWNLOADS -> type != LocalFileType.FOLDER
    LocalFileCategory.APKS_ZIPS -> type == LocalFileType.APK || type == LocalFileType.ARCHIVE
    LocalFileCategory.PHOTOS -> type == LocalFileType.IMAGE
    LocalFileCategory.VIDEOS -> type == LocalFileType.VIDEO
}

/**
 * Normalizes path separators and removes trailing slashes (except for a bare root slash).
 */
internal fun normalizeExplorerPath(path: String): String {
    val unified = path.replace('\\', '/')
    val trimmed = unified.trimEnd('/')
    return if (trimmed.isEmpty() && unified.startsWith("/")) "/" else trimmed
}

/**
 * True when [candidatePath] is equal to [rootPath] or strictly inside it (never escaping via `..`).
 */
fun isPathWithinRoot(rootPath: String, candidatePath: String): Boolean {
    val root = normalizeExplorerPath(rootPath)
    val candidate = normalizeExplorerPath(candidatePath)
    if (candidate.split('/').any { it == ".." }) return false
    return candidate == root || candidate.startsWith("$root/")
}

/**
 * Builds the clickable breadcrumb trail from [rootPath] (labeled "Internal storage") down to
 * [currentPath]. Paths outside [rootPath] safely clamp to the root breadcrumb.
 */
fun buildBreadcrumbs(rootPath: String, currentPath: String): List<ExplorerBreadcrumb> {
    val root = normalizeExplorerPath(rootPath)
    val rootCrumb = ExplorerBreadcrumb(label = "Internal storage", path = root)
    val current = normalizeExplorerPath(currentPath)
    if (!isPathWithinRoot(root, current) || current == root) return listOf(rootCrumb)
    val suffix = current.removePrefix("$root/")
    val segments = suffix.split('/').filter { it.isNotEmpty() }
    val out = ArrayList<ExplorerBreadcrumb>(segments.size + 1)
    out += rootCrumb
    var acc = root
    for (seg in segments) {
        acc = "$acc/$seg"
        out += ExplorerBreadcrumb(label = seg, path = acc)
    }
    return out
}

/**
 * Abstraction for local storage exploration so UI and screenshot tests can swap a synthetic or
 * temp-folder source cleanly without touching host storage.
 */
interface LocalFileExplorerSource {
    fun hasAllFilesAccess(): Boolean
    fun rootPath(): String
    suspend fun listDirectory(path: String): List<LocalFsEntry>
    suspend fun listCategory(category: LocalFileCategory): List<LocalFsEntry>
}

/**
 * Production file-system explorer backed by [Environment.getExternalStorageDirectory]
 * (`/storage/emulated/0`) and [Environment.isExternalStorageManager].
 */
class FileSystemLocalExplorerSource(
    private val rootProvider: () -> File = { Environment.getExternalStorageDirectory() },
    private val permissionCheck: () -> Boolean = { Environment.isExternalStorageManager() },
) : LocalFileExplorerSource {

    override fun hasAllFilesAccess(): Boolean = runCatching { permissionCheck() }.getOrDefault(false)

    override fun rootPath(): String = normalizeExplorerPath(rootProvider().absolutePath)

    override suspend fun listDirectory(path: String): List<LocalFsEntry> = withContext(Dispatchers.IO) {
        val root = rootProvider()
        val rootNorm = normalizeExplorerPath(root.absolutePath)
        val safePath = if (isPathWithinRoot(rootNorm, path)) normalizeExplorerPath(path) else rootNorm
        val dir = File(safePath)
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return@withContext emptyList()
        val relParent = relativeParentOf(rootNorm, safePath)
        val entries = children.mapNotNull { file ->
            val name = file.name
            if (name.isBlank() || name.startsWith(".")) return@mapNotNull null
            toEntry(file, relParent)
        }
        val (dirs, files) = entries.partition { it.isDirectory }
        dirs.sortedWith(compareBy(NaturalOrder) { it.name }) +
            files.sortedWith(compareBy(NaturalOrder) { it.name })
    }

    override suspend fun listCategory(category: LocalFileCategory): List<LocalFsEntry> = withContext(Dispatchers.IO) {
        if (category == LocalFileCategory.ALL) return@withContext listDirectory(rootPath())
        val root = rootProvider()
        val rootNorm = normalizeExplorerPath(root.absolutePath)
        val results = ArrayList<LocalFsEntry>()
        val visited = HashSet<String>()

        fun walk(dir: File, depth: Int) {
            if (depth > MAX_SCAN_DEPTH || results.size >= MAX_CATEGORY_RESULTS) return
            val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: dir.absolutePath
            if (!visited.add(canonical)) return
            val children = runCatching { dir.listFiles() }.getOrNull() ?: return
            for (child in children) {
                if (results.size >= MAX_CATEGORY_RESULTS) return
                val name = child.name
                if (name.isBlank() || name.startsWith(".")) continue
                if (child.isDirectory) {
                    if (depth == 0 && name.lowercase(Locale.US) in SKIPPED_SCAN_DIRS) continue
                    walk(child, depth + 1)
                } else if (child.isFile) {
                    val type = classifyLocalFile(name, isDirectory = false)
                    if (matchesCategory(type, category)) {
                        val parentNorm = normalizeExplorerPath(child.parentFile?.absolutePath ?: rootNorm)
                        results += LocalFsEntry(
                            path = normalizeExplorerPath(child.absolutePath),
                            name = name,
                            isDirectory = false,
                            sizeBytes = child.length().coerceAtLeast(0L),
                            lastModifiedMs = child.lastModified(),
                            fileType = type,
                            mimeType = resolveLocalMimeType(name),
                            relativeParent = relativeParentOf(rootNorm, parentNorm),
                        )
                    }
                }
            }
        }

        if (category == LocalFileCategory.DOWNLOADS) {
            val candidates = listOf(File(root, "Download"), File(root, "Downloads"))
                .filter { it.isDirectory }
            for (dlDir in candidates) {
                walk(dlDir, depth = 0)
            }
        } else {
            walk(root, depth = 0)
        }

        results.sortWith(compareByDescending<LocalFsEntry> { it.lastModifiedMs }.thenBy(NaturalOrder) { it.name })
        results
    }

    private fun toEntry(file: File, relParent: String): LocalFsEntry {
        val isDir = file.isDirectory
        val name = file.name
        val type = classifyLocalFile(name, isDirectory = isDir)
        val count = if (isDir) {
            runCatching { file.listFiles()?.count { !it.name.startsWith(".") } ?: 0 }.getOrDefault(0)
        } else {
            0
        }
        return LocalFsEntry(
            path = normalizeExplorerPath(file.absolutePath),
            name = name,
            isDirectory = isDir,
            sizeBytes = if (isDir) 0L else file.length().coerceAtLeast(0L),
            lastModifiedMs = file.lastModified(),
            itemCount = count,
            fileType = type,
            mimeType = if (isDir) "" else resolveLocalMimeType(name),
            relativeParent = relParent,
        )
    }

    private fun relativeParentOf(rootNorm: String, dirNorm: String): String {
        if (dirNorm == rootNorm) return "Internal storage"
        return dirNorm.removePrefix("$rootNorm/").ifBlank { "Internal storage" }
    }

    companion object {
        private const val MAX_SCAN_DEPTH = 6
        private const val MAX_CATEGORY_RESULTS = 2_000
    }
}

/**
 * Native file opening via [FileProvider] and Android's All-Files Access settings intent.
 */
object LocalFileOpener {
    private const val APK_MIME = "application/vnd.android.package-archive"

    fun allFilesPermissionIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )

    fun openAllFilesPermissionSettings(context: Context): String? {
        val appSpecific = allFilesPermissionIntent(context)
        if (context !is Activity) appSpecific.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(appSpecific)
            null
        } catch (_: ActivityNotFoundException) {
            val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            if (context !is Activity) fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(fallback)
                null
            } catch (_: ActivityNotFoundException) {
                "Could not open Android's All-Files Access settings on this device."
            }
        } catch (_: SecurityException) {
            "Could not open Android's All-Files Access settings on this device."
        }
    }

    fun buildFileProviderUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun buildOpenIntent(context: Context, file: File, mimeType: String = resolveLocalMimeType(file.name)): Intent {
        val uri = buildFileProviderUri(context, file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType.ifBlank { "application/octet-stream" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /**
     * Opens [file] via [FileProvider] with native apps (or Android's APK installer). Returns null
     * on success, or a plain message explaining why it could not be opened.
     */
    fun openFile(context: Context, file: File, mimeType: String = resolveLocalMimeType(file.name)): String? {
        if (!file.isFile) return "This file is no longer available on the phone."
        val isApk = mimeType.equals(APK_MIME, ignoreCase = true) ||
            file.name.endsWith(".apk", ignoreCase = true)
        if (isApk) {
            val canInstall = runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
            if (!canInstall) {
                val settingsIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                )
                if (context !is Activity) settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(settingsIntent) }
                return "Allow DriveLift to install apps, then tap the APK again."
            }
        }
        val intent = try {
            buildOpenIntent(context, file, if (isApk) APK_MIME else mimeType)
        } catch (_: IllegalArgumentException) {
            return "DriveLift could not share this file path with another app."
        }
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            null
        } catch (_: ActivityNotFoundException) {
            "No app on this phone can open ${file.name}."
        } catch (_: SecurityException) {
            "Android did not allow opening ${file.name}."
        }
    }

    fun openEntry(context: Context, entry: LocalFsEntry): String? =
        openFile(context, File(entry.path), entry.mimeType.ifBlank { resolveLocalMimeType(entry.name) })
}

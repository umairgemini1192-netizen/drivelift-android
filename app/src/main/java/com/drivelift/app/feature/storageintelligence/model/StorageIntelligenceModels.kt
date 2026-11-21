package com.drivelift.app.feature.storageintelligence.model

import com.drivelift.app.drive.DriveFile

/** Default local source identifier used across scanner, database, and UI filters. */
const val LOCAL_SOURCE_ID = "local"

/** Default grace period (24 hours) before cache or temp files can be flagged as orphans. */
const val DEFAULT_GRACE_PERIOD_MS = 24L * 60L * 60L * 1000L

/** Default Large-File Radar threshold (50 MB). */
const val DEFAULT_LARGE_THRESHOLD_BYTES = 50L * 1024L * 1024L

/** Default Top-N limit for Large-File Radar queries. */
const val DEFAULT_LARGE_TOP_N = 100

/** Preset threshold options in megabytes shown in Large-File Radar filters. */
val LARGE_THRESHOLD_PRESETS_MB = listOf(50L, 100L, 500L, 1024L)

/** Supported storage backends represented by unified Storage Intelligence scanners. */
enum class StorageSourceType(val displayName: String) {
    LOCAL("This phone"),
    GOOGLE_DRIVE("Google Drive"),
    CLOUD_GENERIC("Cloud storage"),
    ;

    val isCloud: Boolean get() = this != LOCAL
}

/** Checksum algorithms supported by local and cloud metadata providers. */
enum class HashAlgorithm(val label: String, val isStrongFullFile: Boolean) {
    NONE("None", false),
    PARTIAL_64K_SHA256("Partial SHA-256", false),
    SHA256("SHA-256", true),
    MD5("MD5", true),
    QUICK_XOR("QuickXorHash", true),
    CONTENT_HASH("Content hash", true),
    ETAG("ETag", false),
}

/** Scan scope filter chosen by the user before launching a scan. */
enum class ScanScope(val label: String) {
    CROSS_SOURCE("Local + Cloud"),
    LOCAL_ONLY("Local only"),
    SINGLE_CLOUD("Single cloud"),
    ALL_CLOUDS("All clouds"),
}

/** Confidence classification for duplicate candidate groups. */
enum class DuplicateMatchKind(val label: String) {
    EXACT_HASH("Exact hash"),
    PROBABLE_MATCH("Probable match"),
}

/** One-tap smart auto-selection rules for duplicate candidate groups. */
enum class AutoSelectPreset(val label: String, val description: String) {
    KEEP_NEWEST("Keep newest", "Keeps the most recently modified copy in each group"),
    KEEP_OLDEST("Keep oldest", "Keeps the earliest modified copy in each group"),
    KEEP_CLOUD_FREE_LOCAL("Keep cloud copy", "Keeps cloud copies and selects local copies to free phone space"),
    KEEP_LOCAL_COPY("Keep local copy", "Keeps phone copies and selects cloud copies for cleanup"),
    KEEP_SHORTEST_PATH("Keep shortest path", "Keeps the copy with the shortest folder path"),
}

/** Large-File Radar media and archive categories. */
enum class LargeFileCategory(val label: String) {
    ALL("All"),
    VIDEO("Video"),
    ARCHIVES("Archives"),
    APKS("APKs"),
    BACKUPS("Backups"),
    IMAGES("Images"),
    AUDIO("Audio"),
    OTHER("Other"),
}

private val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "zst")
private val APK_EXTENSIONS = setOf("apk", "aab", "xapk", "apks")
private val BACKUP_EXTENSIONS = setOf("bak", "backup", "img", "iso", "db", "sql", "tar.bak", "ab")
private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "mov", "webm", "3gp", "avi", "m4v", "mts")
private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "dng", "raw")
private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus")

/** Maps a file name and MIME type to its [LargeFileCategory]. */
fun classifyLargeFileCategory(name: String, mimeType: String): LargeFileCategory {
    val mime = mimeType.lowercase().trim()
    val ext = name.substringAfterLast('.', "").lowercase().trim()
    return when {
        mime.startsWith("video/") || ext in VIDEO_EXTENSIONS -> LargeFileCategory.VIDEO
        mime == "application/vnd.android.package-archive" || ext in APK_EXTENSIONS -> LargeFileCategory.APKS
        mime in setOf(
            "application/zip",
            "application/x-zip-compressed",
            "application/x-rar-compressed",
            "application/vnd.rar",
            "application/x-7z-compressed",
            "application/x-tar",
            "application/gzip",
        ) || ext in ARCHIVE_EXTENSIONS -> LargeFileCategory.ARCHIVES
        ext in BACKUP_EXTENSIONS || ext.startsWith("crypt") || name.lowercase().contains("backup") ->
            LargeFileCategory.BACKUPS
        mime.startsWith("image/") || ext in IMAGE_EXTENSIONS -> LargeFileCategory.IMAGES
        mime.startsWith("audio/") || ext in AUDIO_EXTENSIONS -> LargeFileCategory.AUDIO
        else -> LargeFileCategory.OTHER
    }
}

/** Orphaned cache and junk artifact classifications. */
enum class OrphanCategory(val label: String) {
    LOCAL_APP_CACHE("App and media caches"),
    TRANSFER_ORPHAN("Transfer orphans"),
    CLOUD_ZERO_BYTE_STUB("Cloud 0-byte stubs"),
    CLOUD_EMPTY_FOLDER("Cloud empty folders"),
    CLOUD_TRASH_SUMMARY("Cloud trash summary"),
}

/** Execution stage reported by [ScanProgress]. */
enum class ScanPhase(val label: String) {
    IDLE("Ready"),
    SCANNING_SOURCES("Scanning sources"),
    HASHING_DUPLICATES("Comparing duplicates"),
    INDEXING_LARGE_FILES("Indexing large files"),
    SCANNING_ORPHANS("Checking orphaned caches"),
    SAVING("Saving results"),
    COMPLETED("Scan complete"),
    CANCELLED("Scan cancelled"),
    FAILED("Scan failed"),
}

/**
 * Normalized representation of a local or cloud file scanned by a [StorageSourceScanner].
 */
data class UnifiedFileNode(
    val id: String,
    val nativeId: String,
    val sourceId: String,
    val sourceLabel: String,
    val sourceType: StorageSourceType,
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val mimeType: String,
    val modifiedAt: Long,
    val hashAlgorithm: HashAlgorithm = HashAlgorithm.NONE,
    val hashValue: String? = null,
    val secondaryHashAlgorithm: HashAlgorithm = HashAlgorithm.NONE,
    val secondaryHashValue: String? = null,
    val thumbnailRef: String? = null,
    val isTrashed: Boolean = false,
    val localUri: String? = null,
    val bucketId: Long? = null,
    val parentFolderId: String? = null,
    val isFolder: Boolean = false,
    val ownedByMe: Boolean = true,
) {
    val isLocal: Boolean get() = sourceType == StorageSourceType.LOCAL
    val isCloud: Boolean get() = sourceType.isCloud
}

/** Per-source failure captured during a scan while allowing other sources to succeed. */
data class SourceScanError(
    val sourceId: String,
    val sourceLabel: String,
    val message: String,
    val authExpired: Boolean = false,
)

/** Live progress exposed by [StorageIntelligenceWorker] and [StorageIntelligenceScanBus]. */
data class ScanProgress(
    val sessionId: Long? = null,
    val phase: ScanPhase = ScanPhase.IDLE,
    val activeSourceId: String? = null,
    val activeSourceLabel: String? = null,
    val scannedCount: Int = 0,
    val bytesAnalyzed: Long = 0L,
    val totalEstimatedCount: Int = 0,
    val errors: List<SourceScanError> = emptyList(),
    val isRunning: Boolean = false,
    val isCancellable: Boolean = false,
)

/** A group of duplicate candidates sharing exact bytes or matching size and normalized name. */
data class DuplicateGroupModel(
    val groupKey: String,
    val matchKind: DuplicateMatchKind,
    val hashAlgorithm: HashAlgorithm,
    val hashValue: String?,
    val normalizedName: String?,
    val sizeBytes: Long,
    val reclaimableBytes: Long,
    val isCrossSource: Boolean,
    val items: List<UnifiedFileNode>,
)

/** Cleanable or informational orphan artifact discovered during scanning. */
data class OrphanArtifactCandidate(
    val artifactId: String,
    val sourceId: String,
    val sourceLabel: String,
    val sourceType: StorageSourceType,
    val category: OrphanCategory,
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val itemCount: Int = 1,
    val nativeId: String? = null,
    val detail: String,
    val localUri: String? = null,
    val transferStatus: String? = null,
    val canClean: Boolean = true,
)

/** Per-source item count and reclaimable space summary shown in cleanup confirmation modals. */
data class SourceCleanupBreakdown(
    val sourceId: String,
    val sourceLabel: String,
    val sourceType: StorageSourceType,
    val itemCount: Int,
    val reclaimableBytes: Long,
    val destinationNote: String,
)

/**
 * Validated cleanup plan passed into the confirmation modal before moving files to Trash or
 * clearing safe local caches.
 */
data class BatchCleanupPlan(
    val title: String,
    val perSourceBreakdown: List<SourceCleanupBreakdown>,
    val localEligibleMediaIds: List<Long> = emptyList(),
    val localEligibleNodeIds: List<String> = emptyList(),
    val cloudEligibleFiles: List<DriveFile> = emptyList(),
    val cloudEligibleNodeIds: List<String> = emptyList(),
    val orphanArtifactIds: List<String> = emptyList(),
    val skippedHeldCount: Int = 0,
    val skippedLastCopyCount: Int = 0,
    val skippedLockedOrphanCount: Int = 0,
    val totalReclaimableBytes: Long = 0L,
) {
    val totalEligibleCount: Int
        get() = localEligibleMediaIds.size + cloudEligibleFiles.size + orphanArtifactIds.size
}

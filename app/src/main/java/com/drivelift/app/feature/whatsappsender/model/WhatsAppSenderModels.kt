package com.drivelift.app.feature.whatsappsender.model

import android.net.Uri
import java.io.File

const val MAX_WHATSAPP_FILE_BYTES: Long = 2L * 1024L * 1024L * 1024L
const val MAX_WHATSAPP_BATCH_FILES: Int = 30

/** Supported WhatsApp package targets, or Android Sharesheet fallback when neither is installed. */
enum class WhatsAppTarget(
    val packageName: String?,
    val label: String,
) {
    PERSONAL("com.whatsapp", "WhatsApp"),
    BUSINESS("com.whatsapp.w4b", "WhatsApp Business"),
    SYSTEM_SHARESHEET(null, "System share"),
    ;

    companion object {
        fun fromStored(name: String?): WhatsAppTarget =
            entries.firstOrNull { it.name == name && it != SYSTEM_SHARESHEET } ?: PERSONAL
    }
}

/** Installed WhatsApp client state detected via PackageManager. */
enum class WhatsAppAvailability {
    PERSONAL,
    BUSINESS,
    BOTH,
    NONE,
    ;

    fun installedTargets(): List<WhatsAppTarget> = when (this) {
        PERSONAL -> listOf(WhatsAppTarget.PERSONAL)
        BUSINESS -> listOf(WhatsAppTarget.BUSINESS)
        BOTH -> listOf(WhatsAppTarget.PERSONAL, WhatsAppTarget.BUSINESS)
        NONE -> emptyList()
    }

    fun resolveTarget(preferred: WhatsAppTarget?): WhatsAppTarget = when (this) {
        NONE -> WhatsAppTarget.SYSTEM_SHARESHEET
        PERSONAL -> WhatsAppTarget.PERSONAL
        BUSINESS -> WhatsAppTarget.BUSINESS
        BOTH -> if (preferred == WhatsAppTarget.BUSINESS) WhatsAppTarget.BUSINESS else WhatsAppTarget.PERSONAL
    }

    fun statusSummary(): String = when (this) {
        BOTH -> "WhatsApp and WhatsApp Business installed"
        PERSONAL -> "WhatsApp installed"
        BUSINESS -> "WhatsApp Business installed"
        NONE -> "WhatsApp not found, using System share"
    }
}

/** Controls whether media is sent with standard compression or as an uncompressed document. */
enum class QualityMode(
    val label: String,
    val detail: String,
) {
    ORIGINAL_QUALITY(
        label = "Original Quality (Document)",
        detail = "Uncompressed document, keeps full resolution and EXIF.",
    ),
    STANDARD_MEDIA(
        label = "Standard Media (Compressed)",
        detail = "Standard compressed photo or video mode.",
    ),
    ;

    companion object {
        fun fromStored(name: String?): QualityMode =
            entries.firstOrNull { it.name == name } ?: ORIGINAL_QUALITY
    }
}

enum class LocalSourceKind {
    MEDIA_STORE,
    SAF_URI,
    LOCAL_FILE,
}

/** Pure-Kotlin representation of a local file queued for WhatsApp Quick Sender. */
data class LocalSendItem(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
    val uriString: String? = null,
    val filePath: String? = null,
    val isReadable: Boolean = true,
    val sourceKind: LocalSourceKind = LocalSourceKind.MEDIA_STORE,
    val relativeFolderPath: String = "",
    val bucketName: String = "",
)

/** Lightweight saved combination of target app, quality toggle, and folder source. */
data class SendPreset(
    val label: String,
    val target: WhatsAppTarget,
    val qualityMode: QualityMode,
    val folderLabel: String,
    val updatedAtMs: Long,
)

enum class BatchStrategy {
    SINGLE_SEND,
    SPLIT_BATCHES_30,
    ZIP_ARCHIVE,
}

/** Pre-flight breakdown identifying oversized files, >30-file batches, and skipped unreadable/0-byte items. */
data class PreFlightReport(
    val eligibleItems: List<LocalSendItem>,
    val oversizedItems: List<LocalSendItem>,
    val skippedZeroByteCount: Int,
    val skippedUnreadableCount: Int,
    val isFolderSource: Boolean,
) {
    val skippedTotalCount: Int
        get() = skippedZeroByteCount + skippedUnreadableCount

    fun effectiveItems(excludeOversized: Boolean): List<LocalSendItem> =
        if (excludeOversized) eligibleItems else eligibleItems + oversizedItems

    fun totalBytes(excludeOversized: Boolean): Long =
        effectiveItems(excludeOversized).sumOf { it.sizeBytes }

    fun exceedsBatchLimit(excludeOversized: Boolean): Boolean =
        effectiveItems(excludeOversized).size > MAX_WHATSAPP_BATCH_FILES

    fun batchCount(excludeOversized: Boolean): Int {
        val count = effectiveItems(excludeOversized).size
        return if (count == 0) 0 else (count + MAX_WHATSAPP_BATCH_FILES - 1) / MAX_WHATSAPP_BATCH_FILES
    }

    fun recommendZip(excludeOversized: Boolean): Boolean =
        isFolderSource || exceedsBatchLimit(excludeOversized)
}

/** Progress event emitted while streaming selected files into a staged ZIP archive. */
data class ZipProgress(
    val filesCompleted: Int,
    val totalFiles: Int,
    val bytesProcessed: Long,
    val totalBytes: Long,
    val currentFileName: String,
) {
    val fraction: Float
        get() = when {
            totalBytes > 0L -> (bytesProcessed.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            totalFiles > 0 -> (filesCompleted.toFloat() / totalFiles.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }
}

sealed interface ZipPreparationResult {
    data class Success(
        val archiveFile: File,
        val archiveUri: Uri,
        val fileCount: Int,
        val archiveSizeBytes: Long,
    ) : ZipPreparationResult

    data class Failure(
        val message: String,
        val cause: Throwable? = null,
    ) : ZipPreparationResult
}

enum class SendLaunchResult {
    LAUNCHED_WHATSAPP,
    LAUNCHED_SHARESHEET,
    NO_TARGET_ACTIVITY,
    EMPTY_SELECTION,
    ERROR,
}

/** Input payload delivered from Phone folders, folder multi-select, or SAF picker to WhatsAppSenderSheet. */
data class WhatsAppSendPayload(
    val title: String,
    val items: List<LocalSendItem>,
    val isFolderSource: Boolean,
    val sourceBucketId: Long? = null,
    val mediaItemIds: List<Long> = emptyList(),
)

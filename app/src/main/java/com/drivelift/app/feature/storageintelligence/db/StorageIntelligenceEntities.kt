package com.drivelift.app.feature.storageintelligence.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.drivelift.app.feature.storageintelligence.model.DuplicateGroupModel
import com.drivelift.app.feature.storageintelligence.model.DuplicateMatchKind
import com.drivelift.app.feature.storageintelligence.model.HashAlgorithm
import com.drivelift.app.feature.storageintelligence.model.LargeFileCategory
import com.drivelift.app.feature.storageintelligence.model.OrphanArtifactCandidate
import com.drivelift.app.feature.storageintelligence.model.OrphanCategory
import com.drivelift.app.feature.storageintelligence.model.SourceScanError
import com.drivelift.app.feature.storageintelligence.model.StorageSourceType
import com.drivelift.app.feature.storageintelligence.model.UnifiedFileNode
import com.drivelift.app.feature.storageintelligence.model.classifyLargeFileCategory
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "storage_scan_sessions")
data class StorageScanSessionEntity(
    @PrimaryKey(autoGenerate = true) val sessionId: Long = 0,
    val scope: String,
    val startedAtMs: Long,
    val completedAtMs: Long,
    val status: String,
    val scannedFilesCount: Int,
    val bytesAnalyzed: Long,
    val duplicateReclaimableBytes: Long,
    val largeFilesTotalBytes: Long,
    val orphanReclaimableBytes: Long,
    val totalReclaimableBytes: Long,
    val partialErrorsJson: String,
    val thresholdBytes: Long,
)

@Entity(
    tableName = "duplicate_groups",
    indices = [Index("sessionId"), Index("reclaimableBytes")],
)
data class DuplicateGroupEntity(
    @PrimaryKey val groupKey: String,
    val sessionId: Long,
    val matchKind: String,
    val hashAlgorithm: String,
    val hashValue: String?,
    val normalizedName: String?,
    val sizeBytes: Long,
    val itemCount: Int,
    val reclaimableBytes: Long,
    val isCrossSource: Boolean,
)

@Entity(
    tableName = "duplicate_items",
    indices = [Index("sessionId"), Index("groupKey"), Index("sourceId")],
)
data class DuplicateItemEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val sessionId: Long,
    val groupKey: String,
    val nodeId: String,
    val nativeId: String,
    val sourceId: String,
    val sourceLabel: String,
    val sourceType: String,
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val mimeType: String,
    val modifiedAt: Long,
    val hashAlgorithm: String,
    val hashValue: String?,
    val thumbnailRef: String?,
    val localUri: String?,
    val bucketId: Long?,
    val parentFolderId: String?,
    val isTrashed: Boolean,
)

@Entity(
    tableName = "large_files",
    indices = [Index("sessionId"), Index("sizeBytes"), Index("sourceId"), Index("category")],
)
data class LargeFileEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val sessionId: Long,
    val nodeId: String,
    val nativeId: String,
    val sourceId: String,
    val sourceLabel: String,
    val sourceType: String,
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val mimeType: String,
    val category: String,
    val modifiedAt: Long,
    val thumbnailRef: String?,
    val localUri: String?,
    val bucketId: Long?,
    val parentFolderId: String?,
)

@Entity(
    tableName = "orphan_artifacts",
    indices = [Index("sessionId"), Index("category"), Index("sourceId")],
)
data class OrphanArtifactEntity(
    @PrimaryKey val artifactId: String,
    val sessionId: Long,
    val sourceId: String,
    val sourceLabel: String,
    val sourceType: String,
    val category: String,
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val itemCount: Int,
    val nativeId: String?,
    val detail: String,
    val localUri: String?,
    val transferStatus: String?,
    val isLocked: Boolean,
    val canClean: Boolean,
)

fun encodeScanErrors(errors: List<SourceScanError>): String {
    if (errors.isEmpty()) return "[]"
    val arr = JSONArray()
    for (err in errors) {
        arr.put(
            JSONObject()
                .put("sourceId", err.sourceId)
                .put("sourceLabel", err.sourceLabel)
                .put("message", err.message)
                .put("authExpired", err.authExpired),
        )
    }
    return arr.toString()
}

fun decodeScanErrors(raw: String): List<SourceScanError> {
    if (raw.isBlank() || raw == "[]") return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        List(arr.length()) { idx ->
            val obj = arr.getJSONObject(idx)
            SourceScanError(
                sourceId = obj.optString("sourceId"),
                sourceLabel = obj.optString("sourceLabel"),
                message = obj.optString("message"),
                authExpired = obj.optBoolean("authExpired", false),
            )
        }
    }.getOrDefault(emptyList())
}

fun DuplicateGroupEntity.toModel(items: List<DuplicateItemEntity>): DuplicateGroupModel =
    DuplicateGroupModel(
        groupKey = groupKey,
        matchKind = DuplicateMatchKind.entries.firstOrNull { it.name == matchKind }
            ?: DuplicateMatchKind.PROBABLE_MATCH,
        hashAlgorithm = HashAlgorithm.entries.firstOrNull { it.name == hashAlgorithm }
            ?: HashAlgorithm.NONE,
        hashValue = hashValue,
        normalizedName = normalizedName,
        sizeBytes = sizeBytes,
        reclaimableBytes = reclaimableBytes,
        isCrossSource = isCrossSource,
        items = items.map { it.toUnifiedNode() },
    )

fun DuplicateItemEntity.toUnifiedNode(): UnifiedFileNode =
    UnifiedFileNode(
        id = nodeId,
        nativeId = nativeId,
        sourceId = sourceId,
        sourceLabel = sourceLabel,
        sourceType = StorageSourceType.entries.firstOrNull { it.name == sourceType }
            ?: StorageSourceType.LOCAL,
        name = name,
        path = path,
        sizeBytes = sizeBytes,
        mimeType = mimeType,
        modifiedAt = modifiedAt,
        hashAlgorithm = HashAlgorithm.entries.firstOrNull { it.name == hashAlgorithm }
            ?: HashAlgorithm.NONE,
        hashValue = hashValue,
        thumbnailRef = thumbnailRef,
        isTrashed = isTrashed,
        localUri = localUri,
        bucketId = bucketId,
        parentFolderId = parentFolderId,
    )

fun LargeFileEntity.toUnifiedNode(): UnifiedFileNode =
    UnifiedFileNode(
        id = nodeId,
        nativeId = nativeId,
        sourceId = sourceId,
        sourceLabel = sourceLabel,
        sourceType = StorageSourceType.entries.firstOrNull { it.name == sourceType }
            ?: StorageSourceType.LOCAL,
        name = name,
        path = path,
        sizeBytes = sizeBytes,
        mimeType = mimeType,
        modifiedAt = modifiedAt,
        thumbnailRef = thumbnailRef,
        localUri = localUri,
        bucketId = bucketId,
        parentFolderId = parentFolderId,
    )

fun UnifiedFileNode.toLargeFileEntity(sessionId: Long): LargeFileEntity =
    LargeFileEntity(
        sessionId = sessionId,
        nodeId = id,
        nativeId = nativeId,
        sourceId = sourceId,
        sourceLabel = sourceLabel,
        sourceType = sourceType.name,
        name = name,
        path = path,
        sizeBytes = sizeBytes,
        mimeType = mimeType,
        category = classifyLargeFileCategory(name, mimeType).name,
        modifiedAt = modifiedAt,
        thumbnailRef = thumbnailRef,
        localUri = localUri,
        bucketId = bucketId,
        parentFolderId = parentFolderId,
    )

fun OrphanArtifactEntity.toCandidate(): OrphanArtifactCandidate =
    OrphanArtifactCandidate(
        artifactId = artifactId,
        sourceId = sourceId,
        sourceLabel = sourceLabel,
        sourceType = StorageSourceType.entries.firstOrNull { it.name == sourceType }
            ?: StorageSourceType.LOCAL,
        category = OrphanCategory.entries.firstOrNull { it.name == category }
            ?: OrphanCategory.LOCAL_APP_CACHE,
        name = name,
        path = path,
        sizeBytes = sizeBytes,
        modifiedAt = modifiedAt,
        itemCount = itemCount,
        nativeId = nativeId,
        detail = detail,
        localUri = localUri,
        transferStatus = transferStatus,
        canClean = canClean,
    )

fun OrphanArtifactCandidate.toEntity(sessionId: Long): OrphanArtifactEntity =
    OrphanArtifactEntity(
        artifactId = artifactId,
        sessionId = sessionId,
        sourceId = sourceId,
        sourceLabel = sourceLabel,
        sourceType = sourceType.name,
        category = category.name,
        name = name,
        path = path,
        sizeBytes = sizeBytes,
        modifiedAt = modifiedAt,
        itemCount = itemCount,
        nativeId = nativeId,
        detail = detail,
        localUri = localUri,
        transferStatus = transferStatus,
        isLocked = false,
        canClean = canClean,
    )

fun LargeFileEntity.parsedCategory(): LargeFileCategory =
    LargeFileCategory.entries.firstOrNull { it.name == category } ?: LargeFileCategory.OTHER

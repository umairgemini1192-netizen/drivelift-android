package com.drivelift.app.feature.storageintelligence.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface StorageIntelligenceDao {
    @Insert
    suspend fun insertSession(session: StorageScanSessionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDuplicateGroups(groups: List<DuplicateGroupEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDuplicateItems(items: List<DuplicateItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLargeFiles(items: List<LargeFileEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrphans(items: List<OrphanArtifactEntity>)

    @Query("SELECT * FROM storage_scan_sessions ORDER BY sessionId DESC LIMIT 1")
    fun observeLatestSession(): Flow<StorageScanSessionEntity?>

    @Query("SELECT * FROM storage_scan_sessions ORDER BY sessionId DESC LIMIT 1")
    suspend fun latestSessionOnce(): StorageScanSessionEntity?

    @Query("SELECT * FROM duplicate_groups WHERE sessionId = :sessionId ORDER BY reclaimableBytes DESC, groupKey ASC")
    fun observeDuplicateGroups(sessionId: Long): Flow<List<DuplicateGroupEntity>>

    @Query("SELECT * FROM duplicate_items WHERE sessionId = :sessionId ORDER BY rowId ASC")
    fun observeDuplicateItems(sessionId: Long): Flow<List<DuplicateItemEntity>>

    @Query("SELECT * FROM large_files WHERE sessionId = :sessionId ORDER BY sizeBytes DESC, name ASC")
    fun observeLargeFiles(sessionId: Long): Flow<List<LargeFileEntity>>

    @Query("SELECT * FROM orphan_artifacts WHERE sessionId = :sessionId ORDER BY sizeBytes DESC, name ASC")
    fun observeOrphans(sessionId: Long): Flow<List<OrphanArtifactEntity>>

    @Query("SELECT * FROM duplicate_groups WHERE sessionId = :sessionId")
    suspend fun duplicateGroupsForSession(sessionId: Long): List<DuplicateGroupEntity>

    @Query("SELECT * FROM duplicate_items WHERE sessionId = :sessionId")
    suspend fun duplicateItemsForSession(sessionId: Long): List<DuplicateItemEntity>

    @Query("SELECT * FROM large_files WHERE sessionId = :sessionId")
    suspend fun largeFilesForSession(sessionId: Long): List<LargeFileEntity>

    @Query("SELECT * FROM orphan_artifacts WHERE sessionId = :sessionId")
    suspend fun orphansForSession(sessionId: Long): List<OrphanArtifactEntity>

    @Query("DELETE FROM duplicate_items WHERE sessionId = :sessionId AND nodeId IN (:nodeIds)")
    suspend fun deleteDuplicateItemsByNodeIds(sessionId: Long, nodeIds: List<String>)

    @Query("DELETE FROM large_files WHERE sessionId = :sessionId AND nodeId IN (:nodeIds)")
    suspend fun deleteLargeFilesByNodeIds(sessionId: Long, nodeIds: List<String>)

    @Query("DELETE FROM orphan_artifacts WHERE sessionId = :sessionId AND artifactId IN (:artifactIds)")
    suspend fun deleteOrphansByIds(sessionId: Long, artifactIds: List<String>)

    @Query(
        """
        DELETE FROM duplicate_groups
        WHERE sessionId = :sessionId
          AND (SELECT COUNT(*) FROM duplicate_items WHERE duplicate_items.groupKey = duplicate_groups.groupKey AND duplicate_items.sessionId = :sessionId) < 2
        """,
    )
    suspend fun deleteUndersizedDuplicateGroups(sessionId: Long)

    @Query(
        """
        DELETE FROM duplicate_items
        WHERE sessionId = :sessionId
          AND groupKey NOT IN (SELECT groupKey FROM duplicate_groups WHERE sessionId = :sessionId)
        """,
    )
    suspend fun deleteDanglingDuplicateItems(sessionId: Long)

    @Query("UPDATE duplicate_groups SET itemCount = (SELECT COUNT(*) FROM duplicate_items WHERE duplicate_items.groupKey = duplicate_groups.groupKey AND duplicate_items.sessionId = :sessionId), reclaimableBytes = sizeBytes * MAX(0, (SELECT COUNT(*) FROM duplicate_items WHERE duplicate_items.groupKey = duplicate_groups.groupKey AND duplicate_items.sessionId = :sessionId) - 1) WHERE sessionId = :sessionId")
    suspend fun refreshGroupCounts(sessionId: Long)

    @Query(
        """
        UPDATE storage_scan_sessions
        SET duplicateReclaimableBytes = COALESCE((SELECT SUM(reclaimableBytes) FROM duplicate_groups WHERE sessionId = :sessionId), 0),
            largeFilesTotalBytes = COALESCE((SELECT SUM(sizeBytes) FROM large_files WHERE sessionId = :sessionId), 0),
            orphanReclaimableBytes = COALESCE((SELECT SUM(sizeBytes) FROM orphan_artifacts WHERE sessionId = :sessionId AND canClean = 1), 0),
            totalReclaimableBytes = COALESCE((SELECT SUM(reclaimableBytes) FROM duplicate_groups WHERE sessionId = :sessionId), 0)
                + COALESCE((SELECT SUM(sizeBytes) FROM orphan_artifacts WHERE sessionId = :sessionId AND canClean = 1), 0)
        WHERE sessionId = :sessionId
        """,
    )
    suspend fun refreshSessionTotals(sessionId: Long)

    @Query("DELETE FROM duplicate_items WHERE sessionId != :keepSessionId")
    suspend fun deleteDuplicateItemsExcept(keepSessionId: Long)

    @Query("DELETE FROM duplicate_groups WHERE sessionId != :keepSessionId")
    suspend fun deleteDuplicateGroupsExcept(keepSessionId: Long)

    @Query("DELETE FROM large_files WHERE sessionId != :keepSessionId")
    suspend fun deleteLargeFilesExcept(keepSessionId: Long)

    @Query("DELETE FROM orphan_artifacts WHERE sessionId != :keepSessionId")
    suspend fun deleteOrphansExcept(keepSessionId: Long)

    @Query("DELETE FROM storage_scan_sessions WHERE sessionId != :keepSessionId")
    suspend fun deleteSessionsExcept(keepSessionId: Long)

    /** Persists a complete scan session and replaces stale cached sessions atomically. */
    @Transaction
    suspend fun persistScanResult(
        session: StorageScanSessionEntity,
        groups: List<DuplicateGroupEntity>,
        duplicateItems: List<DuplicateItemEntity>,
        largeFiles: List<LargeFileEntity>,
        orphans: List<OrphanArtifactEntity>,
    ): Long {
        val id = insertSession(session)
        if (groups.isNotEmpty()) {
            insertDuplicateGroups(groups.map { it.copy(sessionId = id) })
        }
        if (duplicateItems.isNotEmpty()) {
            duplicateItems.chunked(250).forEach { chunk ->
                insertDuplicateItems(chunk.map { it.copy(sessionId = id) })
            }
        }
        if (largeFiles.isNotEmpty()) {
            largeFiles.chunked(250).forEach { chunk ->
                insertLargeFiles(chunk.map { it.copy(sessionId = id) })
            }
        }
        if (orphans.isNotEmpty()) {
            orphans.chunked(250).forEach { chunk ->
                insertOrphans(chunk.map { it.copy(sessionId = id) })
            }
        }
        deleteDuplicateItemsExcept(id)
        deleteDuplicateGroupsExcept(id)
        deleteLargeFilesExcept(id)
        deleteOrphansExcept(id)
        deleteSessionsExcept(id)
        return id
    }

    /** Removes nodes confirmed deleted/trashed and keeps group and session totals consistent. */
    @Transaction
    suspend fun pruneCleanedNodes(sessionId: Long, nodeIds: List<String>) {
        if (nodeIds.isEmpty()) return
        nodeIds.chunked(250).forEach { chunk ->
            deleteDuplicateItemsByNodeIds(sessionId, chunk)
            deleteLargeFilesByNodeIds(sessionId, chunk)
        }
        deleteUndersizedDuplicateGroups(sessionId)
        deleteDanglingDuplicateItems(sessionId)
        refreshGroupCounts(sessionId)
        refreshSessionTotals(sessionId)
    }

    /** Removes cleaned orphan artifacts and updates session reclaimable totals. */
    @Transaction
    suspend fun pruneCleanedOrphans(sessionId: Long, artifactIds: List<String>) {
        if (artifactIds.isEmpty()) return
        artifactIds.chunked(250).forEach { chunk ->
            deleteOrphansByIds(sessionId, chunk)
        }
        refreshSessionTotals(sessionId)
    }
}

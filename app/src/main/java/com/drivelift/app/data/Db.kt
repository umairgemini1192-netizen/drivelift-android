package com.drivelift.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Life of one file:
 * QUEUED -> UPLOADING -> UPLOADED -> VERIFIED -> REMOVED
 * A failed verification sends the row back to QUEUED. FAILED is reserved for
 * things no retry can fix (the source file is gone). CANCELLED is user choice.
 */
enum class TransferStatus { QUEUED, UPLOADING, UPLOADED, VERIFIED, REMOVED, FAILED, CANCELLED }

@Entity(
    tableName = "transfers",
    indices = [Index("status"), Index(value = ["mediaUri"], unique = true)],
)
data class TransferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaUri: String,
    val name: String,
    val mime: String,
    val size: Long,
    val dateModifiedMs: Long,
    val dateTakenMs: Long,
    val bucketName: String,
    /** Null means "auto-organize": resolved to a Drive folder when the upload starts. */
    val destFolderId: String?,
    val destLabel: String,
    val status: String = TransferStatus.QUEUED.name,
    /** Drive resumable-upload session, kept so an interrupted upload continues mid-file. */
    val sessionUri: String? = null,
    val driveFileId: String? = null,
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

data class StatusCount(val status: String, val count: Int, val bytes: Long)

@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey val folderId: String,
    val name: String,
    val path: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface TransferDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<TransferEntity>): List<Long>

    @Query(
        """SELECT * FROM transfers ORDER BY CASE status
            WHEN 'UPLOADING' THEN 0 WHEN 'QUEUED' THEN 1 WHEN 'UPLOADED' THEN 2
            WHEN 'FAILED' THEN 3 WHEN 'VERIFIED' THEN 4 ELSE 5 END, id LIMIT :limit"""
    )
    fun observeRecent(limit: Int): Flow<List<TransferEntity>>

    @Query("SELECT status, COUNT(*) AS count, COALESCE(SUM(size), 0) AS bytes FROM transfers GROUP BY status")
    fun observeCounts(): Flow<List<StatusCount>>

    @Query("SELECT mediaUri FROM transfers WHERE status NOT IN ('REMOVED', 'CANCELLED', 'FAILED')")
    fun observeTrackedUris(): Flow<List<String>>

    @Query(
        """SELECT * FROM transfers WHERE status IN ('UPLOADING', 'QUEUED')
            ORDER BY CASE status WHEN 'UPLOADING' THEN 0 ELSE 1 END, id LIMIT 1"""
    )
    suspend fun nextPending(): TransferEntity?

    @Query("SELECT * FROM transfers WHERE status = :status ORDER BY id LIMIT :limit")
    suspend fun byStatus(status: String, limit: Int): List<TransferEntity>

    @Query("SELECT * FROM transfers WHERE id = :id")
    suspend fun get(id: Long): TransferEntity?

    @Query("SELECT * FROM transfers WHERE mediaUri IN (:uris)")
    suspend fun byMediaUris(uris: List<String>): List<TransferEntity>

    @Query("SELECT * FROM transfers WHERE driveFileId IN (:ids)")
    suspend fun byDriveFileIds(ids: List<String>): List<TransferEntity>

    /** Transfers still copying a file of one of these names (their Drive id may not be known yet). */
    @Query("SELECT * FROM transfers WHERE status IN ('QUEUED', 'UPLOADING') AND name IN (:names)")
    suspend fun activeByNames(names: List<String>): List<TransferEntity>

    @Query("SELECT COUNT(*) FROM transfers WHERE status IN (:statuses)")
    suspend fun count(statuses: List<String>): Int

    @Query("UPDATE transfers SET destFolderId = :folderId WHERE id = :id")
    suspend fun setDest(id: Long, folderId: String)

    @Query("UPDATE transfers SET sessionUri = :sessionUri WHERE id = :id")
    suspend fun setSession(id: Long, sessionUri: String?)

    @Query("UPDATE transfers SET size = :size WHERE id = :id")
    suspend fun setSize(id: Long, size: Long)

    @Query("UPDATE transfers SET status = 'UPLOADING' WHERE id = :id AND status = 'QUEUED'")
    suspend fun markUploading(id: Long)

    @Query(
        """UPDATE transfers SET status = 'UPLOADED', driveFileId = :fileId, sessionUri = NULL, lastError = NULL
            WHERE id = :id AND status != 'CANCELLED'"""
    )
    suspend fun markUploaded(id: Long, fileId: String)

    @Query("UPDATE transfers SET status = :status, lastError = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, error: String?)

    @Query("UPDATE transfers SET attempts = attempts + 1, lastError = :error WHERE id = :id")
    suspend fun noteError(id: Long, error: String)

    @Query(
        """UPDATE transfers SET status = 'QUEUED', sessionUri = NULL, driveFileId = NULL,
            attempts = attempts + 1, lastError = :error WHERE id = :id"""
    )
    suspend fun requeue(id: Long, error: String)

    @Query("UPDATE transfers SET status = 'REMOVED', lastError = NULL WHERE mediaUri IN (:uris) AND status = 'VERIFIED'")
    suspend fun markRemoved(uris: List<String>)

    @Query("DELETE FROM transfers WHERE mediaUri IN (:uris) AND status IN ('REMOVED', 'FAILED', 'CANCELLED')")
    suspend fun purgeFinished(uris: List<String>)

    @Query("DELETE FROM transfers WHERE status IN ('REMOVED', 'CANCELLED', 'FAILED')")
    suspend fun clearFinished()

    @Query("UPDATE transfers SET status = 'CANCELLED' WHERE id = :id AND status IN ('QUEUED', 'UPLOADING')")
    suspend fun cancel(id: Long)

    @Query("UPDATE transfers SET status = 'CANCELLED' WHERE status IN ('QUEUED', 'UPLOADING')")
    suspend fun cancelAllPending()

    @Query("UPDATE transfers SET status = 'QUEUED', lastError = NULL WHERE status = 'FAILED'")
    suspend fun retryFailed()
}

@Dao
interface PresetDao {
    @Query("SELECT * FROM presets ORDER BY createdAt")
    fun observe(): Flow<List<PresetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preset: PresetEntity)

    @Query("DELETE FROM presets WHERE folderId = :folderId")
    suspend fun delete(folderId: String)

    /** Pinned destinations are folder ids of one Drive account; they go when the account changes. */
    @Query("DELETE FROM presets")
    suspend fun clear()
}

@Database(entities = [TransferEntity::class, PresetEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transfers(): TransferDao
    abstract fun presets(): PresetDao
}

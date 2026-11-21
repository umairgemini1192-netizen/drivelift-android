package com.drivelift.app.feature.intelligence.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/** The key fields the incremental indexer compares, without loading any text. */
data class IndexedDocumentKey(
    val uri: String,
    val modifiedAt: Long,
    val sizeBytes: Long,
    val extractionStatus: String,
)

data class StatusCount(val extractionStatus: String, val count: Int)

@Dao
abstract class IntelligenceDao {

    // Offline document index

    @Query("SELECT uri, modifiedAt, sizeBytes, extractionStatus FROM indexed_documents")
    abstract suspend fun documentKeys(): List<IndexedDocumentKey>

    @Query("SELECT * FROM indexed_documents WHERE uri = :uri LIMIT 1")
    abstract suspend fun documentByUri(uri: String): IndexedDocumentEntity?

    @Query("SELECT * FROM indexed_documents WHERE extractionStatus = 'PENDING' ORDER BY modifiedAt DESC LIMIT :limit")
    abstract suspend fun pendingDocuments(limit: Int): List<IndexedDocumentEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertDocument(doc: IndexedDocumentEntity): Long

    @Update
    abstract suspend fun updateDocument(doc: IndexedDocumentEntity)

    /** Adds [doc] or replaces the row with the same uri, keeping its rowid so FTS stays aligned. */
    @Transaction
    open suspend fun upsertDocument(doc: IndexedDocumentEntity) {
        val existing = documentByUri(doc.uri)
        if (existing == null) insertDocument(doc.copy(id = 0)) else updateDocument(doc.copy(id = existing.id))
    }

    @Query("DELETE FROM indexed_documents WHERE uri IN (:uris)")
    abstract suspend fun deleteDocuments(uris: List<String>)

    @Query("UPDATE indexed_documents SET extractionStatus = 'PENDING'")
    abstract suspend fun markAllPending()

    @Query("SELECT extractionStatus, COUNT(*) AS count FROM indexed_documents GROUP BY extractionStatus")
    abstract fun observeStatusCounts(): Flow<List<StatusCount>>

    @Query("SELECT extractionStatus, COUNT(*) AS count FROM indexed_documents GROUP BY extractionStatus")
    abstract suspend fun statusCounts(): List<StatusCount>

    /** Search built by `SearchQueryBuilder`: always parameterized, never string-concatenated user text. */
    @RawQuery
    abstract suspend fun searchDocuments(query: SupportSQLiteQuery): List<IndexedDocumentEntity>

    // Smart rules

    @Query("SELECT * FROM smart_rules ORDER BY id")
    abstract fun observeRules(): Flow<List<SmartRuleEntity>>

    @Query("SELECT * FROM smart_rules ORDER BY id")
    abstract suspend fun rules(): List<SmartRuleEntity>

    @Query("SELECT * FROM smart_rules WHERE id = :id")
    abstract suspend fun rule(id: Long): SmartRuleEntity?

    @Insert
    abstract suspend fun insertRule(rule: SmartRuleEntity): Long

    @Update
    abstract suspend fun updateRule(rule: SmartRuleEntity)

    @Query("DELETE FROM smart_rules WHERE id = :id")
    abstract suspend fun deleteRule(id: Long)

    @Query("UPDATE smart_rules SET lastRunAt = :at, totalProcessedCount = totalProcessedCount + :processed WHERE id = :id")
    abstract suspend fun recordRun(id: Long, at: Long, processed: Int)

    // Execution log

    @Insert
    abstract suspend fun insertLogs(logs: List<RuleExecutionLogEntity>)

    @Query("SELECT * FROM rule_execution_logs ORDER BY timestamp DESC, id DESC LIMIT :limit")
    abstract fun observeRecentLogs(limit: Int): Flow<List<RuleExecutionLogEntity>>

    @Query("SELECT * FROM rule_execution_logs WHERE ruleId = :ruleId ORDER BY timestamp DESC, id DESC")
    abstract suspend fun logsForRule(ruleId: Long): List<RuleExecutionLogEntity>

    /** Keeps the log bounded: only the newest [keep] entries stay. */
    @Query("DELETE FROM rule_execution_logs WHERE id NOT IN (SELECT id FROM rule_execution_logs ORDER BY timestamp DESC, id DESC LIMIT :keep)")
    abstract suspend fun trimLogs(keep: Int)
}

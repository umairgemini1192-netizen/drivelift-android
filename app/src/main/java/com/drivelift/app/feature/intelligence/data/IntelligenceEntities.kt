package com.drivelift.app.feature.intelligence.data

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

/** Where a document is in the OCR / text index. */
enum class ExtractionStatus { PENDING, INDEXED, FAILED, SKIPPED }

/**
 * One phone document (photo, screenshot, scanned page or PDF) known to the offline index.
 *
 * [extractedText] keeps the original text, Harakat and Tashkeel included, for result snippets.
 * [normalizedText] is the same text folded by `ArabicUrduTextNormalizer` (plus the normalized
 * name and folder) and is what [IndexedDocumentFtsEntity] indexes. [id] is the SQLite rowid the
 * FTS table is keyed on.
 */
@Entity(
    tableName = "indexed_documents",
    indices = [Index(value = ["uri"], unique = true), Index(value = ["modifiedAt"])],
)
data class IndexedDocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val displayName: String,
    val folderPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val indexedAt: Long = 0,
    val extractionStatus: String = ExtractionStatus.PENDING.name,
    val pageCount: Int = 0,
    val extractedText: String = "",
    val normalizedText: String = "",
)

/** External-content FTS4 index over [IndexedDocumentEntity]; Room keeps it in sync with triggers. */
@Fts4(contentEntity = IndexedDocumentEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "indexed_documents_fts")
data class IndexedDocumentFtsEntity(
    val displayName: String,
    val folderPath: String,
    val normalizedText: String,
)

enum class RuleActionType { MOVE_LOCAL, COPY_LOCAL, UPLOAD_CLOUD }

enum class ConflictPolicy { SKIP, RENAME }

enum class RuleLogStatus { SUCCESS, SKIPPED, FAILED }

/**
 * A Smart Folder rule. Sources are either a phone media folder (`mediastore:<RELATIVE_PATH>`) or a
 * folder the user picked with Android's folder picker (a `content://` tree URI). Targets are a
 * picked folder for local actions, or `drive:<folderId>` / `drive:auto` for uploads.
 * Size and age bounds of `null` mean "no bound".
 */
@Entity(tableName = "smart_rules")
data class SmartRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean = false,
    val sourceFolderUri: String = "",
    val sourceFolderLabel: String = "",
    val extensionsCsv: String = "",
    val minSizeBytes: Long? = null,
    val maxSizeBytes: Long? = null,
    val minVideoHeightPx: Int? = null,
    val minAgeHours: Int? = null,
    val actionType: String = RuleActionType.COPY_LOCAL.name,
    val targetDestinationUri: String = "",
    val targetDestinationLabel: String = "",
    val requireWifi: Boolean = false,
    val requireCharging: Boolean = false,
    val conflictPolicy: String = ConflictPolicy.SKIP.name,
    val lastRunAt: Long? = null,
    val totalProcessedCount: Int = 0,
)

val SmartRuleEntity.action: RuleActionType
    get() = RuleActionType.entries.firstOrNull { it.name == actionType } ?: RuleActionType.COPY_LOCAL

val SmartRuleEntity.conflict: ConflictPolicy
    get() = ConflictPolicy.entries.firstOrNull { it.name == conflictPolicy } ?: ConflictPolicy.SKIP

/** True when both ends are chosen, so the rule can run. */
val SmartRuleEntity.isComplete: Boolean
    get() = sourceFolderUri.isNotBlank() && targetDestinationUri.isNotBlank()

@Entity(tableName = "rule_execution_logs", indices = [Index(value = ["timestamp"]), Index(value = ["ruleId"])])
data class RuleExecutionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleId: Long,
    val ruleName: String,
    val fileName: String,
    val actionType: String,
    val status: String,
    val message: String,
    val timestamp: Long,
)

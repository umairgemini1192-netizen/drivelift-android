package com.drivelift.app.feature.intelligence.rules

import com.drivelift.app.data.MediaItem
import com.drivelift.app.feature.intelligence.data.ConflictPolicy
import com.drivelift.app.feature.intelligence.data.IntelligenceDao
import com.drivelift.app.feature.intelligence.data.RuleActionType
import com.drivelift.app.feature.intelligence.data.RuleExecutionLogEntity
import com.drivelift.app.feature.intelligence.data.RuleLogStatus
import com.drivelift.app.feature.intelligence.data.SmartRuleEntity
import com.drivelift.app.feature.intelligence.data.action
import com.drivelift.app.feature.intelligence.data.conflict
import com.drivelift.app.feature.intelligence.data.isComplete
import com.drivelift.app.transfer.Destination
import kotlinx.coroutines.CancellationException
import java.util.Locale

/** One file a rule can act on. [mediaItem] is set for phone media (needed to queue an upload). */
data class RuleFile(
    val uri: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val videoHeightPx: Int? = null,
    val mediaItem: MediaItem? = null,
)

/** File access the rule executor needs. The Android version uses MediaStore and the folder picker. */
interface RuleFileSystem {
    /** Files directly inside [sourceUri]. Video heights are read only when [needVideoHeight]. */
    suspend fun list(sourceUri: String, needVideoHeight: Boolean): List<RuleFile>

    /** Names already present in the target folder (compared ignoring letter case). */
    suspend fun namesIn(targetUri: String): Set<String>

    /** Copies [file] into [targetUri] as [name]; returns the bytes written. Throws on failure. */
    suspend fun copy(file: RuleFile, targetUri: String, name: String): Long

    /** Removes the original after a verified copy. Returns false when it could not. */
    suspend fun delete(file: RuleFile): Boolean
}

/** Read-only bridge to DriveLift's transfer queue (the existing upload pipeline). */
interface CloudUploadAdapter {
    suspend fun enqueue(items: List<MediaItem>, destination: Destination): Int
}

/** Phone media DriveLift's transfer queue still depends on (QUEUED, UPLOADING, UPLOADED, VERIFIED). */
interface TransferLockLookup {
    suspend fun lockedUris(): Set<String>
}

/** Why a file was left out even though it matched. */
enum class SafetySkip(val message: String) {
    ACTIVE_FILE("Changed in the last minute (still recording or downloading)"),
    TRANSFER_LOCKED("In DriveLift's transfer queue"),
}

sealed interface MatchOutcome {
    data object Match : MatchOutcome
    data object NoMatch : MatchOutcome
    data class Skipped(val reason: SafetySkip) : MatchOutcome
}

/**
 * Decides whether a file meets a rule's conditions: type (extensions or MIME patterns such as
 * `video/`), size bounds, minimum video height (2160 for 4K) and minimum age. A file changed in the
 * last [ACTIVE_WINDOW_MS] or held by the transfer queue is always skipped.
 */
class SmartRuleMatcher(private val now: () -> Long) {

    fun evaluate(rule: SmartRuleEntity, file: RuleFile, lockedUris: Set<String>): MatchOutcome {
        if (!matchesType(rule.extensionsCsv, file)) return MatchOutcome.NoMatch
        rule.minSizeBytes?.let { if (file.sizeBytes < it) return MatchOutcome.NoMatch }
        rule.maxSizeBytes?.let { if (file.sizeBytes > it) return MatchOutcome.NoMatch }
        rule.minVideoHeightPx?.let { minHeight ->
            if (!file.mimeType.startsWith("video/")) return MatchOutcome.NoMatch
            // A video whose resolution could not be read never counts as 4K.
            val height = file.videoHeightPx ?: return MatchOutcome.NoMatch
            if (height < minHeight) return MatchOutcome.NoMatch
        }
        val age = now() - file.lastModifiedMs
        rule.minAgeHours?.let { if (age < it * HOUR_MS) return MatchOutcome.NoMatch }
        if (age < ACTIVE_WINDOW_MS) return MatchOutcome.Skipped(SafetySkip.ACTIVE_FILE)
        if (file.uri in lockedUris) return MatchOutcome.Skipped(SafetySkip.TRANSFER_LOCKED)
        return MatchOutcome.Match
    }

    companion object {
        const val ACTIVE_WINDOW_MS = 60_000L
        const val HOUR_MS = 60L * 60 * 1000

        /** `apk, .pdf, video/*` style lists. Empty means every file. */
        fun parseTypes(csv: String): List<String> =
            csv.split(',', ';', ' ').map { it.trim().lowercase(Locale.ROOT).removePrefix(".").removeSuffix("*") }
                .filter { it.isNotEmpty() }

        fun matchesType(csv: String, file: RuleFile): Boolean {
            val types = parseTypes(csv)
            if (types.isEmpty()) return true
            val name = file.name.lowercase(Locale.ROOT)
            val mime = file.mimeType.lowercase(Locale.ROOT)
            return types.any { t -> if ('/' in t) mime.startsWith(t) else name.endsWith(".$t") }
        }
    }
}

/** What a run (or a dry run) found. */
data class RuleRunReport(
    val ruleId: Long,
    val matched: List<RuleFile>,
    val skipped: List<Pair<RuleFile, String>>,
    val processed: Int,
    val failed: Int,
    val dryRun: Boolean,
) {
    val matchedBytes: Long get() = matched.sumOf { it.sizeBytes }
}

/**
 * Runs a rule. A dry run lists the matching files and their total size and changes nothing. A
 * real run copies, moves or queues each match and logs every processed, skipped and failed file.
 *
 * Moves are copy, compare the written byte count with the original, then remove the original;
 * when they differ or the removal fails, the original stays and the file is logged as failed.
 */
class SmartRuleExecutor(
    private val dao: IntelligenceDao,
    private val fs: RuleFileSystem,
    private val uploader: CloudUploadAdapter,
    private val locks: TransferLockLookup,
    private val matcher: SmartRuleMatcher,
    private val clock: () -> Long,
) {
    suspend fun run(rule: SmartRuleEntity, dryRun: Boolean): RuleRunReport {
        if (!rule.isComplete) return RuleRunReport(rule.id, emptyList(), emptyList(), 0, 0, dryRun)
        val files = fs.list(rule.sourceFolderUri, needVideoHeight = rule.minVideoHeightPx != null)
        val locked = locks.lockedUris()
        val matched = ArrayList<RuleFile>()
        val skipped = ArrayList<Pair<RuleFile, String>>()
        for (file in files) {
            when (val outcome = matcher.evaluate(rule, file, locked)) {
                MatchOutcome.Match -> matched += file
                is MatchOutcome.Skipped -> skipped += file to outcome.reason.message
                MatchOutcome.NoMatch -> Unit
            }
        }
        if (dryRun) return RuleRunReport(rule.id, matched, skipped, 0, 0, dryRun = true)

        val logs = ArrayList<RuleExecutionLogEntity>()
        fun log(file: RuleFile, status: RuleLogStatus, message: String) {
            logs += RuleExecutionLogEntity(
                ruleId = rule.id, ruleName = rule.name, fileName = file.name, actionType = rule.actionType,
                status = status.name, message = message, timestamp = clock(),
            )
        }
        skipped.forEach { (file, why) -> log(file, RuleLogStatus.SKIPPED, why) }

        var processed = 0
        var failed = 0
        when (rule.action) {
            RuleActionType.UPLOAD_CLOUD -> {
                val media = matched.filter { it.mediaItem != null }
                matched.filter { it.mediaItem == null }.forEach {
                    failed++
                    log(it, RuleLogStatus.FAILED, "Only phone photos and videos can be uploaded by a rule")
                }
                if (media.isNotEmpty()) {
                    val destination = parseDriveDestination(rule.targetDestinationUri, rule.targetDestinationLabel)
                    try {
                        uploader.enqueue(media.map { it.mediaItem!! }, destination)
                        processed += media.size
                        media.forEach { log(it, RuleLogStatus.SUCCESS, "Queued for upload to ${destination.label}") }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed += media.size
                        media.forEach { log(it, RuleLogStatus.FAILED, "Could not queue: ${e.message ?: "unknown error"}") }
                    }
                }
            }
            RuleActionType.COPY_LOCAL, RuleActionType.MOVE_LOCAL -> {
                val move = rule.action == RuleActionType.MOVE_LOCAL
                val taken = try {
                    fs.namesIn(rule.targetDestinationUri).map { it.lowercase(Locale.ROOT) }.toMutableSet()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    matched.forEach { log(it, RuleLogStatus.FAILED, "Destination folder cannot be opened") }
                    dao.insertLogs(logs)
                    dao.trimLogs(LOG_KEEP)
                    return RuleRunReport(rule.id, matched, skipped, 0, matched.size, dryRun = false)
                }
                for (file in matched) {
                    val name = resolveName(file.name, taken, rule.conflict)
                    if (name == null) {
                        log(file, RuleLogStatus.SKIPPED, "A file with this name is already in the destination")
                        continue
                    }
                    try {
                        val written = fs.copy(file, rule.targetDestinationUri, name)
                        taken += name.lowercase(Locale.ROOT)
                        if (written != file.sizeBytes) {
                            failed++
                            log(file, RuleLogStatus.FAILED, "Copy size did not match the original; the original was kept")
                            continue
                        }
                        if (move && !fs.delete(file)) {
                            failed++
                            log(file, RuleLogStatus.FAILED, "Copied as $name, but the original could not be removed")
                            continue
                        }
                        processed++
                        val verb = if (move) "Moved" else "Copied"
                        log(file, RuleLogStatus.SUCCESS, if (name == file.name) "$verb to ${rule.targetDestinationLabel}" else "$verb as $name")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed++
                        log(file, RuleLogStatus.FAILED, e.message ?: "Copy failed")
                    }
                }
            }
        }
        if (logs.isNotEmpty()) {
            dao.insertLogs(logs)
            dao.trimLogs(LOG_KEEP)
        }
        dao.recordRun(rule.id, clock(), processed)
        return RuleRunReport(rule.id, matched, skipped, processed, failed, dryRun = false)
    }

    companion object {
        const val LOG_KEEP = 500

        /**
         * The name to write under [policy], or null to skip. RENAME picks `name (1).ext`,
         * `name (2).ext` and so on, comparing names ignoring letter case.
         */
        fun resolveName(original: String, takenLower: Set<String>, policy: ConflictPolicy): String? {
            if (original.lowercase(Locale.ROOT) !in takenLower) return original
            if (policy == ConflictPolicy.SKIP) return null
            val dot = original.lastIndexOf('.').takeIf { it > 0 }
            val stem = if (dot != null) original.substring(0, dot) else original
            val ext = if (dot != null) original.substring(dot) else ""
            var n = 1
            while (true) {
                val candidate = "$stem ($n)$ext"
                if (candidate.lowercase(Locale.ROOT) !in takenLower) return candidate
                n++
            }
        }

        fun parseDriveDestination(uri: String, label: String): Destination {
            val id = uri.removePrefix(DRIVE_PREFIX)
            return if (id.isBlank() || id == DRIVE_AUTO) Destination.Auto else Destination(id, label.ifBlank { "Google Drive" })
        }

        const val DRIVE_PREFIX = "drive:"
        const val DRIVE_AUTO = "auto"
        const val MEDIASTORE_PREFIX = "mediastore:"
    }
}

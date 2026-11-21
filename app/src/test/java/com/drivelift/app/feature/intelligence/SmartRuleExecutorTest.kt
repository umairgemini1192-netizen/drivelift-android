package com.drivelift.app.feature.intelligence

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.drivelift.app.data.MediaItem
import com.drivelift.app.feature.intelligence.data.ConflictPolicy
import com.drivelift.app.feature.intelligence.data.IntelligenceAutomationDatabase
import com.drivelift.app.feature.intelligence.data.RuleActionType
import com.drivelift.app.feature.intelligence.data.RuleLogStatus
import com.drivelift.app.feature.intelligence.data.SmartRuleEntity
import com.drivelift.app.feature.intelligence.rules.CloudUploadAdapter
import com.drivelift.app.feature.intelligence.rules.RuleFile
import com.drivelift.app.feature.intelligence.rules.RuleFileSystem
import com.drivelift.app.feature.intelligence.rules.SmartRuleExecutor
import com.drivelift.app.feature.intelligence.rules.SmartRuleMatcher
import com.drivelift.app.feature.intelligence.rules.TransferLockLookup
import com.drivelift.app.transfer.Destination
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SmartRuleExecutorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: IntelligenceAutomationDatabase
    private val now = 1_800_000_000_000L

    private class FakeFs(
        val files: List<RuleFile>,
        val existing: MutableSet<String> = mutableSetOf(),
        val shortWrite: Set<String> = emptySet(),
        val undeletable: Set<String> = emptySet(),
    ) : RuleFileSystem {
        val copies = ArrayList<Pair<String, String>>()
        val deleted = ArrayList<String>()
        override suspend fun list(sourceUri: String, needVideoHeight: Boolean) = files
        override suspend fun namesIn(targetUri: String): Set<String> = existing.toSet()
        override suspend fun copy(file: RuleFile, targetUri: String, name: String): Long {
            copies += file.name to name
            existing += name
            return if (file.name in shortWrite) file.sizeBytes - 1 else file.sizeBytes
        }
        override suspend fun delete(file: RuleFile): Boolean {
            if (file.name in undeletable) return false
            deleted += file.name
            return true
        }
    }

    private class FakeUploader : CloudUploadAdapter {
        val queued = ArrayList<Pair<List<MediaItem>, Destination>>()
        override suspend fun enqueue(items: List<MediaItem>, destination: Destination): Int {
            queued += items to destination
            return items.size
        }
    }

    private fun file(name: String, size: Long = 100, media: Boolean = false): RuleFile {
        val uri = "content://test/$name"
        return RuleFile(
            uri = uri, name = name, mimeType = if (media) "video/mp4" else "application/pdf", sizeBytes = size,
            lastModifiedMs = now - 48 * SmartRuleMatcher.HOUR_MS,
            mediaItem = if (media) MediaItem(1, Uri.parse(uri), name, size, "video/mp4", now, now, 1, "Camera", "DCIM/Camera/", true, 1_000) else null,
        )
    }

    private fun executor(fs: RuleFileSystem, uploader: CloudUploadAdapter = FakeUploader(), locked: Set<String> = emptySet()) =
        SmartRuleExecutor(
            db.dao(), fs, uploader,
            object : TransferLockLookup { override suspend fun lockedUris() = locked },
            SmartRuleMatcher { now }, { now },
        )

    private suspend fun saved(rule: SmartRuleEntity): SmartRuleEntity = rule.copy(id = db.dao().insertRule(rule))

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, IntelligenceAutomationDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private val base = SmartRuleEntity(
        name = "Docs", enabled = true, sourceFolderUri = "content://src", sourceFolderLabel = "Download",
        extensionsCsv = "pdf", targetDestinationUri = "content://dst", targetDestinationLabel = "Docs",
        actionType = RuleActionType.COPY_LOCAL.name,
    )

    @Test
    fun dryRunIsNonDestructivePreview() = runBlocking {
        val fs = FakeFs(listOf(file("a.pdf", 300), file("b.pdf", 200), file("c.zip")))
        val rule = saved(base.copy(actionType = RuleActionType.MOVE_LOCAL.name))
        val report = executor(fs).run(rule, dryRun = true)
        assertEquals(listOf("a.pdf", "b.pdf"), report.matched.map { it.name })
        assertEquals(500L, report.matchedBytes)
        assertTrue(fs.copies.isEmpty())
        assertTrue(fs.deleted.isEmpty())
        assertTrue(db.dao().logsForRule(rule.id).isEmpty())
        assertNull(db.dao().rule(rule.id)!!.lastRunAt)
    }

    @Test
    fun skipConflictPolicyLeavesTakenNamesAlone() = runBlocking {
        val fs = FakeFs(listOf(file("a.pdf"), file("b.pdf")), existing = mutableSetOf("A.PDF"))
        val rule = saved(base.copy(conflictPolicy = ConflictPolicy.SKIP.name))
        val report = executor(fs).run(rule, dryRun = false)
        assertEquals(listOf("b.pdf" to "b.pdf"), fs.copies)
        assertEquals(1, report.processed)
        val logs = db.dao().logsForRule(rule.id).associateBy { it.fileName }
        assertEquals(RuleLogStatus.SKIPPED.name, logs.getValue("a.pdf").status)
        assertEquals(RuleLogStatus.SUCCESS.name, logs.getValue("b.pdf").status)
        val after = db.dao().rule(rule.id)!!
        assertEquals(now, after.lastRunAt)
        assertEquals(1, after.totalProcessedCount)
    }

    @Test
    fun renameConflictPolicyKeepsBoth() = runBlocking {
        val fs = FakeFs(listOf(file("a.pdf")), existing = mutableSetOf("a.pdf", "a (1).pdf"))
        val rule = saved(base.copy(conflictPolicy = ConflictPolicy.RENAME.name))
        executor(fs).run(rule, dryRun = false)
        assertEquals(listOf("a.pdf" to "a (2).pdf"), fs.copies)
        assertEquals("Copied as a (2).pdf", db.dao().logsForRule(rule.id).single().message)
        assertEquals("notes (1)", SmartRuleExecutor.resolveName("notes", setOf("notes"), ConflictPolicy.RENAME))
        assertNull(SmartRuleExecutor.resolveName("x.pdf", setOf("x.pdf"), ConflictPolicy.SKIP))
    }

    @Test
    fun moveRemovesOriginalOnlyAfterVerifiedCopy() = runBlocking {
        val fs = FakeFs(listOf(file("ok.pdf"), file("short.pdf"), file("stuck.pdf")), shortWrite = setOf("short.pdf"), undeletable = setOf("stuck.pdf"))
        val rule = saved(base.copy(actionType = RuleActionType.MOVE_LOCAL.name))
        val report = executor(fs).run(rule, dryRun = false)
        assertEquals(listOf("ok.pdf"), fs.deleted)
        assertEquals(1, report.processed)
        assertEquals(2, report.failed)
        val logs = db.dao().logsForRule(rule.id).associateBy { it.fileName }
        assertEquals(RuleLogStatus.SUCCESS.name, logs.getValue("ok.pdf").status)
        assertEquals(RuleLogStatus.FAILED.name, logs.getValue("short.pdf").status)
        assertEquals(RuleLogStatus.FAILED.name, logs.getValue("stuck.pdf").status)
    }

    @Test
    fun safetySkipsAreLoggedAndUploadsGoThroughTheTransferQueue() = runBlocking {
        val clip = file("clip.mp4", media = true)
        val queued = file("queued.mp4", media = true)
        val fs = FakeFs(listOf(clip, queued))
        val uploader = FakeUploader()
        val rule = saved(
            base.copy(
                extensionsCsv = "video/", actionType = RuleActionType.UPLOAD_CLOUD.name,
                targetDestinationUri = "drive:folder123", targetDestinationLabel = "Videos",
            ),
        )
        val report = executor(fs, uploader, locked = setOf(queued.uri)).run(rule, dryRun = false)
        assertEquals(1, uploader.queued.size)
        assertEquals(listOf("clip.mp4"), uploader.queued[0].first.map { it.name })
        assertEquals(Destination("folder123", "Videos"), uploader.queued[0].second)
        assertEquals(1, report.processed)
        val logs = db.dao().logsForRule(rule.id).associateBy { it.fileName }
        assertEquals(RuleLogStatus.SKIPPED.name, logs.getValue("queued.mp4").status)
        assertEquals(RuleLogStatus.SUCCESS.name, logs.getValue("clip.mp4").status)
        assertEquals(Destination.Auto, SmartRuleExecutor.parseDriveDestination("drive:auto", ""))
    }

    @Test
    fun incompleteRuleDoesNothing() = runBlocking {
        val fs = FakeFs(listOf(file("a.pdf")))
        val rule = saved(base.copy(targetDestinationUri = ""))
        val report = executor(fs).run(rule, dryRun = false)
        assertTrue(report.matched.isEmpty())
        assertTrue(fs.copies.isEmpty())
    }
}

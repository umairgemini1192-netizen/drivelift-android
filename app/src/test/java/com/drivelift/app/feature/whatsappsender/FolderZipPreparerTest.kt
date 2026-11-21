package com.drivelift.app.feature.whatsappsender

import android.content.ContentResolver
import androidx.test.core.app.ApplicationProvider
import com.drivelift.app.DriveLiftApp
import com.drivelift.app.feature.whatsappsender.engine.FolderZipPreparer
import com.drivelift.app.feature.whatsappsender.engine.SecureUriResolver
import com.drivelift.app.feature.whatsappsender.model.LocalSendItem
import com.drivelift.app.feature.whatsappsender.model.ZipPreparationResult
import com.drivelift.app.feature.whatsappsender.model.ZipProgress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderZipPreparerTest {

    private lateinit var app: DriveLiftApp
    private lateinit var tempCacheRoot: File
    private var nowMs = 1_800_000_000_000L

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        tempCacheRoot = File(app.cacheDir, "zip_preparer_test_${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempCacheRoot.deleteRecursively()
    }

    @Test
    fun zipsFolderEntriesWithDeduplicationAndFileProviderUri() = runBlocking {
        val payloads = mapOf(
            "1" to "SYNTH_ALPHA_BYTES".toByteArray(),
            "2" to "SYNTH_BETA_BYTES_12345".toByteArray(),
            "3" to "SYNTH_DUPLICATE_NAME_BYTES".toByteArray(),
        )
        val items = listOf(
            LocalSendItem(
                id = "1",
                name = "photo.jpg",
                sizeBytes = payloads.getValue("1").size.toLong(),
                mimeType = "image/jpeg",
                relativeFolderPath = "Day1",
            ),
            LocalSendItem(
                id = "2",
                name = "clip.mp4",
                sizeBytes = payloads.getValue("2").size.toLong(),
                mimeType = "video/mp4",
                relativeFolderPath = "",
            ),
            LocalSendItem(
                id = "3",
                name = "photo.jpg",
                sizeBytes = payloads.getValue("3").size.toLong(),
                mimeType = "image/jpeg",
                relativeFolderPath = "Day1",
            ),
        )

        val preparer = FolderZipPreparer(
            cacheDir = tempCacheRoot,
            uriResolver = SecureUriResolver(app),
            streamOpener = { item -> payloads[item.id]?.let(::ByteArrayInputStream) },
            clock = { nowMs },
        )

        val progressEvents = ArrayList<ZipProgress>()
        val result = preparer.prepareZip("Trip / 2026?", items) { progressEvents += it }

        assertTrue(result is ZipPreparationResult.Success)
        val success = result as ZipPreparationResult.Success
        assertEquals(3, success.fileCount)
        assertTrue(success.archiveFile.exists())
        assertTrue(success.archiveFile.name.endsWith(".zip"))
        assertEquals(ContentResolver.SCHEME_CONTENT, success.archiveUri.scheme)
        assertTrue(progressEvents.isNotEmpty())
        assertEquals(3, progressEvents.last().filesCompleted)

        val extracted = LinkedHashMap<String, ByteArray>()
        ZipInputStream(success.archiveFile.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                extracted[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        assertEquals(setOf("Day1/photo.jpg", "clip.mp4", "Day1/photo (1).jpg"), extracted.keys)
        assertArrayEquals(payloads["1"], extracted["Day1/photo.jpg"])
        assertArrayEquals(payloads["2"], extracted["clip.mp4"])
        assertArrayEquals(payloads["3"], extracted["Day1/photo (1).jpg"])
    }

    @Test
    fun cleansStaleArchivesOlderThanTwentyFourHoursWhilePreservingFreshOnes() {
        val preparer = FolderZipPreparer(
            cacheDir = tempCacheRoot,
            uriResolver = SecureUriResolver(app),
            streamOpener = { null },
            clock = { nowMs },
        )
        val staging = preparer.stagingDir().apply { mkdirs() }
        val staleZip = File(staging, "old.zip").apply {
            writeText("stale")
            setLastModified(nowMs - FolderZipPreparer.STALE_MAX_AGE_MS - 60_000L)
        }
        val staleTmp = File(staging, "broken.zip.tmp").apply {
            writeText("tmp")
            setLastModified(nowMs - FolderZipPreparer.STALE_MAX_AGE_MS - 1_000L)
        }
        val freshZip = File(staging, "fresh.zip").apply {
            writeText("fresh")
            setLastModified(nowMs - 60_000L)
        }

        val deleted = preparer.cleanupStaleArchives(nowMs = nowMs)

        assertEquals(2, deleted)
        assertFalse(staleZip.exists())
        assertFalse(staleTmp.exists())
        assertTrue(freshZip.exists())
    }

    @Test
    fun failsCleanlyWhenNoStreamsAreReadableWithoutLeavingTempFiles() = runBlocking {
        val preparer = FolderZipPreparer(
            cacheDir = tempCacheRoot,
            uriResolver = SecureUriResolver(app),
            streamOpener = { null },
            clock = { nowMs },
        )
        val result = preparer.prepareZip(
            "EmptyFolder",
            listOf(LocalSendItem(id = "x", name = "missing.jpg", sizeBytes = 100L, mimeType = "image/jpeg")),
        )

        assertTrue(result is ZipPreparationResult.Failure)
        val leftover = preparer.stagingDir().listFiles().orEmpty()
        assertTrue(leftover.none { it.name.endsWith(".tmp") })
    }
}

package com.drivelift.app.feature.desktopdrop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.drivelift.app.data.LocalFileType
import com.drivelift.app.feature.desktopdrop.data.AndroidDropThumbnailProvider
import com.drivelift.app.feature.desktopdrop.data.DropPathSanitizer
import com.drivelift.app.feature.desktopdrop.data.DropThumbnailSupport
import com.drivelift.app.feature.desktopdrop.data.FileSharedItemSource
import com.drivelift.app.feature.desktopdrop.model.DropJson
import com.drivelift.app.feature.desktopdrop.model.SharedDropItem
import com.drivelift.app.feature.desktopdrop.network.LanNetworkMonitor
import com.drivelift.app.feature.desktopdrop.network.LocalHotspotController
import com.drivelift.app.feature.desktopdrop.qr.QrBitmapGenerator
import com.drivelift.app.feature.desktopdrop.server.ResumableUploadStore
import com.drivelift.app.feature.desktopdrop.server.SessionAuthManager
import com.drivelift.app.feature.desktopdrop.service.TransferPowerLockHolder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DesktopDropHelpersTest {

    @Test
    fun privateLanIpFilterAllowsRfc1918SubnetsAndRejectsPublicAndLoopbackByDefault() {
        assertTrue(SessionAuthManager.isPrivateLanIp("192.168.1.42"))
        assertTrue(SessionAuthManager.isPrivateLanIp("10.0.0.15"))
        assertTrue(SessionAuthManager.isPrivateLanIp("172.16.5.9"))
        assertTrue(SessionAuthManager.isPrivateLanIp("::ffff:192.168.43.1"))
        assertTrue(SessionAuthManager.isPrivateLanIp("/192.168.1.2"))

        assertFalse(SessionAuthManager.isPrivateLanIp("8.8.8.8"))
        assertFalse(SessionAuthManager.isPrivateLanIp("172.32.0.1"))
        assertFalse(SessionAuthManager.isPrivateLanIp("127.0.0.1", allowLoopback = false))
        assertTrue(SessionAuthManager.isPrivateLanIp("127.0.0.1", allowLoopback = true))
    }

    @Test
    fun pathSanitizerBlocksTraversalAndNormalizesWebkitRelativePath() {
        assertEquals("secret.txt", DropPathSanitizer.sanitizeFilename("../../secret.txt"))
        assertEquals("drop_file", DropPathSanitizer.sanitizeFilename("..."))
        assertEquals(
            listOf("Vacation", "2026"),
            DropPathSanitizer.sanitizeRelativeFolderSegments("../Vacation/./2026/IMG_1.jpg", "IMG_1.jpg"),
        )
    }

    @Test
    fun wifiQrPayloadEscapesSpecialCharactersAndQrBitmapGeneratesModules() {
        val payload = LocalHotspotController.buildWifiQrPayload("Pixel;Net", "pass:word\\12")
        assertEquals("""WIFI:T:WPA;S:Pixel\;Net;P:pass\:word\\12;;""", payload)

        val bmp = QrBitmapGenerator.generate("http://192.168.1.55:8080/?token=ABCD2345", sizePx = 256)
        assertNotNull(bmp)
        assertEquals(256, bmp!!.width)
        assertEquals(256, bmp.height)
    }

    @Test
    fun excludedNetworkInterfacesCoverVpnAndCellularAndDropJsonParsesEscapes() {
        assertTrue(LanNetworkMonitor.isExcludedInterface("tun0"))
        assertTrue(LanNetworkMonitor.isExcludedInterface("wg0"))
        assertTrue(LanNetworkMonitor.isExcludedInterface("rmnet_data0"))
        assertFalse(LanNetworkMonitor.isExcludedInterface("wlan0"))
        assertFalse(LanNetworkMonitor.isExcludedInterface("swlan0"))

        val parsed = DropJson.parseObject("""{"filename":"clip \"1\".mp4","totalSize":1048576,"resumed":true}""")
        assertEquals("""clip "1".mp4""", parsed["filename"])
        assertEquals(1048576L, parsed["totalSize"])
        assertEquals(true, parsed["resumed"])
    }

    @Test
    fun uploadChunkSizeScalesTo16MbAbove64MbAndPowerLocksTrackTransferLifecycle() {
        assertEquals(8L * 1024L * 1024L, ResumableUploadStore.recommendedChunkSizeBytes(64L * 1024L * 1024L))
        assertEquals(16L * 1024L * 1024L, ResumableUploadStore.recommendedChunkSizeBytes(64L * 1024L * 1024L + 1L))

        val context = ApplicationProvider.getApplicationContext<Context>()
        val locks = TransferPowerLockHolder(context)
        assertFalse(locks.isWifiLockHeld)
        assertFalse(locks.isWakeLockHeld)

        locks.update(transferActive = true)
        assertTrue(locks.isWifiLockHeld)
        assertTrue(locks.isWakeLockHeld)

        locks.update(transferActive = false)
        assertFalse(locks.isWifiLockHeld)
        assertFalse(locks.isWakeLockHeld)

        val html = context.assets.open("desktop_drop/index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        assertTrue(html.contains("\"queue_updated\""))
        assertTrue(html.contains("16 * 1024 * 1024"))
        assertTrue(html.contains("64 * 1024 * 1024"))
        assertTrue(html.contains("5000"))
        assertTrue(html.contains("/thumb/"))
        assertTrue(html.contains("badge-latest"))
        assertTrue(html.contains("addedAtMs"))
        assertFalse("Browser HTML must not contain em-dashes", html.contains('\u2014'))
    }

    @Test
    fun thumbnailSupportClassifiesAllTypesAndAndroidProviderDownscalesAndCachesImages() = runBlocking {
        assertEquals(
            LocalFileType.IMAGE,
            DropThumbnailSupport.classifyItem(SharedDropItem("1", "photo", "", 100L, "image/jpeg")),
        )
        assertEquals(
            LocalFileType.VIDEO,
            DropThumbnailSupport.classifyItem(SharedDropItem("2", "clip.mp4", "", 100L, "application/octet-stream")),
        )
        assertEquals(
            LocalFileType.PDF,
            DropThumbnailSupport.classifyItem(SharedDropItem("3", "report.pdf", "", 100L, "application/pdf")),
        )
        assertEquals(
            LocalFileType.APK,
            DropThumbnailSupport.classifyItem(SharedDropItem("4", "app.apk", "", 100L, "application/vnd.android.package-archive")),
        )
        assertEquals(
            LocalFileType.DOCUMENT,
            DropThumbnailSupport.classifyItem(SharedDropItem("5", "notes.docx", "", 100L, "application/octet-stream")),
        )
        assertEquals(1, DropThumbnailSupport.computeInSampleSize(200, 150, 256))
        assertEquals(4, DropThumbnailSupport.computeInSampleSize(2048, 1536, 256))

        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempDir = Files.createTempDirectory("drop-thumb-test").toFile()
        try {
            val bigPng = File(tempDir, "SYNTH_BIG.png")
            val bmp = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.CYAN)
            FileOutputStream(bigPng).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
            bmp.recycle()

            val pdfFile = File(tempDir, "SYNTH_DOC.pdf").apply { writeText("%PDF-1.4") }

            val provider = AndroidDropThumbnailProvider(context, FileSharedItemSource())
            val imageItem = SharedDropItem(
                id = "img-big",
                name = "SYNTH_BIG.png",
                sizeBytes = bigPng.length(),
                mimeType = "image/png",
                localFilePath = bigPng.absolutePath,
                addedAtMs = 1000L,
            )
            val first = provider.loadThumbnail(imageItem, maxEdgePx = 256)
            assertNotNull(first)
            assertEquals("image/jpeg", first!!.contentType)
            assertTrue(first.bytes.isNotEmpty())

            val decoded = BitmapFactory.decodeByteArray(first.bytes, 0, first.bytes.size)
            assertNotNull(decoded)
            assertTrue("Scaled width should be <= 256, got ${decoded.width}", decoded.width <= 256)
            assertTrue("Scaled height should be <= 256, got ${decoded.height}", decoded.height <= 256)
            decoded.recycle()

            // Repeat request hits in-memory LRU cache.
            val second = provider.loadThumbnail(imageItem, maxEdgePx = 256)
            assertSame(first, second)

            // Non-visual file returns null cleanly.
            val pdfItem = SharedDropItem(
                id = "pdf-1",
                name = "SYNTH_DOC.pdf",
                sizeBytes = pdfFile.length(),
                mimeType = "application/pdf",
                localFilePath = pdfFile.absolutePath,
                addedAtMs = 900L,
            )
            assertNull(provider.loadThumbnail(pdfItem))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}

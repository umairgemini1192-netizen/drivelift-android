package com.drivelift.app

import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import coil.ImageLoader
import coil.decode.DataSource
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.SuccessResult
import com.drivelift.app.data.MediaThumbnailFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phone thumbnails come from Android's thumbnail store, through a real Coil image loader, and
 * fall back to decoding the original when Android has none. All pictures are generated.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MediaThumbnailTest {
    private val app: DriveLiftApp get() = ApplicationProvider.getApplicationContext()
    private val video: Uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, 3)

    @Before
    fun fakeWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    private fun bitmap(edge: Int, color: Int) =
        Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun loader(load: (Uri, Size, android.os.CancellationSignal) -> Bitmap) =
        ImageLoader.Builder(app).allowHardware(false)
            .components { add(MediaThumbnailFetcher.Factory(load)) }
            .build()

    private fun request(uri: Uri) = ImageRequest.Builder(app).data(uri).size(256).build()

    /**
     * Runs a load to the end. The load runs on its own thread while this one, Android's main
     * thread here, keeps processing the work Coil hands to it; waiting for it here would deadlock.
     */
    private fun ImageLoader.load(request: ImageRequest): ImageResult {
        val done = CompletableFuture<ImageResult>()
        Thread { done.complete(runBlocking { execute(request) }) }.start()
        val deadline = System.currentTimeMillis() + 15_000
        while (!done.isDone) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
            assertTrue("The load never finished", System.currentTimeMillis() < deadline)
        }
        return done.get()
    }

    @Test
    fun onlyMediaStoreAddressesAreHandled() {
        assertTrue(MediaThumbnailFetcher.isMediaStoreUri(video))
        assertTrue(MediaThumbnailFetcher.isMediaStoreUri(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 1)))
        assertFalse(MediaThumbnailFetcher.isMediaStoreUri(Uri.parse("content://com.example.files/1")))
        assertFalse(MediaThumbnailFetcher.isMediaStoreUri(Uri.parse("file:///sdcard/DCIM/IMG_0001.jpg")))
        assertFalse(MediaThumbnailFetcher.isMediaStoreUri(Uri.parse("https://example.com/a.jpg")))
    }

    @Test
    fun thumbnailComesFromAndroidAtTheRequestedSize() {
        val asked = ArrayList<Pair<Uri, Size>>()
        val result = loader { uri, size, _ -> asked += uri to size; bitmap(size.width, Color.RED) }.load(request(video))
        assertTrue("Loading failed: $result", result is SuccessResult)
        result as SuccessResult
        assertEquals(listOf(video to Size(256, 256)), asked)
        assertEquals(DataSource.DISK, result.dataSource)
        assertEquals(Color.RED, (result.drawable as BitmapDrawable).bitmap.getPixel(0, 0))
    }

    @Test
    fun withoutAnAndroidThumbnailTheOriginalIsDecodedInstead() {
        // Android has no thumbnail for this file; the original picture is still readable.
        val original = ByteArrayOutputStream().also { bitmap(64, Color.BLUE).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val image = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 7)
        shadowOf(app.contentResolver).registerInputStreamSupplier(image) { ByteArrayInputStream(original) }
        val tries = AtomicInteger()
        val result = loader { _, _, _ -> tries.incrementAndGet(); throw IOException("no thumbnail") }.load(request(image))
        assertEquals(1, tries.get())
        assertTrue("The fallback to the original failed: $result", result is SuccessResult)
        assertEquals(Color.BLUE, ((result as SuccessResult).drawable as BitmapDrawable).bitmap.getPixel(0, 0))
    }

    @Test
    fun aFileNeitherWayCanReadIsAnErrorNotACrash() {
        val missing = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 99)
        val result = loader { _, _, _ -> throw IOException("no thumbnail") }.load(request(missing))
        assertTrue(result is ErrorResult)
    }
}

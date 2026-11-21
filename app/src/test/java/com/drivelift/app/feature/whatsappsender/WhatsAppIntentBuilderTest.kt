package com.drivelift.app.feature.whatsappsender

import android.content.Intent
import android.net.Uri
import com.drivelift.app.feature.whatsappsender.engine.AndroidWhatsAppAvailabilityChecker
import com.drivelift.app.feature.whatsappsender.engine.PackageInstalledLookup
import com.drivelift.app.feature.whatsappsender.engine.WhatsAppIntentBuilder
import com.drivelift.app.feature.whatsappsender.model.QualityMode
import com.drivelift.app.feature.whatsappsender.model.WhatsAppAvailability
import com.drivelift.app.feature.whatsappsender.model.WhatsAppTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WhatsAppIntentBuilderTest {

    private val builder = WhatsAppIntentBuilder()
    private val uri1 = Uri.parse("content://media/external/images/media/101")
    private val uri2 = Uri.parse("content://media/external/video/media/102")
    private val uri3 = Uri.parse("content://com.drivelift.app.fileprovider/whatsapp_send_staging/Camera.zip")

    @Test
    fun buildsSingleFileSendIntentWithPermissionFlagClipDataAndTargetPackage() {
        val intent = builder.buildSendIntent(
            uris = listOf(uri1),
            mimeTypes = listOf("image/jpeg"),
            target = WhatsAppTarget.PERSONAL,
            qualityMode = QualityMode.STANDARD_MEDIA,
            caption = "  Holiday shots  ",
        )

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("com.whatsapp", intent.`package`)
        assertEquals("image/jpeg", intent.type)
        assertEquals(uri1, intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals("Holiday shots", intent.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertNotNull(intent.clipData)
        assertEquals(1, intent.clipData!!.itemCount)
        assertEquals(uri1, intent.clipData!!.getItemAt(0).uri)
    }

    @Test
    fun buildsMultiFileSendMultipleIntentTargetingBusinessAndOriginalQualityMimeOverride() {
        val intent = builder.buildSendIntent(
            uris = listOf(uri1, uri2),
            mimeTypes = listOf("image/jpeg", "video/mp4"),
            target = WhatsAppTarget.BUSINESS,
            qualityMode = QualityMode.ORIGINAL_QUALITY,
            caption = "   ",
        )

        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
        assertEquals("com.whatsapp.w4b", intent.`package`)
        assertEquals("application/octet-stream", intent.type)
        assertFalse(intent.hasExtra(Intent.EXTRA_TEXT))
        val streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        assertEquals(listOf(uri1, uri2), streams)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertNotNull(intent.clipData)
        assertEquals(2, intent.clipData!!.itemCount)
        assertEquals(uri1, intent.clipData!!.getItemAt(0).uri)
        assertEquals(uri2, intent.clipData!!.getItemAt(1).uri)
    }

    @Test
    fun resolvesStandardMimeTypesForHomogeneousAndMixedBatches() {
        assertEquals(
            "image/*",
            builder.resolveMimeType(listOf("image/jpeg", "image/png"), QualityMode.STANDARD_MEDIA),
        )
        assertEquals(
            "video/*",
            builder.resolveMimeType(listOf("video/mp4", "video/quicktime"), QualityMode.STANDARD_MEDIA),
        )
        assertEquals(
            "*/*",
            builder.resolveMimeType(listOf("image/jpeg", "video/mp4"), QualityMode.STANDARD_MEDIA),
        )
        assertEquals(
            "application/zip",
            builder.resolveMimeType(listOf("application/zip"), QualityMode.ORIGINAL_QUALITY),
        )
    }

    @Test
    fun systemSharesheetTargetClearsPackageAndWrapsChooser() {
        val sendIntent = builder.buildSendIntent(
            uris = listOf(uri3),
            mimeTypes = listOf("application/zip"),
            target = WhatsAppTarget.SYSTEM_SHARESHEET,
            qualityMode = QualityMode.STANDARD_MEDIA,
        )
        assertNull(sendIntent.`package`)
        val chooser = builder.buildChooserIntent(sendIntent, "Send archive")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesRawFileSchemeUri() {
        builder.buildSendIntent(
            uris = listOf(Uri.parse("file:///storage/emulated/0/DCIM/photo.jpg")),
            mimeTypes = listOf("image/jpeg"),
            target = WhatsAppTarget.PERSONAL,
            qualityMode = QualityMode.ORIGINAL_QUALITY,
        )
    }

    @Test
    fun detectsWhatsAppAvailabilityCombinationsAndTargetFallbacks() {
        val both = AndroidWhatsAppAvailabilityChecker(PackageInstalledLookup { true }).check()
        assertEquals(WhatsAppAvailability.BOTH, both)
        assertEquals(WhatsAppTarget.BUSINESS, both.resolveTarget(WhatsAppTarget.BUSINESS))

        val personalOnly = AndroidWhatsAppAvailabilityChecker(PackageInstalledLookup { it == "com.whatsapp" }).check()
        assertEquals(WhatsAppAvailability.PERSONAL, personalOnly)
        assertEquals(WhatsAppTarget.PERSONAL, personalOnly.resolveTarget(WhatsAppTarget.BUSINESS))

        val businessOnly = AndroidWhatsAppAvailabilityChecker(PackageInstalledLookup { it == "com.whatsapp.w4b" }).check()
        assertEquals(WhatsAppAvailability.BUSINESS, businessOnly)
        assertEquals(WhatsAppTarget.BUSINESS, businessOnly.resolveTarget(WhatsAppTarget.PERSONAL))

        val none = AndroidWhatsAppAvailabilityChecker(PackageInstalledLookup { false }).check()
        assertEquals(WhatsAppAvailability.NONE, none)
        assertEquals(WhatsAppTarget.SYSTEM_SHARESHEET, none.resolveTarget(WhatsAppTarget.PERSONAL))
    }
}

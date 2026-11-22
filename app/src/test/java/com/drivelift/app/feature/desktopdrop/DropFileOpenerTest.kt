package com.drivelift.app.feature.desktopdrop

import com.drivelift.app.feature.desktopdrop.data.DropFileOpener
import com.drivelift.app.feature.desktopdrop.data.DropOpenPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What tapping a saved file in Recent transfers does: install an APK, open anything else. */
class DropFileOpenerTest {

    private val saved = "content://media/external/downloads/42"

    @Test
    fun anApkGoesToTheInstallerWhenAndroidAllowsInstallsFromDriveLift() {
        val plan = DropFileOpener.plan(
            fileName = "DriveLift-preview.apk",
            savedUri = saved,
            mimeType = "application/vnd.android.package-archive",
            canRequestInstalls = true,
        )
        assertEquals(DropOpenPlan.InstallApk(saved), plan)
    }

    @Test
    fun anApkSendsTheOwnerToTheInstallPermissionWhenItIsNotGrantedYet() {
        val plan = DropFileOpener.plan("tool.apk", saved, null, canRequestInstalls = false)
        assertEquals(DropOpenPlan.NeedsInstallPermission(saved), plan)
    }

    @Test
    fun anApkIsRecognisedByNameEvenWhenTheMimeTypeIsGeneric() {
        assertTrue(DropFileOpener.isApk("Setup.APK", "application/octet-stream"))
        assertTrue(DropFileOpener.isApk("odd-name", "application/vnd.android.package-archive"))
        assertFalse(DropFileOpener.isApk("photo.jpg", "image/jpeg"))
    }

    @Test
    fun everyOtherFileOpensWithItsOwnMimeType() {
        val cases = mapOf(
            "report.pdf" to "application/pdf",
            "holiday.jpg" to "image/jpeg",
            "clip.mp4" to "video/mp4",
            "archive.zip" to "application/zip",
            "notes.txt" to "text/plain",
        )
        for ((name, mime) in cases) {
            assertEquals(
                name,
                DropOpenPlan.View(saved, mime),
                DropFileOpener.plan(name, saved, mimeType = null, canRequestInstalls = false),
            )
        }
    }

    @Test
    fun anUnknownExtensionFallsBackToAGenericBinaryType() {
        val plan = DropFileOpener.plan("data.xyz", saved, null, canRequestInstalls = false)
        assertEquals(DropOpenPlan.View(saved, "application/octet-stream"), plan)
    }

    @Test
    fun aTransferWithoutASavedFileCannotBeOpened() {
        assertTrue(DropFileOpener.plan("a.pdf", null, "application/pdf", true) is DropOpenPlan.Unavailable)
        assertTrue(DropFileOpener.plan("a.pdf", "  ", "application/pdf", true) is DropOpenPlan.Unavailable)
        assertFalse(DropFileOpener.isOpenable(null))
        assertTrue(DropFileOpener.isOpenable(saved))
    }
}

package com.drivelift.app.feature.desktopdrop.data

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/** What tapping a saved file in Recent transfers should do. */
sealed interface DropOpenPlan {
    /** Open the file with whatever app handles [mimeType]. */
    data class View(val uri: String, val mimeType: String) : DropOpenPlan

    /** Hand an APK to Android's package installer. */
    data class InstallApk(val uri: String) : DropOpenPlan

    /** An APK, but Android has not yet let DriveLift install apps; send the owner to that setting. */
    data class NeedsInstallPermission(val uri: String) : DropOpenPlan

    /** The transfer did not save a file that can be opened. */
    data class Unavailable(val reason: String) : DropOpenPlan
}

/** Decides what to do with a saved file, then does it. The decision is pure so it can be tested. */
object DropFileOpener {
    const val APK_MIME = "application/vnd.android.package-archive"

    fun isApk(fileName: String, mimeType: String?): Boolean =
        mimeType.equals(APK_MIME, ignoreCase = true) || fileName.endsWith(".apk", ignoreCase = true)

    fun plan(
        fileName: String,
        savedUri: String?,
        mimeType: String?,
        canRequestInstalls: Boolean,
    ): DropOpenPlan {
        if (savedUri.isNullOrBlank()) return DropOpenPlan.Unavailable("This transfer has no saved file to open")
        if (isApk(fileName, mimeType)) {
            return if (canRequestInstalls) {
                DropOpenPlan.InstallApk(savedUri)
            } else {
                DropOpenPlan.NeedsInstallPermission(savedUri)
            }
        }
        return DropOpenPlan.View(savedUri, DropPathSanitizer.resolveMimeType(fileName, mimeType))
    }

    /** True when the row should show the open or install chevron. */
    fun isOpenable(savedUri: String?): Boolean = !savedUri.isNullOrBlank()

    /**
     * Opens or installs the file. Never throws: a missing handler, a revoked grant or a blocked
     * activity start becomes a message for the owner.
     */
    fun open(context: Context, fileName: String, savedUri: String?, mimeType: String?, onMessage: (String) -> Unit) {
        val canInstall = runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
        when (val plan = plan(fileName, savedUri, mimeType, canInstall)) {
            is DropOpenPlan.Unavailable -> onMessage(plan.reason)
            is DropOpenPlan.NeedsInstallPermission -> {
                onMessage("Allow DriveLift to install apps, then tap the file again")
                launch(
                    context,
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                    onMessage,
                    "Could not open the install permission screen",
                )
            }
            is DropOpenPlan.InstallApk -> {
                val shareable = shareableUri(context, plan.uri)
                if (shareable == null) {
                    onMessage("The saved file is no longer available")
                } else {
                    launch(context, viewIntent(shareable, APK_MIME), onMessage, "Android could not start the installer")
                }
            }
            is DropOpenPlan.View -> {
                val shareable = shareableUri(context, plan.uri)
                if (shareable == null) {
                    onMessage("The saved file is no longer available")
                } else {
                    launch(context, viewIntent(shareable, plan.mimeType), onMessage, "No app can open this file")
                }
            }
        }
    }

    private fun viewIntent(uri: Uri, mime: String): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /**
     * A content Uri (MediaStore Downloads, or a folder the owner picked) is shared as it is. A plain
     * file path goes through [FileProvider], because other apps may not read it directly.
     */
    private fun shareableUri(context: Context, saved: String): Uri? = runCatching {
        val uri = Uri.parse(saved)
        if (uri.scheme == "file") {
            val path = uri.path ?: return@runCatching null
            val file = File(path)
            if (!file.isFile) return@runCatching null
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } else {
            uri
        }
    }.getOrNull()

    private fun launch(context: Context, intent: Intent, onMessage: (String) -> Unit, failure: String) {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            onMessage(failure)
        } catch (_: SecurityException) {
            onMessage(failure)
        }
    }
}

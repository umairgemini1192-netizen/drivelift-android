package com.drivelift.app.feature.whatsappsender.engine

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.drivelift.app.feature.whatsappsender.data.WhatsAppSenderPrefs
import com.drivelift.app.feature.whatsappsender.model.LocalSendItem
import com.drivelift.app.feature.whatsappsender.model.QualityMode
import com.drivelift.app.feature.whatsappsender.model.SendLaunchResult
import com.drivelift.app.feature.whatsappsender.model.WhatsAppAvailability
import com.drivelift.app.feature.whatsappsender.model.WhatsAppTarget

/** Orchestrates URI resolution, intent construction, and fallback to Android System Sharesheet. */
class WhatsAppShareEngine(
    private val availabilityChecker: WhatsAppAvailabilityChecker,
    val validator: WhatsAppPreFlightValidator,
    private val uriResolver: SecureUriResolver,
    val intentBuilder: WhatsAppIntentBuilder,
    val zipPreparer: FolderZipPreparer,
    val prefs: WhatsAppSenderPrefs,
    private val activityLauncher: (Context, Intent) -> Unit = ::defaultLaunch,
) {

    fun checkAvailability(): WhatsAppAvailability = availabilityChecker.check()

    fun sendItems(
        context: Context,
        items: List<LocalSendItem>,
        target: WhatsAppTarget,
        qualityMode: QualityMode,
        caption: String? = null,
    ): SendLaunchResult {
        if (items.isEmpty()) return SendLaunchResult.EMPTY_SELECTION
        val uris = runCatching { items.map { uriResolver.resolve(it) } }
            .getOrElse { return SendLaunchResult.ERROR }
        val mimeTypes = items.map { it.mimeType }
        return launchUris(
            context = context,
            uris = uris,
            mimeTypes = mimeTypes,
            target = target,
            qualityMode = qualityMode,
            caption = caption,
        )
    }

    fun sendStagedArchive(
        context: Context,
        archiveUri: Uri,
        target: WhatsAppTarget,
        caption: String? = null,
    ): SendLaunchResult = launchUris(
        context = context,
        uris = listOf(archiveUri),
        mimeTypes = listOf("application/zip"),
        target = target,
        qualityMode = QualityMode.STANDARD_MEDIA,
        caption = caption,
    )

    private fun launchUris(
        context: Context,
        uris: List<Uri>,
        mimeTypes: List<String>,
        target: WhatsAppTarget,
        qualityMode: QualityMode,
        caption: String?,
    ): SendLaunchResult {
        val sendIntent = intentBuilder.buildSendIntent(
            uris = uris,
            mimeTypes = mimeTypes,
            target = target,
            qualityMode = qualityMode,
            caption = caption,
        )
        if (target == WhatsAppTarget.SYSTEM_SHARESHEET) {
            return launchChooser(context, sendIntent)
        }
        return try {
            activityLauncher(context, sendIntent)
            SendLaunchResult.LAUNCHED_WHATSAPP
        } catch (_: ActivityNotFoundException) {
            val fallbackIntent = intentBuilder.buildSendIntent(
                uris = uris,
                mimeTypes = mimeTypes,
                target = WhatsAppTarget.SYSTEM_SHARESHEET,
                qualityMode = qualityMode,
                caption = caption,
            )
            launchChooser(context, fallbackIntent)
        } catch (_: SecurityException) {
            SendLaunchResult.ERROR
        }
    }

    private fun launchChooser(context: Context, sendIntent: Intent): SendLaunchResult {
        val chooser = intentBuilder.buildChooserIntent(sendIntent)
        return try {
            activityLauncher(context, chooser)
            SendLaunchResult.LAUNCHED_SHARESHEET
        } catch (_: ActivityNotFoundException) {
            SendLaunchResult.NO_TARGET_ACTIVITY
        }
    }

    companion object {
        private fun defaultLaunch(context: Context, intent: Intent) {
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}

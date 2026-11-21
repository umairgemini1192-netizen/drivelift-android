package com.drivelift.app.feature.whatsappsender.engine

import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import com.drivelift.app.feature.whatsappsender.model.QualityMode
import com.drivelift.app.feature.whatsappsender.model.WhatsAppTarget

/** Builds secure ACTION_SEND / ACTION_SEND_MULTIPLE intents with ClipData and MIME quality control. */
class WhatsAppIntentBuilder {

    fun resolveMimeType(
        mimeTypes: List<String>,
        qualityMode: QualityMode,
    ): String {
        val normalized = mimeTypes
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

        if (qualityMode == QualityMode.ORIGINAL_QUALITY) {
            if (normalized.size == 1 && normalized.first() == "application/zip") {
                return "application/zip"
            }
            return "application/octet-stream"
        }

        if (normalized.isEmpty()) return "*/*"
        if (normalized.size == 1) return normalized.first()

        val allImages = normalized.all { it.startsWith("image/") }
        if (allImages) return "image/*"

        val allVideos = normalized.all { it.startsWith("video/") }
        if (allVideos) return "video/*"

        val first = normalized.first()
        if (normalized.all { it == first }) return first

        return "*/*"
    }

    fun buildSendIntent(
        uris: List<Uri>,
        mimeTypes: List<String>,
        target: WhatsAppTarget,
        qualityMode: QualityMode,
        caption: String? = null,
    ): Intent {
        require(uris.isNotEmpty()) { "At least one URI is required to build a send intent" }
        require(uris.all { it.scheme == ContentResolver.SCHEME_CONTENT }) {
            "All shared URIs must use the content:// scheme"
        }

        val action = if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
        val resolvedMime = resolveMimeType(mimeTypes, qualityMode)

        return Intent(action).apply {
            type = resolvedMime
            if (uris.size == 1) {
                putExtra(Intent.EXTRA_STREAM, uris.first())
            } else {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }

            val trimmedCaption = caption?.trim().orEmpty()
            if (trimmedCaption.isNotEmpty()) {
                putExtra(Intent.EXTRA_TEXT, trimmedCaption)
            }

            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            val clip = ClipData.newRawUri("drivelift_whatsapp_send", uris.first())
            for (i in 1 until uris.size) {
                clip.addItem(ClipData.Item(uris[i]))
            }
            clipData = clip

            target.packageName?.let { setPackage(it) }
        }
    }

    fun buildChooserIntent(
        sendIntent: Intent,
        title: String = "Send files",
    ): Intent = Intent.createChooser(sendIntent, title).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

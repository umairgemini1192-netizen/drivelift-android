package com.drivelift.app.feature.whatsappsender.di

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import com.drivelift.app.Graph
import com.drivelift.app.feature.whatsappsender.data.LocalMediaAdapter
import com.drivelift.app.feature.whatsappsender.data.WhatsAppSenderPrefs
import com.drivelift.app.feature.whatsappsender.engine.AndroidWhatsAppAvailabilityChecker
import com.drivelift.app.feature.whatsappsender.engine.FolderZipPreparer
import com.drivelift.app.feature.whatsappsender.engine.SecureUriResolver
import com.drivelift.app.feature.whatsappsender.engine.WhatsAppAvailabilityChecker
import com.drivelift.app.feature.whatsappsender.engine.WhatsAppIntentBuilder
import com.drivelift.app.feature.whatsappsender.engine.WhatsAppPreFlightValidator
import com.drivelift.app.feature.whatsappsender.engine.WhatsAppShareEngine

/** Self-contained dependency assembly for the WhatsApp Quick Sender feature. */
class WhatsAppSenderModule(
    private val app: Application,
    private val graph: Graph,
) {
    val prefs = WhatsAppSenderPrefs(app)
    val uriResolver = SecureUriResolver(app)
    val intentBuilder = WhatsAppIntentBuilder()
    val validator = WhatsAppPreFlightValidator()
    val zipPreparer = FolderZipPreparer(
        cacheDir = app.cacheDir,
        uriResolver = uriResolver,
        streamOpener = { item -> LocalMediaAdapter.openItemStream(app, item) },
        clock = { graph.clock() },
    )

    /** Replaceable in tests to simulate PERSONAL, BUSINESS, BOTH, or NONE without PackageManager hooks. */
    var availabilityChecker: WhatsAppAvailabilityChecker = AndroidWhatsAppAvailabilityChecker(app)

    /** Replaceable in tests to capture outbound intents without launching external activities. */
    var activityLauncher: (Context, Intent) -> Unit = { context, intent ->
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun shareEngine(): WhatsAppShareEngine = WhatsAppShareEngine(
        availabilityChecker = availabilityChecker,
        validator = validator,
        uriResolver = uriResolver,
        intentBuilder = intentBuilder,
        zipPreparer = zipPreparer,
        prefs = prefs,
        activityLauncher = { context, intent -> activityLauncher(context, intent) },
    )
}

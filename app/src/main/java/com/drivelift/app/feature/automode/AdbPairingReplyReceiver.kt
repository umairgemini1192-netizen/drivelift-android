package com.drivelift.app.feature.automode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.drivelift.app.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives the 6-digit Wireless debugging pairing code entered into DriveLift's [RemoteInput]
 * notification without leaving Android Settings, pairs immediately over `127.0.0.1`, and runs
 * the one-time permission bootstrap.
 *
 * The pairing code is kept only in transient memory and is never logged or persisted.
 */
class AdbPairingReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AdbPairingNotifier.ACTION_PAIRING_REPLY) return
        val remoteResults = RemoteInput.getResultsFromIntent(intent) ?: return
        val rawCode = remoteResults.getCharSequence(AdbPairingNotifier.KEY_PAIRING_CODE)?.toString()
            ?: return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                Graph.get(appContext).cleanerAndSleep.handlePairingCodeFromNotification(rawCode)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

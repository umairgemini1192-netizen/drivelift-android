package com.drivelift.app.feature.desktopdrop.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivelift.app.Graph
import com.drivelift.app.feature.desktopdrop.model.PeerSession
import com.drivelift.app.feature.desktopdrop.service.DropApprovalNotifier

/**
 * The phone-side check that unlocks a waiting browser. Uses Android's BiometricPrompt with a
 * Class 3 biometric (fingerprint or face on a Pixel) or the screen lock. DriveLift never sees
 * biometric data; it only learns that the check passed.
 */
object DropBiometric {
    const val AUTHENTICATORS = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

    fun canAuthenticate(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Shows the prompt for [knock]. Returns false when there is no activity to show it on.
     * [onApproved] runs only after the system reports success; every other outcome leaves the
     * knock waiting and reports through [onMessage] (a plain cancel stays silent).
     */
    fun prompt(
        context: Context,
        knock: PeerSession,
        onApproved: () -> Unit,
        onMessage: (String) -> Unit,
    ): Boolean {
        val activity = context.findFragmentActivity() ?: return false
        if (!canAuthenticate(activity)) {
            onMessage("Set up a fingerprint, face unlock or screen lock on this phone to approve a laptop")
            return true
        }
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onApproved()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                if (!cancelled) onMessage("Approval not completed: $errString")
            }
        }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Approve laptop connection")
                .setSubtitle("${knock.deviceLabel} at ${knock.peerIp}")
                .setDescription("The laptop must show match code ${knock.matchCode}")
                .setAllowedAuthenticators(AUTHENTICATORS)
                .setConfirmationRequired(true)
                .build(),
        )
        return true
    }
}

/**
 * Opens the biometric prompt as soon as a browser knocks, on whichever tab is open, and again when
 * the owner taps the approval notification. Does nothing where there is no FragmentActivity.
 */
@Composable
fun DropKnockBiometricHost() {
    val context = LocalContext.current
    val controller = remember(context) { Graph.get(context).desktopDrop.controller }
    val snapshot by controller.snapshot.collectAsStateWithLifecycle()
    val nonce by controller.knockPromptNonce.collectAsStateWithLifecycle()
    var promptedId by rememberSaveable { mutableStateOf("") }
    var promptedNonce by rememberSaveable { mutableStateOf(0) }
    val knock = snapshot.pendingApproval

    LaunchedEffect(knock?.sessionId, nonce) {
        val pending = knock ?: return@LaunchedEffect
        if (pending.sessionId == promptedId && nonce == promptedNonce) return@LaunchedEffect
        promptedId = pending.sessionId
        promptedNonce = nonce
        DropApprovalNotifier.cancel(context)
        DropBiometric.prompt(
            context = context,
            knock = pending,
            onApproved = { controller.approveKnockAfterBiometric(pending.sessionId) },
            onMessage = controller::notifyMessage,
        )
    }
}

internal tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

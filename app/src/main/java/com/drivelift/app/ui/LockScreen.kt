package com.drivelift.app.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Shows [content] only after a successful biometric check in this session. Until then a neutral
 * locked screen is shown: no files, thumbnails, account, settings or transfer history.
 */
@Composable
fun LockGate(lock: AppLock, authenticator: Authenticator, content: @Composable () -> Unit) {
    val state by lock.state.collectAsStateWithLifecycle()
    if (state.unlocked) {
        content()
    } else {
        val context = LocalContext.current
        fun start() = startUnlock(context, lock, authenticator)
        // One automatic prompt per lock when the screen is in front. After a cancel or failure
        // the user retries with the button, so prompts never loop.
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            if (lock.claimAutoPrompt()) start()
        }
        LockedScreen(
            state = state,
            onUnlock = ::start,
            onEnroll = { openBiometricEnrollment(context) },
        )
    }
}

private fun startUnlock(context: Context, lock: AppLock, authenticator: Authenticator) {
    if (lock.state.value.prompting) return
    when (authenticator.availability(context)) {
        BiometricAvailability.READY -> authenticator.authenticate(context)
        BiometricAvailability.NOT_ENROLLED -> lock.onProblem(LockProblem.NotEnrolled)
        BiometricAvailability.UNAVAILABLE -> lock.onProblem(LockProblem.Unavailable)
    }
}

private fun openBiometricEnrollment(context: Context) {
    val enroll = Intent(Settings.ACTION_BIOMETRIC_ENROLL)
        .putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, BIOMETRIC_WEAK)
    runCatching { context.startActivity(enroll) }
        .recoverCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
}

@Composable
fun LockedScreen(state: AppLock.State, onUnlock: () -> Unit, onEnroll: () -> Unit) {
    val problem = state.problem
    val body = when (problem) {
        null -> "Confirm it's you with your fingerprint or face to see your files and transfers. Uploads already in progress keep running."
        LockProblem.Cancelled -> "Unlock was cancelled. DriveLift stays locked until you confirm it's you."
        LockProblem.NotEnrolled -> "No fingerprint or face is set up on this phone. Add one in Android settings, then come back and unlock."
        LockProblem.Unavailable -> "This phone can't use fingerprint or face right now, so DriveLift stays locked. Try again in a moment."
        is LockProblem.Lockout -> if (problem.permanent) {
            "Fingerprint and face are blocked after too many attempts. Lock your phone, unlock it with your PIN, pattern or password, then try again here."
        } else {
            "Too many attempts. Wait a moment, then try again."
        }
        is LockProblem.Error -> "Couldn't confirm it's you: ${problem.message}. Try again."
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Tokens.Section, vertical = Tokens.Section),
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(Tokens.EmptyState),
        )
        Text(
            "DriveLift is locked",
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.padding(top = Tokens.Gutter),
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Tokens.Medium).widthIn(max = Tokens.ReadableWidth),
        )
        Column(
            Modifier.padding(top = Tokens.Section).widthIn(max = Tokens.ReadableWidth),
            verticalArrangement = Arrangement.spacedBy(Tokens.Small),
        ) {
            if (state.prompting) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            } else if (problem == LockProblem.NotEnrolled) {
                Button(onClick = onEnroll, modifier = Modifier.fillMaxWidth()) { Text("Set up fingerprint or face") }
                OutlinedButton(onClick = onUnlock, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
            } else {
                Button(onClick = onUnlock, modifier = Modifier.fillMaxWidth()) {
                    Text(if (problem == null) "Unlock" else "Try again")
                }
            }
        }
    }
}

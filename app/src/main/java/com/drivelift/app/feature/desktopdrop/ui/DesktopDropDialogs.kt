package com.drivelift.app.feature.desktopdrop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import com.drivelift.app.feature.desktopdrop.model.DesktopDropPrefsState
import com.drivelift.app.feature.desktopdrop.model.PeerSession
import com.drivelift.app.ui.Tokens

/**
 * Shown while a browser waits at the Drop address. Approving opens the phone's biometric prompt;
 * nothing is granted until that prompt succeeds.
 */
@Composable
fun PeerApprovalDialog(
    peer: PeerSession,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDeny,
        modifier = Modifier.testTag("dd-peer-approval-dialog"),
        title = { Text("Approve laptop with biometrics?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                Text(
                    peer.deviceLabel,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "LAN address: ${peer.peerIp}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (peer.matchCode.isNotBlank()) {
                    Text(
                        "Match code ${peer.matchCode}",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("dd-match-code"),
                    )
                    Text(
                        "Approve only if the laptop shows the same code.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "Your fingerprint, face or screen lock unlocks an HttpOnly local session cookie for this browser.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onApprove,
                modifier = Modifier.heightIn(min = Tokens.TouchTarget).testTag("dd-verify-biometric"),
            ) {
                Text("Verify with biometrics")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDeny,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.heightIn(min = Tokens.TouchTarget),
            ) {
                Text("Deny")
            }
        },
    )
}

/**
 * Configuration modal for the HTTP port (8080 by default) and the idle auto-stop timer.
 */
@Composable
fun DesktopDropSettingsDialog(
    prefs: DesktopDropPrefsState,
    onSave: (port: Int, idleMinutes: Int) -> Unit,
    onResetDestination: () -> Unit,
    onDismiss: () -> Unit,
) {
    var portText by remember(prefs.preferredPort) { mutableStateOf(prefs.preferredPort.toString()) }
    var idleText by remember(prefs.idleTimeoutMinutes) { mutableStateOf(prefs.idleTimeoutMinutes.toString()) }

    val parsedPort = portText.trim().toIntOrNull()
    val parsedIdle = idleText.trim().toIntOrNull()
    val valid = parsedPort != null && parsedPort in 1024..65535 &&
        parsedIdle != null && parsedIdle in 1..240

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("dd-settings-dialog"),
        title = { Text("Desktop Drop settings") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Tokens.Medium),
            ) {
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                    label = { Text("Default HTTP port (1024-65535)") },
                    supportingText = { Text("Uses 8080 by default for locked-down PC compatibility.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = idleText,
                    onValueChange = { idleText = it.filter(Char::isDigit).take(3) },
                    label = { Text("Idle auto-stop (minutes)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (prefs.destinationTreeUri != null) {
                    TextButton(
                        onClick = onResetDestination,
                        modifier = Modifier.padding(top = Tokens.Tiny).heightIn(min = Tokens.TouchTarget),
                    ) {
                        Text("Reset save folder to Downloads/DriveLift Drop")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (parsedPort != null && parsedIdle != null) {
                        onSave(parsedPort, parsedIdle)
                    }
                },
                enabled = valid,
                modifier = Modifier.heightIn(min = Tokens.TouchTarget),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = Tokens.TouchTarget),
            ) {
                Text("Cancel")
            }
        },
    )
}

package com.drivelift.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * App preferences, opened from the gear on Transfers. The switches write the same preferences,
 * with the same defaults, as they did when they sat on Transfers; only their place changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val wifiOnly by vm.prefs.wifiOnly.collectAsStateWithLifecycle()
    val autoRemove by vm.prefs.autoRemove.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
            ),
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to transfers")
                }
            },
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Tokens.Edge)
                .padding(bottom = Tokens.Edge)
                .widthIn(max = Tokens.ReadableWidth),
        ) {
            SectionHeading("Transfers")
            GlassPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = Tokens.Gutter)) {
                    SwitchRow(
                        "Upload on Wi-Fi only", "Mobile data is never used for uploads.",
                        wifiOnly, vm::setWifiOnly,
                    )
                    SwitchRow(
                        "Offer removal when verified",
                        "Opens Android's confirmation as soon as a batch is fully verified.",
                        autoRemove, vm.prefs::setAutoRemove,
                    )
                }
            }
            SectionHeading("About")
            GlassPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = Tokens.Gutter, vertical = Tokens.Medium)) {
                    Text(appVersionLabel(context), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Removal from this phone always asks Android to confirm, and only for files whose Drive copy matched in size and checksum.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Tokens.Tiny),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = Tokens.Section, bottom = Tokens.Small),
    )
}

@Composable
private fun SwitchRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            // Toggleable, not clickable, so TalkBack reads the row as a switch with its state.
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = Tokens.Row),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = Tokens.Gutter)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

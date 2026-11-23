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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
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
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.clickable
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
fun SettingsScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpenCockpit: (() -> Unit)? = null,
    onOpenAppLock: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val wifiOnly by vm.prefs.wifiOnly.collectAsStateWithLifecycle()
    val autoRemove by vm.prefs.autoRemove.collectAsStateWithLifecycle()
    // Read again whenever Settings comes back to the front, for example after Android's screen.
    var mediaManagement by remember { mutableStateOf(vm.textOnlyRemoval()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { mediaManagement = vm.textOnlyRemoval() }

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
                        autoRemoveDescription(mediaManagement),
                        autoRemove, vm.prefs::setAutoRemove,
                    )
                }
            }
            SectionHeading("Removal")
            GlassPanel(Modifier.fillMaxWidth()) {
                MediaManagementRow(granted = mediaManagement)
            }
            SectionHeading("About")
            GlassPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = Tokens.Gutter, vertical = Tokens.Medium)) {
                    Text(appVersionLabel(context), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        REMOVAL_ABOUT,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Tokens.Tiny),
                    )
                }
            }
            if (onOpenAppLock != null) {
                SectionHeading("Security")
                GlassPanel(
                    modifier = Modifier.fillMaxWidth().testTag("applock-settings-entry"),
                    onClick = onOpenAppLock,
                    onClickLabel = "Open App Lock manager",
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = Tokens.Gutter, vertical = Tokens.Medium),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("App Lock", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Lock installed apps with Class 3 biometric, backup PIN, grace periods, and Settings tamper protection",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (onOpenCockpit != null) {
                SectionHeading("Cockpit")
                GlassPanel(
                    modifier = Modifier.fillMaxWidth().testTag("cockpit-settings-entry"),
                    onClick = onOpenCockpit,
                    onClickLabel = "Open Cockpit Dynamic Island setup",
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = Tokens.Gutter, vertical = Tokens.Medium),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Dynamic Island and widget", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Configure system overlay, live activities, pinned apps, and cutout fit",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Who confirms removal depends on Media management access (invariant 3 in docs/AI_CONTEXT.md):
 * with it, DriveLift lists the files as text; without it, Android shows its own dialog.
 */
internal fun autoRemoveDescription(textOnly: Boolean): String =
    if (textOnly) "Lists the files for you to confirm as soon as a batch is fully verified."
    else "Opens Android's confirmation as soon as a batch is fully verified."

/** True in both access states, so it does not name who shows the confirmation. */
internal const val REMOVAL_ABOUT =
    "Nothing is removed from this phone until you confirm, and only files whose Drive copy matched in size and checksum are offered."

@Composable
private fun MediaManagementRow(granted: Boolean) {
    val context = LocalContext.current
    val supported = android.os.Build.VERSION.SDK_INT >= 31
    Row(
        Modifier.fillMaxWidth()
            .then(if (supported) Modifier.clickable(role = Role.Button) { openMediaManagementSettings(context) } else Modifier)
            .padding(horizontal = Tokens.Gutter, vertical = Tokens.Row)
            .testTag("media-management-row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Text-only removal confirmation", style = MaterialTheme.typography.bodyLarge)
            Text(
                when {
                    !supported -> "Needs Android 12 or later. Android's own confirmation is used."
                    granted -> "On. DriveLift lists the files by name before removing them; Android shows no preview."
                    else -> "Off. Android's confirmation shows a preview. Tap to allow Media management for DriveLift."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

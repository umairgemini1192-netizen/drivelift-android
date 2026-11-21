package com.drivelift.app.feature.intelligence.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivelift.app.feature.intelligence.data.ConflictPolicy
import com.drivelift.app.feature.intelligence.data.RuleActionType
import com.drivelift.app.feature.intelligence.data.SmartRuleEntity
import com.drivelift.app.feature.intelligence.data.action
import com.drivelift.app.feature.intelligence.data.conflict
import com.drivelift.app.feature.intelligence.rules.SmartRuleExecutor
import com.drivelift.app.feature.intelligence.rules.RuleTemplates
import com.drivelift.app.transfer.Destination
import com.drivelift.app.ui.AppViewModel
import com.drivelift.app.ui.Tokens

private const val MB = 1024L * 1024L

/** Create or edit a Smart Folder rule. A solid sheet (rule 3), not glass. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RuleEditorSheet(
    initial: SmartRuleEntity,
    appViewModel: AppViewModel,
    onDismiss: () -> Unit,
    onSave: (SmartRuleEntity) -> Unit,
) {
    val context = LocalContext.current
    val folders by appViewModel.folders.collectAsStateWithLifecycle()
    val presets by appViewModel.presets.collectAsStateWithLifecycle()
    var rule by remember { mutableStateOf(initial) }
    var minSizeText by remember { mutableStateOf(initial.minSizeBytes?.let { (it / MB).toString() }.orEmpty()) }
    var ageText by remember { mutableStateOf(initial.minAgeHours?.toString().orEmpty()) }
    var phoneMenu by remember { mutableStateOf(false) }
    var driveMenu by remember { mutableStateOf(false) }

    fun takeTree(uri: android.net.Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }
    val pickSource = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            takeTree(uri)
            rule = rule.copy(sourceFolderUri = uri.toString(), sourceFolderLabel = treeLabel(uri.toString()))
        }
    }
    val pickTarget = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            takeTree(uri)
            rule = rule.copy(targetDestinationUri = uri.toString(), targetDestinationLabel = treeLabel(uri.toString()))
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.testTag("intel-rule-editor"),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Tokens.Edge, vertical = Tokens.Small),
            verticalArrangement = Arrangement.spacedBy(Tokens.Medium),
        ) {
            Text(if (initial.id == 0L) "New rule" else "Edit rule", style = MaterialTheme.typography.titleLarge)

            if (initial.id == 0L) {
                Text("Start from", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                    RuleTemplates.all.forEach { template ->
                        FilterChip(
                            selected = rule.name == template.rule.name,
                            onClick = {
                                rule = template.rule.copy(id = 0)
                                minSizeText = template.rule.minSizeBytes?.let { (it / MB).toString() }.orEmpty()
                                ageText = template.rule.minAgeHours?.toString().orEmpty()
                            },
                            label = { Text(template.title) },
                            modifier = Modifier.heightIn(min = Tokens.TouchTarget),
                        )
                    }
                }
            }

            OutlinedTextField(
                value = rule.name,
                onValueChange = { rule = rule.copy(name = it) },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Source", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                rule.sourceFolderLabel.ifBlank { "Not chosen" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                Column {
                    OutlinedButton(onClick = { phoneMenu = true }, modifier = Modifier.heightIn(min = Tokens.TouchTarget)) {
                        Text("Phone media folder")
                    }
                    DropdownMenu(expanded = phoneMenu, onDismissRequest = { phoneMenu = false }) {
                        folders.distinctBy { it.path }.forEach { folder ->
                            DropdownMenuItem(
                                text = { Text(folder.path.ifBlank { folder.name }) },
                                onClick = {
                                    rule = rule.copy(
                                        sourceFolderUri = SmartRuleExecutor.MEDIASTORE_PREFIX + folder.path,
                                        sourceFolderLabel = folder.path.trimEnd('/').ifBlank { folder.name },
                                    )
                                    phoneMenu = false
                                },
                            )
                        }
                    }
                }
                OutlinedButton(onClick = { pickSource.launch(null) }, modifier = Modifier.heightIn(min = Tokens.TouchTarget)) {
                    Text("Pick any folder")
                }
            }

            Text("Conditions", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = rule.extensionsCsv,
                onValueChange = { rule = rule.copy(extensionsCsv = it) },
                label = { Text("File types (apk, pdf, video/)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                OutlinedTextField(
                    value = minSizeText,
                    onValueChange = { value ->
                        minSizeText = value.filter { it.isDigit() }.take(6)
                        rule = rule.copy(minSizeBytes = minSizeText.toLongOrNull()?.takeIf { it > 0 }?.times(MB))
                    },
                    label = { Text("Min size MB") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = ageText,
                    onValueChange = { value ->
                        ageText = value.filter { it.isDigit() }.take(5)
                        rule = rule.copy(minAgeHours = ageText.toIntOrNull()?.takeIf { it > 0 })
                    },
                    label = { Text("Older than (hours)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            SwitchRow("Only 4K videos or higher", rule.minVideoHeightPx != null) {
                rule = rule.copy(minVideoHeightPx = if (it) 2160 else null)
            }

            Text("Action", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                RuleActionType.entries.forEach { type ->
                    val label = when (type) {
                        RuleActionType.MOVE_LOCAL -> "Move"
                        RuleActionType.COPY_LOCAL -> "Copy"
                        RuleActionType.UPLOAD_CLOUD -> "Upload to Drive"
                    }
                    FilterChip(
                        selected = rule.action == type,
                        onClick = {
                            // Switching between local and cloud clears a destination of the other kind.
                            val wasCloud = rule.action == RuleActionType.UPLOAD_CLOUD
                            val isCloud = type == RuleActionType.UPLOAD_CLOUD
                            rule = if (wasCloud != isCloud) {
                                rule.copy(actionType = type.name, targetDestinationUri = "", targetDestinationLabel = "")
                            } else {
                                rule.copy(actionType = type.name)
                            }
                        },
                        label = { Text(label) },
                        modifier = Modifier.heightIn(min = Tokens.TouchTarget),
                    )
                }
            }
            if (rule.action == RuleActionType.MOVE_LOCAL && rule.sourceFolderUri.startsWith(SmartRuleExecutor.MEDIASTORE_PREFIX)) {
                Text(
                    "Android asks before any phone photo or video is removed, so from a phone media folder a move keeps the original. Pick the folder with Pick any folder to move files.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (rule.action == RuleActionType.UPLOAD_CLOUD && !rule.sourceFolderUri.startsWith(SmartRuleExecutor.MEDIASTORE_PREFIX)) {
                Text(
                    "Uploads use DriveLift's transfer queue, which takes phone photos and videos. Choose a Phone media folder as the source.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text("Destination", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(rule.targetDestinationLabel.ifBlank { "Not chosen" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (rule.action == RuleActionType.UPLOAD_CLOUD) {
                Column {
                    OutlinedButton(onClick = { driveMenu = true }, modifier = Modifier.heightIn(min = Tokens.TouchTarget)) {
                        Text("Choose Drive folder")
                    }
                    DropdownMenu(expanded = driveMenu, onDismissRequest = { driveMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(Destination.Auto.label) },
                            onClick = {
                                rule = rule.copy(
                                    targetDestinationUri = SmartRuleExecutor.DRIVE_PREFIX + SmartRuleExecutor.DRIVE_AUTO,
                                    targetDestinationLabel = Destination.Auto.label,
                                )
                                driveMenu = false
                            },
                        )
                        presets.forEach { preset ->
                            DropdownMenuItem(
                                text = { Text(preset.path) },
                                onClick = {
                                    rule = rule.copy(
                                        targetDestinationUri = SmartRuleExecutor.DRIVE_PREFIX + preset.folderId,
                                        targetDestinationLabel = preset.path,
                                    )
                                    driveMenu = false
                                },
                            )
                        }
                    }
                }
            } else {
                OutlinedButton(onClick = { pickTarget.launch(null) }, modifier = Modifier.heightIn(min = Tokens.TouchTarget)) {
                    Text("Pick destination folder")
                }
                Text("If the name is taken", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                    ConflictPolicy.entries.forEach { policy ->
                        FilterChip(
                            selected = rule.conflict == policy,
                            onClick = { rule = rule.copy(conflictPolicy = policy.name) },
                            label = { Text(if (policy == ConflictPolicy.SKIP) "Skip the file" else "Keep both (rename)") },
                            modifier = Modifier.heightIn(min = Tokens.TouchTarget),
                        )
                    }
                }
            }

            Text("When it runs", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SwitchRow("Only on Wi-Fi", rule.requireWifi) { rule = rule.copy(requireWifi = it) }
            SwitchRow("Only while charging", rule.requireCharging) { rule = rule.copy(requireCharging = it) }

            Row(
                Modifier.fillMaxWidth().padding(bottom = Tokens.Section),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Small, Alignment.End),
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = Tokens.TouchTarget)) { Text("Cancel") }
                Button(
                    onClick = { onSave(rule.copy(name = rule.name.trim())) },
                    enabled = rule.name.isNotBlank(),
                    modifier = Modifier.heightIn(min = Tokens.TouchTarget).testTag("intel-save-rule"),
                ) { Text("Save rule") }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Tokens.TouchTarget).toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

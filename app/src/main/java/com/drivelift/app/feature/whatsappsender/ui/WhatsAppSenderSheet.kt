package com.drivelift.app.feature.whatsappsender.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.drivelift.app.feature.whatsappsender.model.BatchStrategy
import com.drivelift.app.feature.whatsappsender.model.LocalSendItem
import com.drivelift.app.feature.whatsappsender.model.MAX_WHATSAPP_BATCH_FILES
import com.drivelift.app.feature.whatsappsender.model.PreFlightReport
import com.drivelift.app.feature.whatsappsender.model.QualityMode
import com.drivelift.app.feature.whatsappsender.model.WhatsAppAvailability
import com.drivelift.app.feature.whatsappsender.model.WhatsAppSendPayload
import com.drivelift.app.feature.whatsappsender.model.WhatsAppTarget
import com.drivelift.app.ui.GlassPalette
import com.drivelift.app.ui.Tokens
import com.drivelift.app.ui.formatBytes

/**
 * Dark Tactile Glass bottom sheet for WhatsApp Quick Sender: target app selector,
 * Original Quality vs Standard Media toggle, pre-flight warnings, folder ZIP staging, and send CTA.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WhatsAppSenderSheet(
    payload: WhatsAppSendPayload,
    onDismiss: () -> Unit,
    onSent: (String) -> Unit,
    onHandoffToDrive: ((List<Long>) -> Unit)? = null,
    onHandoffToDesktopDrop: ((List<LocalSendItem>) -> Unit)? = null,
    senderVm: WhatsAppSenderViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by senderVm.state.collectAsStateWithLifecycle()

    LaunchedEffect(payload) {
        senderVm.loadPayload(payload)
    }
    if (state.payload != payload) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val report = state.report
    val effectiveItems = report.effectiveItems(state.excludeOversized)
    val effectiveCount = effectiveItems.size
    val effectiveBytes = report.totalBytes(state.excludeOversized)
    val exceedsBatchLimit = report.exceedsBatchLimit(state.excludeOversized)
    val totalBatches = report.batchCount(state.excludeOversized)
    val oversizedMediaIds = report.oversizedItems.mapNotNull {
        it.id.removePrefix("media:").toLongOrNull()
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (state.isZipping) {
                senderVm.cancelZip()
            } else {
                onDismiss()
            }
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Tokens.Edge)
                .padding(bottom = Tokens.Edge)
                .navigationBarsPadding()
                .testTag("wa-sender-sheet"),
            verticalArrangement = Arrangement.spacedBy(Tokens.Medium),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Tiny)) {
                Text("Send to WhatsApp", style = MaterialTheme.typography.titleLarge)
                Text(
                    payload.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Target selector chips (auto-hides uninstalled variants, falls back to System share).
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                Text("Target app", style = MaterialTheme.typography.titleSmall)
                if (state.availability == WhatsAppAvailability.NONE) {
                    SheetCard {
                        Text(
                            "WhatsApp is not installed on this device. Files will open in Android System share.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                        verticalArrangement = Arrangement.spacedBy(Tokens.Small),
                    ) {
                        state.availability.installedTargets().forEach { target ->
                            FilterChip(
                                selected = state.selectedTarget == target,
                                onClick = { senderVm.selectTarget(target) },
                                enabled = !state.isZipping,
                                label = { Text(target.label) },
                                modifier = Modifier
                                    .heightIn(min = Tokens.TouchTarget)
                                    .testTag("wa-target-${target.name.lowercase()}"),
                            )
                        }
                    }
                }
            }

            // Quality mode selector: Original Quality (Document) vs Standard Media (Compressed).
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                Text("Quality mode", style = MaterialTheme.typography.titleSmall)
                QualityMode.entries.forEach { mode ->
                    val selected = state.qualityMode == mode
                    Surface(
                        color = if (selected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        contentColor = if (selected) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        shape = Tokens.PanelShape,
                        border = BorderStroke(
                            width = if (selected) Tokens.Stroke else Tokens.Hairline,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = Tokens.TouchTarget)
                            .clip(Tokens.PanelShape)
                            .selectable(
                                selected = selected,
                                enabled = !state.isZipping,
                                role = Role.RadioButton,
                                onClick = { senderVm.selectQualityMode(mode) },
                            )
                            .testTag("wa-quality-${mode.name.lowercase()}"),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Tokens.Gutter, vertical = Tokens.Small),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = null,
                                enabled = !state.isZipping,
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = Tokens.Small),
                            ) {
                                Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    mode.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // Selection summary card.
            SheetCard(modifier = Modifier.testTag("wa-summary-card")) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "$effectiveCount ${if (effectiveCount == 1) "file" else "files"} ready",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = Tokens.Small),
                    )
                    Text(
                        formatBytes(context, effectiveBytes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (report.skippedTotalCount > 0) {
                    Text(
                        skippedSummary(report),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Tokens.Tiny),
                    )
                }
            }

            // Over 2 GB per-file ceiling warning.
            if (report.oversizedItems.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = Tokens.PanelShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("wa-oversized-warning"),
                ) {
                    Column(
                        modifier = Modifier.padding(Tokens.Gutter),
                        verticalArrangement = Arrangement.spacedBy(Tokens.Small),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                        ) {
                            Icon(
                                Icons.Outlined.WarningAmber,
                                contentDescription = null,
                                modifier = Modifier.size(Tokens.Icon),
                            )
                            Text(
                                "${report.oversizedItems.size} ${if (report.oversizedItems.size == 1) "file exceeds" else "files exceed"} 2 GB",
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                        Text(
                            buildString {
                                append(oversizedNamesSummary(report.oversizedItems))
                                append(
                                    if (state.excludeOversized) {
                                        " Excluded from this WhatsApp send."
                                    } else {
                                        " WhatsApp blocks files over 2 GB. Exclude them or hand off."
                                    },
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                            verticalArrangement = Arrangement.spacedBy(Tokens.Small),
                        ) {
                            OutlinedButton(
                                onClick = senderVm::toggleExcludeOversized,
                                enabled = !state.isZipping,
                                modifier = Modifier
                                    .defaultMinSize(minHeight = Tokens.TouchTarget)
                                    .testTag("wa-toggle-exclude-oversized"),
                            ) {
                                Text(if (state.excludeOversized) "Include over-2 GB" else "Exclude over-2 GB")
                            }
                            if (onHandoffToDesktopDrop != null) {
                                OutlinedButton(
                                    onClick = { onHandoffToDesktopDrop(report.oversizedItems) },
                                    enabled = !state.isZipping,
                                    modifier = Modifier
                                        .defaultMinSize(minHeight = Tokens.TouchTarget)
                                        .testTag("wa-handoff-desktop-drop"),
                                ) {
                                    Text("Desktop Drop")
                                }
                            }
                            if (onHandoffToDrive != null && oversizedMediaIds.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = { onHandoffToDrive(oversizedMediaIds) },
                                    enabled = !state.isZipping,
                                    modifier = Modifier
                                        .defaultMinSize(minHeight = Tokens.TouchTarget)
                                        .testTag("wa-handoff-drive"),
                                ) {
                                    Text("Drive link")
                                }
                            }
                        }
                    }
                }
            }

            // Large batch (>30 files) or folder archive option.
            if ((exceedsBatchLimit || payload.isFolderSource) && effectiveCount > 0) {
                SheetCard(modifier = Modifier.testTag("wa-batch-card")) {
                    if (exceedsBatchLimit) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                        ) {
                            Icon(
                                Icons.Outlined.FolderZip,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(Tokens.Icon),
                            )
                            Text(
                                "Over $MAX_WHATSAPP_BATCH_FILES files ($effectiveCount total)",
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                        Text(
                            "WhatsApp limits media batches to $MAX_WHATSAPP_BATCH_FILES files. Zip into one archive or split into $totalBatches batches.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Tokens.Tiny, bottom = Tokens.Small),
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                            verticalArrangement = Arrangement.spacedBy(Tokens.Small),
                        ) {
                            FilterChip(
                                selected = state.batchStrategy == BatchStrategy.ZIP_ARCHIVE,
                                onClick = { senderVm.selectBatchStrategy(BatchStrategy.ZIP_ARCHIVE) },
                                enabled = !state.isZipping,
                                label = { Text("Zip into 1 archive") },
                                modifier = Modifier
                                    .heightIn(min = Tokens.TouchTarget)
                                    .testTag("wa-strategy-zip"),
                            )
                            FilterChip(
                                selected = state.batchStrategy == BatchStrategy.SPLIT_BATCHES_30,
                                onClick = { senderVm.selectBatchStrategy(BatchStrategy.SPLIT_BATCHES_30) },
                                enabled = !state.isZipping,
                                label = { Text("Split into batches of $MAX_WHATSAPP_BATCH_FILES") },
                                modifier = Modifier
                                    .heightIn(min = Tokens.TouchTarget)
                                    .testTag("wa-strategy-split"),
                            )
                        }
                        if (state.batchStrategy == BatchStrategy.SPLIT_BATCHES_30 && state.sentBatches.isNotEmpty()) {
                            Text(
                                "Sent ${state.sentBatches.size} of $totalBatches batches. Next: batch ${state.currentBatchIndex + 1}.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = Tokens.Small),
                            )
                        }
                    } else {
                        val zipEnabled = state.batchStrategy == BatchStrategy.ZIP_ARCHIVE
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = Tokens.TouchTarget)
                                .toggleable(
                                    value = zipEnabled,
                                    enabled = !state.isZipping,
                                    role = Role.Switch,
                                    onValueChange = senderVm::toggleFolderZip,
                                )
                                .testTag("wa-zip-folder-toggle"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = Tokens.Medium),
                            ) {
                                Text("Zip & Send folder as 1 archive", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Packages $effectiveCount files into a temporary zip in cache.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = zipEnabled,
                                onCheckedChange = null,
                                enabled = !state.isZipping,
                            )
                        }
                    }
                }
            }

            // Optional caption field.
            OutlinedTextField(
                value = state.caption,
                onValueChange = senderVm::updateCaption,
                enabled = !state.isZipping,
                label = { Text("Caption (optional)") },
                maxLines = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("wa-caption-field"),
            )

            // Live folder zip progress.
            if (state.isZipping) {
                val progress = state.zipProgress
                SheetCard(modifier = Modifier.testTag("wa-zip-progress")) {
                    Text(
                        if (progress != null) {
                            "Zipping ${progress.filesCompleted} of ${progress.totalFiles}: ${progress.currentFileName}"
                        } else {
                            "Preparing archive..."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    LinearProgressIndicator(
                        progress = { progress?.fraction ?: 0f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Tokens.Small)
                            .height(Tokens.ProgressHeight),
                        color = MaterialTheme.colorScheme.tertiary,
                        trackColor = GlassPalette.Dim,
                    )
                    OutlinedButton(
                        onClick = senderVm::cancelZip,
                        modifier = Modifier.defaultMinSize(minHeight = Tokens.TouchTarget),
                    ) {
                        Text("Cancel zip")
                    }
                }
            }

            state.feedbackMessage?.let { note ->
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            state.errorMessage?.let { error ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = Tokens.PanelShape,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(Tokens.Gutter),
                    )
                }
            }

            if (effectiveCount == 0 && !state.isZipping) {
                Text(
                    "No sendable files remain. Include excluded items or choose a different selection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val sizeLabel = formatBytes(context, effectiveBytes)
            val ctaText = buildCtaLabel(
                isZipping = state.isZipping,
                strategy = state.batchStrategy,
                effectiveCount = effectiveCount,
                sizeLabel = sizeLabel,
                target = state.selectedTarget,
                currentBatchIndex = state.currentBatchIndex,
                totalBatches = totalBatches,
                nextBatchCount = if (state.batchStrategy == BatchStrategy.SPLIT_BATCHES_30 && totalBatches > 0) {
                    effectiveItems.chunked(MAX_WHATSAPP_BATCH_FILES)
                        .getOrNull(state.currentBatchIndex)
                        ?.size ?: 0
                } else {
                    0
                },
            )

            Button(
                onClick = { senderVm.send(context, onSent) },
                enabled = effectiveCount > 0 && !state.isZipping,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = Tokens.TouchTarget)
                    .testTag("wa-send-cta"),
            ) {
                if (state.isZipping) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(Tokens.IconSmall),
                        strokeWidth = Tokens.Stroke,
                    )
                    Spacer(Modifier.width(Tokens.Small))
                } else {
                    Icon(
                        Icons.AutoMirrored.Outlined.Send,
                        contentDescription = null,
                        modifier = Modifier.size(Tokens.IconSmall),
                    )
                    Spacer(Modifier.width(Tokens.Small))
                }
                Text(ctaText, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SheetCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = Tokens.PanelShape,
        border = BorderStroke(Tokens.Hairline, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Tokens.Gutter)) {
            content()
        }
    }
}

private fun skippedSummary(report: PreFlightReport): String {
    val parts = ArrayList<String>(2)
    if (report.skippedZeroByteCount > 0) {
        val c = report.skippedZeroByteCount
        parts += "$c empty ${if (c == 1) "file" else "files"}"
    }
    if (report.skippedUnreadableCount > 0) {
        val c = report.skippedUnreadableCount
        parts += "$c unreadable ${if (c == 1) "file" else "files"}"
    }
    return "Skipped ${parts.joinToString(" and ")}."
}

private fun oversizedNamesSummary(items: List<LocalSendItem>): String {
    val shown = items.take(3).joinToString(", ") { it.name }
    val extra = items.size - 3
    return if (extra > 0) "$shown and $extra more." else "$shown."
}

private fun buildCtaLabel(
    isZipping: Boolean,
    strategy: BatchStrategy,
    effectiveCount: Int,
    sizeLabel: String,
    target: WhatsAppTarget,
    currentBatchIndex: Int,
    totalBatches: Int,
    nextBatchCount: Int,
): String = when {
    isZipping -> "Zipping files..."
    strategy == BatchStrategy.ZIP_ARCHIVE ->
        "Zip & Send $effectiveCount ${if (effectiveCount == 1) "file" else "files"} ($sizeLabel) to ${target.label}"
    strategy == BatchStrategy.SPLIT_BATCHES_30 && totalBatches > 0 ->
        "Send batch ${currentBatchIndex + 1} of $totalBatches ($nextBatchCount files) to ${target.label}"
    else ->
        "Send $effectiveCount ${if (effectiveCount == 1) "file" else "files"} ($sizeLabel) to ${target.label}"
}

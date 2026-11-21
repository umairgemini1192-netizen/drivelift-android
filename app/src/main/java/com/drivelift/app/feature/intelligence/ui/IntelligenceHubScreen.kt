package com.drivelift.app.feature.intelligence.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.drivelift.app.feature.intelligence.data.OcrLanguageMode
import com.drivelift.app.feature.intelligence.data.RuleActionType
import com.drivelift.app.feature.intelligence.data.RuleExecutionLogEntity
import com.drivelift.app.feature.intelligence.data.RuleLogStatus
import com.drivelift.app.feature.intelligence.data.SmartRuleEntity
import com.drivelift.app.feature.intelligence.data.action
import com.drivelift.app.feature.intelligence.data.isComplete
import com.drivelift.app.feature.intelligence.nlsearch.SearchResult
import com.drivelift.app.feature.intelligence.nlsearch.SearchSnippet
import com.drivelift.app.feature.intelligence.ocr.IndexingTelemetry
import com.drivelift.app.feature.intelligence.rules.RuleTemplates
import com.drivelift.app.ui.AppViewModel
import com.drivelift.app.ui.GlassPalette
import com.drivelift.app.ui.GlassPanel
import com.drivelift.app.ui.Tokens
import com.drivelift.app.ui.formatBytes
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val INTEL_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm")

private fun formatTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(INTEL_TIME)

/** Quick suggestions: the visible label and the query it runs. */
private val SUGGESTIONS = listOf(
    "PDFs from last week" to "pdfs last week",
    "Receipts" to "receipts",
    "Duas / دعا" to "دعا",
    "> 50MB" to "> 50mb",
)

/**
 * Intelligence & Automation hub: offline natural-language and OCR search across phone pictures and
 * PDFs (English, Arabic and Urdu), and Smart Folder rules. Everything runs on the phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntelligenceHubScreen(
    appViewModel: AppViewModel,
    onBack: () -> Unit,
    vm: IntelligenceViewModel = viewModel(),
) {
    val tab by vm.tab.collectAsStateWithLifecycle()
    val dryRun by vm.dryRun.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<SmartRuleEntity?>(null) }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("intel-hub"),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                title = { Text("Intelligence", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to phone folders")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            HubTabs(tab, onSelect = { vm.tab.value = it })
            when (tab) {
                HubTab.SEARCH -> SearchTab(vm)
                HubTab.RULES -> RulesTab(vm, onEdit = { editing = it })
            }
        }
    }

    dryRun?.let { DryRunDialog(it, onDismiss = vm::dismissDryRun) }
    editing?.let { rule ->
        RuleEditorSheet(
            initial = rule,
            appViewModel = appViewModel,
            onDismiss = { editing = null },
            onSave = {
                vm.saveRule(it)
                editing = null
            },
        )
    }
}

@Composable
private fun HubTabs(active: HubTab, onSelect: (HubTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Tokens.Edge, vertical = Tokens.Small).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
    ) {
        for (tab in HubTab.entries) {
            val label = if (tab == HubTab.SEARCH) "Offline search" else "Smart rules"
            Pill(
                label = label,
                selected = tab == active,
                onClick = { onSelect(tab) },
                modifier = Modifier.testTag(if (tab == HubTab.SEARCH) "intel-tab-search" else "intel-tab-rules"),
            )
        }
    }
}

/** A capsule control well (rule 3: flat, token borders, no glass of its own). */
@Composable
private fun Pill(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = Tokens.PillShape
    val look = if (selected) {
        Modifier.background(MaterialTheme.colorScheme.primaryContainer, shape).border(Tokens.Hairline, MaterialTheme.colorScheme.primary, shape)
    } else {
        Modifier.background(GlassPalette.Well, shape).border(Tokens.Hairline, GlassPalette.WellEdge, shape)
    }
    Box(
        modifier
            .clip(shape)
            .then(look)
            .heightIn(min = Tokens.TouchTarget)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = Tokens.Gutter, vertical = Tokens.Small),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** A small non-interactive tag inside a panel (Wi-Fi, Charging, privacy). */
@Composable
private fun Tag(label: String, modifier: Modifier = Modifier, icon: (@Composable () -> Unit)? = null) {
    Row(
        modifier
            .clip(Tokens.PillShape)
            .background(GlassPalette.Well, Tokens.PillShape)
            .border(Tokens.Hairline, GlassPalette.WellEdge, Tokens.PillShape)
            .padding(horizontal = Tokens.Row, vertical = Tokens.Tiny),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Tiny),
    ) {
        icon?.invoke()
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// Offline search

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchTab(vm: IntelligenceViewModel) {
    val context = LocalContext.current
    val search by vm.search.collectAsStateWithLifecycle()
    val telemetry by vm.telemetry.collectAsStateWithLifecycle()
    val paused by vm.paused.collectAsStateWithLifecycle()
    val packReady by vm.arabicUrduPackReady.collectAsStateWithLifecycle()
    val mode by vm.ocrMode.collectAsStateWithLifecycle()
    val chargingOnly by vm.chargingOnly.collectAsStateWithLifecycle()
    val folders by vm.documentFolders.collectAsStateWithLifecycle()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    val importPack = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importArabicUrduPack(uris)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            vm.addDocumentFolder(uri)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().testTag("intel-search-list"),
        contentPadding = PaddingValues(bottom = Tokens.Section),
    ) {
        item(key = "field") {
            OutlinedTextField(
                value = search.query,
                onValueChange = vm::onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.Edge, vertical = Tokens.Small).testTag("intel-search-field"),
                singleLine = true,
                placeholder = { Text("Try: receipts from October") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (search.query.isNotEmpty()) {
                        IconButton(onClick = { vm.onQueryChange("") }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Clear search")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.submitQuery() }),
            )
        }
        item(key = "suggestions") {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Tokens.Edge),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
            ) {
                SUGGESTIONS.forEach { (label, query) ->
                    Pill(label = label, selected = search.query == query, onClick = {
                        vm.onQueryChange(query)
                        vm.submitQuery()
                    })
                }
            }
        }
        item(key = "status") {
            IndexStatusBanner(
                telemetry = telemetry,
                paused = paused,
                packReady = packReady,
                onTogglePause = vm::togglePause,
                onScanNow = vm::scanNow,
                onImportPack = { importPack.launch(arrayOf("*/*")) },
            )
        }

        val parsed = search.parsed
        if (search.query.isNotBlank() && parsed != null && parsed.understood.isNotEmpty()) {
            item(key = "understood") {
                Text(
                    "Looking for: " + parsed.understood.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Tokens.Edge, vertical = Tokens.Tiny).testTag("intel-understood"),
                )
            }
        }

        when {
            search.query.isBlank() -> item(key = "hint") {
                EmptyNote(
                    if (telemetry.indexedCount == 0 && telemetry.pendingCount == 0) {
                        "Nothing is indexed yet. Scan now reads your photos, screenshots and picked PDF folders on this phone."
                    } else {
                        "Type what you remember: words in a photo or PDF, \"pdf from last month\", \"over 10MB\", or Arabic and Urdu text."
                    },
                )
            }
            search.searching && search.results.isEmpty() -> item(key = "searching") {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = Tokens.Edge, vertical = Tokens.Medium))
            }
            search.searched && search.results.isEmpty() -> item(key = "none") {
                EmptyNote("Nothing matched. Try fewer words, or scan again if the file is new.")
            }
            else -> {
                item(key = "count") {
                    Text(
                        if (search.results.size == 1) "1 result" else "${search.results.size} results",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Tokens.Edge, vertical = Tokens.Tiny),
                    )
                }
                items(search.results, key = { "r-" + it.document.id }) { result ->
                    ResultCard(
                        result = result,
                        onOpen = { openDocument(context, result) },
                        onShare = { shareDocument(context, result) },
                    )
                }
            }
        }

        item(key = "settings") {
            IndexSettingsPanel(
                open = settingsOpen,
                onToggle = { settingsOpen = !settingsOpen },
                mode = mode,
                onMode = vm::setOcrMode,
                chargingOnly = chargingOnly,
                onChargingOnly = vm::setChargingOnly,
                folders = folders,
                onAddFolder = { pickFolder.launch(null) },
                onRemoveFolder = vm::removeDocumentFolder,
                onRebuild = vm::rebuildIndex,
            )
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Tokens.Edge, vertical = Tokens.Gutter).testTag("intel-empty"),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IndexStatusBanner(
    telemetry: IndexingTelemetry,
    paused: Boolean,
    packReady: Boolean,
    onTogglePause: () -> Unit,
    onScanNow: () -> Unit,
    onImportPack: () -> Unit,
) {
    val number = remember { NumberFormat.getIntegerInstance() }
    GlassPanel(Modifier.fillMaxWidth().padding(horizontal = Tokens.Edge, vertical = Tokens.Small).testTag("intel-status")) {
        Column(Modifier.padding(Tokens.Gutter), verticalArrangement = Arrangement.spacedBy(Tokens.Small)) {
            Text("${number.format(telemetry.indexedCount)} indexed", style = MaterialTheme.typography.titleMedium)
            val detail = buildList {
                if (telemetry.pendingCount > 0) add("${number.format(telemetry.pendingCount)} waiting")
                if (telemetry.failedCount > 0) add("${number.format(telemetry.failedCount)} unreadable")
                if (paused) add("paused")
                else if (telemetry.running) add("reading now")
            }
            if (detail.isNotEmpty()) {
                Text(detail.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            telemetry.currentFile?.let {
                Text("Reading $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small), verticalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                Tag("100% on-device · offline", icon = {
                    Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(Tokens.IconSmall), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }, modifier = Modifier.testTag("intel-privacy"))
                Tag(if (packReady) "Arabic and Urdu ready" else "Arabic and Urdu pack missing", modifier = Modifier.testTag("intel-pack"))
            }
            if (!packReady) {
                TextButton(onClick = onImportPack, modifier = Modifier.heightIn(min = Tokens.TouchTarget)) {
                    Text("Import Arabic and Urdu pack")
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small), verticalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                OutlinedButton(onClick = onTogglePause, modifier = Modifier.heightIn(min = Tokens.TouchTarget).testTag("intel-pause")) {
                    Text(if (paused) "Resume" else "Pause")
                }
                Button(onClick = onScanNow, modifier = Modifier.heightIn(min = Tokens.TouchTarget).testTag("intel-scan")) {
                    Text("Scan now")
                }
            }
        }
    }
}

@Composable
private fun ResultCard(result: SearchResult, onOpen: () -> Unit, onShare: () -> Unit) {
    val context = LocalContext.current
    val doc = result.document
    GlassPanel(
        Modifier.fillMaxWidth().padding(horizontal = Tokens.Edge, vertical = Tokens.Tiny).testTag("intel-result"),
        onClick = onOpen,
        onClickLabel = "Open ${doc.displayName}",
    ) {
        Row(Modifier.padding(Tokens.Medium), verticalAlignment = Alignment.Top) {
            val thumbShape = Tokens.ThumbShape
            Box(
                Modifier.size(Tokens.FolderThumb).clip(thumbShape).background(GlassPalette.Thumb, thumbShape),
                contentAlignment = Alignment.Center,
            ) {
                if (doc.mimeType.startsWith("image/")) {
                    Icon(Icons.Outlined.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    AsyncImage(
                        model = Uri.parse(doc.uri),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(Icons.Outlined.PictureAsPdf, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(1f).padding(start = Tokens.Medium)) {
                Text(doc.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(doc.folderPath.trimEnd('/'), formatBytes(context, doc.sizeBytes)).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                result.snippet?.let { SnippetText(it) }
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Outlined.Share, contentDescription = "Share ${doc.displayName}")
            }
        }
    }
}

/** The matched preview; laid out right to left when it holds Arabic or Urdu text. */
@Composable
private fun SnippetText(snippet: SearchSnippet) {
    val highlight = SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    val text: AnnotatedString = remember(snippet, highlight) {
        buildAnnotatedString {
            append(snippet.text)
            snippet.highlights.forEach { range ->
                val end = (range.last + 1).coerceAtMost(snippet.text.length)
                if (range.first in 0 until end) addStyle(highlight, range.first, end)
            }
        }
    }
    val direction = if (snippet.rightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium.copy(
                textDirection = if (snippet.rightToLeft) TextDirection.Rtl else TextDirection.Content,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = Tokens.Tiny)
                .testTag(if (snippet.rightToLeft) "intel-snippet-rtl" else "intel-snippet"),
        )
    }
}

private fun openDocument(context: android.content.Context, result: SearchResult) {
    val doc = result.document
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(doc.uri), doc.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun shareDocument(context: android.content.Context, result: SearchResult) {
    val doc = result.document
    val send = Intent(Intent.ACTION_SEND)
        .setType(doc.mimeType)
        .putExtra(Intent.EXTRA_STREAM, Uri.parse(doc.uri))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching {
        context.startActivity(Intent.createChooser(send, "Share ${doc.displayName}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun IndexSettingsPanel(
    open: Boolean,
    onToggle: () -> Unit,
    mode: OcrLanguageMode,
    onMode: (OcrLanguageMode) -> Unit,
    chargingOnly: Boolean,
    onChargingOnly: (Boolean) -> Unit,
    folders: List<String>,
    onAddFolder: () -> Unit,
    onRemoveFolder: (String) -> Unit,
    onRebuild: () -> Unit,
) {
    GlassPanel(Modifier.fillMaxWidth().padding(horizontal = Tokens.Edge, vertical = Tokens.Small).testTag("intel-settings")) {
        Column {
            Row(
                Modifier.fillMaxWidth().heightIn(min = Tokens.TouchTarget).clickable(onClickLabel = if (open) "Hide index settings" else "Show index settings", onClick = onToggle)
                    .padding(horizontal = Tokens.Gutter, vertical = Tokens.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Index settings", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
            }
            if (open) {
                Column(Modifier.padding(start = Tokens.Gutter, end = Tokens.Gutter, bottom = Tokens.Gutter)) {
                    Text("Reading text in pictures", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OcrLanguageMode.entries.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = Tokens.TouchTarget)
                                .selectable(selected = option == mode, role = Role.RadioButton, onClick = { onMode(option) }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option == mode, onClick = null)
                            Column(Modifier.padding(start = Tokens.Small)) {
                                Text(option.label, style = MaterialTheme.typography.bodyMedium)
                                Text(option.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = Tokens.TouchTarget)
                            .toggleable(value = chargingOnly, role = Role.Switch, onValueChange = onChargingOnly),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Index only while charging", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(checked = chargingOnly, onCheckedChange = null)
                    }
                    Text(
                        "PDF and document folders",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Tokens.Small),
                    )
                    if (folders.isEmpty()) {
                        Text(
                            "Photos are always included. Add a folder such as Download or Documents to index its PDFs.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    folders.forEach { uri ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(treeLabel(uri), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { onRemoveFolder(uri) }) {
                                Icon(Icons.Outlined.Close, contentDescription = "Stop indexing ${treeLabel(uri)}")
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                        OutlinedButton(onClick = onAddFolder, modifier = Modifier.heightIn(min = Tokens.TouchTarget)) { Text("Add folder") }
                        TextButton(onClick = onRebuild, modifier = Modifier.heightIn(min = Tokens.TouchTarget)) { Text("Rebuild index") }
                    }
                }
            }
        }
    }
}

/** A readable name for a picked folder URI ("primary:Download" -> "Download"). */
internal fun treeLabel(uri: String): String =
    Uri.decode(uri).substringAfterLast('/').substringAfterLast(':').ifBlank { "Folder" }

// Smart rules

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RulesTab(vm: IntelligenceViewModel, onEdit: (SmartRuleEntity) -> Unit) {
    val rules by vm.rules.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()
    val busy by vm.busyRuleId.collectAsStateWithLifecycle()
    var logOpen by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<SmartRuleEntity?>(null) }

    LazyColumn(
        Modifier.fillMaxSize().testTag("intel-rules-list"),
        contentPadding = PaddingValues(bottom = Tokens.Section),
    ) {
        item(key = "new") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Tokens.Edge, vertical = Tokens.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Rules sort files on this phone and queue uploads on their own. Nothing runs until a rule is switched on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
            Button(
                onClick = { onEdit(SmartRuleEntity(name = "")) },
                modifier = Modifier.padding(horizontal = Tokens.Edge).heightIn(min = Tokens.TouchTarget).testTag("intel-new-rule"),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Text("New rule", modifier = Modifier.padding(start = Tokens.Small))
            }
        }

        if (rules.isEmpty()) {
            item(key = "no-rules") { EmptyNote("No rules yet. Start from a template below or make your own.") }
        }
        items(rules, key = { "rule-" + it.id }) { rule ->
            RuleCard(
                rule = rule,
                busy = busy == rule.id,
                anyBusy = busy != null,
                onEnabled = { vm.setRuleEnabled(rule, it) },
                onPreview = { vm.previewRule(rule) },
                onRunNow = { vm.runRuleNow(rule) },
                onEdit = { onEdit(rule) },
                onDelete = { confirmDelete = rule },
            )
        }

        item(key = "templates-title") {
            Text(
                "Templates",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = Tokens.Edge, end = Tokens.Edge, top = Tokens.Gutter, bottom = Tokens.Tiny),
            )
        }
        items(RuleTemplates.all, key = { "tpl-" + it.key }) { template ->
            GlassPanel(
                Modifier.fillMaxWidth().padding(horizontal = Tokens.Edge, vertical = Tokens.Tiny).testTag("intel-template-" + template.key),
                onClick = { vm.addFromTemplate(template) },
                onClickLabel = "Add ${template.title}",
            ) {
                Row(Modifier.padding(Tokens.Gutter), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(template.title, style = MaterialTheme.typography.titleSmall)
                        Text(template.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item(key = "log-header") {
            GlassPanel(
                Modifier.fillMaxWidth().padding(start = Tokens.Edge, end = Tokens.Edge, top = Tokens.Gutter, bottom = Tokens.Tiny).testTag("intel-log"),
                onClick = { logOpen = !logOpen },
                onClickLabel = if (logOpen) "Hide activity" else "Show activity",
            ) {
                Row(Modifier.padding(Tokens.Gutter).heightIn(min = Tokens.Icon), verticalAlignment = Alignment.CenterVertically) {
                    Text("Activity (${logs.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Icon(if (logOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
                }
            }
        }
        if (logOpen) {
            if (logs.isEmpty()) item(key = "log-empty") { EmptyNote("Files a rule copies, moves, queues or skips are listed here.") }
            items(logs, key = { "log-" + it.id }) { LogRow(it) }
        }
    }

    confirmDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this rule?") },
            text = { Text("\"${rule.name}\" stops running. Files it already handled stay where they are.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteRule(rule)
                    confirmDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

internal fun ruleSummary(rule: SmartRuleEntity): String {
    val source = rule.sourceFolderLabel.ifBlank { "No source folder yet" }
    val conditions = buildList {
        if (rule.extensionsCsv.isNotBlank()) add(rule.extensionsCsv)
        rule.minVideoHeightPx?.let { add(if (it >= 2160) "4K or higher" else "${it}p or higher") }
        rule.minAgeHours?.let { add(if (it % 24 == 0) "older than ${it / 24} days" else "older than $it hours") }
    }
    val target = rule.targetDestinationLabel.ifBlank { "no destination yet" }
    val verb = when (rule.action) {
        RuleActionType.MOVE_LOCAL -> "move to"
        RuleActionType.COPY_LOCAL -> "copy to"
        RuleActionType.UPLOAD_CLOUD -> "upload to"
    }
    return (listOf(source) + conditions).joinToString(", ") + ": $verb $target"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleCard(
    rule: SmartRuleEntity,
    busy: Boolean,
    anyBusy: Boolean,
    onEnabled: (Boolean) -> Unit,
    onPreview: () -> Unit,
    onRunNow: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    GlassPanel(Modifier.fillMaxWidth().padding(horizontal = Tokens.Edge, vertical = Tokens.Tiny).testTag("intel-rule")) {
        Column(Modifier.padding(Tokens.Gutter), verticalArrangement = Arrangement.spacedBy(Tokens.Small)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = Tokens.TouchTarget)
                    .toggleable(value = rule.enabled, enabled = rule.isComplete, role = Role.Switch, onValueChange = onEnabled),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(rule.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Switch(checked = rule.enabled, onCheckedChange = null, enabled = rule.isComplete)
            }
            Text(ruleSummary(rule), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!rule.isComplete) {
                Text(
                    "Choose a source and a destination to switch this rule on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (rule.requireWifi || rule.requireCharging) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Small), verticalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                    if (rule.requireWifi) Tag("Wi-Fi")
                    if (rule.requireCharging) Tag("Charging")
                }
            }
            Text(
                rule.lastRunAt?.let { "Last run ${formatTime(it)} · ${rule.totalProcessedCount} handled so far" } ?: "Not run yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                verticalArrangement = Arrangement.spacedBy(Tokens.Small),
            ) {
                OutlinedButton(
                    onClick = onPreview,
                    enabled = rule.isComplete && !anyBusy,
                    modifier = Modifier.heightIn(min = Tokens.TouchTarget).testTag("intel-preview"),
                ) { Text("Preview matches") }
                OutlinedButton(
                    onClick = onRunNow,
                    enabled = rule.isComplete && !anyBusy,
                    modifier = Modifier.heightIn(min = Tokens.TouchTarget).testTag("intel-run"),
                ) { Text("Run now") }
                IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Edit ${rule.name}") }
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Delete ${rule.name}") }
            }
        }
    }
}

@Composable
private fun LogRow(log: RuleExecutionLogEntity) {
    val color = when (log.status) {
        RuleLogStatus.FAILED.name -> MaterialTheme.colorScheme.error
        RuleLogStatus.SUCCESS.name -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val status = when (log.status) {
        RuleLogStatus.SUCCESS.name -> "Done"
        RuleLogStatus.SKIPPED.name -> "Skipped"
        else -> "Failed"
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = Tokens.Edge + Tokens.Gutter, vertical = Tokens.Snug)) {
        Text(log.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "$status · ${log.message} · ${log.ruleName} · ${formatTime(log.timestamp)}",
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

@Composable
private fun DryRunDialog(state: DryRunUi, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val report = state.report
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("intel-dry-run"),
        title = { Text("Preview: ${state.rule.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Tiny)) {
                Text(
                    if (report.matched.isEmpty()) "No files match right now. Nothing would change."
                    else "${report.matched.size} files, ${formatBytes(context, report.matchedBytes)}. Nothing has changed yet.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                report.matched.take(DRY_RUN_SHOWN).forEach {
                    Text(it.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (report.matched.size > DRY_RUN_SHOWN) {
                    Text("and ${report.matched.size - DRY_RUN_SHOWN} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (report.skipped.isNotEmpty()) {
                    Text(
                        "${report.skipped.size} left out for safety (in use or in the transfer queue)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private const val DRY_RUN_SHOWN = 12

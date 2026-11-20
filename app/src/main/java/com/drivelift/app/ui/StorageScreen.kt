package com.drivelift.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.drivelift.app.data.DuplicateCleanupPlan
import com.drivelift.app.data.DuplicateGroup
import com.drivelift.app.data.LeftOutReason
import com.drivelift.app.data.MediaAccess
import com.drivelift.app.data.MediaItem
import com.drivelift.app.data.canSelect
import com.drivelift.app.data.largestItems
import com.drivelift.app.data.storageBreakdown
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val FOLDERS_SHOWN = 8
private const val LARGEST_SHOWN = 10
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

internal fun formatClock(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(TIME_FORMAT)

private fun formatDay(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DATE_FORMAT)

/** What storage insights v1 does not measure, said on the screen itself (issue #42). */
internal const val STORAGE_NOT_COVERED =
    "Not covered: documents, audio, downloads, APK files, and other apps' files and caches. " +
        "Measuring those needs a separate storage access design, planned for later."

/**
 * Storage insights v1 (issue #42), opened from the Phone tab. It measures only the phone photos
 * and videos DriveLift's listing can see, and the connected Google Drive account's quota, and
 * says so with the time each figure was read. Duplicate candidates are files of equal size whose
 * bytes have the same SHA-256 checksum; they are never presented as safe to delete. Cleanup is
 * the user's choice, file by file, and always keeps one copy in each group.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val media by vm.media.collectAsStateWithLifecycle()
    val loaded by vm.mediaLoaded.collectAsStateWithLifecycle()
    val listedAt by vm.mediaListedAt.collectAsStateWithLifecycle()
    val conn by vm.driveConn.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    val accountReadAt by vm.accountReadAt.collectAsStateWithLifecycle()
    val quotaRefreshing by vm.quotaRefreshing.collectAsStateWithLifecycle()
    val scan by vm.duplicateScan.collectAsStateWithLifecycle()
    val groups by vm.duplicateGroups.collectAsStateWithLifecycle()
    val selected by vm.duplicateSelection.collectAsStateWithLifecycle()
    val held by vm.trackedUris.collectAsStateWithLifecycle()
    val cleanup by vm.duplicateCleanup.collectAsStateWithLifecycle()
    val driveScan by vm.driveFolderScan.collectAsStateWithLifecycle()
    val driveOpen by vm.driveFolderOpen.collectAsStateWithLifecycle()
    val driveShown by vm.driveFoldersShown.collectAsStateWithLifecycle()

    val breakdown = remember(media) { storageBreakdown(media) }
    val largest = remember(media) { largestItems(media, LARGEST_SHOWN) }
    val selectedItems = remember(groups, selected) { groups.flatMap { it.items }.filter { it.id in selected } }
    val selectedBytes = selectedItems.sumOf { it.size }

    // Opening Storage refreshes a saved Drive folder count in the background (issue #14).
    DisposableEffect(vm) {
        vm.storageOpened()
        onDispose { vm.storageClosed() }
    }

    // The file open in the viewer, kept across rotation; the list stays composed beneath it.
    var viewingId by rememberSaveable { mutableLongStateOf(NOT_VIEWING) }
    val viewing = remember(viewingId, media) { media.firstOrNull { it.id == viewingId } }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .then(if (viewing != null) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier),
        ) {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                title = { Text("Storage") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to folders")
                    }
                },
            )
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("storage-list")) {
                item(key = "lead") {
                    Column(Modifier.padding(start = Tokens.Edge, end = Tokens.Edge, top = Tokens.Small, bottom = Tokens.Small)) {
                        if (!loaded) {
                            CircularProgressIndicator()
                        } else {
                            Text(formatBytes(context, breakdown.totalBytes), style = MaterialTheme.typography.displayLarge)
                        }
                        Text(
                            "Phone photos and videos DriveLift can see" +
                                (listedAt?.let { ". Listed at ${formatClock(it)}." } ?: ". Listing them now."),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (MediaAccess.isPartial(context)) {
                            Text(
                                "Only the photos you selected are visible, so these figures leave out the rest.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = Tokens.Tiny),
                            )
                        }
                    }
                }
                if (loaded) {
                    item(key = "breakdown") {
                        Section("Photos and videos") {
                            BreakdownPanel(
                                photos = breakdown.photoCount to breakdown.photoBytes,
                                videos = breakdown.videoCount to breakdown.videoBytes,
                            )
                        }
                    }
                    if (breakdown.folders.isNotEmpty()) item(key = "folders") {
                        Section("Phone folders") {
                            GlassPanel(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(vertical = Tokens.Small)) {
                                    breakdown.folders.take(FOLDERS_SHOWN).forEach { folder ->
                                        LabelValueRow(
                                            title = folder.name,
                                            detail = countLabel(folder.photoCount, folder.videoCount),
                                            value = formatBytes(context, folder.bytes),
                                        )
                                    }
                                    val more = breakdown.folders.size - FOLDERS_SHOWN
                                    if (more > 0) Text(
                                        if (more == 1) "and 1 more folder" else "and $more more folders",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = Tokens.Gutter, vertical = Tokens.Small),
                                    )
                                }
                            }
                        }
                    }
                }
                item(key = "drive-folders") {
                    Section("Google Drive folders") {
                        DriveFoldersSummary(
                            scan = driveScan,
                            conn = conn,
                            openId = driveOpen,
                            now = vm.now(),
                            onStart = vm::startDriveFolderScan,
                            onStop = vm::stopDriveFolderScan,
                            onConnect = { vm.connectDrive(interactive = true) },
                        )
                    }
                }
                // The saved or earlier figures are listed even before Drive connects and while a
                // recount runs.
                val done = driveScan as? AppViewModel.DriveFolderScan.Done
                    ?: (driveScan as? AppViewModel.DriveFolderScan.Running)?.previous
                if (done != null) item(key = "drive-folder-list") {
                    DriveFolderList(
                        tree = done.tree,
                        openId = driveOpen,
                        shown = driveShown,
                        onOpen = vm::openDriveFolder,
                        onMore = vm::showMoreDriveFolders,
                    )
                }
                item(key = "quota") {
                    Section("Google Drive quota") {
                        QuotaPanel(
                            conn = conn,
                            email = account?.email,
                            used = account?.usedBytes,
                            limit = account?.limitBytes,
                            readAt = accountReadAt,
                            refreshing = quotaRefreshing,
                            onRefresh = vm::refreshQuota,
                            onConnect = { vm.connectDrive(interactive = true) },
                        )
                    }
                }
                if (loaded) {
                    item(key = "largest-heading") { SectionTitle("Largest files") }
                    if (largest.isEmpty()) item(key = "largest-empty") {
                        EmptyLine("No photos or videos found on this phone.")
                    }
                    items(largest, key = { "large-${it.id}" }) { item ->
                        LargeFileRow(item, onOpen = { viewingId = item.id })
                    }
                    item(key = "duplicates") {
                        Section("Duplicate candidates") {
                            DuplicateScanPanel(
                                scan = scan,
                                groupsFound = groups.size,
                                onStart = vm::startDuplicateScan,
                                onCancel = vm::cancelDuplicateScan,
                            )
                        }
                    }
                    items(groups, key = { "group-${it.key}" }) { group ->
                        DuplicateGroupPanel(
                            group = group,
                            selected = selected,
                            held = held,
                            onToggle = vm::toggleDuplicate,
                            onOpen = { viewingId = it.id },
                        )
                    }
                }
                item(key = "not-covered") {
                    Text(
                        STORAGE_NOT_COVERED,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(horizontal = Tokens.Edge, vertical = Tokens.Section)
                            .widthIn(max = Tokens.ReadableWidth),
                    )
                }
            }
            if (selectedItems.isNotEmpty()) {
                CleanupBar(
                    count = selectedItems.size,
                    bytes = selectedBytes,
                    checking = cleanup is AppViewModel.DuplicateCleanup.Checking,
                    onClear = { vm.duplicateSelection.value = emptySet() },
                    onTrash = vm::reviewDuplicateCleanup,
                )
            }
        }
        viewing?.let { item ->
            FullScreenOverlay {
                LocalMediaViewer(vm = vm, item = item, onClose = { viewingId = NOT_VIEWING })
            }
        }
        (cleanup as? AppViewModel.DuplicateCleanup.Confirm)?.let { state ->
            DuplicateCleanupDialog(
                plan = state.plan,
                onConfirm = vm::confirmDuplicateCleanup,
                onCancel = vm::cancelDuplicateCleanup,
            )
        }
    }
}

private const val NOT_VIEWING = -1L

private fun countLabel(photos: Int, videos: Int): String {
    val p = if (photos == 1) "1 photo" else "$photos photos"
    val v = if (videos == 1) "1 video" else "$videos videos"
    return when {
        videos == 0 -> p
        photos == 0 -> v
        else -> "$p, $v"
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = Tokens.Edge, end = Tokens.Edge, top = Tokens.Section, bottom = Tokens.Small),
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SectionTitle(title)
        Box(Modifier.padding(horizontal = Tokens.Edge)) { content() }
    }
}

@Composable
internal fun EmptyLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Tokens.Edge, vertical = Tokens.Small),
    )
}

/** A title and detail on the left, a figure on the right; stacks with large text or a narrow screen. */
@Composable
internal fun LabelValueRow(title: String, detail: String?, value: String) {
    val stacked = LocalDensity.current.fontScale >= Tokens.STACK_AT_FONT_SCALE
    val tnum = MaterialTheme.typography.bodyLarge
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Tokens.Gutter, vertical = Tokens.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (stacked) Text(value, style = tnum)
        }
        if (!stacked) {
            Spacer(Modifier.width(Tokens.Medium))
            Text(value, style = tnum)
        }
    }
}

@Composable
private fun BreakdownPanel(photos: Pair<Int, Long>, videos: Pair<Int, Long>) {
    val context = LocalContext.current
    val total = photos.second + videos.second
    GlassPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = Tokens.Small)) {
            if (total > 0) {
                // Share of bytes: photos in violet, videos in sky blue (atmosphere colours, not actions).
                Row(
                    Modifier
                        .padding(horizontal = Tokens.Gutter, vertical = Tokens.Small)
                        .fillMaxWidth()
                        .height(Tokens.ProgressHeight)
                        .clip(Tokens.PillShape)
                        .clearAndSetSemantics {},
                ) {
                    if (photos.second > 0) Box(
                        Modifier.weight(photos.second.toFloat()).fillMaxSize().background(MaterialTheme.colorScheme.secondary),
                    )
                    if (videos.second > 0) Box(
                        Modifier.weight(videos.second.toFloat()).fillMaxSize().background(MaterialTheme.colorScheme.tertiary),
                    )
                }
            }
            LabelValueRow(
                title = "Photos",
                detail = if (photos.first == 1) "1 file" else "${photos.first} files",
                value = formatBytes(context, photos.second),
            )
            LabelValueRow(
                title = "Videos",
                detail = if (videos.first == 1) "1 file" else "${videos.first} files",
                value = formatBytes(context, videos.second),
            )
        }
    }
}

@Composable
private fun QuotaPanel(
    conn: DriveConn,
    email: String?,
    used: Long?,
    limit: Long?,
    readAt: Long?,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onConnect: () -> Unit,
) {
    val context = LocalContext.current
    GlassPanel(Modifier.fillMaxWidth()) {
        if (conn != DriveConn.CONNECTED || used == null) {
            Column(Modifier.padding(horizontal = Tokens.Gutter, vertical = Tokens.Medium)) {
                Text(
                    if (conn == DriveConn.CONNECTED) "Google Drive is connected, but its quota has not been read yet."
                    else "Google Drive is not connected, so its quota is not shown.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                when {
                    conn == DriveConn.CONNECTING || conn == DriveConn.UNKNOWN ->
                        CircularProgressIndicator(Modifier.padding(top = Tokens.Small).size(Tokens.Icon), strokeWidth = Tokens.Stroke)
                    conn == DriveConn.CONNECTED ->
                        OutlinedButton(onClick = onRefresh, modifier = Modifier.padding(top = Tokens.Small)) { Text("Read quota") }
                    else -> Button(onClick = onConnect, modifier = Modifier.padding(top = Tokens.Small)) { Text("Connect") }
                }
            }
            return@GlassPanel
        }
        Row(
            Modifier.padding(start = Tokens.Gutter, end = Tokens.Tiny, top = Tokens.Medium, bottom = Tokens.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (limit != null && limit > 0) "${formatBytes(context, used)} used of ${formatBytes(context, limit)}"
                    else "${formatBytes(context, used)} used, no storage limit reported",
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (limit != null && limit > 0) {
                    LinearProgressIndicator(
                        progress = { (used.toFloat() / limit).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = Tokens.Small).height(Tokens.ProgressHeight),
                        color = MaterialTheme.colorScheme.tertiary,
                        trackColor = GlassPalette.Dim,
                    )
                }
                Text(
                    buildString {
                        append(email ?: "Connected account")
                        append(". Whole account: Drive, Gmail and Google Photos share it.")
                        if (readAt != null) append(" Read at ${formatClock(readAt)}.")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (refreshing) {
                Box(Modifier.size(Tokens.TouchTarget), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(Tokens.Icon), strokeWidth = Tokens.Stroke)
                }
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Read the Drive quota again")
                }
            }
        }
    }
}

@Composable
private fun Thumb(item: MediaItem) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context).data(item.uri).size(192).build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(Tokens.FolderThumb).clip(Tokens.ThumbShape).background(GlassPalette.Thumb),
    )
}

@Composable
private fun LargeFileRow(item: MediaItem, onOpen: () -> Unit) {
    val context = LocalContext.current
    GlassPanel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.Edge, vertical = Tokens.Tiny),
        onClick = onOpen,
        onClickLabel = "Open",
    ) {
        Row(
            Modifier.fillMaxWidth().padding(Tokens.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumb(item)
            Column(Modifier.weight(1f).padding(start = Tokens.Gutter)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    formatBytes(context, item.size),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "${item.bucketName}  ·  ${if (item.isVideo) "Video" else "Photo"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DuplicateScanPanel(
    scan: AppViewModel.DuplicateScan,
    groupsFound: Int,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    GlassPanel(Modifier.fillMaxWidth().testTag("duplicate-scan")) {
        Column(Modifier.padding(horizontal = Tokens.Gutter, vertical = Tokens.Medium)) {
            when (scan) {
                AppViewModel.DuplicateScan.Idle -> {
                    Text(
                        "Files of the same size are read and compared by a SHA-256 checksum of their bytes. " +
                            "Names are not used. Matching files are candidates to look at, not files that are safe to delete.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onStart, modifier = Modifier.padding(top = Tokens.Medium)) { Text("Check for duplicates") }
                }
                is AppViewModel.DuplicateScan.Running -> {
                    Text(
                        "Reading ${scan.filesDone} of ${scan.filesTotal} same-size files",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    LinearProgressIndicator(
                        progress = {
                            if (scan.bytesTotal > 0) (scan.bytesDone.toFloat() / scan.bytesTotal).coerceIn(0f, 1f) else 0f
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = Tokens.Small).height(Tokens.ProgressHeight),
                        color = MaterialTheme.colorScheme.tertiary,
                        trackColor = GlassPalette.Dim,
                    )
                    Text(
                        "${formatBytes(context, scan.bytesDone)} of ${formatBytes(context, scan.bytesTotal)}. Nothing is changed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onCancel, modifier = Modifier.padding(top = Tokens.Small)) { Text("Stop") }
                }
                is AppViewModel.DuplicateScan.Done -> {
                    val result = scan.result
                    Text(
                        when {
                            scan.candidates == 0 -> "No two files have the same size, so there are no duplicate candidates."
                            groupsFound == 0 -> "No files with matching content were found."
                            groupsFound == 1 -> "1 group of files with matching content."
                            else -> "$groupsFound groups of files with matching content."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        buildString {
                            append("Checked at ${formatClock(scan.at)}: ")
                            append(if (result.filesRead == 1) "1 same-size file read" else "${result.filesRead} same-size files read")
                            append(" and compared by SHA-256.")
                            if (result.unreadable.isNotEmpty()) {
                                append(" ${result.unreadable.size} could not be read and are left out.")
                            }
                            if (groupsFound > 0) append(" Select the copies to remove; one copy in each group always stays.")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Tokens.Tiny),
                    )
                    TextButton(onClick = onStart, modifier = Modifier.padding(top = Tokens.Tiny)) { Text("Check again") }
                }
            }
        }
    }
}

@Composable
private fun DuplicateGroupPanel(
    group: DuplicateGroup,
    selected: Set<Long>,
    held: Set<String>,
    onToggle: (Long) -> Unit,
    onOpen: (MediaItem) -> Unit,
) {
    val context = LocalContext.current
    GlassPanel(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Edge, vertical = Tokens.Tiny)
            .testTag("duplicate-group"),
    ) {
        Column(Modifier.padding(vertical = Tokens.Small)) {
            Text(
                "${group.items.size} files with matching content  ·  ${formatBytes(context, group.size)} each",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = Tokens.Gutter, vertical = Tokens.Tiny),
            )
            Text(
                "SHA-256 ${group.sha256.take(12)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Tokens.Gutter),
            )
            group.items.forEach { item ->
                val inTransfer = item.uri.toString() in held
                val isSelected = item.id in selected
                val enabled = !inTransfer && canSelect(group, selected, item.id)
                val note = when {
                    inTransfer -> "Part of a transfer, so it stays"
                    !enabled -> "Stays: one copy is always kept"
                    else -> null
                }
                DuplicateRow(
                    item = item,
                    checked = isSelected,
                    enabled = enabled,
                    note = note,
                    onToggle = { onToggle(item.id) },
                    onOpen = { onOpen(item) },
                )
            }
        }
    }
}

@Composable
private fun DuplicateRow(
    item: MediaItem,
    checked: Boolean,
    enabled: Boolean,
    note: String?,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(end = Tokens.Tiny),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                // Toggleable, so TalkBack reads the row as a checkbox with its state.
                .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
                .padding(start = Tokens.Small, top = Tokens.Small, bottom = Tokens.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.size(Tokens.TouchTarget))
            Thumb(item)
            Column(Modifier.weight(1f).padding(start = Tokens.Medium)) {
                // Two lines, so copies with similar names stay distinguishable at large text sizes.
                Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${item.relativePath.trimEnd('/').ifEmpty { item.bucketName }}  ·  ${formatDay(item.dateTakenMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (note != null) Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onOpen) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Open ${item.name}")
        }
    }
}

@Composable
private fun CleanupBar(count: Int, bytes: Long, checking: Boolean, onClear: () -> Unit, onTrash: () -> Unit) {
    val context = LocalContext.current
    Box(Modifier.fillMaxWidth().glassBar()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Tokens.Edge, vertical = Tokens.Medium)) {
            Text(if (count == 1) "1 copy selected" else "$count copies selected", style = MaterialTheme.typography.titleMedium)
            Text(
                if (checking) "Reading the files again before anything is changed"
                else formatBytes(context, bytes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = Tokens.Small),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onClear, enabled = !checking) { Text("Clear") }
                Button(
                    onClick = onTrash,
                    enabled = !checking,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("Move to trash") }
            }
        }
    }
}

/**
 * DriveLift's own confirmation for a duplicate cleanup: count, total size and names of what goes
 * to the trash, which copy stays, and every file left out with the reason.
 */
@Composable
internal fun DuplicateCleanupDialog(plan: DuplicateCleanupPlan, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val count = plan.eligible.size
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag("duplicate-cleanup"),
        title = {
            Text(
                when (count) {
                    0 -> "Nothing can go to the trash"
                    1 -> "Move 1 copy to this phone's trash?"
                    else -> "Move $count copies to this phone's trash?"
                },
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (count > 0) {
                    Text(
                        "${formatBytes(context, plan.totalBytes)} in total. Each was read again just now and still matches " +
                            "a copy that stays on this phone. A matching checksum is strong evidence, not a promise, so check " +
                            "before you confirm. Android keeps trashed items for a limited time, usually 30 days, then deletes " +
                            "them for good. Nothing on Google Drive is changed.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FileNames(plan.eligible)
                    Text("Staying on this phone:", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Tokens.Medium))
                    FileNames(plan.kept)
                } else {
                    Text(
                        "None of the selected copies passed the check made just now. Nothing was changed.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                LeftOut(plan, LeftOutReason.IN_TRANSFER, "Left out because they are part of a transfer:")
                LeftOut(plan, LeftOutReason.CONTENT_CHANGED, "Left out because their content no longer matches or could not be read:")
                LeftOut(plan, LeftOutReason.LAST_COPY, "Left out so that a copy of their group stays:")
                LeftOut(plan, LeftOutReason.GONE, "Left out because they are no longer on this phone:")
            }
        },
        confirmButton = {
            if (count > 0) {
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("Move to trash") }
            } else {
                TextButton(onClick = onCancel) { Text("OK") }
            }
        },
        dismissButton = { if (count > 0) TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun LeftOut(plan: DuplicateCleanupPlan, reason: LeftOutReason, heading: String) {
    val items = plan.leftOut(reason)
    if (items.isEmpty()) return
    Text(heading, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Tokens.Medium))
    FileNames(items)
}

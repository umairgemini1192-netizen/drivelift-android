package com.drivelift.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.drivelift.app.data.MediaAccess
import com.drivelift.app.data.MediaFolder
import com.drivelift.app.data.MediaItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ---------------------------------------------------------------------------
// Folder list
// ---------------------------------------------------------------------------

@Composable
fun PhoneScreen(vm: AppViewModel, onOpen: (Long) -> Unit, onMoveFolder: (Long) -> Unit) {
    val context = LocalContext.current
    val folders by vm.folders.collectAsStateWithLifecycle()
    val media by vm.media.collectAsStateWithLifecycle()
    val loaded by vm.mediaLoaded.collectAsStateWithLifecycle()
    val conn by vm.driveConn.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    val totalBytes = remember(media) { media.sumOf { it.size } }

    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "lead") {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 8.dp)) {
                Text(
                    formatBytes(context, totalBytes),
                    style = MaterialTheme.typography.displayLarge,
                )
                Text(
                    "on this phone, in ${media.size} photos and videos across ${folders.size} folders",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(key = "drive") {
            DriveStatusRow(
                conn = conn,
                label = account?.let { a ->
                    val quota = if (a.limitBytes > 0)
                        "${formatBytes(context, a.limitBytes - a.usedBytes)} free"
                    else "${formatBytes(context, a.usedBytes)} used"
                    "${a.email}  ·  $quota"
                },
                onConnect = { vm.connectDrive(interactive = true) },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        if (MediaAccess.isPartial(context)) {
            item(key = "partial") {
                Text(
                    "Only the photos you selected are visible. Allow full access in system settings to see every folder.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        }
        item(key = "heading") {
            Text(
                "Folders",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
            )
        }
        if (!loaded) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else if (folders.isEmpty()) {
            item(key = "empty") {
                Text(
                    "No photos or videos found on this phone.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            }
        }
        items(folders, key = { it.bucketId }) { folder ->
            FolderRow(folder, onOpen = { onOpen(folder.bucketId) }, onMove = { onMoveFolder(folder.bucketId) })
            HorizontalDivider(
                modifier = Modifier.padding(start = 96.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
fun DriveStatusRow(conn: DriveConn, label: String?, onConnect: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (conn == DriveConn.CONNECTED) {
                Icon(
                    Icons.Outlined.CloudDone, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.padding(start = 12.dp)) {
                    Text("Google Drive connected", style = MaterialTheme.typography.titleSmall)
                    if (label != null) Text(
                        label, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Column(Modifier.weight(1f)) {
                    Text("Google Drive is not connected", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Connect once to choose where files go.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                if (conn == DriveConn.CONNECTING || conn == DriveConn.UNKNOWN) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Button(onClick = onConnect) { Text("Connect") }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(folder: MediaFolder, onOpen: () -> Unit, onMove: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(folder.cover).size(192).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(60.dp).clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(folder.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${folder.count} items  ·  ${formatBytes(context, folder.bytes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (folder.path.isNotEmpty()) Text(
                folder.path, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onMove) {
            Icon(Icons.Outlined.CloudUpload, contentDescription = "Move the whole ${folder.name} folder to Drive")
        }
    }
}

// ---------------------------------------------------------------------------
// Folder contents with bulk selection
// ---------------------------------------------------------------------------

private sealed interface GridEntry {
    data class Header(val day: LocalDate, val label: String, val ids: List<Long>) : GridEntry
    data class Cell(val item: MediaItem, val index: Int) : GridEntry
}

private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(vm: AppViewModel, bucketId: Long, onBack: () -> Unit, onMove: () -> Unit) {
    val context = LocalContext.current
    val media by vm.media.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val tracked by vm.trackedUris.collectAsStateWithLifecycle()

    val items = remember(media, bucketId) { media.filter { it.bucketId == bucketId } }
    val entries = remember(items) {
        val zone = ZoneId.systemDefault()
        val out = ArrayList<GridEntry>(items.size + 32)
        var index = 0
        items.groupBy { Instant.ofEpochMilli(it.dateTakenMs).atZone(zone).toLocalDate() }
            .forEach { (day, list) ->
                out += GridEntry.Header(day, day.format(DAY_FORMAT), list.map { it.id })
                list.forEach { out += GridEntry.Cell(it, index++) }
            }
        out
    }
    val indexById = remember(items) { HashMap<Long, Int>().apply { items.forEachIndexed { i, m -> put(m.id, i) } } }
    val selectedBytes = remember(selection, items) { items.sumOf { if (it.id in selection) it.size else 0L } }
    val title = items.firstOrNull()?.bucketName ?: "Folder"

    LaunchedEffect(items.isEmpty()) { if (items.isEmpty() && media.isNotEmpty()) onBack() }

    val gridState = rememberLazyGridState()
    val autoScroll = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(autoScroll.floatValue) {
        if (autoScroll.floatValue != 0f) {
            while (isActive) {
                gridState.scrollBy(autoScroll.floatValue)
                delay(10)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        if (selection.isEmpty()) title else "${selection.size} selected",
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (selection.isEmpty()) "Tap to select. Hold and drag to select a range."
                        else formatBytes(context, selectedBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            navigationIcon = {
                if (selection.isEmpty()) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to folders")
                    }
                } else {
                    IconButton(onClick = vm::clearSelection) {
                        Icon(Icons.Outlined.Close, contentDescription = "Clear selection")
                    }
                }
            },
            actions = {
                val all = selection.size == items.size && items.isNotEmpty()
                TextButton(onClick = { vm.setSelected(items.map { it.id }, !all) }) {
                    Text(if (all) "Select none" else "Select all")
                }
            },
        )

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            state = gridState,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f).dragSelect(
                state = gridState,
                autoScroll = { autoScroll.floatValue = it },
                indexOfKey = { key -> (key as? Long)?.let(indexById::get) },
                current = { vm.selection.value },
                onRange = { base, from, to ->
                    val range = if (from <= to) from..to else to..from
                    vm.selection.value = base + range.map { items[it].id }
                },
            ),
        ) {
            items(
                entries,
                key = { e -> when (e) { is GridEntry.Header -> "h${e.day}"; is GridEntry.Cell -> e.item.id } },
                span = { e -> if (e is GridEntry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                contentType = { e -> if (e is GridEntry.Header) 0 else 1 },
            ) { entry ->
                when (entry) {
                    is GridEntry.Header -> {
                        val allDay = entry.ids.all { it in selection }
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${entry.label}  ·  ${entry.ids.size}",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { vm.setSelected(entry.ids, !allDay) }) {
                                Text(if (allDay) "Deselect day" else "Select day")
                            }
                        }
                    }
                    is GridEntry.Cell -> MediaCell(
                        item = entry.item,
                        selected = entry.item.id in selection,
                        tracked = entry.item.uri.toString() in tracked,
                        onToggle = { vm.toggle(entry.item.id) },
                    )
                }
            }
        }

        if (selection.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${selection.size} items", style = MaterialTheme.typography.titleMedium)
                        Text(
                            formatBytes(context, selectedBytes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = vm::clearSelection) { Text("Clear") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onMove) { Text("Move to Drive") }
                }
            }
        }
    }
}

@Composable
private fun MediaCell(item: MediaItem, selected: Boolean, tracked: Boolean, onToggle: () -> Unit) {
    val inset by animateDpAsState(if (selected) 10.dp else 0.dp, label = "inset")
    Box(
        Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() }),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(item.uri).size(256).build(),
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().padding(inset).clip(RoundedCornerShape(if (selected) 8.dp else 0.dp)),
        )
        if (item.isVideo) {
            Text(
                formatDuration(item.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        if (tracked) {
            Icon(
                Icons.Outlined.CloudDone,
                contentDescription = "Already in a transfer",
                tint = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).size(20.dp)
                    .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f), CircleShape)
                    .padding(3.dp),
            )
        }
        if (selected) {
            Box(Modifier.fillMaxSize().border(3.dp, MaterialTheme.colorScheme.primary))
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(24.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
    }
}

/**
 * Hold a thumbnail, then drag: everything between the first cell and the
 * finger joins the selection, and the grid scrolls itself near the edges.
 */
@Composable
private fun Modifier.dragSelect(
    state: LazyGridState,
    autoScroll: (Float) -> Unit,
    indexOfKey: (Any) -> Int?,
    current: () -> Set<Long>,
    onRange: (base: Set<Long>, from: Int, to: Int) -> Unit,
): Modifier {
    val lookup by rememberUpdatedState(indexOfKey)
    val range by rememberUpdatedState(onRange)
    val edge = with(LocalDensity.current) { 72.dp.toPx() }
    return pointerInput(Unit) {
        var anchor: Int? = null
        var base: Set<Long> = emptySet()
        fun indexAt(point: Offset): Int? = state.layoutInfo.visibleItemsInfo.firstOrNull { info ->
            point.x >= info.offset.x && point.x < info.offset.x + info.size.width &&
                point.y >= info.offset.y && point.y < info.offset.y + info.size.height
        }?.key?.let { lookup(it) }

        detectDragGesturesAfterLongPress(
            onDragStart = { point ->
                indexAt(point)?.let {
                    anchor = it
                    base = current()
                    range(base, it, it)
                }
            },
            onDragEnd = {
                anchor = null
                autoScroll(0f)
            },
            onDragCancel = {
                anchor = null
                autoScroll(0f)
            },
            onDrag = { change, _ ->
                val start = anchor
                if (start != null) {
                    val fromBottom = state.layoutInfo.viewportSize.height - change.position.y
                    val fromTop = change.position.y
                    autoScroll(
                        when {
                            fromBottom < edge -> (edge - fromBottom) / 4
                            fromTop < edge -> -(edge - fromTop) / 4
                            else -> 0f
                        }
                    )
                    indexAt(change.position)?.let { range(base, start, it) }
                }
            },
        )
    }
}

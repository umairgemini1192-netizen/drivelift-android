package com.drivelift.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import com.drivelift.app.data.DriveMovePlan
import com.drivelift.app.data.DriveTrashPlan
import com.drivelift.app.data.shownDriveEntries
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoSizeSelectLarge
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivelift.app.drive.AuthRequiredException
import com.drivelift.app.drive.DriveClient
import com.drivelift.app.drive.DriveFile
import com.drivelift.app.drive.DrivePreviewKind
import com.drivelift.app.drive.classifyDriveFile
import com.drivelift.app.drive.driveMemoryCacheKey
import com.drivelift.app.drive.drivePreviewTypeLabel
import com.drivelift.app.transfer.Destination
import com.drivelift.app.transfer.describeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private data class Crumb(val id: String, val name: String)

/** Keeps the selected Drive file ids across rotation and process recreation. */
private val StringSetSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })

/** Keeps the folder path across rotation and process recreation. */
private val CrumbStackSaver = listSaver<List<Crumb>, String>(
    save = { stack -> stack.flatMap { listOf(it.id, it.name) } },
    restore = { flat -> flat.chunked(2).map { Crumb(it[0], it[1]) } },
)

/**
 * Keeps the file open in the viewer across rotation, so a playing video or zoomed photo is not
 * closed by turning the phone. The thumbnail link is left out; it is fetched again when needed.
 */
internal val OpenDriveFileSaver = listSaver<DriveFile?, Any?>(
    save = { f ->
        if (f == null) emptyList()
        else listOf(f.id, f.name, f.mimeType, f.size, f.md5, f.trashed, f.durationMillis, f.modifiedTime)
    },
    restore = { v ->
        if (v.isEmpty()) null
        else DriveFile(
            id = v[0] as String,
            name = v[1] as String,
            mimeType = v[2] as String,
            size = v[3] as Long,
            md5 = v[4] as String?,
            trashed = v[5] as Boolean,
            durationMillis = v[6] as Long,
            modifiedTime = v[7] as String?,
        )
    },
)

private sealed interface ThumbState {
    data object Loading : ThumbState
    data class Ready(val bitmap: ImageBitmap) : ThumbState
    data object Unavailable : ThumbState
    data object Error : ThumbState
}

private const val THUMB_FETCH_EDGE_PX = 512

/**
 * Browses Google Drive. As a tab it displays a media gallery grid (with list toggle) and opens
 * supported files inside DriveLift. In pick mode it shows folders only and ends with "Move here".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveBrowser(
    vm: AppViewModel,
    pickMode: Boolean,
    onClose: (() -> Unit)? = null,
    onPick: ((dest: Destination, pin: Boolean) -> Unit)? = null,
    onViewingFileChange: ((Boolean) -> Unit)? = null,
    /** False when the picked folder is not a transfer destination (moving Drive files). */
    offerPreset: Boolean = true,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val safeDrawing = WindowInsets.safeDrawing
    val topInsetPx = safeDrawing.getTop(density)
    val bottomInsetPx = safeDrawing.getBottom(density)
    val conn by vm.driveConn.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    val gridDensity by vm.gridDensity.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var stack by rememberSaveable(stateSaver = CrumbStackSaver) {
        mutableStateOf(listOf(Crumb(DriveClient.ROOT, "My Drive")))
    }
    var entries by remember { mutableStateOf<List<DriveFile>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var showNewFolder by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf(false) }
    var gridView by rememberSaveable { mutableStateOf(true) }
    var viewerFile by rememberSaveable(stateSaver = OpenDriveFileSaver) { mutableStateOf<DriveFile?>(null) }
    var thumbAuthExpired by remember { mutableStateOf(false) }
    // Files selected for a file-manager action (issue #36). Folders are not selectable.
    var selectedIds by rememberSaveable(stateSaver = StringSetSaver) { mutableStateOf(emptySet<String>()) }
    var trashPlan by remember { mutableStateOf<DriveTrashPlan?>(null) }
    // The confirmed batch runs in the view model, so a rotation does not cancel it halfway.
    val trashRun by vm.driveTrashRun.collectAsStateWithLifecycle()
    val trashing = trashRun?.working == true
    // Moving selected files to another Drive folder: choose the folder, then confirm.
    var movePicking by rememberSaveable { mutableStateOf(false) }
    var movePlan by remember { mutableStateOf<DriveMovePlan?>(null) }
    val moveRun by vm.driveMoveRun.collectAsStateWithLifecycle()
    val moving = moveRun?.working == true
    val selecting = selectedIds.isNotEmpty() && !pickMode
    // The tab bar steps aside while a file is open or files are selected, as in Phone folders,
    // so the selection bar has the bottom of the screen to itself.
    LaunchedEffect(viewerFile != null || selecting) {
        onViewingFileChange?.invoke(viewerFile != null || selecting)
    }
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val thumbCache = remember { LruCache<String, ImageBitmap>(120) }
    val accountKey = account?.email.orEmpty()
    val lastLoggedMetrics = remember { arrayOf<PickerWindowMetrics?>(null) }
    val here = stack.last()
    val path = stack.joinToString(" / ") { it.name }
    // Search within this folder and the Drive sort choice (issue #36). The picker lists folders
    // only and keeps Drive's order.
    val driveSort by vm.driveSort.collectAsStateWithLifecycle()
    var query by rememberSaveable(here.id) { mutableStateOf("") }
    val shown = remember(entries, query, driveSort, pickMode) {
        entries?.let { if (pickMode) it else shownDriveEntries(it, query, driveSort) }
    }

    LaunchedEffect(accountKey) {
        thumbCache.evictAll()
        thumbAuthExpired = false
    }


    DisposableEffect(Unit) {
        onDispose { onViewingFileChange?.invoke(false) }
    }

    fun recordPickerBounds(update: PickerWindowMetrics.() -> PickerWindowMetrics) {
        if (!pickMode) return
        val next = vm.pickerMetrics.value.update().copy(
            windowWidthPx = view.width,
            windowHeightPx = view.height,
            topInsetPx = topInsetPx,
            bottomInsetPx = bottomInsetPx,
        )
        vm.pickerMetrics.value = next
        if (next.moveHereHeightPx > 0 && next != lastLoggedMetrics[0]) {
            lastLoggedMetrics[0] = next
            runCatching {
                Log.d(
                    "DriveLiftPicker",
                    "window=${next.windowWidthPx}x${next.windowHeightPx} " +
                        "insets=(${next.topInsetPx},${next.bottomInsetPx}) " +
                        "rootBottom=${next.rootBottomPx} " +
                        "footer=(${next.footerTopPx}..${next.footerBottomPx}) " +
                        "button=(${next.moveHereTopPx}..${next.moveHereBottomPx},h=${next.moveHereHeightPx})",
                )
            }
        }
    }

    LaunchedEffect(here.id, reload, conn) {
        if (conn != DriveConn.CONNECTED) return@LaunchedEffect
        entries = null
        error = null
        thumbAuthExpired = false
        try {
            entries = vm.driveFolders.listChildren(here.id, foldersOnly = pickMode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = describeError(e)
        }
    }

    fun openFolder(file: DriveFile) {
        selectedIds = emptySet()
        stack = stack + Crumb(file.id, file.name)
    }
    fun tapFile(file: DriveFile) {
        if (selecting) {
            selectedIds = if (file.id in selectedIds) selectedIds - file.id else selectedIds + file.id
        } else {
            viewerFile = file
        }
    }
    fun askTrash(files: List<DriveFile>) {
        scope.launch { trashPlan = vm.planDriveTrash(files) }
    }
    fun confirmTrash(plan: DriveTrashPlan) = vm.startDriveTrash(plan)
    // Applies a finished move to whichever folder is shown now, which may not be the one the move
    // started in (the screen can be recreated and navigated while the batch runs): moved files
    // leave the source folder's listing, and the destination's listing is read again.
    LaunchedEffect(moveRun?.outcome) {
        val outcome = moveRun?.outcome ?: return@LaunchedEffect
        movePlan = null
        val gone = outcome.moved.map { it.id }.toSet()
        selectedIds = selectedIds - gone
        when (here.id) {
            outcome.fromFolderId -> entries = entries?.filterNot { it.id in gone }
            outcome.toFolderId -> if (gone.isNotEmpty()) reload++
        }
        vm.driveMoveApplied()
    }
    // Applies a finished batch, including one that finished while the screen was recreated.
    LaunchedEffect(trashRun?.outcome) {
        val outcome = trashRun?.outcome ?: return@LaunchedEffect
        trashPlan = null
        val gone = outcome.trashed.map { it.id }.toSet()
        outcome.trashed.forEach { thumbCache.remove(driveMemoryCacheKey(accountKey, it)) }
        selectedIds = selectedIds - gone
        entries = entries?.filterNot { it.id in gone }
        if (viewerFile?.id in gone) viewerFile = null
        vm.driveTrashApplied()
    }

    BackHandler(enabled = selecting && viewerFile == null) { selectedIds = emptySet() }
    BackHandler(enabled = !selecting && viewerFile == null && (stack.size > 1 || onClose != null)) {
        if (stack.size > 1) stack = stack.dropLast(1) else onClose?.invoke()
    }

    if (movePicking && selecting) {
        // The folder picker takes the screen; this folder and its selection are kept underneath.
        val chosen = entries.orEmpty().filter { it.id in selectedIds }
        val from = here.id
        DriveBrowser(
            vm = vm,
            pickMode = true,
            offerPreset = false,
            onClose = { movePicking = false },
            onPick = { dest, _ ->
                movePicking = false
                dest.folderId?.let { to ->
                    scope.launch { movePlan = vm.planDriveMove(chosen, from, to, dest.label) }
                }
            },
        )
        return
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().then(
                if (pickMode) Modifier.onGloballyPositioned { coords ->
                    recordPickerBounds { copy(rootBottomPx = coords.boundsInWindow().bottom.roundToInt()) }
                } else Modifier
            ),
        ) {
            TopAppBar(
                // Consumed when hosted inside AppRoot's Scaffold; still clears the status bar if
                // DriveBrowser is hosted directly without a Scaffold.
                windowInsets = TopAppBarDefaults.windowInsets,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                title = {
                    if (selecting) {
                        Text("${selectedIds.size} selected", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else Column {
                        Text(here.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (stack.size > 1) path else account?.email ?: "Google Drive",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    when {
                        selecting -> IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Clear selection")
                        }
                        stack.size > 1 -> IconButton(onClick = { stack = stack.dropLast(1) }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Up one folder")
                        }
                        onClose != null -> IconButton(onClick = onClose) {
                            Icon(Icons.Outlined.Close, contentDescription = "Close")
                        }
                    }
                },
                actions = {
                    if (selecting) {
                        val files = shown.orEmpty().filterNot { it.isFolder }
                        val all = files.isNotEmpty() && files.all { it.id in selectedIds }
                        TextButton(onClick = { selectedIds = if (all) emptySet() else files.map { it.id }.toSet() }) {
                            Text(if (all) "Select none" else "Select all")
                        }
                    } else if (conn == DriveConn.CONNECTED) {
                        if (!pickMode) {
                            IconButton(onClick = { gridView = !gridView }) {
                                Icon(
                                    if (gridView) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView,
                                    contentDescription = if (gridView) "Show as list" else "Show as grid",
                                )
                            }
                            if (gridView) {
                                IconButton(
                                    onClick = vm::cycleGridDensity,
                                    modifier = Modifier.size(Tokens.TouchTarget),
                                ) {
                                    Icon(
                                        Icons.Outlined.PhotoSizeSelectLarge,
                                        contentDescription = "Thumbnail size: ${gridDensity.label}",
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { showNewFolder = true }) {
                            Icon(Icons.Outlined.CreateNewFolder, contentDescription = "New folder here")
                        }
                        IconButton(onClick = {
                            thumbCache.evictAll()
                            reload++
                        }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Reload")
                        }
                    }
                },
            )

            if (!pickMode && conn == DriveConn.CONNECTED) {
                FolderFindBar(query = query, onQueryChange = { query = it }, sort = driveSort, onSortChange = vm::setDriveSort)
            }

            if (!pickMode && thumbAuthExpired && conn == DriveConn.CONNECTED) {
                val stacked = density.fontScale >= Tokens.STACK_AT_FONT_SCALE ||
                    LocalConfiguration.current.screenWidthDp < Tokens.COMPACT_WIDTH_DP
                GlassPanel(Modifier.fillMaxWidth().padding(horizontal = Tokens.Edge, vertical = Tokens.Tiny)) {
                    if (stacked) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = Tokens.Gutter, vertical = Tokens.Small),
                            verticalArrangement = Arrangement.spacedBy(Tokens.Small),
                        ) {
                            Text(
                                "Google Drive authorization expired for thumbnails.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(
                                onClick = { vm.connectDrive(interactive = true) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Reconnect")
                            }
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = Tokens.Gutter, vertical = Tokens.Small),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Google Drive authorization expired for thumbnails.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f).padding(end = Tokens.Small),
                            )
                            Button(onClick = { vm.connectDrive(interactive = true) }) {
                                Text("Reconnect")
                            }
                        }
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                val list = shown
                when {
                    conn != DriveConn.CONNECTED -> DriveStatusRow(
                        conn = conn,
                        label = null,
                        onConnect = { vm.connectDrive(interactive = true) },
                        modifier = Modifier.padding(Tokens.Edge),
                    )
                    error != null -> Column(Modifier.padding(Tokens.Edge)) {
                        Text("Could not load this folder", style = MaterialTheme.typography.titleMedium)
                        Text(
                            error.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Tokens.Tiny, bottom = Tokens.Medium),
                        )
                        OutlinedButton(onClick = { reload++ }) { Text("Try again") }
                    }
                    list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    list.isEmpty() && query.isNotBlank() && entries.orEmpty().isNotEmpty() -> NoSearchMatches(query)
                    list.isEmpty() -> Text(
                        if (pickMode) "No folders inside. You can move files right here, or create a folder."
                        else "This folder is empty.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Tokens.Edge),
                    )
                    !pickMode && gridView -> {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = gridDensity.driveMinCell),
                            state = gridState,
                            contentPadding = PaddingValues(horizontal = Tokens.Edge, vertical = Tokens.Small),
                            horizontalArrangement = Arrangement.spacedBy(Tokens.DriveGridGap),
                            verticalArrangement = Arrangement.spacedBy(Tokens.DriveGridGap),
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("drive-media-grid")
                                .pinchGridDensity(
                                    gridState = gridState,
                                    density = gridDensity,
                                    onDensityChange = vm::setGridDensity,
                                ),
                        ) {
                            items(
                                list,
                                key = { it.id },
                                span = { file -> if (file.isFolder) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                                contentType = { file -> if (file.isFolder) 0 else 1 },
                            ) { file ->
                                if (file.isFolder) {
                                    DriveRow(
                                        file = file,
                                        sizeLabel = null,
                                        thumb = null,
                                        padded = false,
                                        onClick = { openFolder(file) },
                                    )
                                } else {
                                    val thumb = rememberDriveThumb(
                                        vm = vm,
                                        file = file,
                                        accountKey = accountKey,
                                        reload = reload,
                                        cache = thumbCache,
                                        onAuthExpired = { thumbAuthExpired = true },
                                    )
                                    DriveMediaGridCard(
                                        file = file,
                                        thumb = thumb,
                                        sizeLabel = formatBytes(context, file.size),
                                        selected = if (selecting) file.id in selectedIds else null,
                                        onClick = { tapFile(file) },
                                        onLongClick = { selectedIds = selectedIds + file.id },
                                    )
                                }
                            }
                        }
                    }
                    else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(list, key = { it.id }) { file ->
                            val thumb = if (!file.isFolder && !pickMode) {
                                rememberDriveThumb(
                                    vm = vm,
                                    file = file,
                                    accountKey = accountKey,
                                    reload = reload,
                                    cache = thumbCache,
                                    onAuthExpired = { thumbAuthExpired = true },
                                )
                            } else {
                                null
                            }
                            DriveRow(
                                file = file,
                                sizeLabel = if (file.isFolder) null else {
                                    "${drivePreviewTypeLabel(file)}  ·  ${formatBytes(context, file.size)}"
                                },
                                thumb = thumb,
                                padded = true,
                                onClick = when {
                                    file.isFolder -> { { openFolder(file) } }
                                    // The picker chooses folders only; a file there is not a target.
                                    pickMode -> null
                                    else -> { { tapFile(file) } }
                                },
                                selected = if (selecting && !file.isFolder) file.id in selectedIds else null,
                                onLongClick = if (file.isFolder || pickMode) null else { { selectedIds = selectedIds + file.id } },
                            )
                        }
                    }
                }
            }

            if (selecting) {
                val chosen = entries.orEmpty().filter { it.id in selectedIds }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .glassBar()
                        .padding(horizontal = Tokens.Edge, vertical = Tokens.Medium),
                ) {
                    Text("${chosen.size} items", style = MaterialTheme.typography.titleMedium)
                    Text(
                        formatBytes(context, chosen.sumOf { it.size }),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = Tokens.Small),
                        horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = { selectedIds = emptySet() }) { Text("Clear") }
                        OutlinedButton(
                            onClick = { movePicking = true },
                            enabled = !moving,
                        ) { Text("Move") }
                        IconButton(onClick = { askTrash(chosen) }, modifier = Modifier.size(Tokens.TouchTarget)) {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = "Move selected to Google Drive's trash",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            if (pickMode && onPick != null && conn == DriveConn.CONNECTED) {
                Box(
                    Modifier.fillMaxWidth().glassBar().onGloballyPositioned { coords ->
                        val bounds = coords.boundsInWindow()
                        recordPickerBounds {
                            copy(
                                footerTopPx = bounds.top.roundToInt(),
                                footerBottomPx = bounds.bottom.roundToInt(),
                            )
                        }
                    },
                ) {
                    // Keep the footer controls above system navigation even if DriveBrowser is hosted
                    // outside AppRoot's Scaffold; inside AppRoot, Scaffold already consumes it.
                    Column(
                        Modifier.fillMaxWidth().navigationBarsPadding()
                            .padding(horizontal = Tokens.Edge, vertical = Tokens.Medium),
                    ) {
                        if (offerPreset) Row(
                            Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { pin = !pin },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = pin, onCheckedChange = null)
                            Text(
                                "Keep this folder as a preset",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = Tokens.Medium, top = Tokens.Small, bottom = Tokens.Small),
                            )
                        }
                        Button(
                            onClick = { onPick(Destination(here.id, path), pin) },
                            modifier = Modifier.fillMaxWidth().padding(top = Tokens.Tiny).onGloballyPositioned { coords ->
                                val bounds = coords.boundsInWindow()
                                recordPickerBounds {
                                    copy(
                                        moveHereTopPx = bounds.top.roundToInt(),
                                        moveHereBottomPx = bounds.bottom.roundToInt(),
                                        moveHereHeightPx = bounds.height.roundToInt(),
                                    )
                                }
                            },
                        ) {
                            Text("Move here: ${here.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        val openFile = viewerFile
        if (openFile != null && !pickMode) {
            FullScreenOverlay {
                DriveFileViewer(
                    vm = vm,
                    file = openFile,
                    cachedThumb = thumbCache.get(driveMemoryCacheKey(accountKey, openFile)),
                    onClose = { viewerFile = null },
                    onOpenExternally = {
                        openInDrive(context, openFile.id)?.let { scope.launch { vm.messages.emit(it) } }
                    },
                    onDelete = { askTrash(listOf(openFile)) },
                )
            }
        }
        movePlan?.let { plan ->
            DriveMoveDialog(
                plan = plan,
                working = moving,
                onConfirm = { vm.startDriveMove(plan) },
                onDismiss = { if (!moving) movePlan = null },
            )
        }
        trashPlan?.let { plan ->
            DriveTrashDialog(
                plan = plan,
                working = trashing,
                onConfirm = { confirmTrash(plan) },
                onDismiss = { if (!trashing) trashPlan = null },
            )
        }
    }

    if (showNewFolder) {
        var name by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var failure by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!busy) showNewFolder = false },
            title = { Text("New folder in ${here.name}") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Folder name") },
                    singleLine = true,
                    enabled = !busy,
                    isError = failure != null,
                    supportingText = failure?.let { msg -> { Text(msg) } },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && !busy,
                    onClick = {
                        busy = true
                        failure = null
                        scope.launch {
                            try {
                                val created = vm.driveFolders.createFolder(name.trim(), here.id)
                                showNewFolder = false
                                stack = stack + Crumb(created.id, created.name)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                failure = describeError(e)
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text(if (busy) "Creating" else "Create") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { showNewFolder = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun rememberDriveThumb(
    vm: AppViewModel,
    file: DriveFile,
    accountKey: String,
    reload: Int,
    cache: LruCache<String, ImageBitmap>,
    onAuthExpired: () -> Unit,
): ThumbState {
    val kind = remember(file.mimeType) { classifyDriveFile(file.mimeType) }
    val shouldLoad = kind == DrivePreviewKind.IMAGE || kind == DrivePreviewKind.VIDEO || file.thumbnailLink != null
    val key = driveMemoryCacheKey(accountKey, file)
    var state by remember(key, reload) {
        val cached = cache.get(key)
        mutableStateOf(
            when {
                cached != null -> ThumbState.Ready(cached)
                !shouldLoad -> ThumbState.Unavailable
                else -> ThumbState.Loading
            }
        )
    }

    LaunchedEffect(key, file.thumbnailLink, reload) {
        if (!shouldLoad || state is ThumbState.Ready) return@LaunchedEffect
        state = ThumbState.Loading
        try {
            val bytes = withContext(Dispatchers.IO) {
                vm.driveFolders.loadThumbnail(file, THUMB_FETCH_EDGE_PX)
            }
            if (bytes == null || bytes.isEmpty()) {
                state = ThumbState.Unavailable
                return@LaunchedEffect
            }
            val bitmap = decodeSampledImageBitmap(bytes, THUMB_FETCH_EDGE_PX)
            if (bitmap != null) {
                cache.put(key, bitmap)
                state = ThumbState.Ready(bitmap)
            } else {
                state = ThumbState.Unavailable
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: AuthRequiredException) {
            onAuthExpired()
            state = ThumbState.Error
        } catch (_: Exception) {
            state = ThumbState.Error
        }
    }
    return state
}

@Composable
private fun DriveMediaGridCard(
    file: DriveFile,
    thumb: ThumbState,
    sizeLabel: String,
    /** Null when not selecting; otherwise whether this file is selected. */
    selected: Boolean? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val kind = classifyDriveFile(file.mimeType)
    val icon = fileTypeIcon(kind)
    GlassPanel(
        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        shape = Tokens.ThumbShape,
        onClick = onClick,
        onClickLabel = when (selected) {
            null -> "Open ${file.name}"
            true -> "Deselect ${file.name}"
            false -> "Select ${file.name}"
        },
        onLongClick = onLongClick,
        onLongClickLabel = "Select",
    ) {
        Box(Modifier.fillMaxSize().background(GlassPalette.Thumb)) {
            // Drawn above the thumbnail and captions (zIndex), so a selected photo always shows it.
            if (selected == true) {
                Box(
                    Modifier.fillMaxSize().zIndex(1f)
                        .border(Tokens.SelectionStroke, MaterialTheme.colorScheme.primary, Tokens.ThumbShape),
                )
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.align(Alignment.TopStart).zIndex(1f).padding(Tokens.Snug).size(Tokens.Icon)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
            when (thumb) {
                is ThumbState.Ready -> Image(
                    bitmap = thumb.bitmap,
                    contentDescription = file.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                ThumbState.Error -> Icon(
                    Icons.Outlined.BrokenImage,
                    contentDescription = file.name,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).size(Tokens.Section),
                )
                ThumbState.Loading, ThumbState.Unavailable -> Icon(
                    icon,
                    contentDescription = file.name,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).size(Tokens.Section),
                )
            }

            if (kind == DrivePreviewKind.VIDEO) {
                val badge = if (file.durationMillis > 0) formatDuration(file.durationMillis) else "Video"
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(Tokens.Snug)
                        .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f), Tokens.TagShape)
                        .padding(horizontal = Tokens.TagPaddingHorizontal, vertical = Tokens.Hairline),
                )
            }

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, GlassPalette.ThumbScrim)))
                    .padding(horizontal = Tokens.Small, vertical = Tokens.Snug),
            ) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${drivePreviewTypeLabel(file)}  ·  $sizeLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DriveRow(
    file: DriveFile,
    sizeLabel: String?,
    thumb: ThumbState?,
    padded: Boolean,
    onClick: (() -> Unit)?,
    selected: Boolean? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val icon = if (file.isFolder) Icons.Outlined.Folder else fileTypeIcon(classifyDriveFile(file.mimeType))
    val rowPadding = if (padded) {
        Modifier.fillMaxWidth().padding(horizontal = Tokens.Edge, vertical = Tokens.Tiny)
    } else {
        Modifier.fillMaxWidth()
    }
    GlassPanel(
        modifier = rowPadding,
        onClick = onClick,
        onClickLabel = when {
            file.isFolder || onClick == null -> null
            selected == true -> "Deselect ${file.name}"
            selected == false -> "Select ${file.name}"
            else -> "Open ${file.name}"
        },
        onLongClick = onLongClick,
        onLongClickLabel = "Select",
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Tokens.Gutter, vertical = Tokens.RowLoose),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected != null) {
                Icon(
                    if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = Tokens.Medium).size(Tokens.Icon),
                )
            }
            if (!file.isFolder && thumb is ThumbState.Ready) {
                Image(
                    bitmap = thumb.bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(Tokens.FolderThumb).clip(Tokens.ThumbShape).background(GlassPalette.Thumb),
                )
            } else {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(Tokens.Icon),
                    tint = if (file.isFolder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(Tokens.Gutter))
            Column(Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sizeLabel != null) Text(
                    sizeLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (file.isFolder || onClick != null) Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun fileTypeIcon(kind: DrivePreviewKind) = when (kind) {
    DrivePreviewKind.IMAGE -> Icons.Outlined.Image
    DrivePreviewKind.VIDEO -> Icons.Outlined.Videocam
    DrivePreviewKind.PDF -> Icons.Outlined.PictureAsPdf
    DrivePreviewKind.WORKSPACE_PDF_EXPORT -> Icons.Outlined.Description
    DrivePreviewKind.UNSUPPORTED -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

/**
 * Google Drive's own page for a file. It holds no token or account detail: whoever opens it is
 * asked by Google to sign in if needed.
 */
fun driveViewUrl(fileId: String): String = "https://drive.google.com/file/d/" + Uri.encode(fileId) + "/view"

/**
 * Opens a Drive file outside the app, in the Google Drive app if it is installed, otherwise in a
 * browser. Used only when the user explicitly requests external opening or for unsupported types.
 */
fun openInDrive(context: Context, fileId: String): String? {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(driveViewUrl(fileId))).addCategory(Intent.CATEGORY_BROWSABLE)
    return try {
        context.startActivity(intent)
        null
    } catch (e: ActivityNotFoundException) {
        "Nothing on this phone can open Google Drive links. Install Google Drive or a web browser."
    }
}

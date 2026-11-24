package com.drivelift.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.drivelift.app.data.LocalFileCategory
import com.drivelift.app.data.LocalFileOpener
import com.drivelift.app.data.LocalFileType
import com.drivelift.app.data.LocalFsEntry
import com.drivelift.app.data.buildBreadcrumbs
import com.drivelift.app.data.localFileTypeLabel
import com.drivelift.app.data.matchesQuery
import com.drivelift.app.transfer.describeError
import kotlinx.coroutines.CancellationException

/**
 * Full All-Files Local Explorer (`/storage/emulated/0`) with MANAGE_EXTERNAL_STORAGE prompt,
 * quick category filters (Documents, Downloads, APKs & ZIPs, Photos, Videos), clickable folder
 * breadcrumbs, crisp file-type icons, and native tap-to-open via FileProvider.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalFileExplorerScreen(
    vm: AppViewModel,
    initialCategory: LocalFileCategory = LocalFileCategory.ALL,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val explorer = vm.localExplorer
    val rootPath = remember(explorer) { explorer.rootPath() }

    var hasAccess by remember(explorer) { mutableStateOf(explorer.hasAllFilesAccess()) }
    var categoryName by rememberSaveable(initialCategory) { mutableStateOf(initialCategory.name) }
    val selectedCategory = remember(categoryName) {
        LocalFileCategory.entries.firstOrNull { it.name == categoryName } ?: LocalFileCategory.ALL
    }
    var currentPath by rememberSaveable(rootPath) { mutableStateOf(rootPath) }
    var query by rememberSaveable { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<LocalFsEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val updated = explorer.hasAllFilesAccess()
        if (updated != hasAccess) {
            hasAccess = updated
            if (updated) reload++
        }
    }

    val breadcrumbs = remember(rootPath, currentPath) { buildBreadcrumbs(rootPath, currentPath) }
    val canStepUp = hasAccess && selectedCategory == LocalFileCategory.ALL && breadcrumbs.size > 1

    fun navigateUp() {
        if (canStepUp) {
            currentPath = breadcrumbs[breadcrumbs.size - 2].path
        } else {
            onBack()
        }
    }

    BackHandler(enabled = canStepUp) { navigateUp() }

    LaunchedEffect(hasAccess, selectedCategory, currentPath, reload) {
        if (!hasAccess) {
            entries = null
            error = null
            return@LaunchedEffect
        }
        entries = null
        error = null
        try {
            entries = if (selectedCategory == LocalFileCategory.ALL) {
                explorer.listDirectory(currentPath)
            } else {
                explorer.listCategory(selectedCategory)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = describeError(e)
        }
    }

    val shown = remember(entries, query) {
        entries?.filter { matchesQuery(it.name, query) }
    }

    Column(Modifier.fillMaxSize().testTag("local-explorer-screen")) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
            ),
            title = {
                Column {
                    Text(
                        if (selectedCategory == LocalFileCategory.ALL) breadcrumbs.last().label else selectedCategory.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when {
                            !hasAccess -> "All-Files access needed"
                            selectedCategory == LocalFileCategory.ALL -> currentPath
                            else -> shown?.let { "${it.size} files" } ?: "Scanning storage"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = ::navigateUp) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = if (canStepUp) "Up one folder" else "Back to phone",
                    )
                }
            },
            actions = {
                if (hasAccess) {
                    IconButton(onClick = { reload++ }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Reload files")
                    }
                }
            },
        )

        if (!hasAccess) {
            AllFilesPermissionPrompt(
                onGrant = {
                    LocalFileOpener.openAllFilesPermissionSettings(context)?.let { vm.messages.tryEmit(it) }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(Tokens.Edge),
            )
            return@Column
        }

        // Quick category filter strip.
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("explorer-category-row"),
            contentPadding = PaddingValues(horizontal = Tokens.Edge, vertical = Tokens.Tiny),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
        ) {
            items(LocalFileCategory.entries, key = { it.name }) { category ->
                val isSelected = category == selectedCategory
                ExplorerCategoryChip(
                    label = category.label,
                    selected = isSelected,
                    onClick = {
                        categoryName = category.name
                        if (category == LocalFileCategory.ALL && currentPath.isBlank()) {
                            currentPath = rootPath
                        }
                    },
                    modifier = Modifier.testTag("explorer-cat-${category.name.lowercase()}"),
                )
            }
        }

        // Live name search bar.
        ExplorerSearchBar(
            query = query,
            onQueryChange = { query = it },
            modifier = Modifier.padding(horizontal = Tokens.Edge, vertical = Tokens.Tiny),
        )

        // Clickable breadcrumbs for folder-tree browsing.
        if (selectedCategory == LocalFileCategory.ALL) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("explorer-breadcrumbs"),
                contentPadding = PaddingValues(horizontal = Tokens.Edge, vertical = Tokens.Tiny),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                itemsIndexed(breadcrumbs, key = { _, crumb -> crumb.path }) { index, crumb ->
                    val isLast = index == breadcrumbs.lastIndex
                    if (index > 0) {
                        Icon(
                            Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(Tokens.IconSmall),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minHeight = Tokens.TouchTarget)
                            .clip(Tokens.PillShape)
                            .clickable(enabled = !isLast, role = Role.Button) { currentPath = crumb.path }
                            .padding(horizontal = Tokens.Small),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            crumb.label,
                            style = if (isLast) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                            color = if (isLast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val list = shown
            when {
                error != null -> Column(Modifier.padding(Tokens.Edge)) {
                    Text("Could not read this folder", style = MaterialTheme.typography.titleMedium)
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
                    when (selectedCategory) {
                        LocalFileCategory.ALL -> "This folder is empty."
                        LocalFileCategory.DOCUMENTS -> "No documents found on this phone."
                        LocalFileCategory.DOWNLOADS -> "No files found in Downloads."
                        LocalFileCategory.APKS_ZIPS -> "No APK or ZIP files found on this phone."
                        LocalFileCategory.PHOTOS -> "No photos found on this phone."
                        LocalFileCategory.VIDEOS -> "No videos found on this phone."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Tokens.Edge),
                )
                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("explorer-list"),
                    contentPadding = PaddingValues(bottom = Tokens.Edge),
                ) {
                    items(list, key = { it.path }) { entry ->
                        LocalFileEntryRow(
                            entry = entry,
                            showParentPath = selectedCategory != LocalFileCategory.ALL,
                            onClick = {
                                if (entry.isDirectory) {
                                    categoryName = LocalFileCategory.ALL.name
                                    currentPath = entry.path
                                    query = ""
                                } else {
                                    LocalFileOpener.openEntry(context, entry)?.let { msg ->
                                        vm.messages.tryEmit(msg)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AllFilesPermissionPrompt(
    onGrant: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        GlassSurface(
            level = GlassLevel.Hero,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = Tokens.ReadableWidth)
                .testTag("explorer-permission-card"),
        ) {
            Column(Modifier.padding(Tokens.Edge)) {
                Box(
                    modifier = Modifier
                        .size(Tokens.TouchTarget)
                        .clip(Tokens.concentricShape(Tokens.HeroRadius, Tokens.Edge))
                        .background(GlassPalette.Well)
                        .border(Tokens.Hairline, GlassPalette.WellEdge, Tokens.concentricShape(Tokens.HeroRadius, Tokens.Edge)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(Tokens.Gutter))
                Text("All-Files Access Required", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(Tokens.Small))
                Text(
                    "Allow DriveLift to read /storage/emulated/0 so you can browse Documents " +
                        "(PDF, Excel, PowerPoint, Word), Downloads, APKs, ZIPs, Photos, and Videos.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Tokens.Gutter))
                Button(
                    onClick = onGrant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = Tokens.TouchTarget)
                        .testTag("explorer-grant-permission"),
                ) {
                    Text("Allow All-Files Access")
                }
            }
        }
    }
}

@Composable
internal fun ExplorerCategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else GlassPalette.WellEdge
    val bgColor = if (selected) MaterialTheme.colorScheme.primaryContainer else GlassPalette.Well
    val textColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = Tokens.TouchTarget)
            .semantics { this.selected = selected }
            .clip(Tokens.PillShape)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(vertical = Tokens.Tiny),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .clip(Tokens.PillShape)
                .background(bgColor, Tokens.PillShape)
                .border(Tokens.Hairline, borderColor, Tokens.PillShape)
                .padding(horizontal = Tokens.RowLoose, vertical = Tokens.Small),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ExplorerSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .testTag("explorer-search"),
        singleLine = true,
        placeholder = { Text("Filter files by name", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Outlined.Close, contentDescription = "Clear search")
                }
            }
        } else null,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        shape = MaterialTheme.shapes.extraLarge,
    )
}

@Composable
private fun LocalFileEntryRow(
    entry: LocalFsEntry,
    showParentPath: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val icon = localFileIcon(entry.fileType)
    val iconTint = localFileIconTint(entry.fileType)
    val typeLabel = localFileTypeLabel(entry.fileType, entry.name)
    val wellShape = Tokens.concentricShape(Tokens.PanelRadius, Tokens.Medium)

    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Edge, vertical = Tokens.Tiny),
        onClick = onClick,
        onClickLabel = if (entry.isDirectory) "Open folder ${entry.name}" else "Open ${entry.name}",
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = Tokens.TouchTarget)
                .padding(horizontal = Tokens.Medium, vertical = Tokens.Row),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(Tokens.DriveFolderIconWell)
                    .clip(wellShape)
                    .background(GlassPalette.Well)
                    .border(Tokens.Hairline, GlassPalette.WellEdge, wellShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(Tokens.Icon))
            }
            Column(Modifier.weight(1f).padding(horizontal = Tokens.Medium)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = if (entry.isDirectory) {
                    if (entry.itemCount == 1) "1 item" else "${entry.itemCount} items"
                } else {
                    buildString {
                        append(typeLabel)
                        append("  \u00B7  ")
                        append(formatBytes(context, entry.sizeBytes))
                        if (showParentPath && entry.relativeParent.isNotBlank()) {
                            append("  \u00B7  ")
                            append(entry.relativeParent)
                        }
                    }
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                if (entry.isDirectory) Icons.Outlined.ChevronRight else Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Tokens.IconSmall),
            )
        }
    }
}

private fun localFileIcon(type: LocalFileType): ImageVector = when (type) {
    LocalFileType.FOLDER -> Icons.Outlined.Folder
    LocalFileType.PDF -> Icons.Outlined.PictureAsPdf
    LocalFileType.SPREADSHEET -> Icons.Outlined.TableChart
    LocalFileType.PRESENTATION -> Icons.Outlined.Slideshow
    LocalFileType.DOCUMENT -> Icons.Outlined.Description
    LocalFileType.ARCHIVE -> Icons.Outlined.FolderZip
    LocalFileType.APK -> Icons.Outlined.Android
    LocalFileType.IMAGE -> Icons.Outlined.Image
    LocalFileType.VIDEO -> Icons.Outlined.Videocam
    LocalFileType.AUDIO -> Icons.Outlined.MusicNote
    LocalFileType.OTHER -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

@Composable
private fun localFileIconTint(type: LocalFileType): Color = when (type) {
    LocalFileType.FOLDER, LocalFileType.DOCUMENT, LocalFileType.IMAGE -> MaterialTheme.colorScheme.primary
    LocalFileType.SPREADSHEET, LocalFileType.APK, LocalFileType.AUDIO -> MaterialTheme.colorScheme.tertiary
    LocalFileType.PRESENTATION -> GlassPalette.HudAmber
    LocalFileType.PDF, LocalFileType.ARCHIVE, LocalFileType.VIDEO -> MaterialTheme.colorScheme.secondary
    LocalFileType.OTHER -> MaterialTheme.colorScheme.onSurfaceVariant
}

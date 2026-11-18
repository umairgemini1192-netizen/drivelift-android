package com.drivelift.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.style.TextOverflow
import com.drivelift.app.data.FileSort

/**
 * Search by name within the folder shown, and the folder's sort choice (file manager, issue #36).
 * Only the listing on screen is searched; nothing is read from elsewhere.
 */
@Composable
internal fun FolderFindBar(
    query: String,
    onQueryChange: (String) -> Unit,
    sort: FileSort,
    onSortChange: (FileSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    var sortOpen by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().padding(horizontal = Tokens.Edge, vertical = Tokens.Tiny),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f).testTag("folder-search"),
            singleLine = true,
            placeholder = { Text("Search this folder", maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
        Box {
            IconButton(onClick = { sortOpen = true }, modifier = Modifier.size(Tokens.TouchTarget)) {
                Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = "Sort: ${sort.label}")
            }
            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                FileSort.entries.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(choice.label) },
                        leadingIcon = {
                            if (choice == sort) Icon(Icons.Outlined.Check, contentDescription = "Current")
                        },
                        onClick = {
                            onSortChange(choice)
                            sortOpen = false
                        },
                    )
                }
            }
        }
    }
}

/** Shown in place of a listing when the folder has items but none match the search. */
@Composable
internal fun NoSearchMatches(query: String, modifier: Modifier = Modifier) {
    Text(
        "Nothing in this folder matches “${query.trim()}”.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(Tokens.Edge),
    )
}

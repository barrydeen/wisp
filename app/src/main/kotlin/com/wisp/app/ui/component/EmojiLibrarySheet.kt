package com.wisp.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.wisp.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiLibrarySheet(
    currentEmojis: List<String>,
    onAddEmojis: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var selectedEmojis by remember { mutableStateOf(emptySet<String>()) }
    val categories = remember { EmojiData.categories }
    val currentEmojiSet = remember(currentEmojis) { currentEmojis.toSet() }
    var selectedTabIndex by remember { mutableStateOf(0) }
    val gridState = rememberLazyGridState()
    var showKeyboardDialog by remember { mutableStateOf(false) }

    // Build a flat list with category headers for the grid
    val gridItems = remember(categories) {
        buildList {
            categories.forEachIndexed { catIndex, category ->
                add(GridEntry.Header(catIndex, category.name))
                category.emojis.forEachIndexed { emojiIndex, emoji ->
                    add(GridEntry.Emoji(catIndex, emojiIndex, emoji))
                }
            }
        }
    }

    // Track which category header indices map to grid positions
    val categoryGridPositions = remember(gridItems) {
        gridItems.mapIndexedNotNull { index, item ->
            if (item is GridEntry.Header) item.categoryIndex to index else null
        }.toMap()
    }

    // Update selected tab based on scroll position
    val currentCategoryIndex by remember {
        derivedStateOf {
            val firstVisible = gridState.firstVisibleItemIndex
            var currentCat = 0
            for ((catIdx, gridPos) in categoryGridPositions) {
                if (gridPos <= firstVisible) currentCat = catIdx
            }
            currentCat
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Category tabs
            ScrollableTabRow(
                selectedTabIndex = currentCategoryIndex,
                edgePadding = 8.dp,
                divider = {}
            ) {
                categories.forEachIndexed { index, category ->
                    Tab(
                        selected = currentCategoryIndex == index,
                        onClick = {
                            selectedTabIndex = index
                            val gridPos = categoryGridPositions[index] ?: 0
                            scope.launch { gridState.scrollToItem(gridPos) }
                        },
                        text = { Text(category.icon, fontSize = 20.sp) }
                    )
                }
            }

            // Emoji grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(8),
                state = gridState,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(
                    items = gridItems,
                    key = { item ->
                        when (item) {
                            is GridEntry.Header -> "header_${item.categoryIndex}"
                            is GridEntry.Emoji -> "emoji_${item.catIndex}_${item.emojiIndex}"
                        }
                    },
                    span = { item ->
                        when (item) {
                            is GridEntry.Header -> GridItemSpan(8)
                            is GridEntry.Emoji -> GridItemSpan(1)
                        }
                    }
                ) { item ->
                    when (item) {
                        is GridEntry.Header -> {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                            )
                        }
                        is GridEntry.Emoji -> {
                            val isAlreadyAdded = item.emoji in currentEmojiSet
                            val isSelected = item.emoji in selectedEmojis
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .then(
                                        if (isAlreadyAdded) {
                                            Modifier.background(
                                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                            )
                                        } else if (isSelected) {
                                            Modifier.background(
                                                MaterialTheme.colorScheme.primaryContainer
                                            )
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .clickable(enabled = !isAlreadyAdded) {
                                        selectedEmojis = if (isSelected) {
                                            selectedEmojis - item.emoji
                                        } else {
                                            selectedEmojis + item.emoji
                                        }
                                    }
                            ) {
                                Text(
                                    text = item.emoji,
                                    fontSize = 24.sp,
                                    textAlign = TextAlign.Center,
                                    color = if (isAlreadyAdded) {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                                if (isAlreadyAdded) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(8.dp)
                                            .background(
                                                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                                CircleShape
                                            )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Pending selected emojis row
            if (selectedEmojis.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    selectedEmojis.forEach { emoji ->
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .clickable { selectedEmojis = selectedEmojis - emoji }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(emoji, fontSize = 22.sp)
                        }
                    }
                }
            }

            // Bottom bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { showKeyboardDialog = true }) {
                    Text(stringResource(R.string.emoji_add_from_keyboard))
                }
                Button(
                    onClick = {
                        onAddEmojis(selectedEmojis.toList())
                        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                    },
                    enabled = selectedEmojis.isNotEmpty()
                ) {
                    Text(
                        if (selectedEmojis.isEmpty()) stringResource(R.string.emoji_done_format, 0)
                        else stringResource(R.string.emoji_done_format, selectedEmojis.size)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showKeyboardDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showKeyboardDialog = false },
            title = { Text(stringResource(R.string.emoji_add_custom)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.emoji_add_from_keyboard_hint)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val emoji = text.trim()
                        if (emoji.isNotEmpty()) {
                            selectedEmojis = selectedEmojis + emoji
                        }
                        showKeyboardDialog = false
                    },
                    enabled = text.isNotBlank()
                ) { Text(stringResource(R.string.emoji_dialog_add)) }
            },
            dismissButton = {
                TextButton(onClick = { showKeyboardDialog = false }) { Text(stringResource(R.string.emoji_dialog_cancel)) }
            }
        )
    }
}

private sealed class GridEntry {
    data class Header(val categoryIndex: Int, val name: String) : GridEntry()
    data class Emoji(val catIndex: Int, val emojiIndex: Int, val emoji: String) : GridEntry()
}

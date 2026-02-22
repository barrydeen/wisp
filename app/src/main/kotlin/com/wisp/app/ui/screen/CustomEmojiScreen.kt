package com.wisp.app.ui.screen

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.wisp.app.nostr.CustomEmoji
import com.wisp.app.nostr.EmojiSet
import com.wisp.app.repo.CustomEmojiRepository

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CustomEmojiScreen(
    customEmojiRepo: CustomEmojiRepository,
    onBack: () -> Unit,
    onCreateSet: (String, List<CustomEmoji>) -> Unit,
    onUpdateSet: (String, String, List<CustomEmoji>) -> Unit,
    onDeleteSet: (String) -> Unit,
    onPublishEmojiList: (List<CustomEmoji>, List<String>) -> Unit,
    onAddSet: (String, String) -> Unit,
    onRemoveSet: (String, String) -> Unit,
    onUploadEmoji: (ContentResolver, Uri, (String) -> Unit) -> Unit = { _, _, _ -> }
) {
    val unicodeEmojis by customEmojiRepo.unicodeEmojis.collectAsState()
    val resolvedEmojis by customEmojiRepo.resolvedEmojis.collectAsState()
    val ownSets by customEmojiRepo.ownSets.collectAsState()
    val userEmojiList by customEmojiRepo.userEmojiList.collectAsState()

    var showAddUnicodeDialog by remember { mutableStateOf(false) }
    var showCreateSetDialog by remember { mutableStateOf(false) }
    var showAddDirectEmojiDialog by remember { mutableStateOf(false) }
    var editingSet by remember { mutableStateOf<EmojiSet?>(null) }
    var managingEmojis by remember { mutableStateOf(false) }
    var removedShortcodes by remember { mutableStateOf(emptySet<String>()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Custom Emojis") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateSetDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Create Emoji Set")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Quick Reactions section
            item {
                Text(
                    text = "Quick Reactions",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                Text(
                    text = "Unicode emojis shown in the reaction picker",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    unicodeEmojis.forEach { emoji ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clickable {
                                val updated = unicodeEmojis.toMutableList()
                                updated.remove(emoji)
                                customEmojiRepo.setUnicodeEmojis(updated)
                            }
                        ) {
                            Text(
                                text = emoji,
                                fontSize = 24.sp,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.clickable { showAddUnicodeDialog = true }
                    ) {
                        Text(
                            text = "+",
                            fontSize = 24.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }

            // Direct Custom Emojis section
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Custom Image Emojis",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "Image emojis from your emoji list (kind 10030)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val directEmojis = userEmojiList?.emojis ?: emptyList()
                    directEmojis.forEach { emoji ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable {
                                val updated = directEmojis.toMutableList()
                                updated.remove(emoji)
                                onPublishEmojiList(updated, userEmojiList?.setReferences ?: emptyList())
                            }
                        ) {
                            AsyncImage(
                                model = emoji.url,
                                contentDescription = emoji.shortcode,
                                modifier = Modifier.size(32.dp)
                            )
                            Text(
                                text = ":${emoji.shortcode}:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.clickable { showAddDirectEmojiDialog = true }
                    ) {
                        Text(
                            text = "+",
                            fontSize = 24.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }

            // All Resolved Emojis section (from sets and direct emojis)
            if (resolvedEmojis.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Available Custom Emojis",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "All custom emojis available in your reaction picker",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!managingEmojis) {
                            TextButton(onClick = {
                                managingEmojis = true
                                removedShortcodes = emptySet()
                            }) {
                                Text("Manage")
                            }
                        }
                    }
                }
                item {
                    val displayEmojis = if (managingEmojis) {
                        resolvedEmojis.filter { it.key !in removedShortcodes }
                    } else resolvedEmojis

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        displayEmojis.forEach { (shortcode, url) ->
                            Box {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    AsyncImage(
                                        model = url,
                                        contentDescription = shortcode,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Text(
                                        text = ":$shortcode:",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (managingEmojis) {
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier
                                            .size(18.dp)
                                            .align(Alignment.TopEnd)
                                            .offset(x = 4.dp, y = (-4).dp)
                                            .clickable {
                                                removedShortcodes = removedShortcodes + shortcode
                                            }
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Remove",
                                            tint = MaterialTheme.colorScheme.onError,
                                            modifier = Modifier.size(14.dp).padding(2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (managingEmojis) {
                    item {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            TextButton(onClick = {
                                managingEmojis = false
                                removedShortcodes = emptySet()
                            }) {
                                Text("Cancel")
                            }
                            Button(
                                onClick = {
                                    // Rebuild: surviving emojis become direct entries,
                                    // set references are dropped so removed set emojis don't reappear
                                    val remaining = resolvedEmojis.filter { it.key !in removedShortcodes }
                                    val newDirectEmojis = remaining.map { (sc, url) -> CustomEmoji(sc, url) }
                                    onPublishEmojiList(newDirectEmojis, emptyList())
                                    managingEmojis = false
                                    removedShortcodes = emptySet()
                                },
                                enabled = removedShortcodes.isNotEmpty()
                            ) {
                                Text("Save (${removedShortcodes.size} removed)")
                            }
                        }
                    }
                }
            }

            // My Emoji Sets section
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "My Emoji Sets",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            if (ownSets.isEmpty()) {
                item {
                    Text(
                        text = "No emoji sets yet. Tap + to create one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(ownSets, key = { it.dTag }) { set ->
                EmojiSetCard(
                    set = set,
                    onEdit = { editingSet = set },
                    onDelete = { onDeleteSet(set.dTag) }
                )
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    if (showAddUnicodeDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddUnicodeDialog = false },
            title = { Text("Add Unicode Emoji") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Paste an emoji") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        customEmojiRepo.addUnicodeEmoji(text.trim())
                        showAddUnicodeDialog = false
                    },
                    enabled = text.isNotBlank()
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddUnicodeDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showAddDirectEmojiDialog) {
        var shortcode by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var uploading by remember { mutableStateOf(false) }
        val context = LocalContext.current
        val emojiPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            if (uri != null) {
                uploading = true
                onUploadEmoji(context.contentResolver, uri) { uploadedUrl ->
                    url = uploadedUrl
                    uploading = false
                }
            }
        }
        AlertDialog(
            onDismissRequest = { showAddDirectEmojiDialog = false },
            title = { Text("Add Custom Emoji") },
            text = {
                Column {
                    OutlinedTextField(
                        value = shortcode,
                        onValueChange = { shortcode = it.replace(Regex("[^a-zA-Z0-9_]"), "") },
                        placeholder = { Text("Shortcode (e.g. pepe)") },
                        singleLine = true
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            placeholder = { Text("Image URL") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                emojiPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            enabled = !uploading
                        ) {
                            if (uploading) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Outlined.FileUpload, contentDescription = "Upload image")
                            }
                        }
                    }
                    if (url.isNotBlank()) {
                        AsyncImage(
                            model = url,
                            contentDescription = "Preview",
                            modifier = Modifier
                                .size(48.dp)
                                .padding(top = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val directEmojis = (userEmojiList?.emojis ?: emptyList()).toMutableList()
                        directEmojis.add(CustomEmoji(shortcode.trim(), url.trim()))
                        onPublishEmojiList(directEmojis, userEmojiList?.setReferences ?: emptyList())
                        showAddDirectEmojiDialog = false
                    },
                    enabled = shortcode.isNotBlank() && url.isNotBlank()
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDirectEmojiDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showCreateSetDialog) {
        CreateEmojiSetDialog(
            onConfirm = { name, emojis ->
                onCreateSet(name, emojis)
                showCreateSetDialog = false
            },
            onDismiss = { showCreateSetDialog = false }
        )
    }

    if (editingSet != null) {
        EditEmojiSetDialog(
            set = editingSet!!,
            onConfirm = { dTag, title, emojis ->
                onUpdateSet(dTag, title, emojis)
                editingSet = null
            },
            onDismiss = { editingSet = null },
            onUploadEmoji = onUploadEmoji
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmojiSetCard(
    set: EmojiSet,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = set.title,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "${set.emojis.size} emojis",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand"
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Edit",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            if (expanded) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    set.emojis.forEach { emoji ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            AsyncImage(
                                model = emoji.url,
                                contentDescription = emoji.shortcode,
                                modifier = Modifier.size(32.dp)
                            )
                            Text(
                                text = ":${emoji.shortcode}:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateEmojiSetDialog(
    onConfirm: (String, List<CustomEmoji>) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Emoji Set") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Set name") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), emptyList()) },
                enabled = name.isNotBlank()
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun EditEmojiSetDialog(
    set: EmojiSet,
    onConfirm: (String, String, List<CustomEmoji>) -> Unit,
    onDismiss: () -> Unit,
    onUploadEmoji: (ContentResolver, Uri, (String) -> Unit) -> Unit = { _, _, _ -> }
) {
    var title by remember { mutableStateOf(set.title) }
    var shortcode by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var emojis by remember { mutableStateOf(set.emojis.toMutableList()) }
    var uploading by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val editPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            uploading = true
            onUploadEmoji(context.contentResolver, uri) { uploadedUrl ->
                url = uploadedUrl
                uploading = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit: ${set.title}") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Set name") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Text("Add emoji:", style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = shortcode,
                        onValueChange = { shortcode = it.replace(Regex("[^a-zA-Z0-9_]"), "") },
                        placeholder = { Text("shortcode") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(4.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        placeholder = { Text("URL") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            editPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        enabled = !uploading
                    ) {
                        if (uploading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.FileUpload, contentDescription = "Upload image")
                        }
                    }
                    IconButton(
                        onClick = {
                            if (shortcode.isNotBlank() && url.isNotBlank()) {
                                emojis = (emojis + CustomEmoji(shortcode.trim(), url.trim())).toMutableList()
                                shortcode = ""
                                url = ""
                            }
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add")
                    }
                }
                Spacer(Modifier.height(8.dp))
                emojis.forEach { emoji ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 2.dp)
                    ) {
                        AsyncImage(
                            model = emoji.url,
                            contentDescription = emoji.shortcode,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = ":${emoji.shortcode}:",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            emojis = emojis.toMutableList().also { it.remove(emoji) }
                        }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Remove",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(set.dTag, title.trim(), emojis) },
                enabled = title.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

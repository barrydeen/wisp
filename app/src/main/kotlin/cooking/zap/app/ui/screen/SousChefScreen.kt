package cooking.zap.app.ui.screen

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cooking.zap.app.souschef.SousChefMode
import cooking.zap.app.souschef.detectMode
import cooking.zap.app.souschef.validateStagedImage
import cooking.zap.app.ui.component.SousChefPurple
import cooking.zap.app.ui.component.recipeBody
import cooking.zap.app.viewmodel.SousChefViewModel
import cooking.zap.app.viewmodel.SousChefViewModel.State

/**
 * Sous Chef import screen — unified input (Phase 2 of web parity): one
 * multi-line field plus a staged-image slot, mode auto-detected live via
 * [detectMode]. URL mode extracts end-to-end as before; IMAGE and TEXT modes
 * are detected and surfaced but their extraction is Phase 3, so the CTA stays
 * disabled with a coming-soon note. Preview renders read-only via the shared
 * [recipeBody].
 *
 * NOTE (pre-ship): the drawer/icon use a placeholder mark — port the real
 * Sous Chef SVG for symbol parity with the web before a user-facing release.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SousChefScreen(
    viewModel: SousChefViewModel,
    onImport: (String) -> Unit,
    onSave: () -> Unit,
    onSaved: (author: String, dTag: String) -> Unit,
    canSign: Boolean,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val saveState by viewModel.saveState.collectAsState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    // Only the Uri is staged — bytes are read/encoded at extraction time (Phase 3).
    var stagedImageUri by rememberSaveable { mutableStateOf<String?>(null) }
    var stagingError by rememberSaveable { mutableStateOf<String?>(null) }

    val loading = state == State.Loading
    val mode = detectMode(input, hasImage = stagedImageUri != null)

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val (mime, size) = queryImageMeta(context.contentResolver, uri)
            val error = validateStagedImage(mime, size)
            if (error == null) {
                stagedImageUri = uri.toString()
                stagingError = null
            } else {
                stagingError = error
            }
        }
    }

    // Optimistic: navigate to the just-published (locally-cached) recipe.
    LaunchedEffect(saveState) {
        (saveState as? SousChefViewModel.SaveState.Saved)?.let { onSaved(it.author, it.dTag) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Sous Chef = sparkle in the web's purple accent (#a855f7),
                        // mirroring the web page header's 32px purple sparkle.
                        Icon(
                            Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = SousChefPurple,
                            modifier = Modifier.size(32.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Sous Chef")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "Turn photos, links, or pasted text into ready-to-post recipes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "A little extra help in the kitchen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                stagedImageUri?.let { uriString ->
                    Box(Modifier.fillMaxWidth()) {
                        AsyncImage(
                            model = uriString,
                            contentDescription = "Staged recipe photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .clip(RoundedCornerShape(12.dp)),
                        )
                        IconButton(
                            onClick = {
                                stagedImageUri = null
                                stagingError = null
                            },
                            enabled = !loading,
                            modifier = Modifier.align(Alignment.TopEnd),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove photo",
                                tint = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Paste a recipe URL, paste recipe text, or add a photo…") },
                    minLines = 3,
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SousChefPurple,
                    ),
                    trailingIcon = {
                        IconButton(
                            onClick = { clipboard.getText()?.text?.let { input = it } },
                            enabled = !loading,
                        ) {
                            Icon(Icons.Outlined.ContentPaste, contentDescription = "Paste")
                        }
                    },
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        enabled = !loading,
                    ) {
                        Icon(
                            Icons.Outlined.AddPhotoAlternate,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Add photo")
                    }
                    Button(
                        onClick = { onImport(input.trim()) },
                        enabled = mode == SousChefMode.URL && !loading,
                        colors = ButtonDefaults.buttonColors(
                            // Web parity: white on the Sous Chef purple — passes
                            // WCAG AA for the button's large/bold label.
                            containerColor = SousChefPurple,
                            contentColor = Color.White,
                        ),
                    ) { Text(if (mode == null) "Get Recipe" else "🤖 Get Recipe") }
                }
                // Phase 3 ships image/text extraction; until then detection is
                // surfaced but the CTA stays disabled.
                if (mode == SousChefMode.IMAGE || mode == SousChefMode.TEXT) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Image and text imports are coming to Android soon.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                stagingError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            HorizontalDivider()

            when (val s = state) {
                State.Idle -> Unit
                State.Loading -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
                is State.Error -> Box(
                    Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = s.message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                is State.Preview -> RecipePreview(
                    preview = s,
                    canSign = canSign,
                    saveState = saveState,
                    onSave = onSave,
                )
            }
        }
    }
}

/**
 * MIME type + size for a picked image, straight from the provider. Size is
 * null when the provider doesn't report one — [validateStagedImage] (pure,
 * unit-tested) decides what passes.
 */
private fun queryImageMeta(resolver: ContentResolver, uri: Uri): Pair<String?, Long?> {
    val mime = resolver.getType(uri)
    var size: Long? = null
    resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) {
            size = cursor.getLong(idx)
        }
    }
    return mime to size
}

@Composable
private fun RecipePreview(
    preview: State.Preview,
    canSign: Boolean,
    saveState: SousChefViewModel.SaveState,
    onSave: () -> Unit,
) {
    var multiplier by remember(preview.recipe) { mutableStateOf(1.0) }
    val hasImage = preview.recipe.image?.isNotBlank() == true
    val saving = saveState is SousChefViewModel.SaveState.Saving

    LazyColumn(Modifier.fillMaxSize()) {
        // Read-only: no byline/engagement slots — an imported recipe has no event yet.
        recipeBody(
            recipe = preview.recipe,
            multiplier = multiplier,
            onMultiplierChange = { multiplier = it },
        )
        item(key = "save") {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))
                // Block reasons surfaced explicitly (not a silent disabled button).
                val reason = when {
                    !canSign -> "Sign in to save this recipe to your account."
                    !hasImage -> "Add an image to publish this recipe."
                    else -> null
                }
                reason?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                (saveState as? SousChefViewModel.SaveState.Error)?.let {
                    Text(
                        text = it.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = onSave,
                    enabled = canSign && hasImage && !saving,
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Save to my recipes")
                    }
                }
            }
        }
        item(key = "footer") { Spacer(Modifier.height(32.dp)) }
    }
}

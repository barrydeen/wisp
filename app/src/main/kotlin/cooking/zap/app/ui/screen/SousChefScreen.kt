package cooking.zap.app.ui.screen

import android.content.ContentResolver
import android.content.Intent
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
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
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
 * Sous Chef import screen — unified input, mode auto-detected live via
 * [detectMode]. URL import is free for everyone; image/text extraction
 * (Phase 3) is member-gated with the web's upsell behavior: the CTA tap is
 * the conversion event (sign-in for watch-only accounts, the membership
 * page for non-members, extraction for members). Preview renders read-only
 * via the shared [recipeBody].
 *
 * NOTE (pre-ship): the drawer/icon use a placeholder mark — port the real
 * Sous Chef SVG for symbol parity with the web before a user-facing release.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SousChefScreen(
    viewModel: SousChefViewModel,
    onImport: (String) -> Unit,
    onImportImage: (Uri) -> Unit,
    onImportText: (String) -> Unit,
    onRefreshMembership: () -> Unit,
    onSignIn: () -> Unit,
    membershipLinkoutEnabled: Boolean,
    onSave: () -> Unit,
    onSaved: (author: String, dTag: String) -> Unit,
    canSign: Boolean,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val saveState by viewModel.saveState.collectAsState()
    val membership by viewModel.membership.collectAsState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    // Only the Uri is staged — bytes are read/encoded at extraction time.
    var stagedImageUri by rememberSaveable { mutableStateOf<String?>(null) }
    var stagingError by rememberSaveable { mutableStateOf<String?>(null) }
    var showMembershipDialog by rememberSaveable { mutableStateOf(false) }

    val loading = state is State.Loading
    val mode = detectMode(input, hasImage = stagedImageUri != null)
    // Unknown (null) counts as non-member for banner DISPLAY only. The CTA
    // tap distinguishes the two: a KNOWN non-member gets the membership
    // link-out (conversion event), while unknown proceeds to extraction and
    // lets the server's 403 be the authoritative answer (web parity).
    val isMember = membership?.isActive == true
    val knownNonMember = membership != null && !isMember

    // Keyed on canSign so a sign-in state change re-triggers the fetch (the
    // ViewModel no-ops while the pubkey is unavailable rather than burning
    // its once-per-entry flag).
    LaunchedEffect(canSign) { onRefreshMembership() }

    val openMembership: () -> Unit = {
        if (membershipLinkoutEnabled) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://zap.cooking/membership"))
            )
        } else {
            showMembershipDialog = true
        }
    }

    if (showMembershipDialog) {
        AlertDialog(
            onDismissRequest = { showMembershipDialog = false },
            confirmButton = {
                TextButton(onClick = { showMembershipDialog = false }) { Text("OK") }
            },
            text = { Text("Sous Chef image and text imports are part of Zap Cooking membership.") },
        )
    }

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
                // Upsell banner (web parity): signed in, not (known to be) a
                // member, and nothing extracted yet.
                if (canSign && !isMember && state !is State.Preview) {
                    Surface(
                        color = SousChefPurple.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = buildAnnotatedString {
                                withStyle(
                                    SpanStyle(
                                        color = SousChefPurple,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                ) { append("URL imports are on us.") }
                                append(" Image and text imports are a Cook+ and above feature. ")
                                withLink(
                                    LinkAnnotation.Clickable(
                                        tag = "view-membership",
                                        styles = TextLinkStyles(
                                            style = SpanStyle(textDecoration = TextDecoration.Underline)
                                        ),
                                    ) { openMembership() }
                                ) { append("View membership") }
                                append(".")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
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
                            // White on a scrim — deterministic contrast over any
                            // photo, unlike theme colors (onPrimary is near-black
                            // in dark theme).
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove photo",
                                tint = Color.White,
                                modifier = Modifier
                                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                    .padding(4.dp),
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
                    // A URL is a single token, so swapping the newline key for Go
                    // in URL mode costs nothing; other inputs keep multi-line enter.
                    keyboardOptions = KeyboardOptions(
                        imeAction = if (mode == SousChefMode.URL) ImeAction.Go else ImeAction.Default,
                    ),
                    keyboardActions = KeyboardActions(
                        // Same guard as the CTA — no duplicate/while-loading imports.
                        onGo = { if (mode == SousChefMode.URL && !loading) onImport(input.trim()) },
                    ),
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
                        onClick = {
                            when (mode) {
                                SousChefMode.URL -> onImport(input.trim())
                                SousChefMode.IMAGE, SousChefMode.TEXT -> when {
                                    // The tap is the conversion event (web
                                    // parity): sign-in for watch-only, the
                                    // membership page for KNOWN non-members.
                                    // Unknown status falls through to
                                    // extraction — the server's 403 is
                                    // authoritative and repaints the banner.
                                    !canSign -> onSignIn()
                                    knownNonMember -> openMembership()
                                    mode == SousChefMode.IMAGE ->
                                        stagedImageUri?.let { onImportImage(Uri.parse(it)) }
                                    else -> onImportText(input.trim())
                                }
                                null -> Unit
                            }
                        },
                        enabled = mode != null && !loading,
                        colors = ButtonDefaults.buttonColors(
                            // Web parity: white on the Sous Chef purple — passes
                            // WCAG AA for the button's large/bold label.
                            containerColor = SousChefPurple,
                            contentColor = Color.White,
                        ),
                    ) {
                        val loadingMode = (state as? State.Loading)?.mode
                        if (loadingMode != null) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                // Match the (disabled) button label color.
                                color = LocalContentColor.current,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(progressLine(loadingMode))
                        } else {
                            Text(if (mode == null) "Get Recipe" else "🤖 Get Recipe")
                        }
                    }
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
                is State.Loading -> Box(
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

/** The web's per-mode extraction progress lines, verbatim. */
private fun progressLine(mode: SousChefMode): String = when (mode) {
    SousChefMode.IMAGE -> "Extracting recipe from image..."
    SousChefMode.TEXT -> "Formatting your recipe..."
    SousChefMode.URL -> "Fetching and extracting recipe from URL..."
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

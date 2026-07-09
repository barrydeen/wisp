package cooking.zap.app.ui.component

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cooking.zap.app.api.NoteReviewMode
import cooking.zap.app.cheffy.Cheffy
import cooking.zap.app.cheffy.NoteReview
import cooking.zap.app.viewmodel.NoteReviewViewModel

/**
 * Cheffy Note Photo Review modal (CHEFFY_NOTE_REVIEW_PLAN.md, Phase 2) —
 * the Android `ModalBottomSheet` counterpart of the web
 * `CheffyNoteReview.svelte`, copy mirrored verbatim. Phase 2 renders the
 * draft flow only: no Post button (Phase 3), no disclosure toggle
 * (Phase 4), no image picker (Phase 4), no sats path on the upsell card
 * (Phase 5).
 *
 * Dumb by design: state comes in, intents go out — the phase machine
 * lives in [NoteReviewViewModel] / [NoteReview].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteReviewSheet(
    state: NoteReviewViewModel.UiState,
    onDismiss: () -> Unit,
    onChoose: (NoteReviewMode) -> Unit,
    onRegenerate: () -> Unit,
    onDraftChange: (String) -> Unit,
    onStartOver: () -> Unit,
    /** Null hides the membership button (Play-flavor linkout policy). */
    onViewMembership: (() -> Unit)?,
    onPost: () -> Unit,
    onRetryPost: () -> Unit,
    /** Open ThreadScreen for the published reply. */
    onViewReply: () -> Unit,
    onToggleDisclosure: () -> Unit,
    onSelectImage: (Int) -> Unit,
    /** Buy one draft for 21 sats (Phase 5a — external payment path). */
    onStartPayment: () -> Unit,
    /** Leave the payment screen; the invoice stays live for reuse. */
    onBackFromPaying: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val busy = state.phase == NoteReview.Phase.SIGNING || state.phase == NoteReview.Phase.LOADING
            Row(verticalAlignment = Alignment.CenterVertically) {
                CheffyIcon(
                    size = 28.dp,
                    expression = if (busy) Cheffy.Expression.COOKING else Cheffy.Expression.HAPPY,
                )
                Spacer(Modifier.width(10.dp))
                Text("Ask Cheffy about this dish", style = MaterialTheme.typography.titleMedium)
            }

            when (state.phase) {
                NoteReview.Phase.CHOOSE -> ChooseContent(state, onChoose, onSelectImage)
                NoteReview.Phase.SIGNING -> WaitContent(
                    expression = Cheffy.Expression.THINKING,
                    line = "Waiting for your signer to approve…",
                    sub = "Using a remote signer? This can take a few seconds.",
                )
                NoteReview.Phase.LOADING -> WaitContent(
                    expression = Cheffy.Expression.COOKING,
                    line = state.loadingLine,
                )
                // Web renders draft and posting as one layout with the
                // controls disabled while the publish is in flight.
                NoteReview.Phase.DRAFT, NoteReview.Phase.POSTING ->
                    DraftContent(state, onDraftChange, onRegenerate, onStartOver, onPost, onToggleDisclosure, onSelectImage)
                NoteReview.Phase.POST_TIMEOUT -> {
                    WaitContent(expression = Cheffy.Expression.THINKING, line = state.message)
                    Row(
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = onRetryPost) { Text("Give it another push") }
                        TextButton(onClick = onDismiss) { Text("Close") }
                    }
                }
                NoteReview.Phase.POSTED -> {
                    WaitContent(
                        expression = Cheffy.Expression.EXCITED,
                        line = "Posted! Cheffy tips his toque to you.",
                    )
                    Row(
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = onViewReply) { Text("View your reply") }
                        TextButton(onClick = onDismiss) { Text("Done") }
                    }
                }
                NoteReview.Phase.DEAD_END -> {
                    WaitContent(expression = Cheffy.Expression.CONCERNED, line = state.message)
                    TextButton(
                        onClick = onStartOver,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) { Text("Back") }
                }
                NoteReview.Phase.UPSELL -> UpsellContent(state, onViewMembership, onStartPayment)
                NoteReview.Phase.PAYING -> PayingContent(state, onBackFromPaying)
                NoteReview.Phase.ERROR -> {
                    WaitContent(
                        expression = Cheffy.Expression.CONCERNED,
                        line = state.errorLine,
                        sub = state.message.takeIf { it.isNotBlank() },
                    )
                    Row(
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = onRegenerate) { Text("Try again") }
                        TextButton(onClick = onStartOver) { Text("Back") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChooseContent(
    state: NoteReviewViewModel.UiState,
    onChoose: (NoteReviewMode) -> Unit,
    onSelectImage: (Int) -> Unit,
) {
    if (state.imageUrl.isNotEmpty()) {
        AsyncImage(
            model = state.imageUrl,
            contentDescription = "Dish from the note",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
    }
    ImagePickerStrip(state, onSelectImage, enabled = true)
    Text(
        "What should Cheffy draft? You'll edit it before anything is posted.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ModeCard(
        title = "Say something nice",
        subtitle = "A short, warm reply about the dish",
        onClick = { onChoose(NoteReviewMode.COMMENT) },
    )
    ModeCard(
        title = "Guess the recipe",
        subtitle = "Reverse-engineer it from the photo",
        onClick = { onChoose(NoteReviewMode.RECIPE) },
    )
}

@Composable
private fun ModeCard(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WaitContent(expression: Cheffy.Expression, line: String, sub: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CheffyIcon(size = 64.dp, expression = expression)
        Text(line, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        if (sub != null) {
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun DraftContent(
    state: NoteReviewViewModel.UiState,
    onDraftChange: (String) -> Unit,
    onRegenerate: () -> Unit,
    onStartOver: () -> Unit,
    onPost: () -> Unit,
    onToggleDisclosure: () -> Unit,
    onSelectImage: (Int) -> Unit,
) {
    val posting = state.phase == NoteReview.Phase.POSTING
    Text(
        "Cheffy's draft — make it yours, then post it as your own reply.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Picking a photo here only ARMS the next Regenerate (web parity —
    // the current draft stays until the member re-runs).
    ImagePickerStrip(state, onSelectImage, enabled = !posting)
    OutlinedTextField(
        value = state.draft,
        onValueChange = onDraftChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = !posting,
        // Recipes are long structured drafts, comments a few sentences —
        // mirrors the web textarea's rows={16 : 5}.
        minLines = if (state.mode == NoteReviewMode.RECIPE) 12 else 4,
        supportingText = { Text("${state.draft.length} characters") },
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = state.disclosureOn,
            onCheckedChange = { onToggleDisclosure() },
            enabled = !posting,
        )
        Text("Add a small \"via Cheffy\" note", style = MaterialTheme.typography.bodyMedium)
    }
    if (state.disclosureOn) {
        // Publish-time footer preview — deliberately NOT part of the text
        // field above. The label makes it read as "will be appended",
        // so nobody edits their draft trying to remove it.
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    "Added when you post:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(NoteReview.DISCLOSURE_FOOTER, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (state.postError.isNotBlank()) {
        Text(
            state.postError,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onRegenerate, enabled = !posting) { Text("Regenerate") }
        TextButton(onClick = onStartOver, enabled = !posting) { Text("Start over") }
        Spacer(Modifier.weight(1f))
        Button(onClick = onPost, enabled = !posting && state.draft.isNotBlank()) {
            Text(if (posting) "Posting…" else "Post reply")
        }
    }
}

/**
 * Multi-image thumbnail strip (Phase 4) — rendered only when the note
 * carries more than one detected image. Selection is client-side only:
 * it picks which photo the NEXT request sends (one imageUrl per call).
 */
@Composable
private fun ImagePickerStrip(
    state: NoteReviewViewModel.UiState,
    onSelectImage: (Int) -> Unit,
    enabled: Boolean,
) {
    if (state.imageUrls.size <= 1) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(state.imageUrls) { index, url ->
            val selected = index == state.selectedImageIndex
            AsyncImage(
                model = url,
                contentDescription = "Photo ${index + 1} of ${state.imageUrls.size}",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .clickable(enabled = enabled) { onSelectImage(index) },
            )
        }
    }
}

@Composable
private fun UpsellContent(
    state: NoteReviewViewModel.UiState,
    onViewMembership: (() -> Unit)?,
    onStartPayment: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CheffyIcon(size = 64.dp, expression = Cheffy.Expression.NEUTRAL)
        Text(
            "Cheffy photo review is a Pro Kitchen feature",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            "Get a drafted reply or a recipe guess for any dish photo on the feed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        // Static output sample (web parity) so first-timers see quality
        // before the 21-sat ask.
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    "The kind of reply Cheffy drafts:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "“${NoteReview.PAYMENT_CARD_EXAMPLE_DRAFT}”",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (state.payError.isNotBlank()) {
            Text(
                state.payError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        // Membership stays visually primary; sats is the impulse lane.
        if (onViewMembership != null) {
            Button(onClick = onViewMembership) { Text("View membership") }
        }
        OutlinedButton(onClick = onStartPayment) {
            Text("⚡ ${NoteReview.CREDIT_PRICE_SATS} sats for one draft")
        }
        Text(
            NoteReview.CREDITS_CROSS_DEVICE_LINE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The PAYING screen (Phase 5a: external path only — QR, copy, and a
 * `lightning:` intent; in-app wallet routing arrives in 5b and slots in
 * front of this). The status poll is already running (it started the
 * moment the invoice existed); everything here is just ways to move sats.
 */
@Composable
private fun PayingContent(
    state: NoteReviewViewModel.UiState,
    onBackFromPaying: () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CheffyIcon(size = 64.dp, expression = Cheffy.Expression.EXCITED)
        Text(
            "Waiting for your ${NoteReview.CREDIT_PRICE_SATS} sats…",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        val invoice = state.invoice
        if (invoice == null) {
            Text(
                "Setting up your invoice…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val qrBitmap = remember(invoice.bolt11) {
                generateQrBitmap("lightning:${invoice.bolt11}")
            }
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .padding(8.dp),
            ) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "Lightning invoice QR code",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    invoice.bolt11,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    clipboardManager.setText(AnnotatedString(invoice.bolt11))
                }) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy invoice",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Button(onClick = {
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    android.net.Uri.parse("lightning:${invoice.bolt11}"),
                )
                try {
                    context.startActivity(Intent.createChooser(intent, null))
                } catch (_: Exception) {
                    // No wallet app — the QR and copy affordances remain.
                }
            }) { Text("Open in wallet") }
            Text(
                "Pay with any Lightning wallet — drafting starts the moment it lands.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        TextButton(onClick = onBackFromPaying) { Text("Back") }
    }
}

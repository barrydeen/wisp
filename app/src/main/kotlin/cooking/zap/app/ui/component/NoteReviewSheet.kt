package cooking.zap.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
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
                NoteReview.Phase.CHOOSE -> ChooseContent(state.imageUrl, onChoose)
                NoteReview.Phase.SIGNING -> WaitContent(
                    expression = Cheffy.Expression.THINKING,
                    line = "Waiting for your signer to approve…",
                    sub = "Using a remote signer? This can take a few seconds.",
                )
                NoteReview.Phase.LOADING -> WaitContent(
                    expression = Cheffy.Expression.COOKING,
                    line = state.loadingLine,
                )
                NoteReview.Phase.DRAFT -> DraftContent(state, onDraftChange, onRegenerate, onStartOver)
                NoteReview.Phase.DEAD_END -> {
                    WaitContent(expression = Cheffy.Expression.CONCERNED, line = state.message)
                    TextButton(
                        onClick = onStartOver,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) { Text("Back") }
                }
                NoteReview.Phase.UPSELL -> UpsellContent(onViewMembership)
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
private fun ChooseContent(imageUrl: String, onChoose: (NoteReviewMode) -> Unit) {
    if (imageUrl.isNotEmpty()) {
        AsyncImage(
            model = imageUrl,
            contentDescription = "Dish from the note",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
    }
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
) {
    Text(
        "Cheffy's draft — make it yours, then post it as your own reply.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = state.draft,
        onValueChange = onDraftChange,
        modifier = Modifier.fillMaxWidth(),
        // Recipes are long structured drafts, comments a few sentences —
        // mirrors the web textarea's rows={16 : 5}.
        minLines = if (state.mode == NoteReviewMode.RECIPE) 12 else 4,
        supportingText = { Text("${state.draft.length} characters") },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onRegenerate) { Text("Regenerate") }
        TextButton(onClick = onStartOver) { Text("Start over") }
    }
}

@Composable
private fun UpsellContent(onViewMembership: (() -> Unit)?) {
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
        // The 21-sats path joins this card in Phase 5.
        if (onViewMembership != null) {
            Button(onClick = onViewMembership) { Text("View membership") }
        }
    }
}

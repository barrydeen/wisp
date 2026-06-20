package cooking.zap.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import cooking.zap.app.cheffy.Cheffy
import cooking.zap.app.ui.component.CheffyIcon
import cooking.zap.app.viewmodel.CheffyViewModel
import cooking.zap.app.viewmodel.CheffyViewModel.Kind
import cooking.zap.app.viewmodel.CheffyViewModel.Message
import cooking.zap.app.viewmodel.CheffyViewModel.Role

/**
 * Cheffy — the member-gated kitchen-companion chat (concern 2.3 v1: chat +
 * hungry). Whole-response (no streaming): a pending bubble shows a status line
 * (one picked per request) while the long-timeout request runs, then resolves
 * to a markdown reply.
 * READ_ONLY (no signing key) is gated to a members/sign-in message with no
 * composer; a 403 surfaces the same message-only "Pro Kitchen" notice inline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheffyScreen(
    viewModel: CheffyViewModel,
    canSign: Boolean,
    onSend: (text: String, mode: cooking.zap.app.api.CheffyMode) -> Unit,
    onRetry: () -> Unit,
    onSaveRecipe: (content: String) -> Unit,
    onBack: () -> Unit,
) {
    val thread by viewModel.thread.collectAsState()
    val loading by viewModel.loading.collectAsState()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Keep the newest message in view.
    androidx.compose.runtime.LaunchedEffect(thread.size) {
        if (thread.isNotEmpty()) listState.animateScrollToItem(thread.size - 1)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CheffyIcon(size = 28.dp, expression = Cheffy.Expression.NEUTRAL)
                        Spacer(Modifier.width(8.dp))
                        Text("Cheffy")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (thread.isNotEmpty()) {
                        TextButton(onClick = viewModel::startOver, enabled = !loading) { Text("Start over") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        if (!canSign) {
            GatedState(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (thread.isEmpty()) {
                EmptyState(
                    modifier = Modifier.weight(1f),
                    onPrompt = { draft = it },
                    onSurprise = { onSend("", cooking.zap.app.api.CheffyMode.HUNGRY) },
                    enabled = !loading,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(thread, key = { it.id }) { msg -> MessageBubble(msg, onRetry, onSaveRecipe, loading) }
                }
            }
            Composer(
                draft = draft,
                onDraftChange = { draft = it.take(Cheffy.MAX_PROMPT_CHARS) },
                onSend = {
                    val text = draft
                    draft = ""
                    onSend(text, cooking.zap.app.api.CheffyMode.CHAT)
                },
                onSurprise = { onSend("", cooking.zap.app.api.CheffyMode.HUNGRY) },
                enabled = !loading,
            )
        }
    }
}

@Composable
private fun GatedState(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            CheffyIcon(size = 72.dp, expression = Cheffy.Expression.NEUTRAL)
            Text(
                Cheffy.MEMBERS_ONLY_MESSAGE,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Sign in with a key to cook with Cheffy.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyState(
    modifier: Modifier,
    onPrompt: (String) -> Unit,
    onSurprise: () -> Unit,
    enabled: Boolean,
) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        CheffyIcon(size = 88.dp, expression = Cheffy.Expression.HAPPY)
        Text("Your kitchen companion", style = MaterialTheme.typography.titleLarge)
        Text(
            "Ask what to cook, how to fix it, or what to do with what's in your fridge.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Cheffy.PROMPT_PLACEHOLDERS.take(4).forEach { p ->
                AssistChip(onClick = { if (enabled) onPrompt(p) }, label = { Text(p) })
            }
            AssistChip(
                onClick = { if (enabled) onSurprise() },
                label = { Text("Surprise me 🎲") },
            )
        }
    }
}

@Composable
private fun MessageBubble(msg: Message, onRetry: () -> Unit, onSaveRecipe: (String) -> Unit, loading: Boolean) {
    val isUser = msg.role == Role.USER
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        when (msg.kind) {
            Kind.PENDING -> PendingBubble(msg)
            Kind.ERROR -> ErrorBubble(msg, onRetry, loading)
            else -> {
                Column(
                    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Surface(
                        color = if (isUser) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.widthIn(max = 320.dp),
                    ) {
                        val textColor = if (isUser) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface
                        if (isUser || msg.kind == Kind.MEMBERS_ONLY) {
                            Text(
                                msg.content,
                                modifier = Modifier.padding(12.dp),
                                color = textColor,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        } else {
                            // Cheffy text / recipe replies render markdown.
                            CheffyMarkdown(msg.content, Modifier.padding(12.dp), textColor)
                        }
                    }
                    // A structured Cheffy recipe → Save to my recipes (concern 2.3c).
                    // Routes through the compose editor (add a photo + category there);
                    // Cheffy chat is gated to signing accounts, so no READ_ONLY case here.
                    if (msg.kind == Kind.RECIPE) {
                        FilledTonalButton(onClick = { onSaveRecipe(msg.content) }) {
                            Icon(Icons.Outlined.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Save to my recipes")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingBubble(msg: Message) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CheffyIcon(size = 28.dp, expression = msg.expression)
            Text(
                msg.statusLine ?: "Thinking…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun ErrorBubble(msg: Message, onRetry: () -> Unit, loading: Boolean) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.widthIn(max = 320.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                msg.statusLine ?: "Cheffy hit a snag.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                msg.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
            )
            TextButton(onClick = onRetry, enabled = !loading) { Text("Try again") }
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onSurprise: () -> Unit,
    enabled: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onSurprise, enabled = enabled) {
            Icon(Icons.Filled.Casino, contentDescription = "Surprise me")
        }
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f).heightIn(max = 120.dp),
            placeholder = { Text(Cheffy.PROMPT_PLACEHOLDERS.first()) },
            maxLines = 4,
        )
        IconButton(onClick = onSend, enabled = enabled && draft.isNotBlank()) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
        }
    }
}

/**
 * Minimal markdown for chat bubbles — headings, bullets, numbered steps, and
 * inline bold/italic. Enough for Cheffy's conversational replies and the
 * structured-recipe format (full article rendering isn't needed in a bubble).
 */
@Composable
private fun CheffyMarkdown(text: String, modifier: Modifier, color: androidx.compose.ui.graphics.Color) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (rawLine in text.trim().split('\n')) {
            val line = rawLine.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.size(2.dp))
                line.startsWith("# ") -> Text(
                    inline(line.removePrefix("# ")), color = color,
                    style = MaterialTheme.typography.titleLarge,
                )
                line.startsWith("## ") -> Text(
                    inline(line.removePrefix("## ")), color = color,
                    style = MaterialTheme.typography.titleMedium,
                )
                line.startsWith("### ") -> Text(
                    inline(line.removePrefix("### ")), color = color,
                    style = MaterialTheme.typography.titleSmall,
                )
                line.startsWith("- ") || line.startsWith("* ") -> Text(
                    inline("•  " + line.drop(2)), color = color,
                    style = MaterialTheme.typography.bodyLarge,
                )
                else -> Text(inline(line), color = color, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** Inline `**bold**` / `*italic*` → AnnotatedString. */
private fun inline(s: String) = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("**", i) -> {
                val end = s.indexOf("**", i + 2)
                if (end == -1) { append(s.substring(i)); i = s.length }
                else { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(i + 2, end)) }; i = end + 2 }
            }
            s[i] == '*' -> {
                val end = s.indexOf('*', i + 1)
                if (end == -1) { append(s.substring(i)); i = s.length }
                else { withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.substring(i + 1, end)) }; i = end + 1 }
            }
            else -> { append(s[i]); i++ }
        }
    }
}

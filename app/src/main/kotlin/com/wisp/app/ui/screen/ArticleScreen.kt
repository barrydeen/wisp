package com.wisp.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.wisp.app.repo.EventRepository
import com.wisp.app.ui.component.ProfilePicture
import com.wisp.app.viewmodel.ArticleViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ArticleScreen(
    viewModel: ArticleViewModel,
    eventRepo: EventRepository,
    onBack: () -> Unit,
    onProfileClick: (String) -> Unit = {},
    onHashtagClick: ((String) -> Unit)? = null
) {
    val article by viewModel.article.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val title by viewModel.title.collectAsState()
    val coverImage by viewModel.coverImage.collectAsState()
    val publishedAt by viewModel.publishedAt.collectAsState()
    val hashtags by viewModel.hashtags.collectAsState()

    val authorPubkey = article?.pubkey
    val profile = remember(authorPubkey) { authorPubkey?.let { eventRepo.getProfileData(it) } }

    val blocks = remember(article) {
        article?.content?.let { parseMarkdownBlocks(it) } ?: emptyList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title ?: "Article",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
            article == null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Article not found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    // Header
                    item(key = "header") {
                        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                            if (coverImage != null) {
                                AsyncImage(
                                    model = coverImage,
                                    contentDescription = title,
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .padding(top = 8.dp)
                                )
                                Spacer(Modifier.height(16.dp))
                            }

                            Text(
                                text = title ?: "Untitled",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Spacer(Modifier.height(12.dp))

                            if (authorPubkey != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { onProfileClick(authorPubkey) }
                                ) {
                                    ProfilePicture(url = profile?.picture, size = 32)
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        val displayName = profile?.displayString
                                            ?: "${authorPubkey.take(8)}...${authorPubkey.takeLast(4)}"
                                        Text(
                                            text = displayName,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        if (publishedAt != null) {
                                            Text(
                                                text = formatArticleDate(publishedAt!!),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            if (hashtags.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                FlowRow {
                                    hashtags.forEach { tag ->
                                        SuggestionChip(
                                            onClick = { onHashtagClick?.invoke(tag) },
                                            label = { Text(tag) },
                                            modifier = Modifier.padding(end = 6.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(16.dp))
                        }
                    }

                    // Markdown blocks
                    items(blocks.size, key = { "block-$it" }) { index ->
                        val block = blocks[index]
                        when (block) {
                            is MdBlock.Heading -> ArticleHeading(block)
                            is MdBlock.Paragraph -> ArticleParagraph(block)
                            is MdBlock.Image -> ArticleImage(block)
                            is MdBlock.CodeBlock -> ArticleCodeBlock(block)
                            is MdBlock.BlockQuote -> ArticleBlockQuote(block)
                            is MdBlock.HorizontalRule -> HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                            )
                        }
                    }

                    item(key = "footer") { Spacer(Modifier.height(32.dp)) }
                }
            }
        }
    }
}

// -- Block renderer composables --

@Composable
private fun ArticleHeading(block: MdBlock.Heading) {
    val style = when (block.level) {
        1 -> MaterialTheme.typography.headlineLarge
        2 -> MaterialTheme.typography.headlineMedium
        3 -> MaterialTheme.typography.headlineSmall
        4 -> MaterialTheme.typography.titleLarge
        5 -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.titleSmall
    }
    Text(
        text = formatInline(block.text),
        style = style,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

@Composable
private fun ArticleParagraph(block: MdBlock.Paragraph) {
    Text(
        text = formatInline(block.text),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

@Composable
private fun ArticleImage(block: MdBlock.Image) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        AsyncImage(
            model = block.url,
            contentDescription = block.alt,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
        )
        if (!block.alt.isNullOrBlank() && block.alt != block.url) {
            Text(
                text = block.alt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun ArticleCodeBlock(block: MdBlock.CodeBlock) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(
            text = block.code,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(12.dp)
        )
    }
}

@Composable
private fun ArticleBlockQuote(block: MdBlock.BlockQuote) {
    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(24.dp)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    RoundedCornerShape(2.dp)
                )
        )
        Text(
            text = formatInline(block.text),
            style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

// -- Inline formatting --

@Composable
private fun formatInline(text: String): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    return remember(text, linkColor) {
        buildAnnotatedString {
            var i = 0
            while (i < text.length) {
                when {
                    // Images inline (skip, they're extracted as blocks)
                    text.startsWith("![", i) -> {
                        val closeBracket = text.indexOf(']', i + 2)
                        val closeParen = if (closeBracket >= 0) text.indexOf(')', closeBracket) else -1
                        if (closeBracket >= 0 && closeParen >= 0 && text.getOrNull(closeBracket + 1) == '(') {
                            i = closeParen + 1
                        } else {
                            append(text[i])
                            i++
                        }
                    }
                    // Links: [text](url)
                    text[i] == '[' -> {
                        val closeBracket = text.indexOf(']', i + 1)
                        val openParen = closeBracket + 1
                        if (closeBracket > i && openParen < text.length && text[openParen] == '(') {
                            val closeParen = text.indexOf(')', openParen)
                            if (closeParen > openParen) {
                                val linkText = text.substring(i + 1, closeBracket)
                                withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                                    append(linkText)
                                }
                                i = closeParen + 1
                            } else {
                                append(text[i])
                                i++
                            }
                        } else {
                            append(text[i])
                            i++
                        }
                    }
                    // Bold+italic: ***text*** or ___text___
                    text.startsWith("***", i) || text.startsWith("___", i) -> {
                        val delim = text.substring(i, i + 3)
                        val end = text.indexOf(delim, i + 3)
                        if (end > i) {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                                append(text.substring(i + 3, end))
                            }
                            i = end + 3
                        } else {
                            append(text[i])
                            i++
                        }
                    }
                    // Bold: **text** or __text__
                    text.startsWith("**", i) || text.startsWith("__", i) -> {
                        val delim = text.substring(i, i + 2)
                        val end = text.indexOf(delim, i + 2)
                        if (end > i) {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                append(text.substring(i + 2, end))
                            }
                            i = end + 2
                        } else {
                            append(text[i])
                            i++
                        }
                    }
                    // Italic: *text* or _text_
                    (text[i] == '*' || text[i] == '_') && i + 1 < text.length && text[i + 1] != ' ' -> {
                        val delim = text[i]
                        val end = text.indexOf(delim, i + 1)
                        if (end > i && end > i + 1) {
                            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                                append(text.substring(i + 1, end))
                            }
                            i = end + 1
                        } else {
                            append(text[i])
                            i++
                        }
                    }
                    // Inline code: `code`
                    text[i] == '`' -> {
                        val end = text.indexOf('`', i + 1)
                        if (end > i) {
                            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
                                append(text.substring(i + 1, end))
                            }
                            i = end + 1
                        } else {
                            append(text[i])
                            i++
                        }
                    }
                    else -> {
                        append(text[i])
                        i++
                    }
                }
            }
        }
    }
}

// -- Markdown block parser --

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Image(val url: String, val alt: String?) : MdBlock
    data class CodeBlock(val code: String, val language: String?) : MdBlock
    data class BlockQuote(val text: String) : MdBlock
    data object HorizontalRule : MdBlock
}

private val imageLineRegex = Regex("""^!\[([^\]]*)]\((\S+?)(?:\s+"[^"]*")?\)\s*$""")

private fun parseMarkdownBlocks(content: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = content.lines()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        when {
            // Empty line — skip
            trimmed.isEmpty() -> i++

            // Fenced code block
            trimmed.startsWith("```") -> {
                val language = trimmed.removePrefix("```").trim().ifEmpty { null }
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    codeLines.add(lines[i])
                    i++
                }
                if (i < lines.size) i++ // skip closing ```
                blocks.add(MdBlock.CodeBlock(codeLines.joinToString("\n"), language))
            }

            // Heading
            trimmed.startsWith("#") -> {
                val level = trimmed.takeWhile { it == '#' }.length.coerceAtMost(6)
                val text = trimmed.drop(level).trimStart()
                blocks.add(MdBlock.Heading(level, text))
                i++
            }

            // Horizontal rule
            trimmed.matches(Regex("""^[-*_]{3,}\s*$""")) -> {
                blocks.add(MdBlock.HorizontalRule)
                i++
            }

            // Standalone image
            imageLineRegex.matches(trimmed) -> {
                val match = imageLineRegex.matchEntire(trimmed)!!
                blocks.add(MdBlock.Image(url = match.groupValues[2], alt = match.groupValues[1].ifEmpty { null }))
                i++
            }

            // Block quote
            trimmed.startsWith(">") -> {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().let { it.startsWith(">") || (it.isNotEmpty() && quoteLines.isNotEmpty()) }) {
                    quoteLines.add(lines[i].trim().removePrefix(">").trimStart())
                    i++
                }
                blocks.add(MdBlock.BlockQuote(quoteLines.joinToString(" ")))
            }

            // Paragraph (collect consecutive non-empty lines)
            else -> {
                val paraLines = mutableListOf<String>()
                while (i < lines.size) {
                    val l = lines[i].trim()
                    if (l.isEmpty() || l.startsWith("#") || l.startsWith("```") ||
                        l.startsWith(">") || l.matches(Regex("""^[-*_]{3,}\s*$"""))
                    ) break
                    // If this line is a standalone image, break so it's handled as its own block
                    if (imageLineRegex.matches(l)) break
                    paraLines.add(l)
                    i++
                }
                if (paraLines.isNotEmpty()) {
                    val text = paraLines.joinToString(" ")
                    // Extract any inline images from the paragraph text
                    val inlineImages = Regex("""!\[([^\]]*)]\((\S+?)(?:\s+"[^"]*")?\)""").findAll(text)
                    val cleanedText = text.replace(Regex("""!\[([^\]]*)]\((\S+?)(?:\s+"[^"]*")?\)"""), "").trim()
                    if (cleanedText.isNotEmpty()) {
                        blocks.add(MdBlock.Paragraph(cleanedText))
                    }
                    for (img in inlineImages) {
                        blocks.add(MdBlock.Image(url = img.groupValues[2], alt = img.groupValues[1].ifEmpty { null }))
                    }
                }
            }
        }
    }
    return blocks
}

private fun formatArticleDate(epoch: Long): String {
    return java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US)
        .format(java.util.Date(epoch * 1000))
}

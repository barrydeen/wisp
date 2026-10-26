package com.wisp.app.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.wisp.app.repo.GIF_COL_WIDTH_DP
import com.wisp.app.repo.Gif
import com.wisp.app.repo.GifErrorKind
import com.wisp.app.repo.GifSearchException
import com.wisp.app.repo.gifSearchRepository
import com.wisp.app.repo.gifTopicsFor
import com.wisp.app.repo.pageAdvanced
import com.wisp.app.repo.shouldLoadMore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import kotlin.math.roundToInt

/** What the picker says below the grid. */
private sealed interface GifPickerStatus {
    /** Nothing is asked for yet. */
    data object Hint : GifPickerStatus

    /** A search answered with nothing to show. */
    data class NoResults(val query: String) : GifPickerStatus

    /** A request failed — the kind picks the message. */
    data class Failed(val kind: GifErrorKind) : GifPickerStatus

    /** Nothing to say. */
    data object Quiet : GifPickerStatus
}

/**
 * GIF picker on gifs.nostr.build (matching the reference composer update):
 * opens on clock-ordered topic chips — the API has no trending endpoint, and
 * nothing loads until a chip is tapped or text is entered. Suggestions
 * replace the chips while typing (failures silently ignored), pages of 24
 * load near the bottom until the API's offset-199 ceiling, and every result
 * is attached by URL — already hosted on a Nostr media host, nothing is
 * uploaded. Strings are hardcoded like the rest of this composer's sheets.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GifPickerSheet(
    onSelect: (Gif) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val repo = gifSearchRepository

    var query by remember { mutableStateOf("") }
    var gifs by remember { mutableStateOf<List<Gif>>(emptyList()) }
    val seen = remember { mutableSetOf<String>() }
    var nextOffset by remember { mutableStateOf<Int?>(null) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<GifPickerStatus>(GifPickerStatus.Hint) }
    var chips by remember { mutableStateOf(gifTopicsFor(LocalTime.now().hour)) }

    // Generation token: a search cut short by a newer one writes nothing —
    // the AbortController-and-pageCtrl-guard of the web picker.
    var generation by remember { mutableIntStateOf(0) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var suggestJob by remember { mutableStateOf<Job?>(null) }
    var typingJob by remember { mutableStateOf<Job?>(null) }
    var pageJob by remember { mutableStateOf<Job?>(null) }

    val gridState = rememberLazyStaggeredGridState()

    suspend fun load(q: String, offset: Int, gen: Int) {
        loading = true
        try {
            val page = repo.search(q, offset)
            if (gen != generation) return
            val fresh = page.gifs.filter { seen.add(it.url) }
            gifs = gifs + fresh
            // A cursor that doesn't move would ask for the same page forever.
            nextOffset = page.next?.takeIf { pageAdvanced(offset, it) }
            status = if (offset == 0 && page.gifs.isEmpty()) {
                GifPickerStatus.NoResults(q)
            } else {
                GifPickerStatus.Quiet
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GifSearchException) {
            if (gen == generation) {
                status = GifPickerStatus.Failed(e.kind)
                // Stop paging: the bottom check would otherwise retry the
                // failed page in a loop. A new search starts over.
                nextOffset = null
            }
        } finally {
            if (gen == generation) loading = false
        }
    }

    // The one way a next page loads — scroll and the post-load recheck both
    // come through here, so page requests never run in parallel. It runs in
    // the sheet's scope, not the effect's, so a recomposition can't cancel it.
    fun loadNextPage() {
        if (loading || pageJob?.isActive == true || searchJob?.isActive == true) return
        val offset = nextOffset ?: return
        val gen = generation
        val q = query.trim()
        pageJob = scope.launch { load(q, offset, gen) }
    }

    fun submitSearch(q: String) {
        generation++
        val gen = generation
        searchJob?.cancel()
        pageJob?.cancel()
        pageJob = null
        seen.clear()
        gifs = emptyList()
        nextOffset = null
        scope.launch { gridState.scrollToItem(0) }
        if (q.isEmpty()) {
            status = GifPickerStatus.Hint
            searchJob = null
            return
        }
        status = GifPickerStatus.Quiet
        searchJob = scope.launch { load(q, 0, gen) }
    }

    fun requestSuggest(q: String) {
        suggestJob?.cancel()
        suggestJob = scope.launch {
            try {
                val terms = repo.suggest(q)
                // Stale answers (typing moved on) leave the chips alone.
                if (q == query.trim()) chips = terms
            } catch (_: GifSearchException) {
                // Suggestions are a nicety: a failure leaves the chips as they were.
            }
        }
    }

    fun onQueryChange(value: String) {
        query = value
        typingJob?.cancel()
        val q = value.trim()
        if (q.isEmpty()) chips = gifTopicsFor(LocalTime.now().hour)
        // Searched once typing pauses, not per keystroke: each search is a
        // direct gifs.nostr.build request and each one replaces the grid.
        typingJob = scope.launch {
            delay(350)
            submitSearch(q)
            if (q.isNotEmpty()) requestSuggest(q)
        }
    }

    // Chip taps and the search key skip the debounce; Enter searches now.
    fun searchNow(q: String) {
        typingJob?.cancel()
        if (q.isEmpty()) chips = gifTopicsFor(LocalTime.now().hour)
        submitSearch(q)
    }

    // Load the next page near the bottom of the grid. The bottom check doesn't
    // read `loading`, so starting a load never restarts this effect; `loading`
    // is in the flow only so the check runs again when a page lands — a page of
    // duplicates or rejected items doesn't grow the grid or fire a scroll event.
    val nearBottom by remember(gridState) {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            shouldLoadMore(false, nextOffset, last, info.totalItemsCount)
        }
    }
    LaunchedEffect(gridState) {
        snapshotFlow { nearBottom to loading }
            .collect { (near, busy) -> if (near && !busy) loadNextPage() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
        ) {
            Text("GIFs", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            // Attribution the gifs.nostr.build registration asks for — one
            // line, with nostr.build an inline link.
            Text(
                buildAnnotatedString {
                    append("GIFs from ")
                    withLink(
                        LinkAnnotation.Url(
                            url = "https://nostr.build",
                            styles = TextLinkStyles(
                                style = SpanStyle(textDecoration = TextDecoration.Underline)
                            )
                        )
                    ) {
                        append("nostr.build")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = ::onQueryChange,
                placeholder = { Text("Search GIFs…") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(50),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { searchNow(query.trim()) }),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                chips.forEach { term ->
                    AssistChip(
                        onClick = {
                            query = term
                            chips = gifTopicsFor(LocalTime.now().hour)
                            searchNow(term)
                        },
                        label = { Text(term) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 240.dp)
            ) {
                if (loading && gifs.isEmpty()) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else if (gifs.isNotEmpty()) {
                    // Columns packed shortest-first, so GIFs of every shape fit
                    // without cropping; content-height up to the cap, so a short
                    // result doesn't leave a tall empty box.
                    val columns = (maxWidth / GIF_COL_WIDTH_DP.dp).roundToInt().coerceIn(2, 5)
                    LazyVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Fixed(columns),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalItemSpacing = 6.dp,
                        state = gridState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 440.dp)
                    ) {
                        items(gifs, key = { it.url }) { gif ->
                            AsyncImage(
                                model = gif.preview,
                                // The real aspect ratio, set before load so the grid never jumps.
                                contentDescription = gif.title.ifBlank { "GIF" },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio((gif.width / gif.height).toFloat())
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onSelect(gif) }
                            )
                        }
                    }
                }
            }
            val statusText = when (val s = status) {
                GifPickerStatus.Hint -> "Search or pick a topic."
                is GifPickerStatus.NoResults -> "No GIFs found for \u201C${s.query}\u201D."
                is GifPickerStatus.Failed -> when (s.kind) {
                    GifErrorKind.UNAVAILABLE -> "GIF search isn't available right now."
                    GifErrorKind.RATE_LIMITED -> "Too many searches. Try again in a minute."
                    GifErrorKind.CONNECTION -> "Couldn't load GIFs. Check your connection and try again."
                }
                GifPickerStatus.Quiet -> null
            }
            if (statusText != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status is GifPickerStatus.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

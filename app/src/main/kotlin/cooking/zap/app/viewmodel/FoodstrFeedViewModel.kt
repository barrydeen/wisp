package cooking.zap.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.Filter
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.RecipeParser
import cooking.zap.app.relay.RelayPool
import cooking.zap.app.repo.EventRepository
import cooking.zap.app.repo.RecipeRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The home foodstr feed: recipes (kind 30023 `#t zapcooking`) merged with
 * `#foodstr` notes (kind 1) into one time-sorted stream.
 *
 * Recipes reuse [RecipeRepository] wholesale — same `ARTICLES_RELAYS` union
 * and `kind:author:dTag` newest-wins dedup as concern 1.2. Notes are a
 * lightweight hashtag subscription (the [HashtagFeedViewModel] pattern) on
 * the search relay. The two using different relay sets is intentional:
 * recipes live on the article aggregators, foodstr chatter on general relays.
 *
 * (Forward note, per review: if `#foodstr` notes come back sparse on the
 * single search relay, union the note query the same way recipes already do —
 * not pre-optimized here.)
 */
class FoodstrFeedViewModel : ViewModel() {

    sealed interface FoodstrItem {
        val timestamp: Long
        val key: String

        data class Recipe(val recipe: RecipeParser.Recipe) : FoodstrItem {
            override val timestamp get() = recipe.publishedAt
            override val key get() = "recipe-${recipe.id}"
        }

        data class Note(val event: NostrEvent) : FoodstrItem {
            override val timestamp get() = event.created_at
            override val key get() = "note-${event.id}"
        }
    }

    private val _items = MutableStateFlow<List<FoodstrItem>>(emptyList())
    val items: StateFlow<List<FoodstrItem>> = _items

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private var recipes: List<RecipeParser.Recipe> = emptyList()
    private val notes = LinkedHashMap<String, NostrEvent>()
    private var started = false
    private var noteJob: Job? = null

    fun load(recipeRepo: RecipeRepository, relayPool: RelayPool, eventRepo: EventRepository) {
        if (started) return
        started = true
        _isLoading.value = true

        // Recipes: observe the shared RecipeRepository flow + kick a load.
        viewModelScope.launch {
            recipeRepo.recipes.collect { list ->
                recipes = list
                emit()
            }
        }
        recipeRepo.loadFeed()

        loadNotes(relayPool, eventRepo)
    }

    private fun loadNotes(relayPool: RelayPool, eventRepo: EventRepository) {
        noteJob?.cancel()
        val sub = NOTE_SUB
        noteJob = viewModelScope.launch {
            try {
                val collector = launch {
                    relayPool.relayEvents.collect { relayEvent ->
                        if (relayEvent.subscriptionId != sub) return@collect
                        val event = relayEvent.event
                        if (event.kind != 1 || event.id in notes) return@collect
                        notes[event.id] = event
                        eventRepo.cacheEvent(event)
                        eventRepo.requestProfileIfMissing(event.pubkey)
                        emit()
                    }
                }
                val filter = Filter(kinds = listOf(1), tTags = listOf(FOODSTR_TAG), limit = 100)
                relayPool.sendToRelayOrEphemeral(
                    SearchViewModel.DEFAULT_SEARCH_RELAY,
                    ClientMessage.req(sub, filter),
                )
                withTimeoutOrNull(8_000) { relayPool.eoseSignals.first { it == sub } }
                _isLoading.value = false
                delay(8_000) // keep collecting stragglers, then tear down
                collector.cancel()
            } finally {
                relayPool.closeOnAllRelays(sub) // no leaked subscription
            }
        }
    }

    private fun emit() {
        _items.value = mergeFoodstrItems(recipes, notes.values)
    }

    override fun onCleared() {
        super.onCleared()
        noteJob?.cancel()
    }

    companion object {
        private const val FOODSTR_TAG = "foodstr"
        private const val NOTE_SUB = "foodstr-notes"
    }
}

/**
 * Interleave recipes and `#foodstr` notes into one newest-first list. Recipes
 * order by `publishedAt` (which already falls back to `created_at`), notes by
 * `created_at`. Pure — unit-tested. Returns a stable, sorted snapshot.
 */
internal fun mergeFoodstrItems(
    recipes: List<RecipeParser.Recipe>,
    notes: Collection<NostrEvent>,
): List<FoodstrFeedViewModel.FoodstrItem> {
    val merged = ArrayList<FoodstrFeedViewModel.FoodstrItem>(recipes.size + notes.size)
    recipes.forEach { merged.add(FoodstrFeedViewModel.FoodstrItem.Recipe(it)) }
    notes.forEach { merged.add(FoodstrFeedViewModel.FoodstrItem.Note(it)) }
    merged.sortByDescending { it.timestamp }
    return merged
}

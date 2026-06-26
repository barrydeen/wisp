package cooking.zap.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.nostr.RecipeFormats
import cooking.zap.app.nostr.RecipeParser
import cooking.zap.app.repo.CookbookCovers
import cooking.zap.app.repo.PackRecipeCoordinate
import cooking.zap.app.repo.RecipeBookmarkRepository
import cooking.zap.app.repo.RecipePackRepository
import cooking.zap.app.repo.RecipePackSummary
import cooking.zap.app.repo.RecipeRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RecipePackDetailViewModel : ViewModel() {
    private val _pack = MutableStateFlow<RecipePackSummary?>(null)
    val pack: StateFlow<RecipePackSummary?> = _pack

    private val _recipes = MutableStateFlow<List<RecipeParser.Recipe>>(emptyList())
    val recipes: StateFlow<List<RecipeParser.Recipe>> = _recipes

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount

    private val _failedCount = MutableStateFlow(0)
    val failedCount: StateFlow<Int> = _failedCount

    private val _notFound = MutableStateFlow(false)
    val notFound: StateFlow<Boolean> = _notFound

    private var loadedKey: String? = null
    private var loadJob: Job? = null

    fun load(
        author: String,
        dTag: String,
        packRepo: RecipePackRepository,
        recipeRepo: RecipeRepository,
    ) {
        val normalizedAuthor = author.trim()
        val normalizedDTag = dTag.trim()
        val key = "$normalizedAuthor:$normalizedDTag"
        if (loadedKey == key && (_pack.value != null || _notFound.value)) return
        loadedKey = key

        loadJob?.cancel()
        _pack.value = null
        _recipes.value = emptyList()
        _pendingCount.value = 0
        _failedCount.value = 0
        _notFound.value = false
        _isLoading.value = true

        loadJob = viewModelScope.launch {
            val packEvent = packRepo.requestPackEvent(normalizedAuthor, normalizedDTag)
            if (loadedKey != key) return@launch
            val summary = packEvent?.let { packRepo.parseSummary(it) }
            if (summary == null || packEvent == null) {
                _notFound.value = true
                _isLoading.value = false
                return@launch
            }

            _pack.value = summary
            val coordinates = packRepo.extractRecipeCoordinates(packEvent)
            resolveCoordinates(key, coordinates, recipeRepo)
        }
    }

    /**
     * Load a **cookbook collection** (A14 PR 3b-i) into the same detail surface a
     * Recipe Pack uses — a synthetic [RecipePackSummary] header built from the
     * kind-30001 list (PR 3a), then the **same** coordinate resolution + poster
     * grid. No second resolver/grid: the collection's `a`-coordinates feed the
     * identical cache-first → network-fill path.
     */
    fun loadCollection(
        list: RecipeBookmarkRepository.CookbookList,
        recipeRepo: RecipeRepository,
    ) {
        val key = "collection:${list.dTag}:${list.event.id}"
        if (loadedKey == key && _pack.value != null) return
        loadedKey = key

        loadJob?.cancel()
        _pack.value = null
        _recipes.value = emptyList()
        _pendingCount.value = 0
        _failedCount.value = 0
        _notFound.value = false
        _isLoading.value = true

        loadJob = viewModelScope.launch {
            val coordinates = list.coordinates.mapNotNull(CookbookCovers::parseCoordinate)
            // Header cover follows the web fallback chain (cover coord → first recipe → image tag).
            val cover = CookbookCovers.resolve(list, recipeRepo)
            if (loadedKey != key || !kotlin.coroutines.coroutineContext.isActive) return@launch
            _pack.value = RecipePackSummary(
                event = list.event,
                author = list.event.pubkey,
                dTag = list.dTag,
                title = list.title,
                description = list.summary.orEmpty(),
                image = cover,
                recipeCount = list.coordinates.size,
            )
            resolveCoordinates(key, coordinates, recipeRepo)
        }
    }

    /**
     * Resolve [coordinates] into the recipe grid for the load identified by [key]:
     * cache-first (event cache + ObjectBox), then a network fill of the misses,
     * emitting incrementally. Bails out if a newer load supersedes [key]. Clears
     * [_isLoading] when it runs to completion. Shared by Recipe Packs and Cookbook
     * collections so both surfaces resolve identically.
     */
    private suspend fun resolveCoordinates(
        key: String,
        coordinates: List<PackRecipeCoordinate>,
        recipeRepo: RecipeRepository,
    ) {
        val orderedKeys = coordinates.map(::coordinateKey)
        _pendingCount.value = orderedKeys.size

        val resolvedByKey = LinkedHashMap<String, RecipeParser.Recipe>()
        fun emitResolved() {
            _recipes.value = orderedKeys.mapNotNull { resolvedByKey[it] }
            _pendingCount.value = (orderedKeys.size - resolvedByKey.size - _failedCount.value).coerceAtLeast(0)
        }

        // Cache-first (event cache + ObjectBox), then network fill missing coordinates.
        for (coord in coordinates) {
            if (loadedKey != key || !kotlin.coroutines.coroutineContext.isActive) return
            val event = recipeRepo.findRecipeEventByCoordinate(coord.kind, coord.author, coord.dTag)
            val parsed = event?.let { RecipeFormats.forEvent(it)?.parse(it) }
            if (parsed != null) {
                resolvedByKey[coordinateKey(coord)] = parsed
            }
        }
        if (loadedKey != key || !kotlin.coroutines.coroutineContext.isActive) return
        emitResolved()

        val unresolved = coordinates.filter { coordinateKey(it) !in resolvedByKey }
        for (coord in unresolved) {
            if (loadedKey != key || !kotlin.coroutines.coroutineContext.isActive) return
            val event = recipeRepo.requestRecipeEventByCoordinate(coord.kind, coord.author, coord.dTag)
            val parsed = event?.let { RecipeFormats.forEvent(it)?.parse(it) }
            if (parsed != null) {
                resolvedByKey[coordinateKey(coord)] = parsed
            } else {
                _failedCount.value = _failedCount.value + 1
            }
            if (loadedKey != key || !kotlin.coroutines.coroutineContext.isActive) return
            emitResolved()
        }

        _isLoading.value = false
    }

    private fun coordinateKey(coord: PackRecipeCoordinate): String =
        "${coord.kind}:${coord.author}:${coord.dTag}"
}

package cooking.zap.app.viewmodel

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.repo.RecipePackRepository
import cooking.zap.app.repo.RecipePackSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class RecipePacksTab { DISCOVER, MINE, SAVED }

class RecipePacksViewModel : ViewModel() {
    private val _selectedTab = MutableStateFlow(RecipePacksTab.DISCOVER)
    val selectedTab: StateFlow<RecipePacksTab> = _selectedTab

    private val _discoverPacks = MutableStateFlow<List<RecipePackSummary>>(emptyList())
    val discoverPacks: StateFlow<List<RecipePackSummary>> = _discoverPacks

    private val _minePacks = MutableStateFlow<List<RecipePackSummary>>(emptyList())
    val minePacks: StateFlow<List<RecipePackSummary>> = _minePacks

    private val _savedPacks = MutableStateFlow<List<RecipePackSummary>>(emptyList())
    val savedPacks: StateFlow<List<RecipePackSummary>> = _savedPacks

    private val _isDiscoverLoading = MutableStateFlow(false)
    val isDiscoverLoading: StateFlow<Boolean> = _isDiscoverLoading

    private val _isMineLoading = MutableStateFlow(false)
    val isMineLoading: StateFlow<Boolean> = _isMineLoading

    private val _isSavedLoading = MutableStateFlow(false)
    val isSavedLoading: StateFlow<Boolean> = _isSavedLoading

    private var started = false
    private var repo: RecipePackRepository? = null
    private var userPubkeyProvider: (() -> String?)? = null
    private val lastFetchAtMs = mutableMapOf<RecipePacksTab, Long>()

    companion object {
        private const val REFRESH_DEBOUNCE_MS = 15_000L
    }

    fun load(
        recipePackRepo: RecipePackRepository,
        currentUserPubkey: () -> String?,
    ) {
        repo = recipePackRepo
        userPubkeyProvider = currentUserPubkey
        if (!started) {
            started = true
            viewModelScope.launch { recipePackRepo.discoverPacks.collect { _discoverPacks.value = it } }
            viewModelScope.launch { recipePackRepo.minePacks.collect { _minePacks.value = it } }
            viewModelScope.launch { recipePackRepo.savedPacks.collect { _savedPacks.value = it } }
            viewModelScope.launch { recipePackRepo.isDiscoverLoading.collect { _isDiscoverLoading.value = it } }
            viewModelScope.launch { recipePackRepo.isMineLoading.collect { _isMineLoading.value = it } }
            viewModelScope.launch { recipePackRepo.isSavedLoading.collect { _isSavedLoading.value = it } }
        }
        // Cache paint must happen on every activation.
        activateCurrentTab(forceNetwork = false)
    }

    fun selectTab(tab: RecipePacksTab) {
        _selectedTab.value = tab
        activateTab(tab, forceNetwork = false)
    }

    fun refreshActiveTab() {
        activateCurrentTab(forceNetwork = true)
    }

    /** Called when the main Packs tab becomes active (including re-entry). */
    fun onPacksActivated() {
        activateCurrentTab(forceNetwork = false)
    }

    private fun activateCurrentTab(forceNetwork: Boolean) {
        activateTab(_selectedTab.value, forceNetwork = forceNetwork)
    }

    private fun activateTab(tab: RecipePacksTab, forceNetwork: Boolean) {
        val repo = repo ?: return
        val userPubkey = userPubkeyProvider?.invoke()
        val shouldFetch = shouldFetch(tab, forceNetwork)

        // Avoid cache-vs-fetch races: when we fetch, repository load* already paints
        // cache first; when debounced, paint directly here.
        if (shouldFetch) {
            when (tab) {
                RecipePacksTab.DISCOVER -> repo.loadDiscover()
                RecipePacksTab.MINE -> repo.loadMine(userPubkey)
                RecipePacksTab.SAVED -> repo.loadSaved(userPubkey)
            }
            lastFetchAtMs[tab] = SystemClock.elapsedRealtime()
            return
        }
        when (tab) {
            RecipePacksTab.DISCOVER -> repo.paintDiscoverFromCache()
            RecipePacksTab.MINE -> repo.paintMineFromCache(userPubkey)
            RecipePacksTab.SAVED -> repo.paintSavedFromCache(userPubkey)
        }
    }

    private fun shouldFetch(tab: RecipePacksTab, forceNetwork: Boolean): Boolean {
        if (forceNetwork) return true
        val last = lastFetchAtMs[tab] ?: return true
        return (SystemClock.elapsedRealtime() - last) >= REFRESH_DEBOUNCE_MS
    }
}


package cooking.zap.app.viewmodel

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

    fun load(
        recipePackRepo: RecipePackRepository,
        currentUserPubkey: () -> String?,
    ) {
        if (started) return
        started = true
        repo = recipePackRepo
        userPubkeyProvider = currentUserPubkey
        viewModelScope.launch { recipePackRepo.discoverPacks.collect { _discoverPacks.value = it } }
        viewModelScope.launch { recipePackRepo.minePacks.collect { _minePacks.value = it } }
        viewModelScope.launch { recipePackRepo.savedPacks.collect { _savedPacks.value = it } }
        viewModelScope.launch { recipePackRepo.isDiscoverLoading.collect { _isDiscoverLoading.value = it } }
        viewModelScope.launch { recipePackRepo.isMineLoading.collect { _isMineLoading.value = it } }
        viewModelScope.launch { recipePackRepo.isSavedLoading.collect { _isSavedLoading.value = it } }
        recipePackRepo.loadDiscover()
    }

    fun selectTab(tab: RecipePacksTab) {
        _selectedTab.value = tab
        when (tab) {
            RecipePacksTab.DISCOVER -> repo?.loadDiscover()
            RecipePacksTab.MINE -> repo?.loadMine(userPubkeyProvider?.invoke())
            RecipePacksTab.SAVED -> repo?.loadSaved(userPubkeyProvider?.invoke())
        }
    }

    fun refreshActiveTab() {
        selectTab(_selectedTab.value)
    }
}


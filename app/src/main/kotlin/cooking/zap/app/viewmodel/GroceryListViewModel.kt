package cooking.zap.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.nostr.GroceryEvents.GroceryItem
import cooking.zap.app.nostr.GroceryEvents.GroceryList
import cooking.zap.app.repo.GroceryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the grocery hub section (UI arrives in PR 5). Mirrors
 * [GroceryRepository]'s flows and forwards mutations, following the
 * [CookbookViewModel] bind/launchWrite shape (plain AndroidX ViewModel, no DI).
 *
 * READ_ONLY / signed-out accounts have a null signer: [canWrite] is false and
 * the UI shows the section-level signed-out state (the `requiresAccount`
 * pattern from PR 2). The repo also rejects writes without a signer, so this is
 * a UX gate, not the safety boundary.
 */
class GroceryListViewModel : ViewModel() {

    private val _lists = MutableStateFlow<List<GroceryList>>(emptyList())
    val lists: StateFlow<List<GroceryList>> = _lists.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _selectedListId = MutableStateFlow<String?>(null)
    val selectedListId: StateFlow<String?> = _selectedListId.asStateFlow()

    private val _selectedList = MutableStateFlow<GroceryList?>(null)
    /** The currently-open single list, or null when none is selected / it vanished. */
    val selectedList: StateFlow<GroceryList?> = _selectedList.asStateFlow()

    private val _canWrite = MutableStateFlow(false)
    /** False for READ_ONLY / signed-out — the UI gates the create/edit affordances. */
    val canWrite: StateFlow<Boolean> = _canWrite.asStateFlow()

    private var bound = false
    private var repo: GroceryRepository? = null
    private var canWriteProvider: () -> Boolean = { false }

    /** Publish outcomes (sent-relay count / 0-relay / signer failure) for UI surfacing. */
    val writeResults: SharedFlow<GroceryRepository.WriteResult>
        get() = requireNotNull(repo).writeResults

    /**
     * Wire this VM to [repo]. [canWriteProvider] reports whether the active
     * account can sign (false for READ_ONLY); it is re-read on [bind] and
     * [load] — the UI calls [load] when the section opens, which is also when
     * an account switch becomes visible. Collectors are wired exactly once, to
     * the FIRST-bound repo: the app has a single [GroceryRepository] instance
     * (owned by FeedViewModel), so later binds only refresh the mutation
     * target/provider refs and must pass that same instance.
     */
    fun bind(repo: GroceryRepository, canWriteProvider: () -> Boolean) {
        this.repo = repo
        this.canWriteProvider = canWriteProvider
        _canWrite.value = canWriteProvider()
        if (bound) return
        bound = true
        viewModelScope.launch {
            repo.lists.collect { lists ->
                _lists.value = lists
                // Keep the open single-list view in sync with repo updates.
                _selectedList.value = _selectedListId.value?.let { id -> lists.firstOrNull { it.id == id } }
            }
        }
        viewModelScope.launch { repo.isLoading.collect { _isLoading.value = it } }
    }

    /** (Re)load for the current account; refreshes [canWrite]. */
    fun load() {
        _canWrite.value = canWriteProvider()
        repo?.load()
    }

    fun openList(id: String) {
        _selectedListId.value = id
        _selectedList.value = _lists.value.firstOrNull { it.id == id }
    }

    fun closeList() {
        // Flush the open list's pending edits before leaving it.
        val id = _selectedListId.value
        _selectedListId.value = null
        _selectedList.value = null
        if (id != null) launchWrite { it.saveNow(id) }
    }

    // ---- mutations (thin pass-throughs; repo is optimistic + debounced) -----

    fun createList(title: String) = launchWrite { it.createList(title) }

    /** PR 5 accommodation: create and return the new list's id so the UI can navigate into it. */
    suspend fun createListReturningId(title: String): String? = repo?.createList(title)
    fun renameList(id: String, title: String) = launchSync { it.renameList(id, title) }
    fun setNotes(id: String, notes: String) = launchSync { it.setNotes(id, notes) }
    fun addItem(id: String, item: GroceryItem) = launchSync { it.addItem(id, item) }
    fun editItem(id: String, item: GroceryItem) = launchSync { it.editItem(id, item) }
    fun setChecked(id: String, itemId: String, checked: Boolean) = launchSync { it.setChecked(id, itemId, checked) }
    fun removeItem(id: String, itemId: String) = launchSync { it.removeItem(id, itemId) }
    fun deleteList(id: String) = launchWrite { it.deleteList(id) }

    /** For suspend repo calls (create/delete/save) off the main thread. */
    private inline fun launchWrite(crossinline block: suspend (GroceryRepository) -> Unit) {
        val r = repo ?: return
        viewModelScope.launch(Dispatchers.Default) { block(r) }
    }

    /** For synchronous repo mutators (optimistic state update, debounced publish). */
    private inline fun launchSync(crossinline block: (GroceryRepository) -> Unit) {
        val r = repo ?: return
        block(r)
    }
}

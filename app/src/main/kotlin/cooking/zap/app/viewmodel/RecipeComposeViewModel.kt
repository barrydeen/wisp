package cooking.zap.app.viewmodel

import android.content.ContentResolver
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.nostr.NostrSigner
import cooking.zap.app.nostr.RecipeParser
import cooking.zap.app.nostr.RecipeSerializer
import cooking.zap.app.repo.BlossomRepository
import cooking.zap.app.repo.RecipePublisher
import cooking.zap.app.ui.util.MediaCompressor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs [cooking.zap.app.ui.screen.RecipeComposeScreen] — authoring a recipe
 * from scratch and publishing it as a kind-30023 event via the proven 2.2
 * spine ([RecipePublisher]), the same path Sous Chef "Save" uses (concern:
 * recipe-compose).
 *
 * Form fields mirror the web `/create` order: title, categories, summary,
 * chef's notes, prep/cook/servings, ingredients, directions, photos,
 * additional resources. Images are uploaded to Blossom **as they're picked**;
 * publish is blocked until every upload has resolved (no half-uploaded image
 * can be signed in). State survives rotation but **not** process death — v1 has
 * no draft autosave (web parity); persistence is a follow-up.
 */
class RecipeComposeViewModel : ViewModel() {

    /** A single ingredient/direction row — stable [id] so Compose keys survive edits/removals. */
    data class Row(val id: Long, val text: String)

    /** A picked image and its Blossom upload status. */
    data class ImageItem(val id: Long, val status: Status) {
        sealed interface Status {
            data object Uploading : Status
            data class Done(val url: String) : Status
            data class Failed(val message: String) : Status
        }
    }

    sealed interface PublishState {
        data object Idle : PublishState
        data object Publishing : PublishState
        data class Error(val message: String) : PublishState
        data class Published(val author: String, val dTag: String) : PublishState
    }

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title

    private val _categories = MutableStateFlow<List<String>>(emptyList())
    val categories: StateFlow<List<String>> = _categories

    private val _summary = MutableStateFlow("")
    val summary: StateFlow<String> = _summary

    private val _chefNotes = MutableStateFlow("")
    val chefNotes: StateFlow<String> = _chefNotes

    private val _prepTime = MutableStateFlow("")
    val prepTime: StateFlow<String> = _prepTime

    private val _cookTime = MutableStateFlow("")
    val cookTime: StateFlow<String> = _cookTime

    private val _servings = MutableStateFlow("")
    val servings: StateFlow<String> = _servings

    private val _additionalResources = MutableStateFlow("")
    val additionalResources: StateFlow<String> = _additionalResources

    private val _ingredients = MutableStateFlow(listOf(Row(nextId(), "")))
    val ingredients: StateFlow<List<Row>> = _ingredients

    private val _directions = MutableStateFlow(listOf(Row(nextId(), "")))
    val directions: StateFlow<List<Row>> = _directions

    private val _images = MutableStateFlow<List<ImageItem>>(emptyList())
    val images: StateFlow<List<ImageItem>> = _images

    private val _publishState = MutableStateFlow<PublishState>(PublishState.Idle)
    val publishState: StateFlow<PublishState> = _publishState

    // --- simple field setters ---
    fun setTitle(v: String) { _title.value = v }
    fun setSummary(v: String) { _summary.value = v }
    fun setChefNotes(v: String) { _chefNotes.value = v }
    fun setPrepTime(v: String) { _prepTime.value = v }
    fun setCookTime(v: String) { _cookTime.value = v }
    fun setServings(v: String) { _servings.value = v }
    fun setAdditionalResources(v: String) { _additionalResources.value = v }

    // --- categories (free-text chips) ---
    fun addCategory(raw: String) {
        val v = raw.trim()
        if (v.isEmpty()) return
        // De-dupe on the slugged form so "Italian" and "italian" don't both add.
        val slug = RecipeSerializer.slug(v.lowercase())
        if (_categories.value.any { RecipeSerializer.slug(it.lowercase()) == slug }) return
        _categories.update { it + v }
    }

    fun removeCategory(value: String) {
        _categories.update { list -> list.filterNot { it == value } }
    }

    // --- ingredient / direction rows ---
    fun updateIngredient(id: Long, text: String) = updateRow(_ingredients, id, text)
    fun addIngredient() = addRow(_ingredients)
    fun removeIngredient(id: Long) = removeRow(_ingredients, id)

    fun updateDirection(id: Long, text: String) = updateRow(_directions, id, text)
    fun addDirection() = addRow(_directions)
    fun removeDirection(id: Long) = removeRow(_directions, id)

    private fun updateRow(flow: MutableStateFlow<List<Row>>, id: Long, text: String) =
        flow.update { rows -> rows.map { if (it.id == id) it.copy(text = text) else it } }

    private fun addRow(flow: MutableStateFlow<List<Row>>) =
        flow.update { it + Row(nextId(), "") }

    private fun removeRow(flow: MutableStateFlow<List<Row>>, id: Long) =
        flow.update { rows ->
            val next = rows.filterNot { it.id == id }
            // Always keep at least one (empty) row so the field never disappears.
            next.ifEmpty { listOf(Row(nextId(), "")) }
        }

    // --- images ---

    /**
     * Read each [uris] entry, compress it, and upload to Blossom. The item is
     * appended in [ImageItem.Status.Uploading] immediately (so the UI shows a
     * placeholder + blocks publish) and flips to [Done]/[Failed] when the
     * upload resolves. [signer] must be non-null (READ_ONLY can't reach here).
     */
    fun addImages(
        uris: List<Uri>,
        contentResolver: ContentResolver,
        blossomRepo: BlossomRepository,
        signer: NostrSigner?,
    ) {
        if (signer == null) return
        // Enqueue all placeholders synchronously (UI shows them + publish is
        // blocked immediately), then read/compress/upload sequentially from a
        // single IO coroutine — never read bytes or sniff MIME on the Main
        // thread, and never fan out N parallel uploads at once (resource spike).
        val pending = uris.map { uri -> nextId() to uri }
        _images.update { list -> list + pending.map { (id, _) -> ImageItem(id, ImageItem.Status.Uploading) } }
        viewModelScope.launch(Dispatchers.IO) {
            for ((id, uri) in pending) {
                val status = try {
                    val rawBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("Couldn't read the selected image.")
                    val rawMime = contentResolver.getType(uri) ?: "image/jpeg"
                    val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(rawMime) ?: "jpg"
                    val compressed = if (rawMime.startsWith("image/")) {
                        MediaCompressor.compressForContent(rawBytes, rawMime).asTriple()
                    } else {
                        Triple(rawBytes, rawMime, ext)
                    }
                    val url = blossomRepo.uploadMedia(compressed.first, compressed.second, compressed.third, signer)
                    ImageItem.Status.Done(url)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ImageItem.Status.Failed(e.message ?: "Upload failed")
                }
                _images.update { list -> list.map { if (it.id == id) it.copy(status = status) else it } }
            }
        }
    }

    fun removeImage(id: Long) {
        _images.update { list -> list.filterNot { it.id == id } }
    }

    // --- derived validation (mirrors the web `canPublish` + upload-block guard) ---

    private val hostedImageUrls: List<String>
        get() = _images.value.mapNotNull { (it.status as? ImageItem.Status.Done)?.url }

    private val hasPendingOrFailedUpload: Boolean
        get() = _images.value.any { it.status !is ImageItem.Status.Done }

    /** Trimmed, non-blank ingredient strings, in row order. */
    private fun cleanIngredients() = _ingredients.value.map { it.text.trim() }.filter { it.isNotEmpty() }
    private fun cleanDirections() = _directions.value.map { it.text.trim() }.filter { it.isNotEmpty() }

    /**
     * Why publish is blocked, or null if ready. Drives both the disabled button
     * and the helper text — never a silently-disabled button.
     */
    fun blockReason(canSign: Boolean): String? = when {
        !canSign -> "Sign in to publish recipes."
        _title.value.isBlank() -> "Add a title."
        _categories.value.isEmpty() -> "Add at least one category."
        _images.value.isEmpty() -> "Add at least one photo."
        hasPendingOrFailedUpload -> "Wait for photos to finish uploading (remove any that failed)."
        cleanIngredients().isEmpty() -> "Add at least one ingredient."
        cleanDirections().isEmpty() -> "Add at least one direction."
        else -> null
    }

    /**
     * Build the recipe from the form and publish via the multi-image
     * [RecipePublisher] overload. Re-validates defensively; the screen also
     * gates the button. Optimistic: on success the just-signed event is already
     * cached, so the caller can navigate straight to the recipe.
     */
    fun publish(publisher: RecipePublisher, signer: NostrSigner?, clientTagEnabled: Boolean) {
        if (_publishState.value == PublishState.Publishing) return
        if (signer == null) {
            _publishState.value = PublishState.Error("Sign in to publish recipes.")
            return
        }
        val reason = blockReason(canSign = true)
        if (reason != null) {
            _publishState.value = PublishState.Error(reason)
            return
        }
        // Snapshot every field once, up front — the user could keep editing
        // while the async publish runs; the built Recipe and the signed tags
        // must agree.
        val title = _title.value.trim()
        val imageUrls = hostedImageUrls
        val categories = _categories.value
        val recipe = RecipeParser.Recipe(
            id = "",
            author = signer.pubkeyHex,
            dTag = RecipeSerializer.slug(title),
            title = title,
            image = imageUrls.firstOrNull(),
            summary = _summary.value.trim().ifBlank { null },
            publishedAt = 0L,
            hashtags = emptyList(),
            categories = categories,
            content = RecipeParser.RecipeContent(
                chefNotes = _chefNotes.value.trim().ifBlank { null },
                details = RecipeParser.RecipeDetails(
                    prepTime = _prepTime.value.trim().ifBlank { null },
                    cookTime = _cookTime.value.trim().ifBlank { null },
                    servings = _servings.value.trim().ifBlank { null },
                ),
                ingredients = cleanIngredients(),
                directions = cleanDirections(),
                additionalMarkdown = _additionalResources.value.trim().ifBlank { null },
            ),
        )
        _publishState.value = PublishState.Publishing
        viewModelScope.launch {
            _publishState.value = when (
                val r = publisher.publish(
                    recipe = recipe,
                    categories = categories,
                    imageUrls = imageUrls,
                    signer = signer,
                    includeClientTag = clientTagEnabled,
                )
            ) {
                is RecipePublisher.Result.Published -> PublishState.Published(r.author, r.dTag)
                is RecipePublisher.Result.Error -> PublishState.Error(r.message)
            }
        }
    }

    companion object {
        // Process-wide monotonic row/image ids (stable Compose keys). Not for crypto.
        private var counter = 0L
        @Synchronized private fun nextId(): Long = ++counter
    }
}

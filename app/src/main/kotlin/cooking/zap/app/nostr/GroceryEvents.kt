package cooking.zap.app.nostr

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Grocery-list events — the Android side of the web's canonical grocery
 * contract (frontend `src/lib/services/groceryService.ts`). **Byte parity with
 * web is the acceptance criterion**: an event written here must be structurally
 * indistinguishable from a web-written one (same kind, same tag array, same
 * payload JSON key order), and both clients must read each other's events.
 *
 * Event shape:
 *  - kind [KIND] (NIP-78 application data, addressable per d-tag)
 *  - tags `[["d","grocery-{id}"],["client","Zap Cooking"]]` plus one
 *    `["a","30023:..."]` per linked recipe
 *  - content: NIP-44 self-encrypted JSON `{id,title,items,notes,createdAt,updatedAt}`
 *    (web `JSON.stringify` key order; `recipeLinks` live in tags only)
 *
 * PRIVACY NOTE (ported as-is for parity): the recipe `a` tags are PLAINTEXT —
 * any relay operator can see which recipes feed a user's shopping list, even
 * though the list contents are encrypted. This deliberately mirrors web (the
 * mealplan contract explicitly deviates from it; see
 * docs/mealplan-contract.md "No plaintext recipe tags"). Tracked upstream as
 * zapcooking/frontend#549 — do not "fix" here without a cross-platform
 * decision, or the two clients stop round-tripping.
 *
 * Grocery has NO decrypt-failed state — an unreadable list is skipped
 * per-list ([decryptListEvent] returns null). That distinction is
 * planner-only, per the mealplan contract.
 */
object GroceryEvents {
    const val KIND = 30078
    const val D_TAG_PREFIX = "grocery-"

    /** Web parity: default title when the payload carries none. */
    const val UNTITLED = "Untitled List"

    /** Web categories (groceryService.ts GroceryCategory). Kept as plain strings
     *  so a future web category never breaks Android reads. */
    val CATEGORIES = listOf("produce", "protein", "dairy", "pantry", "frozen", "other")
    const val CATEGORY_OTHER = "other"

    private const val ID_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"

    // Web parity: readers ignore unknown keys; writers omit null/absent fields
    // (JSON.stringify drops undefined) but DO emit defaults like checked=false.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /**
     * One grocery item. Field order IS the wire order (web createGroceryItem's
     * object literal): id, name, quantity, category, checked, recipeId, addedAt.
     * All fields defaulted so a sparse/legacy web item still decodes.
     */
    @Serializable
    data class GroceryItem(
        val id: String = "",
        val name: String = "",
        val quantity: String = "",
        val category: String = CATEGORY_OTHER,
        val checked: Boolean = false,
        /** a-coordinate of the recipe this item came from ("30023:pubkey:dTag"). */
        val recipeId: String? = null,
        val addedAt: Long = 0,
    )

    /** A decrypted grocery list. [recipeLinks] come from the event's `a` tags, not the payload. */
    data class GroceryList(
        val id: String,
        val title: String,
        val items: List<GroceryItem>,
        val recipeLinks: List<String>,
        val notes: String? = null,
        val createdAt: Long,
        val updatedAt: Long,
    )

    /** The encrypted JSON payload — declaration order is the wire key order
     *  (web saveGroceryList's object literal). */
    @Serializable
    private data class Payload(
        val id: String = "",
        val title: String = "",
        val items: List<GroceryItem> = emptyList(),
        val notes: String? = null,
        val createdAt: Long = 0,
        val updatedAt: Long = 0,
    )

    // ---- ids & d-tags ------------------------------------------------------

    /** Web parity (generateListId): base36 millis + "-" + 6 base36 chars. */
    fun generateListId(): String {
        val timestamp = System.currentTimeMillis().toString(36)
        val random = buildString(6) { repeat(6) { append(ID_ALPHABET.random()) } }
        return "$timestamp-$random"
    }

    fun dTagFor(listId: String): String = "$D_TAG_PREFIX$listId"

    /** List id from a d-tag, or null when it isn't a grocery d-tag. */
    fun listIdFromDTag(dTag: String): String? =
        if (dTag.startsWith(D_TAG_PREFIX)) dTag.removePrefix(D_TAG_PREFIX) else null

    private fun dTagOf(event: NostrEvent): String? =
        event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)

    fun listIdFrom(event: NostrEvent): String? = dTagOf(event)?.let { listIdFromDTag(it) }

    // ---- write side ---------------------------------------------------------

    /**
     * The exact tag array web writes (groceryService.saveGroceryList): d + client,
     * then one plaintext recipe `a` tag per link (see the privacy note above).
     * The client tag is unconditional — part of the contract, not the user's
     * client-tag preference (Nip78 precedent).
     */
    fun buildTags(list: GroceryList): List<List<String>> {
        val tags = mutableListOf(
            listOf("d", dTagFor(list.id)),
            Nip89.clientTag(),
        )
        list.recipeLinks.forEach { tags.add(listOf("a", it)) }
        return tags
    }

    /** The encrypted-payload JSON, byte-matching web's `JSON.stringify` output. */
    fun encodePayload(list: GroceryList): String = json.encodeToString(
        Payload(
            id = list.id,
            title = list.title,
            items = list.items,
            notes = list.notes,
            createdAt = list.createdAt,
            updatedAt = list.updatedAt,
        )
    )

    /**
     * Build + sign a grocery-list event: NIP-44 self-encrypt the payload, then
     * sign with the contract tag set. Always writes nip44 (web parity — nip04 is
     * a legacy read path only). Timestamps in [list] are the caller's concern.
     */
    suspend fun createListEvent(signer: NostrSigner, list: GroceryList): NostrEvent {
        val encrypted = signer.nip44Encrypt(encodePayload(list), signer.pubkeyHex)
        return signer.signEvent(kind = KIND, content = encrypted, tags = buildTags(list))
    }

    // ---- read side ----------------------------------------------------------

    /**
     * Web-parity pre-filter (groceryService.fetchGroceryLists "isOurEvent"):
     * accept by client tag OR grocery d-tag prefix. Deliberately broad — a
     * client-tagged mealplan/backup passes this and is then dropped by
     * [decryptListEvent]'s d-tag check, exactly like web.
     */
    fun isGroceryCandidate(event: NostrEvent): Boolean {
        if (event.kind != KIND) return false
        val clientTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "client" }?.get(1)
        if (clientTag == Nip89.CLIENT_NAME) return true
        return dTagOf(event)?.startsWith(D_TAG_PREFIX) == true
    }

    /** Web parity: only kind-30023 recipe coordinates count as links. */
    fun extractRecipeLinks(event: NostrEvent): List<String> =
        event.tags
            .filter { it.size >= 2 && it[0] == "a" && it[1].startsWith("30023:") }
            .map { it[1] }

    /** Author-scoped fetch filter (web parity: no `#d` — relays can't prefix-match,
     *  so reads fetch the author's 30078s and filter client-side). */
    fun groceryFilter(pubkeyHex: String): Filter = Filter(
        kinds = listOf(KIND),
        authors = listOf(pubkeyHex),
        limit = 100,
    )

    /**
     * Decrypt + parse one grocery event. Returns null for non-grocery d-tags,
     * refused/denied prompts (via [gate]), and any decrypt/parse failure — a
     * broken list is skipped, never fatal, and grocery deliberately has no
     * decrypt-failed state (planner-only distinction).
     *
     * Legacy web lists may be NIP-04 (`?iv=` envelope) — dispatched by
     * [detectEncryptionMethod] to the signer's nip04 path.
     */
    suspend fun decryptListEvent(
        event: NostrEvent,
        signer: NostrSigner,
        gate: SignerDecryptGate? = null,
    ): GroceryList? {
        val listId = listIdFrom(event) ?: return null
        if (event.content.isBlank()) return null
        return try {
            val decrypt: suspend () -> String = when (detectEncryptionMethod(event.content)) {
                EncryptionMethod.NIP44 -> {
                    { signer.nip44Decrypt(event.content, event.pubkey) }
                }
                EncryptionMethod.NIP04 -> {
                    { signer.nip04Decrypt(event.content, event.pubkey) }
                }
            }
            val plaintext = (if (gate != null) gate.attempt(event.content, decrypt) else decrypt())
                ?: return null
            val payload = json.decodeFromString<Payload>(plaintext)
            GroceryList(
                id = payload.id.ifBlank { listId },
                title = payload.title.ifBlank { UNTITLED },
                items = payload.items,
                recipeLinks = extractRecipeLinks(event),
                notes = payload.notes,
                createdAt = payload.createdAt.takeIf { it > 0 } ?: event.created_at,
                updatedAt = payload.updatedAt.takeIf { it > 0 } ?: event.created_at,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    // ---- deletion -----------------------------------------------------------

    /**
     * NIP-09 kind-5 deletion referencing the addressable coordinate (plus the
     * concrete event id when known) — web parity, NOT Nip78's empty-tombstone
     * style. Content string matches web's deleteGroceryList.
     */
    suspend fun createDeletionEvent(
        signer: NostrSigner,
        listId: String,
        eventId: String? = null,
    ): NostrEvent {
        val tags = Nip09
            .buildAddressableDeletionTags(KIND, signer.pubkeyHex, dTagFor(listId))
            .toMutableList()
        if (eventId != null) tags.add(listOf("e", eventId))
        return signer.signEvent(kind = 5, content = "Deleted grocery list", tags = tags)
    }
}

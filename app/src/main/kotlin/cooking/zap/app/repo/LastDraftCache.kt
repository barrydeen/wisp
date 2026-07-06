package cooking.zap.app.repo

import android.content.Context

/**
 * Pubkey-keyed local cache of the most recent top-level compose draft, backing the
 * "continue where you left off" fast path. Survives cold starts and [cooking.zap.app.viewmodel.ComposeViewModel.clear],
 * so it must be cleared explicitly when a draft is deleted/discarded — otherwise a stale
 * entry silently resurrects an old post the next time the composer opens.
 *
 * Extracted from ComposeViewModel so the Drafts screen (a different ViewModel) and logout
 * can also clear it; the prefs file/keys are unchanged so existing caches keep working.
 *
 * Reads/writes go through a tiny [Store] seam so the per-account isolation (clearing account A
 * must not touch account B) can be pinned hermetically with an in-memory fake — the hermetic JVM
 * suite has no Robolectric, so the live SharedPreferences path is exercised on-device.
 */
class LastDraftCache internal constructor(private val store: Store) {

    constructor(context: Context) : this(
        SharedPrefsStore(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    )

    fun save(pubkeyHex: String, content: String, draftId: String) {
        store.putAll(mapOf(contentKey(pubkeyHex) to content, idKey(pubkeyHex) to draftId))
    }

    fun getContent(pubkeyHex: String): String? = store.get(contentKey(pubkeyHex))

    fun getId(pubkeyHex: String): String? = store.get(idKey(pubkeyHex))

    /** Clear the cache for a single account only (discard / account switch). Other accounts'
     *  pubkey-keyed entries are untouched. */
    fun clear(pubkeyHex: String) {
        store.remove(listOf(contentKey(pubkeyHex), idKey(pubkeyHex)))
    }

    /** Wipe every account's cache (full logout — no accounts remain on the device). */
    fun clearAll() {
        store.clearAll()
    }

    /**
     * Clear the cache only when it currently points at [dTag] — an unrelated top-level draft
     * cached under this account must survive deleting a different one. Returns true if cleared.
     */
    fun clearIfId(pubkeyHex: String, dTag: String): Boolean {
        if (!matchesCachedDraft(store.get(idKey(pubkeyHex)), dTag)) return false
        clear(pubkeyHex)
        return true
    }

    private fun contentKey(pubkeyHex: String) = "content_$pubkeyHex"
    private fun idKey(pubkeyHex: String) = "id_$pubkeyHex"

    /** Minimal key-value seam over the backing store; batched writes mirror a SharedPreferences edit. */
    internal interface Store {
        fun get(key: String): String?
        fun putAll(values: Map<String, String>)
        fun remove(keys: List<String>)
        fun clearAll()
    }

    private class SharedPrefsStore(
        private val prefs: android.content.SharedPreferences
    ) : Store {
        override fun get(key: String): String? = prefs.getString(key, null)
        override fun putAll(values: Map<String, String>) {
            prefs.edit().apply { values.forEach { (k, v) -> putString(k, v) } }.apply()
        }
        override fun remove(keys: List<String>) {
            prefs.edit().apply { keys.forEach { remove(it) } }.apply()
        }
        override fun clearAll() {
            prefs.edit().clear().apply()
        }
    }

    /** Which cache action logout should take. See [logoutCacheAction]. */
    enum class LogoutCacheAction { CLEAR_REMOVED_ACCOUNT, CLEAR_ALL }

    companion object {
        const val PREFS_NAME = "compose_last_draft"

        /**
         * The cache action for a logout. When other accounts remain (an account switch after
         * removing one), clear ONLY the removed account's pubkey-keyed entries — a remaining
         * account's cached draft must survive. On a full logout (no accounts remain), wipe all.
         */
        fun logoutCacheAction(hasRemainingAccounts: Boolean): LogoutCacheAction =
            if (hasRemainingAccounts) LogoutCacheAction.CLEAR_REMOVED_ACCOUNT else LogoutCacheAction.CLEAR_ALL

        /**
         * Whether a delete of [dTag] should drop the cache: only when the cached id is present
         * and equal. A null cached id (nothing cached, or a different account's entry that this
         * pubkey-keyed lookup didn't find) must never match — otherwise deleting one draft could
         * wipe an unrelated account's cache, or a no-op delete could clear a live draft.
         */
        fun matchesCachedDraft(cachedId: String?, dTag: String): Boolean =
            cachedId != null && cachedId == dTag
    }
}

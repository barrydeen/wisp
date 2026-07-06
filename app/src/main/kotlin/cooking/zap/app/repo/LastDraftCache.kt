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
 */
class LastDraftCache(context: Context) {
    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(pubkeyHex: String, content: String, draftId: String) {
        prefs.edit()
            .putString(contentKey(pubkeyHex), content)
            .putString(idKey(pubkeyHex), draftId)
            .apply()
    }

    fun getContent(pubkeyHex: String): String? = prefs.getString(contentKey(pubkeyHex), null)

    fun getId(pubkeyHex: String): String? = prefs.getString(idKey(pubkeyHex), null)

    /** Clear the cache unconditionally for [pubkeyHex] (discard / logout). */
    fun clear(pubkeyHex: String) {
        prefs.edit()
            .remove(contentKey(pubkeyHex))
            .remove(idKey(pubkeyHex))
            .apply()
    }

    /**
     * Clear the cache only when it currently points at [dTag] — an unrelated top-level draft
     * cached under this account must survive deleting a different one. Returns true if cleared.
     */
    fun clearIfId(pubkeyHex: String, dTag: String): Boolean {
        if (!matchesCachedDraft(prefs.getString(idKey(pubkeyHex), null), dTag)) return false
        clear(pubkeyHex)
        return true
    }

    private fun contentKey(pubkeyHex: String) = "content_$pubkeyHex"
    private fun idKey(pubkeyHex: String) = "id_$pubkeyHex"

    companion object {
        const val PREFS_NAME = "compose_last_draft"

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

package com.wisp.app.viewmodel

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** An @mention range in the compose text, as the composer restores it. */
@Serializable
data class DraftMention(val start: Int, val end: Int, val pubkey: String)

/** One uploaded attachment: everything the composer needs to re-render it. */
@Serializable
data class DraftAttachment(
    val url: String,
    val mimeType: String,
    val width: Int? = null,
    val height: Int? = null,
    val thumbhash: String? = null
)

/**
 * Snapshot of the composer handed to [PowManager] at publish time, in the same
 * shape the composer hydrates back from. Carries exactly what a restore needs —
 * body text, mentions, NSFW / PoW toggles and uploaded attachments. Poll and
 * gallery structure aren't part of it; those ride on the status pill's Retry,
 * which replays the prepared event rather than the composer state.
 */
@Serializable
data class DraftPayload(
    val content: String,
    val explicit: Boolean,
    val powEnabled: Boolean,
    val mentions: List<DraftMention> = emptyList(),
    val attachments: List<DraftAttachment> = emptyList()
)

/**
 * Persistent bucket behind the "keep the draft when a post is rejected or
 * mining is stopped" behavior (port of wisp-ios #465). The composer hands a
 * snapshot to [PowManager] at publish time; when the post never lands — every
 * relay rejected it, signing failed, or the user stopped mining — the snapshot
 * is written back under the composer's key, so reopening that composer slot
 * restores the text. On success the bucket is emptied, but only while it still
 * holds that same draft.
 *
 * Keys are per-pubkey and per-parent (reply / quote), so a failed reply
 * reappears in that thread's reply box rather than in some generic composer.
 * Private replies never hand over a snapshot, so they never touch this store.
 */
class ComposeDraftStore(private val prefs: SharedPreferences) {

    fun keyFor(pubkey: String, replyToId: String?, quoteToId: String?): String = when {
        replyToId != null -> "compose_draft_reply_${pubkey}_$replyToId"
        quoteToId != null -> "compose_draft_quote_${pubkey}_$quoteToId"
        else -> "compose_draft_new_$pubkey"
    }

    /**
     * Put a rejected / stopped post's text back where the composer looks for
     * it, so dismissing the status pill doesn't take the draft with it.
     *
     * Skipped when a bucket already exists under that key: the user has started
     * a newer draft in the same composer slot since hand-off, and overwriting
     * it would trade one lost draft for another. The pill's Retry still works
     * in that case — the publisher holds its own copy.
     */
    fun restoreIfAbsent(key: String?, payload: DraftPayload?): Boolean {
        if (key == null || payload == null) return false
        if (prefs.contains(key)) return false
        prefs.edit().putString(key, json.encodeToString(DraftPayload.serializer(), payload)).apply()
        return true
    }

    /**
     * Drop the composer bucket once the post is out — but only while it still
     * holds *this* draft, compared by content. The composer takes ownership of
     * a restored draft when it reopens (and re-hands-off whatever is typed
     * next), so a late success from a retried post must not take a newer draft
     * with it.
     */
    fun clearIfStillThisDraft(key: String?, content: String): Boolean {
        if (key == null) return false
        val stored = decode(prefs.getString(key, null)) ?: return false
        if (stored.content != content) return false
        prefs.edit().remove(key).apply()
        return true
    }

    /**
     * Load and remove a restored draft for the composer to hydrate from. The
     * composer owns the text from here on (its own saved-state keeps it), so
     * the bucket entry is consumed rather than left to resurrect on every open.
     */
    fun take(key: String): DraftPayload? {
        val payload = decode(prefs.getString(key, null)) ?: return null
        prefs.edit().remove(key).apply()
        return payload
    }

    private fun decode(raw: String?): DraftPayload? {
        if (raw == null) return null
        return runCatching { json.decodeFromString(DraftPayload.serializer(), raw) }.getOrNull()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun from(context: Context): ComposeDraftStore =
            ComposeDraftStore(context.getSharedPreferences("compose_draft_restore", Context.MODE_PRIVATE))
    }
}

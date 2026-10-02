package com.wisp.app.viewmodel

import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Nip13
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.relay.OutboxRouter
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.PowPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class PowStatus {
    data object Idle : PowStatus()
    data class Mining(val kind: Int, val attempts: Long, val difficulty: Int) : PowStatus()
    data class Done(val message: String) : PowStatus()
    data class Failed(val message: String) : PowStatus()
    /** The user stopped mining before a nonce was found. The draft is saved. */
    data object Stopped : PowStatus()
}

/**
 * A publish handed to this manager. Retained through [PowStatus.Failed] /
 * [PowStatus.Stopped] so [PowManager.retry] can re-run it, alongside the
 * composer snapshot that puts the text back when the post doesn't land.
 */
private class PendingNote(
    val signer: NostrSigner,
    val content: String,
    val tags: List<List<String>>,
    val kind: Int,
    val inboxPubkeys: Collection<String>,
    val onPublished: (() -> Unit)?,
    val restoreKey: String?,
    val restorePayload: DraftPayload?
)

/**
 * Publishes notes with optional NIP-13 proof-of-work, in the background.
 *
 * Single in-flight slot — a second [submitNote] cancels the first. A mining
 * run dropped that way gets its text back: the draft is restored to the
 * composer's bucket, the same thing that happens when a post is rejected by
 * every relay or the user stops mining. Nothing that doesn't land is silently
 * dropped — the note stays retryable from the status pill and the draft
 * survives dismissing the pill and reopening the composer.
 */
class PowManager(
    private val powPrefs: PowPreferences,
    private val relayPool: RelayPool,
    private val outboxRouter: OutboxRouter,
    private val eventRepo: EventRepository,
    private val draftStore: ComposeDraftStore,
    private val scope: CoroutineScope
) {
    private val _status = MutableStateFlow<PowStatus>(PowStatus.Idle)
    val status: StateFlow<PowStatus> = _status

    private var miningJob: Job? = null
    private var pendingNote: PendingNote? = null

    val isBusy: Boolean get() = _status.value is PowStatus.Mining

    fun submitNote(
        signer: NostrSigner,
        content: String,
        tags: List<List<String>>,
        kind: Int = 1,
        replyToPubkey: String? = null,
        inboxPubkeys: Collection<String> = replyToPubkey?.let { listOf(it) } ?: emptyList(),
        onPublished: (() -> Unit)? = null,
        draftRestoreKey: String? = null,
        draftRestorePayload: DraftPayload? = null
    ) {
        // Single slot: a second post drops the one still mining. Nothing has
        // been sent at that point, so hand its text back to the composer before
        // letting go of it. (A superseded *broadcast* is left alone — the event
        // is already at the relays and may well land.)
        if (_status.value is PowStatus.Mining) {
            pendingNote?.let { draftStore.restoreIfAbsent(it.restoreKey, it.restorePayload) }
        }
        miningJob?.cancel()
        pendingNote = PendingNote(
            signer, content, tags, kind, inboxPubkeys, onPublished,
            draftRestoreKey, draftRestorePayload
        )
        val difficulty = powPrefs.getNoteDifficulty()

        miningJob = scope.launch {
            val isCurrent = { miningJob === coroutineContext[Job] }
            try {
                _status.value = PowStatus.Mining(kind, 0, difficulty)
                val createdAt = System.currentTimeMillis() / 1000

                val result = withContext(Dispatchers.Default) {
                    Nip13.mine(
                        pubkeyHex = signer.pubkeyHex,
                        kind = kind,
                        content = content,
                        tags = tags,
                        targetDifficulty = difficulty,
                        createdAt = createdAt,
                        onProgress = { attempts ->
                            _status.value = PowStatus.Mining(kind, attempts, difficulty)
                        }
                    )
                }

                val event = try {
                    signer.signEvent(
                        kind = kind,
                        content = content,
                        tags = result.tags,
                        createdAt = result.createdAt
                    )
                } catch (e: Exception) {
                    fail(isCurrent, "Signing failed. Draft saved.")
                    return@launch
                }

                val msg = ClientMessage.event(event)
                var sentCount = if (inboxPubkeys.isNotEmpty()) {
                    outboxRouter.publishToInbox(msg, inboxPubkeys)
                } else {
                    relayPool.sendToWriteRelays(msg)
                }

                if (sentCount == 0) {
                    val reconnected = relayPool.ensureWriteRelaysConnected()
                    if (reconnected > 0) {
                        sentCount = if (inboxPubkeys.isNotEmpty()) {
                            outboxRouter.publishToInbox(msg, inboxPubkeys)
                        } else {
                            relayPool.sendToWriteRelays(msg)
                        }
                    }
                }

                if (sentCount == 0) {
                    fail(isCurrent, "No relay accepted the post. Draft saved.")
                    return@launch
                }

                relayPool.trackPublish(event.id, sentCount)
                eventRepo.addEvent(event)
                onPublished?.invoke()

                // A newer submitNote may have cancelled this run mid-broadcast.
                // The event still went out — it is tracked and in the repo
                // above — but the pill and the retained draft belong to the
                // newer post now.
                if (!isCurrent()) return@launch
                pendingNote = null
                _status.value = PowStatus.Done("Published to $sentCount relay${if (sentCount != 1) "s" else ""}")
                delay(3000)
                if (isCurrent()) _status.value = PowStatus.Idle
            } catch (e: CancellationException) {
                // The user stopping a mine already set Stopped and a superseding
                // submit already set Mining — either way this run no longer owns
                // the pill, so bail without overwriting either.
                throw e
            } catch (e: Exception) {
                fail(isCurrent, "${e.message ?: "Publishing failed"}. Draft saved.")
            }
        }
    }

    /**
     * Re-run the retained note from the top (mine → sign → broadcast). No-op
     * unless the last attempt failed or was stopped.
     */
    fun retry() {
        val note = pendingNote ?: return
        val s = _status.value
        if (s !is PowStatus.Failed && s !is PowStatus.Stopped) return
        // submitNote re-stamps created_at — PoW commits the timestamp into the
        // event id, so a retry minutes after a stopped mining run (or after the
        // user sat on the error) must not publish a note dated to the failed
        // attempt.
        submitNote(
            signer = note.signer,
            content = note.content,
            tags = note.tags,
            kind = note.kind,
            inboxPubkeys = note.inboxPubkeys,
            onPublished = note.onPublished,
            draftRestoreKey = note.restoreKey,
            draftRestorePayload = note.restorePayload
        )
    }

    /**
     * Cancel during mining only. Once the event is being broadcast, the note is
     * in flight to relays and cancellation is meaningless. No-op outside
     * [PowStatus.Mining].
     *
     * Stopping is not discarding: the draft goes back into the composer's
     * bucket and stays retryable from the pill, so a user who bails on a long
     * mining run keeps their text.
     */
    fun cancel() {
        if (_status.value !is PowStatus.Mining) return
        val job = miningJob ?: return
        pendingNote?.let { draftStore.restoreIfAbsent(it.restoreKey, it.restorePayload) }
        miningJob = null
        job.cancel()
        _status.value = PowStatus.Stopped
    }

    /** Clear a Failed / Stopped pill; drops the retained note with it. */
    fun dismiss() {
        miningJob?.cancel()
        miningJob = null
        pendingNote = null
        _status.value = PowStatus.Idle
    }

    /**
     * Park the publish in a retryable error state. Unlike [PowStatus.Done],
     * this never auto-dismisses — the pill is the only notice the user gets
     * that the post didn't go out, and it carries the Retry button, so it waits
     * to be acknowledged. The draft is back in the composer either way.
     */
    private fun fail(isCurrent: () -> Boolean, message: String) {
        // A run superseded by a newer submitNote unwinds through here (a
        // cancelled broadcast reports zero sends). Its status and its draft
        // belong to the newer post now, so drop the stale failure rather than
        // stomping either.
        if (!isCurrent()) return
        pendingNote?.let { draftStore.restoreIfAbsent(it.restoreKey, it.restorePayload) }
        _status.value = PowStatus.Failed(message)
    }
}

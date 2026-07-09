package cooking.zap.app.repo

import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.Nip10
import cooking.zap.app.nostr.Nip89
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.NostrSigner
import cooking.zap.app.nostr.SignerCancelledException
import cooking.zap.app.nostr.SignerRejectedException
import cooking.zap.app.relay.OutboxRouter
import cooking.zap.app.relay.RelayPool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Publishes a Cheffy Note Review draft as a public kind-1 NIP-10 reply
 * (CHEFFY_NOTE_REVIEW_PLAN.md, Phase 3; finding 0.2).
 *
 * Deliberately NOT routed through `ComposeViewModel.publishNote`: that
 * path is fire-and-forget (its OK tracking only drives a transient
 * toast), never surfaces the signed event, and is coupled to composer
 * state — it cannot satisfy invariant 2 (a publish timeout must retain
 * the SIGNED event so retry re-broadcasts the same id with no second
 * signer round trip). This publisher reuses the same primitives —
 * [Nip10.buildReplyTags] + the outbox relay hint, [Nip89.clientTag],
 * [OutboxRouter.publishToInbox] — and adds the explicit-with-timeout
 * contract on top of [RelayPool.publishResults].
 *
 * An interface (like [WalletProvider]) so the ViewModel's publish state
 * machine is unit-testable with scripted outcomes.
 */
interface NoteReviewReplyPublisher {

    sealed interface Outcome {
        /** At least one relay accepted. [event] is live in the local caches. */
        data class Published(val event: NostrEvent) : Outcome

        /**
         * Signed and broadcast, but no relay OK inside the window. The
         * reply may already be out there — retry via [publishSigned] with
         * this exact [signedEvent] (same id; relays dedupe). Never discard
         * it (invariant 2).
         */
        data class Timeout(val signedEvent: NostrEvent) : Outcome

        /**
         * Nothing was sent (no relay reachable even after a reconnect
         * pass). The draft stays intact; re-posting runs the full
         * sign-and-send again.
         */
        data object Failed : Outcome

        /** The signer declined/cancelled — a user choice, not a network error. */
        data object SignRejected : Outcome
    }

    /** Sign the member-edited [content] as a reply to [parent], then broadcast. */
    suspend fun publish(
        content: String,
        parent: NostrEvent,
        signer: NostrSigner,
        clientTagEnabled: Boolean,
    ): Outcome

    /** Timeout-retry path: re-broadcast an ALREADY-SIGNED event — no re-sign. */
    suspend fun publishSigned(event: NostrEvent, parent: NostrEvent): Outcome
}

class RelayNoteReviewReplyPublisher(
    private val relayPool: RelayPool,
    private val outboxRouter: OutboxRouter,
    private val eventRepo: EventRepository,
    private val okTimeoutMs: Long = OK_TIMEOUT_MS,
) : NoteReviewReplyPublisher {

    override suspend fun publish(
        content: String,
        parent: NostrEvent,
        signer: NostrSigner,
        clientTagEnabled: Boolean,
    ): NoteReviewReplyPublisher.Outcome {
        val tags = buildReplyTags(parent, outboxRouter.getRelayHint(parent.pubkey), clientTagEnabled)
        val event = try {
            signer.signEvent(kind = 1, content = content, tags = tags)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SignerRejectedException) {
            return NoteReviewReplyPublisher.Outcome.SignRejected
        } catch (e: SignerCancelledException) {
            return NoteReviewReplyPublisher.Outcome.SignRejected
        } catch (e: Exception) {
            // Any other sign failure is still "your signer, not the relays".
            return NoteReviewReplyPublisher.Outcome.SignRejected
        }
        return publishSigned(event, parent)
    }

    override suspend fun publishSigned(
        event: NostrEvent,
        parent: NostrEvent,
    ): NoteReviewReplyPublisher.Outcome = coroutineScope {
        val msg = ClientMessage.event(event)

        // Subscribe BEFORE sending (UNDISPATCHED) — publishResults has no
        // replay, so a fast relay OK arriving mid-send must not be missed.
        val firstOk = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(okTimeoutMs) {
                relayPool.publishResults.first { it.eventId == event.id && it.accepted }
            }
        }

        // Own write relays + the parent author's NIP-65 inbox, with one
        // reconnect-and-retry pass — same shape as publishNote.
        var sent = outboxRouter.publishToInbox(msg, setOf(parent.pubkey))
        if (sent == 0 && relayPool.ensureWriteRelaysConnected() > 0) {
            sent = outboxRouter.publishToInbox(msg, setOf(parent.pubkey))
        }
        if (sent == 0) {
            firstOk.cancel()
            return@coroutineScope NoteReviewReplyPublisher.Outcome.Failed
        }
        // House broadcast pill, same as the composer path.
        relayPool.trackPublish(event.id, sent)

        val accepted = firstOk.await()
            ?: return@coroutineScope NoteReviewReplyPublisher.Outcome.Timeout(event)

        // Local bookkeeping mirrors publishNote so the reply and its
        // counts appear immediately without waiting for the relay echo.
        eventRepo.addEvent(event)
        eventRepo.addReplyCount(parent.id, event.id)
        val rootId = Nip10.getRootId(parent)
        if (rootId != null && rootId != parent.id) {
            eventRepo.addReplyCount(rootId, event.id)
        }
        NoteReviewReplyPublisher.Outcome.Published(event)
    }

    companion object {
        /** Web `RETRY_PUBLISH_TIMEOUT_MS` — 15s of waiting on a relay OK. */
        const val OK_TIMEOUT_MS = 15_000L

        /**
         * The exact tag set `ComposeViewModel.publishNote` emits for a
         * plain public reply (no content warning, mentions, hashtags,
         * media, or custom emoji — a Note Review draft): NIP-10 reply
         * tags with the outbox relay hint, then the NIP-89 client tag
         * when the preference allows. Pure — pinned against publishNote
         * by [NoteReviewReplyPublisherTest]'s parity test.
         */
        fun buildReplyTags(
            parent: NostrEvent,
            relayHint: String,
            clientTagEnabled: Boolean,
        ): List<List<String>> {
            val tags = mutableListOf<List<String>>()
            tags.addAll(Nip10.buildReplyTags(parent, relayHint))
            if (clientTagEnabled) tags.add(Nip89.clientTag())
            return tags
        }
    }
}

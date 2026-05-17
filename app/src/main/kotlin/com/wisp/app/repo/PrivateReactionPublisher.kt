package com.wisp.app.repo

import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Nip17
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.relay.RelayPool

/**
 * Sends a gift-wrapped private reaction (kind 7 rumor inside kind 1059) to every
 * participant of a NIP-17 private reply thread.
 *
 * Mirrors [PrivateReplyPublisher] but fans out to multiple recipients (the reply
 * author + every p-tagged participant carried in the reply's rumor tags) so the
 * count updates wherever the reply is visible. A self-copy lands on our own DM
 * relays so we see our own reaction immediately on this device and on other
 * devices that share our keypair.
 */
object PrivateReactionPublisher {
    data class Result(val sentCount: Int, val rumorId: String?)

    suspend fun send(
        signer: NostrSigner,
        relayPool: RelayPool,
        dmRepo: DmRepository,
        relayListRepo: RelayListRepository,
        eventRepo: EventRepository,
        targetEvent: NostrEvent,
        emoji: String,
        emojiUrl: String? = null
    ): Result {
        val myPubkey = signer.pubkeyHex

        // Participants = reply author + every p-tag carried in the reply's rumor,
        // minus us. The reply author lands first so they always receive the wrap
        // even when relay quotas would otherwise cut off later recipients.
        val recipients = buildList {
            add(targetEvent.pubkey)
            for (tag in targetEvent.tags) {
                if (tag.size >= 2 && tag[0] == "p") add(tag[1])
            }
        }.distinct().filter { it != myPubkey }

        if (recipients.isEmpty()) return Result(0, null)

        // Pin the rumor timestamp so every wrap we build (per-recipient + self-copy)
        // and the optimistic synthetic event share the same deterministic rumor id.
        // That lets the relay-echoed self-copy dedup naturally via countedReactionIds.
        val rumorCreatedAt = System.currentTimeMillis() / 1000

        var sentCount = 0
        var rumorIdToReturn: String? = null

        for (recipient in recipients) {
            val recipientWrap = Nip17.createGiftWrappedReactionRemote(
                signer = signer,
                recipientPubkeyHex = recipient,
                targetRumorId = targetEvent.id,
                targetAuthor = targetEvent.pubkey,
                targetKind = 1,
                emoji = emoji,
                emojiUrl = emojiUrl,
                createdAt = rumorCreatedAt
            )

            // Recipient resolution mirrors PrivateReplyPublisher: kind-10050 DM relays
            // first, NIP-65 read relays as fallback, fetch fresh from indexers as a
            // last resort. Public write relays are never used — the recipient won't
            // poll there for kind-1059 wraps and the rumor would be silently lost.
            val recipientRelays: List<String> = run {
                val dm = DmRelayLookup.fetch(recipient, relayPool, dmRepo)
                if (dm.isNotEmpty()) return@run dm
                relayListRepo.getReadRelays(recipient)?.takeIf { it.isNotEmpty() }?.let { return@run it }
                PeerRelayListLookup.fetch(recipient, relayPool, relayListRepo)
                relayListRepo.getReadRelays(recipient)?.takeIf { it.isNotEmpty() } ?: emptyList()
            }
            if (recipientRelays.isEmpty()) continue

            val msg = ClientMessage.event(recipientWrap)
            for (url in recipientRelays) {
                if (relayPool.sendToRelayOrEphemeral(url, msg, skipBadCheck = true)) sentCount++
            }
            rumorIdToReturn = rumorIdToReturn ?: recipientWrap.id
        }

        if (sentCount == 0) return Result(0, null)

        // Self-copy: also gift-wrap to ourselves so other devices on the same key
        // see the reaction via their own kind-1059 subscription.
        val selfWrap = Nip17.createGiftWrappedReactionRemote(
            signer = signer,
            recipientPubkeyHex = myPubkey,
            targetRumorId = targetEvent.id,
            targetAuthor = targetEvent.pubkey,
            targetKind = 1,
            emoji = emoji,
            emojiUrl = emojiUrl,
            createdAt = rumorCreatedAt
        )
        val selfMsg = ClientMessage.event(selfWrap)
        if (relayPool.hasDmRelays()) relayPool.sendToDmRelays(selfMsg)
        else relayPool.sendToWriteRelays(selfMsg)

        // Optimistic local insert. The synthesized kind-7 event id matches the
        // rumor id the wrap helpers computed (same pubkey/createdAt/tags/content),
        // so when the self-copy echoes back through processGiftWrap the existing
        // countedReactionIds dedup makes the redundant call a no-op.
        val rumorTags = buildList<List<String>> {
            add(listOf("e", targetEvent.id))
            add(listOf("p", targetEvent.pubkey))
            add(listOf("k", "1"))
            if (emojiUrl != null) add(listOf("emoji", emoji.removeSurrounding(":"), emojiUrl))
        }
        val syntheticId = NostrEvent.computeId(myPubkey, rumorCreatedAt, 7, rumorTags, emoji)
        val synthetic = NostrEvent(
            id = syntheticId,
            pubkey = myPubkey,
            created_at = rumorCreatedAt,
            kind = 7,
            tags = rumorTags,
            content = emoji,
            sig = ""
        )
        eventRepo.markPrivate(syntheticId)
        eventRepo.addEvent(synthetic)

        return Result(sentCount, rumorIdToReturn)
    }
}

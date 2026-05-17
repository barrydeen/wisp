package com.wisp.app.repo

import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Nip10
import com.wisp.app.nostr.Nip13
import com.wisp.app.nostr.Nip17
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.relay.RelayPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sends a NIP-17 gift-wrapped private reply (kind 1 rumor inside kind 1059 wrap).
 *
 * Shared by [com.wisp.app.viewmodel.ComposeViewModel] (full-screen compose) and the
 * notifications quick-reply path so both apply the same recipient relay resolution,
 * PoW mining on the kind 1 rumor, self-copy, and optimistic local insert.
 */
object PrivateReplyPublisher {
    data class Result(val sentCount: Int, val rumorId: String?)

    suspend fun send(
        signer: NostrSigner,
        relayPool: RelayPool,
        dmRepo: DmRepository,
        relayListRepo: RelayListRepository?,
        eventRepo: EventRepository?,
        replyTo: NostrEvent,
        content: String,
        baseTags: List<List<String>>,
        targetDifficulty: Int = 0,
        onPowProgress: ((Long) -> Unit)? = null
    ): Result {
        val userPubkey = signer.pubkeyHex

        // Mine PoW on the kind 1 rumor first so its committed nonce + difficulty travel inside
        // the encrypted wrap. The recipient renders the rumor as a normal kind 1 reply with a
        // PoW badge when they decrypt — the wrap layer itself stays at difficulty 0.
        var rumorTags = baseTags
        var rumorCreatedAt = System.currentTimeMillis() / 1000
        if (targetDifficulty > 0) {
            val mined = withContext(Dispatchers.Default) {
                Nip13.mine(
                    pubkeyHex = userPubkey,
                    kind = 1,
                    content = content,
                    tags = baseTags,
                    targetDifficulty = targetDifficulty,
                    createdAt = rumorCreatedAt,
                    onProgress = onPowProgress
                )
            }
            rumorTags = mined.tags
            rumorCreatedAt = mined.createdAt
        }

        val recipientWrap = Nip17.createGiftWrapRemote(
            signer = signer,
            recipientPubkeyHex = replyTo.pubkey,
            message = content,
            replyTags = rumorTags,
            rumorKind = 1,
            createdAt = rumorCreatedAt
        )
        val selfWrap = Nip17.createGiftWrapRemote(
            signer = signer,
            recipientPubkeyHex = userPubkey,
            message = content,
            replyTags = rumorTags,
            rumorKind = 1,
            createdAt = rumorCreatedAt
        )

        val recipientRelays: List<String> = run {
            val dmRelays = DmRelayLookup.fetch(replyTo.pubkey, relayPool, dmRepo)
            if (dmRelays.isNotEmpty()) return@run dmRelays
            relayListRepo?.getReadRelays(replyTo.pubkey)?.takeIf { it.isNotEmpty() }?.let { return@run it }
            relayListRepo?.getWriteRelays(replyTo.pubkey)?.takeIf { it.isNotEmpty() }?.let { return@run it }
            emptyList()
        }

        val recipientMsg = ClientMessage.event(recipientWrap)
        var sentCount = 0
        if (recipientRelays.isNotEmpty()) {
            for (url in recipientRelays) {
                if (relayPool.sendToRelayOrEphemeral(url, recipientMsg, skipBadCheck = true)) sentCount++
            }
        } else {
            sentCount += relayPool.sendToWriteRelays(recipientMsg)
        }

        if (sentCount == 0) return Result(0, null)

        val selfMsg = ClientMessage.event(selfWrap)
        if (relayPool.hasDmRelays()) relayPool.sendToDmRelays(selfMsg)
        else relayPool.sendToWriteRelays(selfMsg)

        val rumorId = NostrEvent.computeId(userPubkey, rumorCreatedAt, 1, rumorTags, content)
        val synthetic = NostrEvent(
            id = rumorId,
            pubkey = userPubkey,
            created_at = rumorCreatedAt,
            kind = 1,
            tags = rumorTags,
            content = content,
            sig = ""
        )
        eventRepo?.markPrivateReply(rumorId)
        eventRepo?.cacheEvent(synthetic)
        eventRepo?.addReplyCount(replyTo.id, rumorId)
        Nip10.getRootId(replyTo)?.takeIf { it != replyTo.id }?.let { rootId ->
            eventRepo?.addReplyCount(rootId, rumorId)
        }
        dmRepo.markGiftWrapSeen(selfWrap.id, rumorId)

        return Result(sentCount, rumorId)
    }
}

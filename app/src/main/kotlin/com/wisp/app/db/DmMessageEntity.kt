package com.wisp.app.db

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import io.objectbox.annotation.Unique

/**
 * Persisted decrypted NIP-17 DM. Keyed by gift-wrap event id so re-fetched gift wraps
 * can be deduped before they're sent to the remote signer for re-decryption.
 *
 * Reactions, zaps, file metadata, emoji map and participants are stored as JSON since
 * they're nested collections and don't need to be queryable.
 */
@Entity
data class DmMessageEntity(
    @Id var dbId: Long = 0,
    /** Account pubkey (hex) — the local user this conversation belongs to. */
    @Index val ownerPubkey: String = "",
    /** Internal DmMessage.id ("$giftWrapId:$rumorCreatedAt"). */
    val msgId: String = "",
    /** kind 1059 gift wrap event id. Unique per owner — see [ownerPlusGiftWrap]. */
    val giftWrapId: String = "",
    /** Composite "$ownerPubkey|$giftWrapId" for cross-account dedup. */
    @Unique val ownerPlusGiftWrap: String = "",
    /** DmRepository.conversationKey — sorted, comma-joined participant pubkeys. */
    @Index val conversationKey: String = "",
    val senderPubkey: String = "",
    val content: String = "",
    val createdAt: Long = 0L,
    val rumorId: String = "",
    val replyToId: String? = null,
    /** Other participants (excluding owner) — JSON list. */
    val participantsJson: String = "[]",
    /** Set<String> of relay URLs that delivered this gift wrap — JSON list. */
    val relayUrlsJson: String = "[]",
    /** List<DmReaction> as JSON. */
    val reactionsJson: String = "[]",
    /** List<DmZap> as JSON. */
    val zapsJson: String = "[]",
    /** Map<String, String> emoji shortcode → URL — JSON. */
    val emojiMapJson: String = "{}",
    /** EncryptedFileMetadata as JSON, or null for regular text messages. */
    val encryptedFileMetadataJson: String? = null,
    val debugGiftWrapJson: String? = null,
    val debugRumorJson: String? = null
)

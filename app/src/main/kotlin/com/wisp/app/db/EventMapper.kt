package com.wisp.app.db

import com.wisp.app.nostr.NostrEvent

fun NostrEvent.toEntity(): EventEntity {
    return EventEntity(
        id = id,
        pubkey = pubkey,
        createdAt = created_at,
        kind = kind,
        tags = TagsConverter.tagsToJson(tags),
        content = content,
        sig = sig
    )
}

fun EventEntity.toNostrEvent(): NostrEvent {
    return NostrEvent(
        id = id,
        pubkey = pubkey,
        created_at = createdAt,
        kind = kind,
        tags = TagsConverter.jsonToTags(tags),
        content = content,
        sig = sig
    )
}

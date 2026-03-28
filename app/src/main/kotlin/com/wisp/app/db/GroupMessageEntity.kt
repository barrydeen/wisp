package com.wisp.app.db

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import io.objectbox.annotation.Unique

@Entity
data class GroupMessageEntity(
    @Id var dbId: Long = 0,
    @Unique val eventId: String = "",
    /** "$ownerPubkey|$relayUrl|$groupId" — used to query messages per room */
    @Index val roomKey: String = "",
    val senderPubkey: String = "",
    val content: String = "",
    @Index val createdAt: Long = 0L,
    val replyToId: String? = null
)

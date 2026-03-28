package com.wisp.app.db

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import io.objectbox.annotation.Unique

@Entity
data class GroupMetaEntity(
    @Id var dbId: Long = 0,
    /** "$ownerPubkey|$relayUrl|$groupId" — unique per account+room */
    @Unique val roomKey: String = "",
    @Index val ownerPubkey: String = "",
    val relayUrl: String = "",
    val groupId: String = "",
    val name: String? = null,
    val picture: String? = null,
    val about: String? = null,
    val isPrivate: Boolean = false,
    val isClosed: Boolean = false,
    val adminsJson: String = "[]",
    val membersJson: String = "[]",
    val lastMessageAt: Long = 0L
)

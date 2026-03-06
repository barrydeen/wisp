package com.wisp.app.db

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import io.objectbox.annotation.Unique

@Entity
data class EventEntity(
    @Id var dbId: Long = 0,
    @Unique val eventId: String = "",
    @Index val pubkey: String = "",
    val createdAt: Long = 0,
    @Index val kind: Int = 0,
    val content: String = "",
    val tags: String = "",
    val sig: String = "",
    val insertedAt: Long = System.currentTimeMillis() / 1000
)

package com.wisp.app.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "events",
    indices = [
        Index(value = ["kind", "created_at"]),
        Index(value = ["pubkey"]),
        Index(value = ["created_at"])
    ]
)
data class EventEntity(
    @PrimaryKey val id: String,
    val pubkey: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val kind: Int,
    val tags: String,  // JSON string
    val content: String,
    val sig: String,
    @ColumnInfo(name = "cached_at") val cachedAt: Long = System.currentTimeMillis()
)

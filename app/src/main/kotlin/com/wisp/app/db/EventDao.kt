package com.wisp.app.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: EventEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(events: List<EventEntity>)

    @Query("SELECT * FROM events WHERE kind IN (1, 6) ORDER BY created_at DESC LIMIT :limit")
    suspend fun getFeedEvents(limit: Int = 500): List<EventEntity>

    @Query("SELECT * FROM events WHERE kind IN (7, 9735)")
    suspend fun getEngagementEvents(): List<EventEntity>

    @Query("SELECT MAX(created_at) FROM events WHERE kind IN (1, 6)")
    suspend fun getNewestFeedTimestamp(): Long?

    @Query("SELECT MAX(created_at) FROM events WHERE kind IN (7, 9735)")
    suspend fun getNewestEngagementTimestamp(): Long?

    @Query("DELETE FROM events WHERE cached_at < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)

    @Query("DELETE FROM events")
    suspend fun deleteAll()

    @Query("DELETE FROM events WHERE pubkey = :pubkey")
    suspend fun deleteByPubkey(pubkey: String)
}

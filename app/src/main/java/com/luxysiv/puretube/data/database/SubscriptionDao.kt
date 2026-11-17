package com.luxysiv.puretube.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Đọc/ghi danh sách kênh đăng ký cục bộ. */
@Dao
interface SubscriptionDao {

    @Query("SELECT * FROM subscriptions ORDER BY channelTitle COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT channelId FROM subscriptions")
    fun observeChannelIds(): Flow<List<String>>

    @Query("SELECT * FROM subscriptions ORDER BY channelTitle COLLATE NOCASE ASC")
    suspend fun getAllOnce(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE channelId = :channelId LIMIT 1")
    suspend fun getById(channelId: String): SubscriptionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SubscriptionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<SubscriptionEntity>)

    @Query("DELETE FROM subscriptions WHERE channelId = :channelId")
    suspend fun delete(channelId: String): Int

    @Query("DELETE FROM subscriptions")
    suspend fun deleteAll()

    @Query(
        "UPDATE subscriptions SET lastFetchedAt = :timestamp, nextPageToken = :nextPageToken " +
            "WHERE channelId = :channelId"
    )
    suspend fun setFeedState(channelId: String, timestamp: Long, nextPageToken: String)

    @Query("UPDATE subscriptions SET nextPageToken = :nextPageToken WHERE channelId = :channelId")
    suspend fun setNextPageToken(channelId: String, nextPageToken: String)
}

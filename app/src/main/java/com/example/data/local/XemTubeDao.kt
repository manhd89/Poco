package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface XemTubeDao {
    @Query("SELECT * FROM watch_history ORDER BY watchedAt DESC LIMIT 50")
    fun getWatchHistory(): Flow<List<WatchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWatchHistory(entity: WatchHistoryEntity)

    @Query("DELETE FROM watch_history WHERE videoId = :videoId")
    suspend fun deleteFromWatchHistory(videoId: String)

    @Query("DELETE FROM watch_history")
    suspend fun clearWatchHistory()

    @Query("SELECT * FROM saved_videos ORDER BY savedAt DESC")
    fun getSavedVideos(): Flow<List<SavedVideoEntity>>

    @Query("SELECT videoId FROM saved_videos")
    fun getSavedVideoIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveVideo(entity: SavedVideoEntity)

    @Query("DELETE FROM saved_videos WHERE videoId = :videoId")
    suspend fun unsaveVideo(videoId: String)

    @Query("SELECT * FROM subscribed_channels ORDER BY subscribedAt DESC")
    fun getSubscribedChannels(): Flow<List<SubscribedChannelEntity>>

    @Query("SELECT channelId FROM subscribed_channels")
    fun getSubscribedChannelIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun subscribeChannel(entity: SubscribedChannelEntity)

    @Query("DELETE FROM subscribed_channels WHERE channelId = :channelId")
    suspend fun unsubscribeChannel(channelId: String)

    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT 15")
    fun getRecentSearches(): Flow<List<SearchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSearchQuery(entity: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE `query` = :query")
    suspend fun deleteSearchQuery(query: String)
}

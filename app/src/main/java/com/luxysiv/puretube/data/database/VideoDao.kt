package com.luxysiv.puretube.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoDao {

    @Query("SELECT * FROM saved_videos WHERE isFavorite = 1 ORDER BY favoriteAddedAt DESC, videoId DESC")
    fun getFavorites(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM saved_videos WHERE lastWatchedTimestamp > 0 ORDER BY lastWatchedTimestamp DESC LIMIT 100")
    fun getHistory(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM saved_videos WHERE isWatchLater = 1 ORDER BY watchLaterAddedAt DESC, videoId DESC")
    fun getWatchLater(): Flow<List<VideoEntity>>

    /**
     * ID các video đã từng phát, video xem gần nhất đứng trước. Dùng để ưu tiên
     * video đã play lên đầu kết quả tìm kiếm.
     */
    @Query("SELECT videoId FROM saved_videos WHERE lastWatchedTimestamp > 0 ORDER BY lastWatchedTimestamp DESC")
    suspend fun getWatchedVideoIds(): List<String>

    @Query("SELECT videoId FROM saved_videos WHERE isFavorite = 1")
    fun getFavoriteIds(): Flow<List<String>>

    @Query("SELECT videoId FROM saved_videos WHERE isWatchLater = 1")
    fun getWatchLaterIds(): Flow<List<String>>

    /** ID các video đã từng phát (không giới hạn số lượng, khác với [getHistory]). */
    @Query("SELECT videoId FROM saved_videos WHERE lastWatchedTimestamp > 0")
    fun observeWatchedVideoIds(): Flow<List<String>>

    @Query("SELECT * FROM saved_videos WHERE videoId = :videoId LIMIT 1")
    suspend fun getVideoById(videoId: String): VideoEntity?

    @Query("SELECT * FROM saved_videos WHERE videoId = :videoId LIMIT 1")
    fun observeVideoById(videoId: String): Flow<VideoEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entity: VideoEntity)

    @Query("UPDATE saved_videos SET isFavorite = :isFavorite WHERE videoId = :videoId")
    suspend fun setFavorite(videoId: String, isFavorite: Boolean)

    @Query("UPDATE saved_videos SET isWatchLater = :isWatchLater WHERE videoId = :videoId")
    suspend fun setWatchLater(videoId: String, isWatchLater: Boolean)

    @Query("UPDATE saved_videos SET lastWatchedTimestamp = :timestamp, watchProgressSeconds = :progress WHERE videoId = :videoId")
    suspend fun updateWatchHistory(videoId: String, timestamp: Long, progress: Long)

    @Query("UPDATE saved_videos SET lastWatchedTimestamp = 0, watchProgressSeconds = 0")
    suspend fun clearHistory()

    @Query("UPDATE saved_videos SET watchProgressSeconds = 0, lastWatchedTimestamp = 0 WHERE videoId = :videoId")
    suspend fun clearProgress(videoId: String)

    @Query("DELETE FROM saved_videos WHERE videoId = :videoId")
    suspend fun delete(videoId: String)

    /** Toàn bộ bản ghi, dùng cho xuất bản sao lưu. */
    @Query("SELECT * FROM saved_videos")
    suspend fun getAllVideosOnce(): List<VideoEntity>

    /** Ghi đè/khôi phục hàng loạt từ bản sao lưu. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(videos: List<VideoEntity>)
}

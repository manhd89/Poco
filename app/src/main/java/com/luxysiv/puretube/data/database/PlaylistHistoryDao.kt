package com.luxysiv.puretube.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistHistoryDao {

    @Query("SELECT * FROM playlist_history ORDER BY updatedAt DESC LIMIT 100")
    fun getHistory(): Flow<List<PlaylistHistoryEntity>>

    @Query("SELECT * FROM playlist_history WHERE playlistId = :playlistId LIMIT 1")
    suspend fun getById(playlistId: String): PlaylistHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlaylistHistoryEntity)

    /**
     * Chỉ cập nhật tiến độ khi dòng lịch sử vẫn đang trỏ tới đúng video. Nhờ
     * vậy ticker của video cũ không ghi đè vị trí của video mới sau auto-next.
     */
    @Query(
        "UPDATE playlist_history SET lastPositionSeconds = :progress, updatedAt = :timestamp " +
            "WHERE playlistId = :playlistId AND lastVideoId = :videoId"
    )
    suspend fun updateProgress(
        playlistId: String,
        videoId: String,
        progress: Long,
        timestamp: Long
    )

    @Query("DELETE FROM playlist_history")
    suspend fun clearHistory()

    @Query("DELETE FROM playlist_history WHERE playlistId = :playlistId")
    suspend fun delete(playlistId: String)

    /** Toàn bộ bản ghi, dùng cho xuất bản sao lưu. */
    @Query("SELECT * FROM playlist_history")
    suspend fun getAllOnce(): List<PlaylistHistoryEntity>

    /** Ghi đè/khôi phục hàng loạt từ bản sao lưu. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(playlists: List<PlaylistHistoryEntity>)
}

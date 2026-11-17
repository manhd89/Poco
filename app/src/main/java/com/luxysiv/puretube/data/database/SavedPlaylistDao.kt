package com.luxysiv.puretube.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedPlaylistDao {

    @Query("SELECT * FROM saved_playlists ORDER BY savedAt DESC")
    fun getSaved(): Flow<List<SavedPlaylistEntity>>

    @Query("SELECT playlistId FROM saved_playlists")
    fun getSavedIds(): Flow<List<String>>

    @Query("SELECT * FROM saved_playlists WHERE playlistId = :playlistId LIMIT 1")
    suspend fun getById(playlistId: String): SavedPlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SavedPlaylistEntity)

    @Query("DELETE FROM saved_playlists WHERE playlistId = :playlistId")
    suspend fun delete(playlistId: String)

    @Query("DELETE FROM saved_playlists")
    suspend fun clear()

    /** Toàn bộ bản ghi, dùng cho xuất bản sao lưu. */
    @Query("SELECT * FROM saved_playlists")
    suspend fun getAllOnce(): List<SavedPlaylistEntity>

    /** Ghi đè/khôi phục hàng loạt từ bản sao lưu. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(playlists: List<SavedPlaylistEntity>)
}

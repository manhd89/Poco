package com.luxysiv.puretube.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.luxysiv.puretube.data.model.PlaylistItem

/**
 * Một playlist người dùng chủ động lưu vào "Xem sau".
 *
 * Tách khỏi [PlaylistHistoryEntity]: lịch sử là "đã từng xem" và có thể bị xoá
 * khi dọn lịch sử, còn playlist đã lưu là bộ sưu tập người dùng muốn giữ lại —
 * giống quan hệ giữa lịch sử video và video trong Xem sau.
 */
@Entity(tableName = "saved_playlists")
data class SavedPlaylistEntity(
    @PrimaryKey
    val playlistId: String,
    val title: String,
    val channelTitle: String = "",
    val channelId: String = "",
    val thumbnailUrl: String = "",
    val videoCount: Int = 0,
    val firstVideoId: String = "",
    val firstVideoTitle: String = "",
    @ColumnInfo(defaultValue = "0") val savedAt: Long = 0L
) {
    fun toPlaylistItem(): PlaylistItem = PlaylistItem(
        id = playlistId,
        title = title,
        channelTitle = channelTitle,
        channelId = channelId,
        videoCount = videoCount,
        thumbnailUrl = thumbnailUrl,
        firstVideoId = firstVideoId,
        firstVideoTitle = firstVideoTitle
    )

    companion object {
        fun fromPlaylistItem(item: PlaylistItem, savedAt: Long): SavedPlaylistEntity =
            SavedPlaylistEntity(
                playlistId = item.id,
                title = item.title,
                channelTitle = item.channelTitle,
                channelId = item.channelId,
                thumbnailUrl = item.thumbnailUrl,
                videoCount = item.videoCount,
                firstVideoId = item.firstVideoId,
                firstVideoTitle = item.firstVideoTitle,
                savedAt = savedAt
            )
    }
}

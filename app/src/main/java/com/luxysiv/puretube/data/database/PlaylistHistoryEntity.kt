package com.luxysiv.puretube.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.luxysiv.puretube.data.model.PlaylistItem

/**
 * Một playlist đã từng phát, kèm video và vị trí xem gần nhất.
 *
 * Tách khỏi [VideoEntity] vì lịch sử playlist cần mở lại cả danh sách và resume
 * đúng video người dùng đang xem dở, không chỉ một video đơn lẻ.
 */
@Entity(tableName = "playlist_history")
data class PlaylistHistoryEntity(
    @PrimaryKey
    val playlistId: String,
    val title: String,
    val channelTitle: String = "",
    val channelId: String = "",
    val thumbnailUrl: String = "",
    val videoCount: Int = 0,
    val lastVideoId: String = "",
    val lastVideoTitle: String = "",
    val lastPositionSeconds: Long = 0L,
    val updatedAt: Long = 0L
) {
    fun toPlaylistItem(): PlaylistItem = PlaylistItem(
        id = playlistId,
        title = title,
        channelTitle = channelTitle,
        channelId = channelId,
        videoCount = videoCount,
        thumbnailUrl = thumbnailUrl,
        firstVideoId = lastVideoId,
        firstVideoTitle = lastVideoTitle
    )
}

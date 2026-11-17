package com.luxysiv.puretube.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.luxysiv.puretube.data.model.VideoItem

@Entity(tableName = "saved_videos")
data class VideoEntity(
    @PrimaryKey
    val videoId: String,
    val title: String,
    val channelTitle: String,
    val channelId: String = "",
    val description: String = "",
    val duration: String = "00:00",
    val viewCount: String = "0",
    val publishedTime: String = "",
    val thumbnailUrl: String = "",
    val category: String = "General",
    /** Avatar kênh để thẻ video trong Lịch sử/Yêu thích vẫn hiện đúng ảnh. */
    val channelAvatarUrl: String = "",
    val isFavorite: Boolean = false,
    val isWatchLater: Boolean = false,
    val lastWatchedTimestamp: Long = 0L,
    val watchProgressSeconds: Long = 0L,
    @ColumnInfo(defaultValue = "0") val favoriteAddedAt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val watchLaterAddedAt: Long = 0L
) {
    fun toVideoItem(): VideoItem = VideoItem(
        id = videoId,
        title = title,
        channelTitle = channelTitle,
        channelId = channelId,
        description = description,
        duration = duration,
        viewCount = viewCount,
        publishedTime = publishedTime,
        thumbnailUrl = thumbnailUrl,
        category = category,
        channelAvatarUrl = channelAvatarUrl
    ).withMappedDefaults()

    companion object {
        fun fromVideoItem(
            item: VideoItem,
            isFavorite: Boolean = false,
            isWatchLater: Boolean = false,
            lastWatchedTimestamp: Long = 0L,
            progress: Long = 0L,
            favoriteAddedAt: Long = 0L,
            watchLaterAddedAt: Long = 0L
        ): VideoEntity {
            return VideoEntity(
                videoId = item.id,
                title = item.title,
                channelTitle = item.channelTitle,
                channelId = item.channelId,
                description = item.description,
                duration = item.duration,
                viewCount = item.viewCount,
                publishedTime = item.publishedTime,
                thumbnailUrl = item.thumbnailUrl,
                category = item.category,
                channelAvatarUrl = item.channelAvatarUrl,
                isFavorite = isFavorite,
                isWatchLater = isWatchLater,
                lastWatchedTimestamp = lastWatchedTimestamp,
                watchProgressSeconds = progress,
                favoriteAddedAt = favoriteAddedAt,
                watchLaterAddedAt = watchLaterAddedAt
            )
        }
    }
}

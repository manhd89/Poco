package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.model.ChannelInfo
import com.example.data.model.VideoItem

@Entity(tableName = "watch_history")
data class WatchHistoryEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val channelId: String,
    val channelName: String,
    val channelAvatarUrl: String,
    val thumbnailUrl: String,
    val viewCountText: String,
    val publishedText: String,
    val durationText: String,
    val watchedAt: Long = System.currentTimeMillis(),
) {
    fun toVideoItem(): VideoItem = VideoItem(
        id = videoId,
        title = title,
        channelId = channelId,
        channelName = channelName,
        channelAvatarUrl = channelAvatarUrl,
        thumbnailUrl = thumbnailUrl,
        viewCountText = viewCountText,
        publishedText = publishedText,
        durationText = durationText,
    )
}

@Entity(tableName = "saved_videos")
data class SavedVideoEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val channelId: String,
    val channelName: String,
    val channelAvatarUrl: String,
    val thumbnailUrl: String,
    val viewCountText: String,
    val publishedText: String,
    val durationText: String,
    val savedAt: Long = System.currentTimeMillis(),
) {
    fun toVideoItem(): VideoItem = VideoItem(
        id = videoId,
        title = title,
        channelId = channelId,
        channelName = channelName,
        channelAvatarUrl = channelAvatarUrl,
        thumbnailUrl = thumbnailUrl,
        viewCountText = viewCountText,
        publishedText = publishedText,
        durationText = durationText,
    )
}

@Entity(tableName = "subscribed_channels")
data class SubscribedChannelEntity(
    @PrimaryKey val channelId: String,
    val name: String,
    val avatarUrl: String,
    val subscriberText: String,
    val subscribedAt: Long = System.currentTimeMillis(),
) {
    fun toChannelInfo(): ChannelInfo = ChannelInfo(
        id = channelId,
        name = name,
        avatarUrl = avatarUrl,
        subscriberText = subscriberText,
    )
}

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val searchedAt: Long = System.currentTimeMillis(),
)

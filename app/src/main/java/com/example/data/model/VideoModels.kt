package com.example.data.model

data class VideoItem(
    val id: String,
    val title: String,
    val channelId: String,
    val channelName: String,
    val channelAvatarUrl: String = "",
    val thumbnailUrl: String = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
    val viewCountText: String = "",
    val publishedText: String = "",
    val durationText: String = "",
    val description: String = "",
)

data class ChannelInfo(
    val id: String,
    val name: String,
    val handle: String = "",
    val avatarUrl: String = "",
    val subscriberText: String = "",
    val category: String = "",
)

data class VideoCategory(
    val id: String,
    val label: String,
    val searchQuery: String,
    val fallbackChannelIds: List<String> = emptyList(),
)

data class VideoDetail(
    val video: VideoItem,
    val description: String,
    val subscriberCountText: String,
    val likeCountText: String,
    val relatedVideos: List<VideoItem>,
)

sealed interface ResourceState<out T> {
    data object Loading : ResourceState<Nothing>
    data class Success<T>(val data: T) : ResourceState<T>
    data class Error(val message: String) : ResourceState<Nothing>
}

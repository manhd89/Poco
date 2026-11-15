package com.example.data.repository

import com.example.data.local.SavedVideoEntity
import com.example.data.local.SearchHistoryEntity
import com.example.data.local.SubscribedChannelEntity
import com.example.data.local.WatchHistoryEntity
import com.example.data.local.XemTubeDao
import com.example.data.model.ChannelInfo
import com.example.data.model.VideoCategory
import com.example.data.model.VideoDetail
import com.example.data.model.VideoItem
import com.example.data.remote.YouTubeFetcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class YouTubeRepository(
    private val fetcher: YouTubeFetcher,
    private val dao: XemTubeDao,
) {
    val categories: List<VideoCategory> = fetcher.categories
    val curatedChannels: List<ChannelInfo> = fetcher.curatedChannels

    val watchHistory: Flow<List<VideoItem>> = dao.getWatchHistory().map { list ->
        list.map { it.toVideoItem() }
    }

    val savedVideos: Flow<List<VideoItem>> = dao.getSavedVideos().map { list ->
        list.map { it.toVideoItem() }
    }

    val savedVideoIds: Flow<Set<String>> = dao.getSavedVideoIds().map { it.toSet() }

    val subscribedChannels: Flow<List<ChannelInfo>> = dao.getSubscribedChannels().map { list ->
        list.map { it.toChannelInfo() }
    }

    val subscribedChannelIds: Flow<Set<String>> = dao.getSubscribedChannelIds().map { it.toSet() }

    val recentSearches: Flow<List<String>> = dao.getRecentSearches().map { list ->
        list.map { it.query }
    }

    suspend fun getCategoryVideos(category: VideoCategory): List<VideoItem> =
        fetcher.fetchCategoryFeed(category)

    suspend fun getTrendingVideos(): List<VideoItem> =
        fetcher.fetchTrendingVideos()

    suspend fun searchVideos(query: String): List<VideoItem> {
        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) {
            dao.upsertSearchQuery(SearchHistoryEntity(query = trimmed))
        }
        return fetcher.searchVideos(trimmed)
    }

    suspend fun getChannelVideos(channelId: String, channelName: String): List<VideoItem> {
        val rss = runCatching { fetcher.fetchChannelRss(channelId) }.getOrDefault(emptyList())
        if (rss.isNotEmpty()) return rss
        return if (channelName.isNotBlank()) fetcher.searchVideos(channelName) else emptyList()
    }

    suspend fun getVideoDetail(video: VideoItem): VideoDetail =
        fetcher.fetchVideoDetail(video.id, video)

    suspend fun recordWatchHistory(video: VideoItem) {
        dao.upsertWatchHistory(
            WatchHistoryEntity(
                videoId = video.id,
                title = video.title,
                channelId = video.channelId,
                channelName = video.channelName,
                channelAvatarUrl = video.channelAvatarUrl,
                thumbnailUrl = video.thumbnailUrl,
                viewCountText = video.viewCountText,
                publishedText = video.publishedText,
                durationText = video.durationText,
            ),
        )
    }

    suspend fun removeFromHistory(videoId: String) = dao.deleteFromWatchHistory(videoId)

    suspend fun clearWatchHistory() = dao.clearWatchHistory()

    suspend fun toggleSaveVideo(video: VideoItem, currentlySaved: Boolean) {
        if (currentlySaved) {
            dao.unsaveVideo(video.id)
        } else {
            dao.saveVideo(
                SavedVideoEntity(
                    videoId = video.id,
                    title = video.title,
                    channelId = video.channelId,
                    channelName = video.channelName,
                    channelAvatarUrl = video.channelAvatarUrl,
                    thumbnailUrl = video.thumbnailUrl,
                    viewCountText = video.viewCountText,
                    publishedText = video.publishedText,
                    durationText = video.durationText,
                ),
            )
        }
    }

    suspend fun toggleSubscribeChannel(channel: ChannelInfo, currentlySubscribed: Boolean) {
        if (channel.id.isBlank()) return
        if (currentlySubscribed) {
            dao.unsubscribeChannel(channel.id)
        } else {
            dao.subscribeChannel(
                SubscribedChannelEntity(
                    channelId = channel.id,
                    name = channel.name,
                    avatarUrl = channel.avatarUrl,
                    subscriberText = channel.subscriberText,
                ),
            )
        }
    }

    suspend fun deleteSearchQuery(query: String) = dao.deleteSearchQuery(query)
}

package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.XemTubeDatabase
import com.example.data.model.ChannelInfo
import com.example.data.model.ResourceState
import com.example.data.model.VideoCategory
import com.example.data.model.VideoDetail
import com.example.data.model.VideoItem
import com.example.data.remote.YouTubeFetcher
import com.example.data.repository.YouTubeRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val categories: List<VideoCategory> = emptyList(),
    val selectedCategoryId: String = "all",
    val feedState: ResourceState<List<VideoItem>> = ResourceState.Loading,
    val isRefreshing: Boolean = false,
)

data class ExploreUiState(
    val trendingState: ResourceState<List<VideoItem>> = ResourceState.Loading,
    val curatedChannels: List<ChannelInfo> = emptyList(),
)

data class SearchUiState(
    val query: String = "",
    val hasSearched: Boolean = false,
    val resultsState: ResourceState<List<VideoItem>> = ResourceState.Success(emptyList()),
)

data class ChannelUiState(
    val channel: ChannelInfo? = null,
    val videosState: ResourceState<List<VideoItem>> = ResourceState.Loading,
)

data class PlayerUiState(
    val activeVideo: VideoItem? = null,
    val isExpanded: Boolean = false,
    val isFullscreen: Boolean = false,
    val isPlaying: Boolean = true,
    val currentSeconds: Float = 0f,
    val durationSeconds: Float = 0f,
    val playbackSpeed: Float = 1.0f,
    val detailState: ResourceState<VideoDetail>? = null,
    val embedError: Boolean = false,
)

data class LibraryUiState(
    val subscribedChannels: List<ChannelInfo> = emptyList(),
    val savedVideos: List<VideoItem> = emptyList(),
    val watchHistory: List<VideoItem> = emptyList(),
)

class XemTubeViewModel(application: Application) : AndroidViewModel(application) {

    private val database = XemTubeDatabase.getInstance(application)
    private val repository = YouTubeRepository(
        fetcher = YouTubeFetcher(),
        dao = database.xemTubeDao(),
    )

    private val _isDarkTheme = MutableStateFlow(true)
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    private val _homeState = MutableStateFlow(
        HomeUiState(categories = repository.categories, selectedCategoryId = "all"),
    )
    val homeState: StateFlow<HomeUiState> = _homeState.asStateFlow()

    private val _exploreState = MutableStateFlow(
        ExploreUiState(curatedChannels = repository.curatedChannels),
    )
    val exploreState: StateFlow<ExploreUiState> = _exploreState.asStateFlow()

    private val _searchState = MutableStateFlow(SearchUiState())
    val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()

    private val _channelState = MutableStateFlow(ChannelUiState())
    val channelState: StateFlow<ChannelUiState> = _channelState.asStateFlow()

    private val _playerState = MutableStateFlow(PlayerUiState())
    val playerState: StateFlow<PlayerUiState> = _playerState.asStateFlow()

    val savedVideoIds: StateFlow<Set<String>> = repository.savedVideoIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val subscribedChannelIds: StateFlow<Set<String>> = repository.subscribedChannelIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val recentSearches: StateFlow<List<String>> = repository.recentSearches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val libraryState: StateFlow<LibraryUiState> = combine(
        repository.subscribedChannels,
        repository.savedVideos,
        repository.watchHistory,
    ) { channels, saved, history ->
        LibraryUiState(
            subscribedChannels = channels,
            savedVideos = saved,
            watchHistory = history,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryUiState())

    private var homeJob: Job? = null
    private var detailJob: Job? = null
    private var searchJob: Job? = null
    private var channelJob: Job? = null

    init {
        loadCategory("all")
        loadExplore()
    }

    fun toggleTheme() {
        _isDarkTheme.update { !it }
    }

    fun selectCategory(categoryId: String) {
        if (_homeState.value.selectedCategoryId == categoryId &&
            _homeState.value.feedState is ResourceState.Success
        ) {
            return
        }
        loadCategory(categoryId)
    }

    fun refreshHome() {
        loadCategory(_homeState.value.selectedCategoryId, isRefresh = true)
    }

    private fun loadCategory(categoryId: String, isRefresh: Boolean = false) {
        val category = repository.categories.find { it.id == categoryId } ?: repository.categories.first()
        homeJob?.cancel()
        homeJob = viewModelScope.launch {
            _homeState.update {
                it.copy(
                    selectedCategoryId = category.id,
                    feedState = if (isRefresh) it.feedState else ResourceState.Loading,
                    isRefreshing = isRefresh,
                )
            }
            runCatching { repository.getCategoryVideos(category) }
                .onSuccess { videos ->
                    _homeState.update {
                        it.copy(
                            feedState = ResourceState.Success(videos),
                            isRefreshing = false,
                        )
                    }
                }
                .onFailure { err ->
                    _homeState.update {
                        it.copy(
                            feedState = ResourceState.Error(err.message ?: "Lỗi kết nối mạng"),
                            isRefreshing = false,
                        )
                    }
                }
        }
    }

    fun loadExplore() {
        viewModelScope.launch {
            _exploreState.update { it.copy(trendingState = ResourceState.Loading) }
            runCatching { repository.getTrendingVideos() }
                .onSuccess { videos ->
                    // Also harvest discovered channels with avatars for richer Explore strip
                    val discovered = videos
                        .filter { it.channelId.isNotBlank() && it.channelAvatarUrl.isNotBlank() }
                        .distinctBy { it.channelId }
                        .take(6)
                        .map {
                            ChannelInfo(
                                id = it.channelId,
                                name = it.channelName,
                                avatarUrl = it.channelAvatarUrl,
                                category = "Thịnh hành",
                            )
                        }
                    val merged = (discovered + repository.curatedChannels).distinctBy { it.id }
                    _exploreState.update {
                        it.copy(
                            trendingState = ResourceState.Success(videos),
                            curatedChannels = merged,
                        )
                    }
                }
                .onFailure { err ->
                    _exploreState.update {
                        it.copy(
                            trendingState = ResourceState.Error(err.message ?: "Lỗi tải xu hướng"),
                        )
                    }
                }
        }
    }

    fun updateSearchQuery(query: String) {
        _searchState.update { it.copy(query = query) }
    }

    fun submitSearch(queryOverride: String? = null) {
        val query = (queryOverride ?: _searchState.value.query).trim()
        if (query.isEmpty()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _searchState.update {
                it.copy(
                    query = query,
                    hasSearched = true,
                    resultsState = ResourceState.Loading,
                )
            }
            runCatching { repository.searchVideos(query) }
                .onSuccess { results ->
                    _searchState.update {
                        it.copy(resultsState = ResourceState.Success(results))
                    }
                }
                .onFailure { err ->
                    _searchState.update {
                        it.copy(
                            resultsState = ResourceState.Error(err.message ?: "Không thể tìm kiếm lúc này"),
                        )
                    }
                }
        }
    }

    fun deleteSearchHistory(query: String) {
        viewModelScope.launch { repository.deleteSearchQuery(query) }
    }

    fun loadChannel(channelId: String, channelName: String, avatarUrl: String) {
        channelJob?.cancel()
        channelJob = viewModelScope.launch {
            _channelState.value = ChannelUiState(
                channel = ChannelInfo(
                    id = channelId,
                    name = channelName,
                    avatarUrl = avatarUrl,
                ),
                videosState = ResourceState.Loading,
            )
            runCatching { repository.getChannelVideos(channelId, channelName) }
                .onSuccess { videos ->
                    _channelState.update {
                        it.copy(videosState = ResourceState.Success(videos))
                    }
                }
                .onFailure { err ->
                    _channelState.update {
                        it.copy(
                            videosState = ResourceState.Error(err.message ?: "Không tải được video kênh"),
                        )
                    }
                }
        }
    }

    fun playVideo(video: VideoItem) {
        viewModelScope.launch { repository.recordWatchHistory(video) }
        _playerState.value = PlayerUiState(
            activeVideo = video,
            isExpanded = true,
            isFullscreen = false,
            isPlaying = true,
            currentSeconds = 0f,
            durationSeconds = 0f,
            playbackSpeed = 1.0f,
            detailState = ResourceState.Loading,
            embedError = false,
        )
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            runCatching { repository.getVideoDetail(video) }
                .onSuccess { detail ->
                    _playerState.update { state ->
                        if (state.activeVideo?.id == video.id) {
                            state.copy(
                                activeVideo = detail.video,
                                detailState = ResourceState.Success(detail),
                            )
                        } else {
                            state
                        }
                    }
                }
                .onFailure { err ->
                    _playerState.update { state ->
                        if (state.activeVideo?.id == video.id) {
                            state.copy(detailState = ResourceState.Error(err.message ?: "Không tải được chi tiết"))
                        } else {
                            state
                        }
                    }
                }
        }
    }

    fun expandPlayer() {
        _playerState.update { it.copy(isExpanded = true) }
    }

    fun minimizePlayer() {
        _playerState.update { it.copy(isExpanded = false, isFullscreen = false) }
    }

    fun closePlayer() {
        detailJob?.cancel()
        _playerState.value = PlayerUiState()
    }

    fun setFullscreen(fullscreen: Boolean) {
        _playerState.update { it.copy(isFullscreen = fullscreen) }
    }

    fun updatePlaybackProgress(current: Float, duration: Float, isPlaying: Boolean) {
        _playerState.update {
            it.copy(
                currentSeconds = current,
                durationSeconds = duration,
                isPlaying = isPlaying,
            )
        }
    }

    fun setPlayingState(playing: Boolean) {
        _playerState.update { it.copy(isPlaying = playing) }
    }

    fun setPlaybackSpeed(speed: Float) {
        _playerState.update { it.copy(playbackSpeed = speed) }
    }

    fun markEmbedError() {
        _playerState.update { it.copy(embedError = true) }
    }

    fun toggleSaveVideo(video: VideoItem) {
        viewModelScope.launch {
            val saved = savedVideoIds.value.contains(video.id)
            repository.toggleSaveVideo(video, saved)
        }
    }

    fun toggleSubscribeChannel(channel: ChannelInfo) {
        viewModelScope.launch {
            val subscribed = subscribedChannelIds.value.contains(channel.id)
            repository.toggleSubscribeChannel(channel, subscribed)
        }
    }

    fun removeFromWatchHistory(videoId: String) {
        viewModelScope.launch { repository.removeFromHistory(videoId) }
    }

    fun clearWatchHistory() {
        viewModelScope.launch { repository.clearWatchHistory() }
    }
}

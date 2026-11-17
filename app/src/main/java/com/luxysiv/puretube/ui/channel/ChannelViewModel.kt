package com.luxysiv.puretube.ui.channel

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.luxysiv.puretube.data.model.ChannelPage
import com.luxysiv.puretube.data.model.VideoItem
import com.luxysiv.puretube.data.repository.YouTubeDataRepository
import com.luxysiv.puretube.error.AppError
import com.luxysiv.puretube.ui.base.PureTubeViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChannelViewModel(application: Application) : PureTubeViewModel(application) {

    private val _page = MutableStateFlow<ChannelPage?>(null)
    val page: StateFlow<ChannelPage?> = _page.asStateFlow()

    private val _videos = MutableStateFlow<List<VideoItem>>(emptyList())
    val videos: StateFlow<List<VideoItem>> = _videos.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _error = MutableStateFlow<AppError?>(null)
    val error: StateFlow<AppError?> = _error.asStateFlow()

    private var loadedId: String? = null
    private var nextToken = ""
    private var loadJob: Job? = null

    fun start(channelId: String, fallbackTitle: String = "") {
        if (channelId.isBlank()) return
        if (loadedId == channelId && (_videos.value.isNotEmpty() || _isLoading.value)) return
        if (fallbackTitle.isNotBlank() && _page.value?.channelId != channelId) {
            _page.value = ChannelPage(channelId = channelId, title = fallbackTitle)
        }
        load(channelId)
    }

    fun retry() {
        val id = loadedId ?: _page.value?.channelId ?: return
        load(id)
    }

    fun loadMore() {
        val id = _page.value?.channelId ?: loadedId ?: return
        val token = nextToken
        if (token.isBlank() || _isLoading.value || _isLoadingMore.value) return

        viewModelScope.launch {
            _isLoadingMore.value = true
            val result = YouTubeDataRepository.getChannelPageMore(id, token)
            if (loadedId != id) {
                _isLoadingMore.value = false
                return@launch
            }

            val more = result.getOrNull()
            if (more != null) {
                _videos.value = (_videos.value + more.videos).distinctBy { it.id }
                nextToken = more.nextToken
                _hasMore.value = more.hasMore
            } else {
                nextToken = ""
                _hasMore.value = false
            }
            _isLoadingMore.value = false
        }
    }

    private fun load(channelId: String) {
        loadJob?.cancel()
        loadedId = channelId
        _videos.value = emptyList()
        _error.value = null
        nextToken = ""
        _hasMore.value = false
        _isLoading.value = true

        loadJob = viewModelScope.launch {
            val result = YouTubeDataRepository.getChannelPage(channelId)
            if (loadedId != channelId) return@launch

            val page = result.getOrNull()
            if (page != null) {
                _page.value = page
                _videos.value = page.videos
                nextToken = page.nextToken
                _hasMore.value = page.hasMore
                _error.value = null
            } else {
                _error.value = result.errorOrNull() ?: AppError.Unavailable()
                _hasMore.value = false
            }
            _isLoading.value = false
        }
    }

    override fun onCleared() {
        loadJob?.cancel()
        super.onCleared()
    }
}

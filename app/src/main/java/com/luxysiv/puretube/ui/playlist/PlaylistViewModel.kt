package com.luxysiv.puretube.ui.playlist

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.luxysiv.puretube.data.model.PlaylistItem
import com.luxysiv.puretube.data.model.VideoItem
import com.luxysiv.puretube.data.repository.YouTubeDataRepository
import com.luxysiv.puretube.error.AppError
import com.luxysiv.puretube.ui.base.PureTubeViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Trạng thái của màn hình chi tiết playlist.
 *
 * Playlist detail tải qua InnerTube `browseId=VL<id>`; mỗi trang sau dùng
 * `continuation` cho tới khi YouTube không trả token nữa.
 */
class PlaylistViewModel(application: Application) : PureTubeViewModel(application) {

    private val _playlist = MutableStateFlow<PlaylistItem?>(null)
    val playlist: StateFlow<PlaylistItem?> = _playlist.asStateFlow()

    private val _videos = MutableStateFlow<List<VideoItem>>(emptyList())
    val videos: StateFlow<List<VideoItem>> = _videos.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    /** Lỗi của lần tải đầu; lỗi tải thêm không xoá danh sách đang có. */
    private val _error = MutableStateFlow<AppError?>(null)
    val error: StateFlow<AppError?> = _error.asStateFlow()

    private var loadedId: String? = null
    private var nextToken = ""
    private var loadJob: Job? = null

    /** Continuation của trang cuối đã tải, chuyển sang hàng đợi phát. */
    val nextPageToken: String get() = nextToken

    /** Mở playlist; bỏ qua nếu đang hiển thị đúng playlist đó. */
    fun start(playlist: PlaylistItem) {
        _playlist.value = playlist
        if (loadedId == playlist.id && (_videos.value.isNotEmpty() || _isLoading.value)) return
        load(playlist)
    }

    fun retry() {
        val current = _playlist.value ?: return
        load(current)
    }

    fun loadMore() {
        val current = _playlist.value ?: return
        val token = nextToken
        if (token.isBlank() || _isLoading.value || _isLoadingMore.value) return

        viewModelScope.launch {
            _isLoadingMore.value = true
            val result = YouTubeDataRepository.getPlaylistPage(current.id, token)
            if (_playlist.value?.id != current.id) {
                _isLoadingMore.value = false
                return@launch
            }

            val page = result.getOrNull()
            if (page != null) {
                val merged = (_videos.value + page.videos).distinctBy { it.id }
                _videos.value = merged
                nextToken = page.nextToken
                _hasMore.value = page.hasMore
            } else {
                // Tránh cuộn lại kích hoạt request lỗi liên tục; danh sách đang
                // có vẫn được giữ nguyên.
                nextToken = ""
                _hasMore.value = false
            }
            _isLoadingMore.value = false
        }
    }

    /**
     * Tải tiếp cho tới khi tìm thấy video cần resume hoặc hết danh sách.
     *
     * Dùng khi mở lại playlist từ Lịch sử: video đang xem dở có thể nằm ở
     * trang thứ hai trở đi, nên chỉ tải trang đầu là chưa đủ.
     */
    suspend fun ensureVideoLoaded(videoId: String): VideoItem? {
        if (videoId.isBlank()) return null
        loadJob?.join()
        _videos.value.firstOrNull { it.id == videoId }?.let { return it }

        var pages = 0
        while (_hasMore.value && nextToken.isNotBlank() && pages < MAX_RESUME_PAGES) {
            val current = _playlist.value ?: return null
            val token = nextToken
            _isLoadingMore.value = true
            val result = YouTubeDataRepository.getPlaylistPage(current.id, token)
            if (_playlist.value?.id != current.id) {
                _isLoadingMore.value = false
                return null
            }

            val page = result.getOrNull()
            if (page == null) {
                // Dừng để không lặp request lỗi; danh sách đang có vẫn giữ.
                nextToken = ""
                _hasMore.value = false
                _isLoadingMore.value = false
                return null
            }

            val merged = (_videos.value + page.videos).distinctBy { it.id }
            _videos.value = merged
            nextToken = page.nextToken
            _hasMore.value = page.hasMore
            pages++
            merged.firstOrNull { it.id == videoId }?.let {
                _isLoadingMore.value = false
                return it
            }
        }
        _isLoadingMore.value = false
        return null
    }

    private fun load(playlist: PlaylistItem) {
        loadJob?.cancel()
        loadedId = playlist.id
        _videos.value = emptyList()
        _error.value = null
        nextToken = ""
        _hasMore.value = false
        _isLoading.value = true

        loadJob = viewModelScope.launch {
            val result = YouTubeDataRepository.getPlaylistPage(playlist.id)
            if (loadedId != playlist.id) return@launch

            val page = result.getOrNull()
            if (page != null) {
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

    private companion object {
        /** Giới hạn số trang tải thêm khi resume từ lịch sử (~1000 video). */
        const val MAX_RESUME_PAGES = 20
    }
}

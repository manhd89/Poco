package com.luxysiv.puretube.ui.search

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.luxysiv.puretube.data.model.SearchItem
import com.luxysiv.puretube.data.model.VideoCategory
import com.luxysiv.puretube.data.model.VideoItem
import com.luxysiv.puretube.data.repository.YouTubeDataRepository
import com.luxysiv.puretube.error.AppError
import com.luxysiv.puretube.error.getOrElse
import com.luxysiv.puretube.error.runAppCatching
import com.luxysiv.puretube.ui.base.PureTubeViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel riêng cho trang tìm kiếm: từ khoá, gợi ý, lịch sử tìm kiếm và
 * kết quả. Không phụ thuộc vào trạng thái tab của trang khác.
 */
class SearchViewModel(application: Application) : PureTubeViewModel(application) {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SearchItem>>(emptyList())
    val searchResults: StateFlow<List<SearchItem>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    /** true = đang hiển thị kết quả của lần tìm kiếm đã xác nhận; false = đang gợi ý dạng chữ. */
    private val _isShowingResults = MutableStateFlow(false)
    val isShowingResults: StateFlow<Boolean> = _isShowingResults.asStateFlow()

    /** Lỗi của lần tìm gần nhất (khác với "không có kết quả"); null = thành công. */
    private val _searchError = MutableStateFlow<AppError?>(null)
    val searchError: StateFlow<AppError?> = _searchError.asStateFlow()

    /** Gợi ý tìm kiếm THẬT từ YouTube autocomplete (giống danh sách chữ của youtube.com). */
    private val _searchSuggestions = MutableStateFlow<List<String>>(emptyList())
    val searchSuggestions: StateFlow<List<String>> = _searchSuggestions.asStateFlow()

    /** Lịch sử tìm kiếm THẬT của người dùng (đọc từ bộ nhớ thiết bị). */
    private val _recentSearches = MutableStateFlow(searchHistoryStore.load())
    val recentSearches: StateFlow<List<String>> = _recentSearches.asStateFlow()

    /** Gợi ý ở trạng thái trống, lấy từ kênh thật của feed đang hiển thị. */
    private val _popularSearches = MutableStateFlow<List<String>>(emptyList())
    val popularSearches: StateFlow<List<String>> = _popularSearches.asStateFlow()

    // Search debounce
    private var searchJob: Job? = null

    // Debounce cho gợi ý tìm kiếm (autocomplete)
    private var suggestionJob: Job? = null

    // Đánh dấu lượt tìm kiếm mới nhất để bỏ qua kết quả của lượt đã huỷ
    private var searchRequestId = 0

    init {
        viewModelScope.launch {
            val videos = YouTubeDataRepository.loadTrendingVideos(VideoCategory.ALL)
                .getOrNull()
                .orEmpty()
            _popularSearches.value = buildPopularSearches(videos)
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        suggestionJob?.cancel()
        searchRequestId++
        _searchError.value = null

        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _searchSuggestions.value = emptyList()
            _isSearching.value = false
            _isShowingResults.value = false
            return
        }

        // Người dùng đang gõ: hiện danh sách gợi ý dạng chữ (giống YouTube).
        // Kết quả chỉ tải khi họ xác nhận tìm kiếm.
        _isSearching.value = false
        _isShowingResults.value = false

        // Hiện ngay các từ khóa thật trong lịch sử khớp với từ khóa đang gõ
        val typed = query.trim()
        _searchSuggestions.value = _recentSearches.value
            .filter { it.contains(typed, ignoreCase = true) }
            .distinctBy { it.lowercase() }

        suggestionJob = viewModelScope.launch {
            delay(250) // debounce
            val remote = YouTubeDataRepository.getSearchSuggestions(typed)
                .getOrNull()
                .orEmpty()

            // Bỏ kết quả cũ nếu người dùng đã gõ từ khóa khác hoặc đã tìm kiếm
            if (_searchQuery.value.trim() != typed) return@launch
            if (_isShowingResults.value) return@launch

            // Ghép gợi ý thật từ YouTube với lịch sử tìm kiếm thật khớp từ khóa
            val local = _recentSearches.value.filter {
                it.contains(typed, ignoreCase = true)
            }
            _searchSuggestions.value = (local + remote)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }
                .take(10)
        }
    }

    fun performSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return

        searchJob?.cancel()
        suggestionJob?.cancel()
        _searchSuggestions.value = emptyList()
        _searchQuery.value = trimmed
        _isShowingResults.value = true
        _searchError.value = null

        val requestId = ++searchRequestId
        searchJob = viewModelScope.launch {
            _isSearching.value = true
            // Lưu từ khóa vào lịch sử tìm kiếm thật trên thiết bị
            _recentSearches.value = searchHistoryStore.add(trimmed)

            val result = YouTubeDataRepository.searchItemsWithStatus(trimmed)

            if (requestId == searchRequestId) {
                _searchResults.value = prioritizeWatched(result.getOrNull()?.items.orEmpty())
                _searchError.value = result.errorOrNull()
                _isSearching.value = false
            }
        }
    }

    /**
     * Đưa các video người dùng đã phát lên đầu danh sách video, video xem gần
     * nhất đứng trước; playlist giữ nguyên thứ tự và vị trí xen kẽ của YouTube.
     */
    private suspend fun prioritizeWatched(items: List<SearchItem>): List<SearchItem> {
        if (items.isEmpty()) return items
        val watchedIds = runAppCatching { videoDao.getWatchedVideoIds() }
            .getOrElse(emptyList())
        if (watchedIds.isEmpty()) return items

        val videos = items.filterIsInstance<SearchItem.Video>()
        if (videos.size < 2) return items

        val rankById = watchedIds.withIndex().associate { (index, id) -> id to index }
        val ordered = videos.sortedBy { rankById[it.video.id] ?: Int.MAX_VALUE }
        val queue = ArrayDeque(ordered)
        return items.map { item ->
            if (item is SearchItem.Video) queue.removeFirst() else item
        }
    }

    override fun clearSearchHistory() {
        super.clearSearchHistory()
        _recentSearches.value = emptyList()
    }

    override fun removeSearchHistory(query: String) {
        super.removeSearchHistory(query)
        _recentSearches.value = searchHistoryStore.load()
    }

    /**
     * Gợi ý ở trạng thái trống lấy từ KÊNH THẬT của feed đang hiển thị,
     * không còn danh sách từ khóa set cứng.
     */
    private fun buildPopularSearches(videos: List<VideoItem>): List<String> =
        videos.asSequence()
            .map { it.channelTitle.trim() }
            .filter { it.isNotEmpty() && !it.equals("YouTube", ignoreCase = true) }
            .distinctBy { it.lowercase() }
            .take(8)
            .toList()

    override fun onCleared() {
        searchJob?.cancel()
        suggestionJob?.cancel()
        super.onCleared()
    }
}

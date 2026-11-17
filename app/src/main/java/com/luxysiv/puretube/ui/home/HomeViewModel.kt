package com.luxysiv.puretube.ui.home

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.luxysiv.puretube.data.database.PlaylistHistoryEntity
import com.luxysiv.puretube.data.database.VideoEntity
import com.luxysiv.puretube.data.model.HomeFeedPage
import com.luxysiv.puretube.data.model.HomeMix
import com.luxysiv.puretube.data.model.VideoCategory
import com.luxysiv.puretube.data.model.VideoItem
import com.luxysiv.puretube.data.repository.YouTubeDataRepository
import com.luxysiv.puretube.error.AppError
import com.luxysiv.puretube.error.AppResult
import com.luxysiv.puretube.error.getOrElse
import com.luxysiv.puretube.error.runAppCatching
import com.luxysiv.puretube.ui.base.PureTubeViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Số video gần nhất trong lịch sử dùng để tạo mix cho trang chủ. */
private const val HOME_MIX_SEED_COUNT = 2

/** Số video gợi ý hiển thị ngay ở trang đầu tab Dành cho bạn. */
private const val HOME_SUGGESTION_COUNT = 12

/** Số video nối thêm mỗi lần cuộn ở tab Dành cho bạn. */
private const val FOR_YOU_PAGE_SIZE = 12

/**
 * ViewModel riêng cho trang chủ.
 *
 * Chỉ giữ trạng thái feed, danh mục và phần cá nhân hoá (mix/gợi ý). Trạng
 * thái phát và thư viện dùng chung nằm ở [PureTubeViewModel].
 *
 * Luồng dữ liệu được mô tả khai báo thay vì dùng cờ version thủ công:
 * - chọn danh mục/làm mới phát một [FeedRequest] vào [feedRequests];
 * - mỗi request chạy trong `flatMapLatest`, nên request cũ tự bị huỷ và không
 *   bao giờ ghi đè kết quả mới;
 * - lịch sử xem được `distinctUntilChanged` theo seed trước khi tải mix, nên
 *   ticker ghi tiến độ mỗi 5 giây không tạo request mạng lặp.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(application: Application) : PureTubeViewModel(application) {

    private val _selectedTab = MutableStateFlow(HomeTab.TRENDING)
    private val _selectedCategory = MutableStateFlow(VideoCategory.ALL)
    private val _feedState = MutableStateFlow(FeedState())
    private val _homeMixes = MutableStateFlow<List<HomeMix>>(emptyList())
    private val _forYouFeed = MutableStateFlow(ForYouFeedState())
    private val _isLoadingPersonalization = MutableStateFlow(false)
    private val _isHistoryLoaded = MutableStateFlow(false)

    /**
     * Request feed gần nhất; replay để collector khởi động muộn vẫn nhận request
     * đầu, buffer thêm một phần tử + DROP_OLDEST để thao tác đổi danh mục/làm mới
     * liên tiếp không bị mất request mới nhất khi collector đang bận.
     */
    private val feedRequests = MutableSharedFlow<FeedRequest>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Tăng khi người dùng kéo làm mới, buộc tải lại mix dù seed không đổi. */
    private val personalizationRefresh = MutableStateFlow(0)

    private var defaultTabResolved = false
    private var tabPinnedByUser = false
    private var loadMoreJob: Job? = null
    private var forYouLoadMoreJob: Job? = null

    /**
     * Các nguồn mix còn trang kế tiếp cho tab Dành cho bạn.
     *
     * [anchorVideoId] là video cuối đã nhận của mix đó; lần tải sau dùng nó làm
     * mốc để YouTube trả đúng 24 video tiếp theo.
     */
    private var forYouSources: List<ForYouSource> = emptyList()

    /** Phần video của trang đầu chưa hiển thị, nối tiếp trước khi gọi mạng. */
    private var forYouPending: List<VideoItem> = emptyList()

    /** Video đã xem/seed không được lặp lại trong các trang tải thêm. */
    private var forYouExcludedIds: Set<String> = emptySet()

    /**
     * Tăng mỗi khi feed Dành cho bạn được dựng lại. Kết quả tải thêm của thế
     * hệ cũ bị bỏ, tránh nối nhầm video cũ vào lịch sử mới.
     */
    private var forYouGeneration = 0

    private class ForYouSource(
        val mixId: String,
        val anchorVideoId: String
    )

    private data class FeedRequest(
        val category: VideoCategory,
        val forceRefresh: Boolean = false
    )

    private data class FeedState(
        val videos: List<VideoItem> = emptyList(),
        val isLoading: Boolean = false,
        val error: AppError? = null,
        val isLoadingMore: Boolean = false,
        val hasMore: Boolean = false,
        val loadMoreError: AppError? = null
    )

    private data class ForYouFeedState(
        val videos: List<VideoItem> = emptyList(),
        val isLoadingMore: Boolean = false,
        val hasMore: Boolean = false,
        val loadMoreError: AppError? = null
    )

    private sealed interface FeedUpdate {
        data object Loading : FeedUpdate
        data class Loaded(val result: AppResult<HomeFeedPage>) : FeedUpdate
    }

    private sealed interface PersonalizationUpdate {
        data object Cleared : PersonalizationUpdate
        data object Loading : PersonalizationUpdate
        data class Loaded(
            val mixes: List<HomeMix>,
            val suggestions: List<VideoItem>,
            val pending: List<VideoItem>,
            val excludedIds: Set<String>
        ) : PersonalizationUpdate
    }

    private data class HistoryEntries(
        val videos: List<VideoEntity>,
        val playlists: List<PlaylistHistoryEntity>
    )

    private data class PersonalizationState(
        val history: HistoryEntries,
        val mixes: List<HomeMix>,
        val forYou: ForYouFeedState,
        val isLoading: Boolean,
        val historyLoaded: Boolean
    )

    private data class LibraryState(
        val favoriteIds: Set<String>,
        val watchLaterIds: Set<String>,
        val savedPlaylistIds: Set<String>,
        val currentVideoId: String?
    )

    /** Lịch sử video và playlist được gộp để cùng quyết định tab mặc định. */
    private val historyEntries = combine(history, playlistHistory) { videos, playlists ->
        HistoryEntries(videos, playlists)
    }

    private val personalizationState = combine(
        historyEntries,
        _homeMixes,
        _forYouFeed,
        _isLoadingPersonalization,
        _isHistoryLoaded
    ) { entries, mixes, forYou, loading, loaded ->
        PersonalizationState(entries, mixes, forYou, loading, loaded)
    }

    private val libraryState = combine(
        favoriteIds,
        watchLaterIds,
        savedPlaylistIds,
        playerState.map { it.currentVideo?.id }.distinctUntilChanged()
    ) { favorites, watchLater, savedPlaylists, videoId ->
        LibraryState(favorites, watchLater, savedPlaylists, videoId)
    }

    /** Trạng thái duy nhất cho HomeNativeView. */
    val uiState: StateFlow<HomeUiState> = combine(
        _selectedTab,
        _selectedCategory,
        _feedState,
        personalizationState,
        libraryState
    ) { tab, category, feed, personalization, library ->
        HomeUiState(
            selectedTab = tab,
            selectedCategory = category,
            trendingVideos = feed.videos,
            isLoadingFeed = feed.isLoading,
            feedError = feed.error,
            isLoadingMoreFeed = feed.isLoadingMore,
            hasMoreFeed = feed.hasMore,
            feedLoadMoreError = feed.loadMoreError,
            history = personalization.history.videos,
            playlistHistory = personalization.history.playlists,
            homeMixes = personalization.mixes,
            forYouVideos = personalization.forYou.videos,
            isLoadingMoreForYou = personalization.forYou.isLoadingMore,
            hasMoreForYou = personalization.forYou.hasMore,
            forYouLoadMoreError = personalization.forYou.loadMoreError,
            isLoadingPersonalization = personalization.isLoading,
            historyLoaded = personalization.historyLoaded,
            favoriteIds = library.favoriteIds,
            watchLaterIds = library.watchLaterIds,
            savedPlaylistIds = library.savedPlaylistIds,
            currentVideoId = library.currentVideoId
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())

    init {
        observeFeed()
        observeHomePersonalization()
        feedRequests.tryEmit(FeedRequest(VideoCategory.ALL))
    }

    /** Người dùng tự chuyển tab: từ đây không tự đổi tab mặc định nữa. */
    fun selectTab(tab: HomeTab) {
        tabPinnedByUser = true
        _selectedTab.value = tab
    }

    fun selectCategory(category: VideoCategory) {
        loadMoreJob?.cancel()
        _selectedCategory.value = category
        feedRequests.tryEmit(FeedRequest(category))
    }

    fun refreshFeed() {
        loadMoreJob?.cancel()
        forYouLoadMoreJob?.cancel()
        _forYouFeed.update { it.copy(isLoadingMore = false) }
        feedRequests.tryEmit(FeedRequest(_selectedCategory.value, forceRefresh = true))
        personalizationRefresh.update { it + 1 }
    }

    /**
     * Giữ API tải feed tường minh cho nơi khác (nếu cần); UI Trang chủ chỉ cần
     * [refreshFeed]/[selectCategory].
     */
    fun loadTrendingFeed(
        category: VideoCategory = _selectedCategory.value,
        forceRefresh: Boolean = false
    ) {
        loadMoreJob?.cancel()
        _selectedCategory.value = category
        feedRequests.tryEmit(FeedRequest(category, forceRefresh))
    }

    /**
     * Tải thêm một trang cho feed Thịnh hành hiện tại.
     *
     * Lỗi trang trước dừng tự động tải để scroll listener không gọi mạng liên
     * tục; UI hiện footer thử lại và gọi [retryLoadMore] khi người dùng bấm.
     */
    fun loadMoreFeed() {
        // Job đang chạy được kiểm tra ngay (không chờ coroutine đặt cờ) để
        // nhiều sự kiện scroll trong cùng một khung hình không tạo request trùng.
        if (loadMoreJob?.isActive == true) return
        val state = _feedState.value
        if (state.isLoading ||
            state.isLoadingMore ||
            !state.hasMore ||
            state.loadMoreError != null
        ) {
            return
        }

        val category = _selectedCategory.value
        loadMoreJob = viewModelScope.launch {
            _feedState.update { it.copy(isLoadingMore = true, loadMoreError = null) }
            val result = YouTubeDataRepository.loadMoreTrendingFeed(category)
            // Job có thể vừa bị huỷ do đổi danh mục/làm mới; không ghi đè trạng
            // thái mới bằng kết quả của feed cũ.
            coroutineContext.ensureActive()
            if (_selectedCategory.value != category) return@launch

            val page = result.getOrNull()
            if (page != null) {
                _feedState.update { current ->
                    if (_selectedCategory.value != category) {
                        current
                    } else {
                        current.copy(
                            videos = (current.videos + page.videos).distinctBy { it.id },
                            isLoadingMore = false,
                            hasMore = page.hasMore,
                            loadMoreError = null
                        )
                    }
                }
                // Feed dài hơn cũng là nguồn dự phòng cuối khi hết mix/related.
                if (_selectedCategory.value == category) {
                    playbackController.setTrending(_feedState.value.videos)
                }
            } else {
                _feedState.update {
                    it.copy(
                        isLoadingMore = false,
                        loadMoreError = result.errorOrNull() ?: AppError.Unknown()
                    )
                }
            }
        }
    }

    /** Người dùng bấm "Thử lại" ở footer sau khi tải thêm thất bại. */
    fun retryLoadMore() {
        _feedState.update { it.copy(loadMoreError = null) }
        loadMoreFeed()
    }

    /**
     * Tải thêm video cho tab Dành cho bạn.
     *
     * Trang đầu gồm các gợi ý trộn từ 2 mix gần nhất. Khi cuộn, phần còn lại
     * của trang đầu được nối trước (không gọi mạng), sau đó mỗi mix lấy thêm
     * một trang và được trộn đều với nhau. Nhờ vậy feed vừa có gợi ý cá nhân
     * hoá vừa có video khác nối tiếp không giới hạn.
     */
    fun loadMoreForYou() {
        // Kiểm tra job ngay để nhiều sự kiện scroll trong cùng khung hình không
        // tạo request trùng.
        if (forYouLoadMoreJob?.isActive == true) return
        val state = _forYouFeed.value
        if (state.isLoadingMore || !state.hasMore || state.loadMoreError != null) return

        val generation = forYouGeneration
        forYouLoadMoreJob = viewModelScope.launch {
            val pending = forYouPending
            if (pending.isNotEmpty()) {
                val batch = pending.take(FOR_YOU_PAGE_SIZE)
                forYouPending = pending.drop(batch.size)
                val currentIds = _forYouFeed.value.videos.mapTo(HashSet()) { it.id }
                val fresh = batch.filter { currentIds.add(it.id) }
                _forYouFeed.update {
                    it.copy(
                        videos = (it.videos + fresh).distinctBy { video -> video.id },
                        hasMore = forYouPending.isNotEmpty() || forYouSources.isNotEmpty()
                    )
                }
                return@launch
            }

            val sources = forYouSources
            if (sources.isEmpty()) {
                _forYouFeed.update { it.copy(hasMore = false) }
                return@launch
            }

            _forYouFeed.update { it.copy(isLoadingMore = true, loadMoreError = null) }
            val fetched = coroutineScope {
                sources.map { source ->
                    async {
                        source to YouTubeDataRepository.getMoreHomeMix(
                            mixId = source.mixId,
                            anchorVideoId = source.anchorVideoId
                        )
                    }
                }.awaitAll()
            }
            // Lịch sử/làm mới có thể vừa dựng lại feed trong lúc chờ mạng.
            coroutineContext.ensureActive()
            if (generation != forYouGeneration) return@launch

            val seenIds = _forYouFeed.value.videos.mapTo(HashSet()) { it.id }
            seenIds += forYouExcludedIds
            val nextSources = ArrayList<ForYouSource>(fetched.size)
            val loadedPages = ArrayList<List<VideoItem>>(fetched.size)
            var failure: AppError? = null
            var anySuccess = false

            for ((source, result) in fetched) {
                val page = result.getOrNull()
                if (page == null) {
                    // Giữ nguồn lỗi để lần thử lại sau vẫn nối đúng trang.
                    failure = result.errorOrNull() ?: AppError.Unknown()
                    nextSources += source
                    continue
                }

                anySuccess = true
                val last = page.videos.lastOrNull()
                if (last != null && last.id != source.anchorVideoId) {
                    // Tiến mốc lên video cuối vừa nhận, kể cả khi mọi video
                    // trong trang đã hiển thị (trang sau sẽ khác).
                    nextSources += ForYouSource(source.mixId, last.id)
                }
                // Trang rỗng hoặc lặp lại đúng mốc cũ: bỏ nguồn này để không
                // cuộn mãi mà không có video mới.
                if (page.videos.isNotEmpty()) {
                    loadedPages += page.videos.filter { it.id !in seenIds }
                }
            }

            if (!anySuccess && failure != null) {
                _forYouFeed.update {
                    it.copy(isLoadingMore = false, loadMoreError = failure)
                }
                return@launch
            }

            forYouSources = nextSources
            val appended = interleaveHomeVideos(loadedPages, seenIds)
            _forYouFeed.update {
                it.copy(
                    videos = (it.videos + appended).distinctBy { video -> video.id },
                    isLoadingMore = false,
                    hasMore = forYouPending.isNotEmpty() || forYouSources.isNotEmpty(),
                    loadMoreError = null
                )
            }
        }
    }

    /** Người dùng bấm "Thử lại" ở footer tab Dành cho bạn. */
    fun retryLoadMoreForYou() {
        _forYouFeed.update { it.copy(loadMoreError = null) }
        loadMoreForYou()
    }

    /** Chạm thẻ mix ở trang chủ: phát video đầu tiên của mix đó. */
    fun playHomeMix(mix: HomeMix) {
        playVideo(mix.playlist.videos.firstOrNull() ?: mix.seed)
    }

    // ------------------------------------------------------------------
    // Feed
    // ------------------------------------------------------------------

    private fun observeFeed() {
        viewModelScope.launch {
            feedRequests
                .flatMapLatest { request -> feedFlow(request) }
                .collect { update -> applyFeedUpdate(update) }
        }
    }

    private fun feedFlow(request: FeedRequest): Flow<FeedUpdate> = flow {
        loadMoreJob?.cancel()
        emit(FeedUpdate.Loading)
        emit(
            FeedUpdate.Loaded(
                YouTubeDataRepository.loadTrendingFeed(
                    category = request.category,
                    forceRefresh = request.forceRefresh
                )
            )
        )
    }

    private fun applyFeedUpdate(update: FeedUpdate) {
        when (update) {
            FeedUpdate.Loading -> _feedState.update {
                it.copy(
                    isLoading = true,
                    isLoadingMore = false,
                    loadMoreError = null
                )
            }

            is FeedUpdate.Loaded -> {
                val page = update.result.getOrNull()
                val videos = page?.videos.orEmpty()
                _feedState.value = FeedState(
                    videos = videos,
                    isLoading = false,
                    error = update.result.errorOrNull(),
                    hasMore = page?.hasMore == true
                )
                // Coordinator dùng danh sách này làm phương án cuối khi hết mix/related.
                playbackController.setTrending(videos)
            }
        }
    }

    // ------------------------------------------------------------------
    // Cá nhân hoá theo lịch sử
    // ------------------------------------------------------------------

    private fun observeHomePersonalization() {
        viewModelScope.launch {
            combine(historyEntries, personalizationRefresh) { entries, token ->
                entries to token
            }
                .onEach { (entries, _) ->
                    _isHistoryLoaded.value = true
                    resolveDefaultTab(entries)
                }
                // Ticker lịch sử phát lại mỗi 5 giây; chỉ tải lại khi seed video
                // đổi hoặc người dùng chủ động làm mới (token tăng).
                .distinctUntilChanged { old, new ->
                    old.second == new.second &&
                        old.first.videos.map { it.videoId } ==
                        new.first.videos.map { it.videoId }
                }
                .flatMapLatest { (entries, _) -> personalizationFlow(entries.videos) }
                .collect { update -> applyPersonalizationUpdate(update) }
        }
    }

    private fun personalizationFlow(
        seeds: List<VideoEntity>
    ): Flow<PersonalizationUpdate> = flow {
        if (seeds.isEmpty()) {
            emit(PersonalizationUpdate.Cleared)
            return@flow
        }
        emit(PersonalizationUpdate.Loading)

        val mixes = seeds.take(HOME_MIX_SEED_COUNT).mapNotNull { entry ->
            val playlist = YouTubeDataRepository.getHomeMix(entry.videoId)
                .getOrNull() ?: return@mapNotNull null
            HomeMix(seed = entry.toVideoItem(), playlist = playlist)
        }
        val watched = runAppCatching { videoDao.getWatchedVideoIds() }
            .getOrElse(emptyList())
            .toSet()
        val excluded = watched + mixes.map { it.seed.id }
        // Trộn đều toàn bộ video của các mix; 12 video đầu là gợi ý hiển thị
        // ngay, phần còn lại được nối dần khi người dùng cuộn.
        val interleaved = interleaveHomeVideos(
            mixVideos = mixes.map { it.playlist.videos },
            excludedIds = excluded
        )
        emit(
            PersonalizationUpdate.Loaded(
                mixes = mixes,
                suggestions = interleaved.take(HOME_SUGGESTION_COUNT),
                pending = interleaved.drop(HOME_SUGGESTION_COUNT),
                excludedIds = excluded
            )
        )
    }

    private fun applyPersonalizationUpdate(update: PersonalizationUpdate) {
        when (update) {
            PersonalizationUpdate.Cleared -> {
                forYouGeneration++
                forYouLoadMoreJob?.cancel()
                forYouSources = emptyList()
                forYouPending = emptyList()
                forYouExcludedIds = emptySet()
                _homeMixes.value = emptyList()
                _forYouFeed.value = ForYouFeedState()
                _isLoadingPersonalization.value = false
            }

            PersonalizationUpdate.Loading -> _isLoadingPersonalization.value = true

            is PersonalizationUpdate.Loaded -> {
                _isLoadingPersonalization.value = false
                // Mạng lỗi tạm thời: giữ kệ cũ thay vì làm trang chủ nhấp nháy.
                if (update.mixes.isEmpty()) return

                // Feed mới: huỷ trang tải thêm của feed cũ và dựng lại từ đầu.
                forYouGeneration++
                forYouLoadMoreJob?.cancel()
                forYouExcludedIds = update.excludedIds
                forYouPending = update.pending
                forYouSources = update.mixes.mapNotNull { mix ->
                    val anchor = mix.playlist.videos.lastOrNull()?.id
                        ?: return@mapNotNull null
                    if (mix.playlist.id.isBlank()) return@mapNotNull null
                    ForYouSource(mixId = mix.playlist.id, anchorVideoId = anchor)
                }
                _homeMixes.value = update.mixes
                _forYouFeed.value = ForYouFeedState(
                    videos = update.suggestions,
                    hasMore = update.pending.isNotEmpty() || forYouSources.isNotEmpty()
                )
            }
        }
    }

    /**
     * Chọn tab mặc định theo lịch sử xem:
     * - chưa xem video/playlist nào -> Thịnh hành;
     * - đã có lịch sử -> Dành cho bạn.
     *
     * Chỉ áp dụng khi người dùng chưa tự bấm tab. Nếu lịch sử bị xoá sạch khi
     * đang ở Dành cho bạn thì quay về Thịnh hành để trang chủ không trống.
     */
    private fun resolveDefaultTab(entries: HistoryEntries) {
        if (tabPinnedByUser) return
        val hasHistory = entries.videos.isNotEmpty() || entries.playlists.isNotEmpty()
        if (!defaultTabResolved) {
            _selectedTab.value = if (hasHistory) HomeTab.FOR_YOU else HomeTab.TRENDING
            defaultTabResolved = true
            return
        }
        if (!hasHistory && _selectedTab.value == HomeTab.FOR_YOU) {
            _selectedTab.value = HomeTab.TRENDING
        }
    }
}

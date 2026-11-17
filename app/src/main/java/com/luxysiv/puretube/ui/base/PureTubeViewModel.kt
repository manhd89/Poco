package com.luxysiv.puretube.ui.base

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.luxysiv.puretube.R
import com.luxysiv.puretube.data.database.AppDatabase
import com.luxysiv.puretube.data.database.SavedPlaylistEntity
import com.luxysiv.puretube.data.database.VideoEntity
import com.luxysiv.puretube.data.local.AppSettings
import com.luxysiv.puretube.data.local.SearchHistoryStore
import com.luxysiv.puretube.data.repository.SubscriptionRepository
import com.luxysiv.puretube.data.repository.YouTubeDataRepository
import com.luxysiv.puretube.data.model.PlaybackPlan
import com.luxysiv.puretube.data.model.PlaylistItem
import com.luxysiv.puretube.data.model.SponsorCategory
import com.luxysiv.puretube.data.model.SponsorMode
import com.luxysiv.puretube.data.model.VideoDetails
import com.luxysiv.puretube.data.model.VideoItem
import com.luxysiv.puretube.error.AppError
import com.luxysiv.puretube.player.AutoPlayMode
import com.luxysiv.puretube.player.LibraryFlags
import com.luxysiv.puretube.player.PlaybackFailure
import com.luxysiv.puretube.player.PlaybackControllerProvider
import com.luxysiv.puretube.player.PlaybackOrigin
import com.luxysiv.puretube.player.PlaybackPosition
import com.luxysiv.puretube.player.PlaybackUiState
import com.luxysiv.puretube.player.PurePlayerState
import com.luxysiv.puretube.player.QueueUiState
import com.luxysiv.puretube.player.SponsorUiState
import com.luxysiv.puretube.util.Formatters
import com.luxysiv.puretube.util.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Trạng thái và hành động dùng chung cho mọi trang.
 *
 * Mỗi trang có ViewModel riêng, nhưng chúng cùng kế thừa lớp này để không lặp
 * lại phần đọc/ghi Room, điều khiển trình phát cấp process và các cờ thư viện.
 * Nhờ vậy không có hai nguồn sự thật: ExoPlayer, hàng đợi và cài đặt vẫn sống
 * trong [PlaybackControllerProvider] và mọi ViewModel chỉ quan sát lại.
 */
abstract class PureTubeViewModel(application: Application) : AndroidViewModel(application) {

    protected val videoDao = AppDatabase.getDatabase(application).videoDao()
    protected val playlistHistoryDao = AppDatabase.getDatabase(application).playlistHistoryDao()
    protected val savedPlaylistDao = AppDatabase.getDatabase(application).savedPlaylistDao()

    /** Đăng ký cục bộ (kênh + feed đã tải) dùng chung cho mọi trang. */
    protected val subscriptionRepository = SubscriptionRepository(application)

    protected val searchHistoryStore = SearchHistoryStore(application)

    /** Cửa duy nhất xuống bộ điều phối phát cấp process. */
    protected val playbackController = PlaybackControllerProvider.get(application)

    /** Cài đặt dùng chung với coordinator (luôn là bản mới nhất). */
    protected val settings: AppSettings get() = playbackController.settings.value

    val player: Player get() = playbackController.player

    // ------------------------------------------------------------------
    // Trạng thái phát tổng hợp cho Compose (nguồn: PlaybackCoordinator)
    // ------------------------------------------------------------------

    private val _playerState = MutableStateFlow(PurePlayerState())
    val playerState: StateFlow<PurePlayerState> = _playerState.asStateFlow()

    val queueState: StateFlow<QueueUiState> = playbackController.queueState

    val position: StateFlow<PlaybackPosition> = playbackController.position

    val playbackPlan: StateFlow<PlaybackPlan?> = playbackController.plan

    /** Mô tả đầy đủ + lượt xem/thích/avatar kênh thật của video đang xem. */
    val videoDetails: StateFlow<VideoDetails?> = playbackController.details

    /** Lỗi phát gần nhất; null = đang phát/tải bình thường. */
    private val _playbackFailure = MutableStateFlow<PlaybackFailure?>(null)
    val playbackFailure: StateFlow<PlaybackFailure?> = _playbackFailure.asStateFlow()

    /** Snackbar/toast ngắn cho lỗi link, clipboard, khôi phục dữ liệu... */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    // ------------------------------------------------------------------
    // Thư viện (Room) dùng chung cho trang chủ, đăng ký, tìm kiếm, bạn...
    // ------------------------------------------------------------------

    val favorites = videoDao.getFavorites()
    val history = videoDao.getHistory()
    val playlistHistory = playlistHistoryDao.getHistory()
    val watchLater = videoDao.getWatchLater()
    val savedPlaylists = savedPlaylistDao.getSaved()
    val favoriteIds = videoDao.getFavoriteIds().map { it.toSet() }
    val watchLaterIds = videoDao.getWatchLaterIds().map { it.toSet() }
    val savedPlaylistIds = savedPlaylistDao.getSavedIds().map { it.toSet() }
    val watchedIds = videoDao.observeWatchedVideoIds().map { it.toSet() }

    /** Tập channelId đã đăng ký, để nút Đăng ký và tab Đăng ký cùng trạng thái. */
    val subscribedChannelIds: Flow<Set<String>> = subscriptionRepository.subscribedChannelIds

    init {
        // Một collector duy nhất: mọi thay đổi của 5 nguồn đều đi qua combine,
        // tránh 5 collector cùng dựng lại PurePlayerState và gây render trùng.
        viewModelScope.launch {
            combine(
                playbackController.playback,
                playbackController.sponsorState,
                playbackController.settings,
                playbackController.libraryFlags,
                playbackController.isMeteredNetwork
            ) { playback, sponsor, settings, library, metered ->
                PlaybackProjection(playback, sponsor, settings, library, metered)
            }.collect { projection -> syncPlaybackState(projection) }
        }
        viewModelScope.launch {
            playbackController.messages.collect { _message.value = it }
        }
    }

    /** Ảnh chụp 5 nguồn trạng thái tại cùng một thời điểm. */
    private data class PlaybackProjection(
        val playback: PlaybackUiState,
        val sponsor: SponsorUiState,
        val settings: AppSettings,
        val library: LibraryFlags,
        val metered: Boolean
    )

    /** Gom trạng thái phát + cấu hình + thư viện thành [PurePlayerState] cho UI. */
    private fun syncPlaybackState(projection: PlaybackProjection) {
        val playback = projection.playback
        val sponsor = projection.sponsor
        val settings = projection.settings
        val library = projection.library
        val metered = projection.metered
        val failure = playback.failure

        _playbackFailure.value = failure

        _playerState.update { current ->
            current.copy(
                currentVideo = playback.video,
                isPlaying = playback.isPlaying,
                playbackSpeed = playback.speed,
                playbackPitch = playback.pitch,
                isSkipSilenceEnabled = playback.skipSilence,
                isBuffering = playback.isBuffering,
                isBackgroundPlayEnabled = settings.backgroundPlay,
                isAutoNextEnabled = playback.isAutoNextEnabled,
                isAutoPiPEnabled = settings.autoPiP,
                isResumeEnabled = playback.isResumeEnabled,
                isSponsorBlockEnabled = settings.skipSponsors,
                sponsorSegments = sponsor.segments,
                sponsorLastSkipped = sponsor.lastSkipped,
                sponsorCanUnskip = sponsor.canUnskip,
                sponsorManualSegment = sponsor.manualSegment,
                sponsorCategoryModes = settings.sponsorCategoryModes,
                sponsorNotify = settings.sponsorNotify,
                sponsorShowSkipButton = settings.sponsorShowSkipButton,
                sponsorGracefulRewind = settings.sponsorGracefulRewind,
                sponsorApiUrl = settings.sponsorApiUrl,
                sponsorWhitelistCount = settings.sponsorWhitelist.size,
                isCurrentChannelSponsorWhitelisted = sponsor.isCurrentChannelWhitelisted,
                sleepTimerMinutes = playback.sleepTimerMinutes,
                sleepTimerRemainingSeconds = playback.sleepTimerRemainingSeconds,
                isFavorite = library.isFavorite,
                isWatchLater = library.isWatchLater,
                selectedQuality = playback.selectedQuality,
                qualityPreferenceHeight =
                    if (metered) settings.qualityMobileHeight else settings.qualityHeight,
                wifiQualityHeight = settings.qualityHeight,
                mobileQualityHeight = settings.qualityMobileHeight,
                wifiQualityLabel = Formatters.quality(settings.qualityHeight, "Tự động"),
                mobileQualityLabel = Formatters.quality(settings.qualityMobileHeight, "Tự động"),
                isDataSaverEnabled = settings.dataSaver,
                isMeteredNetwork = metered,
                availableQualities = playback.availableQualities,
                captionsEnabled = playback.captionsEnabled,
                captionLanguageTag = playback.captionLanguageTag,
                isWaitingForNetwork = playback.isWaitingForNetwork,
                hideComments = settings.hideComments,
                hideRelated = settings.hideRelated,
                hideDescription = settings.hideDescription
            )
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    /** Cho ViewModel con đẩy thông báo ngắn lên snackbar của trang. */
    protected fun postMessage(text: String) {
        _message.value = text
    }

    /** Đẩy lỗi đã chuẩn hoá lên snackbar với nội dung hướng dẫn thống nhất. */
    protected fun postError(error: AppError) {
        postMessage(error.userMessage(getApplication<Application>()))
    }

    // ------------------------------------------------------------------
    // Phát video
    // ------------------------------------------------------------------

    fun playVideo(
        video: VideoItem,
        addToBackStack: Boolean = true
    ) {
        playbackController.play(
            video = video,
            origin = PlaybackOrigin.USER,
            addToBackStack = addToBackStack
        )
    }

    /**
     * Phát một video trong playlist và giữ playlist làm hàng đợi kế tiếp.
     *
     * [videos] là danh sách đang hiển thị (đúng thứ tự YouTube), [nextToken] là
     * continuation để hàng đợi tự tải thêm khi hết trang.
     */
    fun playPlaylistVideo(
        video: VideoItem,
        playlist: PlaylistItem,
        videos: List<VideoItem>,
        nextToken: String = ""
    ) {
        playbackController.playPlaylist(video, playlist, videos, nextToken)
    }

    fun retryPlayback() = playbackController.retry()

    /** Nút đóng trên mini player: dừng phát và xoá now-playing. */
    fun closePlayer() = playbackController.close()

    fun togglePlayPause() = playbackController.togglePlayPause()

    fun seekTo(seconds: Float) = playbackController.seekTo(seconds)

    fun forward10Seconds() = playbackController.forward10Seconds()

    fun rewind10Seconds() = playbackController.rewind10Seconds()

    fun setPlaybackSpeed(speed: Float) = playbackController.setPlaybackSpeed(speed)

    fun setPlaybackPitch(pitch: Float) = playbackController.setPlaybackPitch(pitch)

    fun toggleSkipSilence() = playbackController.toggleSkipSilence()

    /** Đưa tốc độ về 1.0x như nút "Đặt lại" của YouTube. */
    fun resetPlaybackParameters() = playbackController.resetPlaybackParameters()

    /**
     * Chọn chất lượng cho **loại mạng đang dùng**. Trình phát của YouTube cũng
     * ghi nhớ riêng Wi-Fi và dữ liệu di động.
     */
    fun setQuality(height: Int?) = playbackController.setQuality(height)

    fun setWifiQuality(height: Int?) = playbackController.setWifiQuality(height)

    fun setMobileQuality(height: Int?) = playbackController.setMobileQuality(height)

    fun toggleDataSaver() = playbackController.toggleDataSaver()

    fun toggleFullscreen() {
        _playerState.update { it.copy(isFullscreen = !it.isFullscreen) }
    }

    fun setFullscreen(fullscreen: Boolean) {
        _playerState.update { it.copy(isFullscreen = fullscreen) }
    }

    fun playNextVideo(force: Boolean = false) = playbackController.next(force)

    fun playPreviousVideo() = playbackController.previous()

    fun skipCurrentSponsorSegment() =
        playbackController.skipCurrentSponsorSegment()

    fun unskipCurrentSponsorSegment() =
        playbackController.unskipCurrentSponsorSegment()

    // ------------------------------------------------------------------
    // Cài đặt phát
    // ------------------------------------------------------------------

    fun toggleBackgroundPlay() = playbackController.toggleBackgroundPlay()

    fun toggleAutoNext() = playbackController.toggleAutoNext()

    fun setAutoPlayMode(mode: AutoPlayMode) = playbackController.setAutoPlayMode(mode)

    fun toggleAutoPiP() = playbackController.toggleAutoPiP()

    fun toggleResumePlayback() = playbackController.toggleResumePlayback()

    /** Bật/tắt tự tua qua đoạn tài trợ (SponsorBlock). */
    fun toggleSponsorBlock() = playbackController.toggleSponsorBlock()

    /** Đổi cách xử lý một category rồi tải lại dữ liệu của video hiện tại. */
    fun setSponsorCategoryMode(category: SponsorCategory, mode: SponsorMode) =
        playbackController.setSponsorCategoryMode(category, mode)

    fun setSponsorNotify(enabled: Boolean) =
        playbackController.setSponsorNotify(enabled)

    fun setSponsorShowSkipButton(enabled: Boolean) =
        playbackController.setSponsorShowSkipButton(enabled)

    fun setSponsorGracefulRewind(enabled: Boolean) =
        playbackController.setSponsorGracefulRewind(enabled)

    fun setSponsorApiUrl(url: String) = playbackController.setSponsorApiUrl(url)

    /** Thêm/bỏ kênh của video hiện tại khỏi danh sách miễn trừ SponsorBlock. */
    fun toggleCurrentChannelSponsorWhitelist() =
        playbackController.toggleCurrentChannelSponsorWhitelist()

    /** Xoá toàn bộ kênh miễn trừ SponsorBlock. */
    fun clearSponsorWhitelist() = playbackController.clearSponsorWhitelist()

    open fun setHideComments(enabled: Boolean) =
        playbackController.setHideComments(enabled)

    fun setHideRelated(enabled: Boolean) = playbackController.setHideRelated(enabled)

    fun setHideDescription(enabled: Boolean) =
        playbackController.setHideDescription(enabled)

    /** Bật/tắt phụ đề và nhớ lựa chọn cho các video sau. */
    fun toggleCaptions() = playbackController.toggleCaptions()

    fun setCaptionsEnabled(enabled: Boolean) =
        playbackController.setCaptionsEnabled(enabled)

    /** Chọn ngôn ngữ phụ đề; chọn ngôn ngữ cũng tự bật CC như YouTube. */
    fun setCaptionLanguage(languageTag: String) =
        playbackController.setCaptionLanguage(languageTag)

    fun shouldAutoEnterPiP(): Boolean = settings.autoPiP

    /**
     * Gọi khi toàn bộ app vào background (không phải PiP): nếu người dùng TẮT
     * phát nền thì dừng phát; nếu BẬT thì để service tiếp tục.
     */
    fun onAppBackgrounded() = playbackController.onAppBackgrounded()

    fun setSleepTimer(minutes: Int) = playbackController.setSleepTimer(minutes)

    // ------------------------------------------------------------------
    // Hàng đợi
    // ------------------------------------------------------------------

    fun addToQueue(video: VideoItem) = playbackController.addToQueue(video)

    /** Chèn ngay sau video hiện tại (đầu hàng đợi), giống "Phát tiếp theo". */
    fun playNextInQueue(video: VideoItem) =
        playbackController.playNextInQueue(video)

    fun cycleRepeatMode() = playbackController.cycleRepeatMode()

    // ------------------------------------------------------------------
    // Thư viện
    // ------------------------------------------------------------------

    fun toggleFavorite(video: VideoItem) {
        viewModelScope.launch {
            val existing = videoDao.getVideoById(video.id)
            val base = existing ?: VideoEntity.fromVideoItem(video)
            val newState = !base.isFavorite
            val entity = base.copy(
                isFavorite = newState,
                favoriteAddedAt = if (newState) System.currentTimeMillis() else 0L
            )
            videoDao.insertOrUpdate(entity)
            if (_playerState.value.currentVideo?.id == video.id) {
                _playerState.update { it.copy(isFavorite = newState) }
                playbackController.refreshLibraryFlags(video.id)
            }
        }
    }

    fun toggleWatchLater(video: VideoItem) {
        viewModelScope.launch {
            val existing = videoDao.getVideoById(video.id)
            val base = existing ?: VideoEntity.fromVideoItem(video)
            val newState = !base.isWatchLater
            val entity = base.copy(
                isWatchLater = newState,
                watchLaterAddedAt = if (newState) System.currentTimeMillis() else 0L
            )
            videoDao.insertOrUpdate(entity)
            if (_playerState.value.currentVideo?.id == video.id) {
                _playerState.update { it.copy(isWatchLater = newState) }
                playbackController.refreshLibraryFlags(video.id)
            }
        }
    }

    fun removeFromFavorites(videoId: String) {
        viewModelScope.launch {
            val existing = videoDao.getVideoById(videoId) ?: return@launch
            videoDao.insertOrUpdate(existing.copy(isFavorite = false, favoriteAddedAt = 0L))
            if (_playerState.value.currentVideo?.id == videoId) {
                playbackController.refreshLibraryFlags(videoId)
            }
        }
    }

    fun removeFromWatchLater(videoId: String) {
        viewModelScope.launch {
            val existing = videoDao.getVideoById(videoId) ?: return@launch
            videoDao.insertOrUpdate(existing.copy(isWatchLater = false, watchLaterAddedAt = 0L))
            if (_playerState.value.currentVideo?.id == videoId) {
                playbackController.refreshLibraryFlags(videoId)
            }
        }
    }

    /**
     * Đăng ký/hủy đăng ký kênh của [video] ngay trên thiết bị.
     *
     * Sau khi đăng ký, feed của kênh được tải ở nền để tab Đăng ký có nội dung
     * ngay; lỗi mạng ở bước này không làm hỏng thao tác đăng ký (feed sẽ được
     * thử lại ở lần làm mới sau).
     */
    fun toggleSubscription(video: VideoItem) {
        val application = getApplication<Application>()
        viewModelScope.launch {
            // Nhiều nguồn video (bảng xếp hạng Âm nhạc, một số lockup) không
            // trả channelId. Trang xem lại có channelId trong metadata
            // player/next, nên hỏi lại trước khi báo "không có thông tin kênh".
            val subscribable = resolveChannelForSubscription(video)
            if (subscribable == null) {
                postMessage(application.getString(R.string.subscriptions_channel_unavailable))
                return@launch
            }

            val result = runCatching { subscriptionRepository.toggleSubscription(subscribable) }
            result.fold(
                onSuccess = { subscribed ->
                    postMessage(
                        application.getString(
                            if (subscribed) R.string.subscriptions_added
                            else R.string.subscriptions_removed
                        )
                    )
                    if (subscribed) {
                        runCatching {
                            subscriptionRepository.refreshChannel(subscribable.channelId)
                        }
                        // Nạp channelId vào metadata trang xem để nút Đăng ký
                        // đổi trạng thái ngay cả khi thẻ video ban đầu bị thiếu.
                        runCatching { playbackController.refreshDetails(video) }
                    }
                },
                onFailure = {
                    postMessage(application.getString(R.string.subscriptions_action_failed))
                }
            )
        }
    }

    /** Bổ sung channelId/tên kênh từ metadata trang xem khi [VideoItem] bị thiếu. */
    private suspend fun resolveChannelForSubscription(video: VideoItem): VideoItem? {
        if (video.channelId.isNotBlank()) return video

        val currentDetails = videoDetails.value
            ?.takeIf { _playerState.value.currentVideo?.id == video.id }
        currentDetails?.channelId?.takeIf { it.isNotBlank() }?.let { channelId ->
            return video.copy(
                channelId = channelId,
                channelTitle = realChannelTitle(video.channelTitle, currentDetails.channelTitle)
            )
        }

        val details = withContext(Dispatchers.IO) {
            runCatching { YouTubeDataRepository.getVideoDetails(video.id) }
                .getOrNull()
                ?.getOrNull()
        }
        val channelId = details?.channelId?.takeIf { it.isNotBlank() } ?: return null
        return video.copy(
            channelId = channelId,
            channelTitle = realChannelTitle(video.channelTitle, details.channelTitle)
        )
    }

    /** Bỏ tên mặc định "YouTube" để ưu tiên tên thật lấy từ metadata trang xem. */
    private fun realChannelTitle(fromVideo: String, fromDetails: String): String =
        fromVideo.takeIf { it.isNotBlank() && it != "YouTube" }
            ?: fromDetails.ifBlank { fromVideo }

    /**
     * Lưu/bỏ lưu playlist trong "Xem sau".
     *
     * Playlist được lưu vào bảng riêng thay vì bảng video: người dùng có thể
     * vừa lưu một video lẻ vừa lưu cả danh sách chứa video đó, và hai bộ sưu
     * tập cần được hiển thị tách biệt trong Thư viện.
     */
    fun toggleSavedPlaylist(playlist: PlaylistItem) {
        if (playlist.id.isBlank()) return
        viewModelScope.launch {
            if (savedPlaylistDao.getById(playlist.id) != null) {
                savedPlaylistDao.delete(playlist.id)
            } else {
                savedPlaylistDao.upsert(
                    SavedPlaylistEntity.fromPlaylistItem(
                        item = playlist,
                        savedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    /** Xoá một playlist khỏi Xem sau (giữ nguyên lịch sử phát nếu có). */
    fun removeSavedPlaylist(playlistId: String) {
        viewModelScope.launch { savedPlaylistDao.delete(playlistId) }
    }

    fun clearWatchHistory() {
        viewModelScope.launch {
            videoDao.clearHistory()
            // Lịch sử playlist nằm cùng tab Lịch sử nên xoá luôn để không còn
            // dòng "mồ côi" trỏ tới video vừa bị xoá khỏi lịch sử.
            playlistHistoryDao.clearHistory()
        }
    }

    /** Xoá một playlist khỏi lịch sử phát. */
    fun removePlaylistFromHistory(playlistId: String) {
        viewModelScope.launch {
            playlistHistoryDao.delete(playlistId)
        }
    }

    /** Xoá một video khỏi lịch sử xem (giữ nguyên trạng thái Yêu thích / Xem sau). */
    fun removeFromHistory(videoId: String) {
        viewModelScope.launch {
            videoDao.clearProgress(videoId)
        }
    }

    /** Xoá lịch sử tìm kiếm thật đang lưu trên thiết bị. */
    open fun clearSearchHistory() {
        searchHistoryStore.clear()
    }

    /** Xoá một từ khoá khỏi lịch sử tìm kiếm. */
    open fun removeSearchHistory(query: String) {
        searchHistoryStore.remove(query)
    }
}

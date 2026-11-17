@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.luxysiv.puretube.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.luxysiv.puretube.R
import com.luxysiv.puretube.data.database.AppDatabase
import com.luxysiv.puretube.data.database.VideoDao
import com.luxysiv.puretube.data.local.AppSettings
import com.luxysiv.puretube.data.local.NowPlayingState
import com.luxysiv.puretube.data.local.QueueStore
import com.luxysiv.puretube.data.local.SettingsStore
import com.luxysiv.puretube.data.model.PlaybackPlan
import com.luxysiv.puretube.data.model.PlaylistItem
import com.luxysiv.puretube.data.model.SponsorCategory
import com.luxysiv.puretube.data.model.SponsorMode
import com.luxysiv.puretube.data.model.VideoDetails
import com.luxysiv.puretube.data.model.VideoItem
import com.luxysiv.puretube.data.model.sponsorChannelKey
import com.luxysiv.puretube.data.repository.SponsorBlockDataSource
import com.luxysiv.puretube.data.repository.YouTubeDataRepository
import com.luxysiv.puretube.error.AppError
import com.luxysiv.puretube.util.Formatters
import com.luxysiv.puretube.util.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Bộ điều phối phát cấp process.
 *
 * Đây là nơi duy nhất quyết định "phát gì tiếp theo": nó sở hữu [PlaybackQueue]
 * (hàng đợi logic), [PlaybackQueueManager] (cửa sổ playlist ExoPlayer), listener
 * duy nhất của player, SponsorBlock, lịch sử, retry và sleep timer. Vì scope
 * không gắn với ViewModel, mọi logic vẫn chạy khi UI đã bị huỷ.
 */
class PlaybackCoordinator(
    context: Context,
    private val engine: PlaybackEngine,
    private val settingsStore: SettingsStore,
    private val queueStore: QueueStore,
    private val videoDao: VideoDao
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val resolver = PlaybackResolver()
    private val queueManager = PlaybackQueueManager(engine, resolver)
    private val networkMonitor = PlaybackNetworkMonitor(appContext)

    private val queue = PlaybackQueue(
        initialQueue = queueStore.load(),
        mixLoader = { videoId ->
            YouTubeDataRepository.getMixPlaylist(videoId).getOrNull()
        },
        moreMixLoader = { mixId ->
            YouTubeDataRepository.getMoreMix(mixId).getOrNull()
        },
        playlistMoreLoader = { playlistId, token ->
            YouTubeDataRepository.getPlaylistPage(playlistId, token).getOrNull()
        },
        persistQueue = { items -> queueStore.save(items) }
    )

    private val history = PlaybackHistoryRepository(
        videoDao = videoDao,
        playlistDao = AppDatabase.getDatabase(appContext).playlistHistoryDao(),
        resumeEnabled = { settings.value.resumePlayback }
    )

    private val sponsor = SponsorBlockController(
        context = appContext,
        engine = engine,
        scope = scope,
        settingsProvider = { settings.value },
        onPositionSeek = { positionMs ->
            lastKnownPositionMs = positionMs
            _position.update { it.copy(currentSeconds = positionMs / 1000f) }
        },
        onMessage = { message -> _messages.tryEmit(message) }
    )

    private val _playback = MutableStateFlow(PlaybackUiState())
    val playback: StateFlow<PlaybackUiState> = _playback.asStateFlow()

    private val _position = MutableStateFlow(PlaybackPosition())
    val position: StateFlow<PlaybackPosition> = _position.asStateFlow()

    private val _plan = MutableStateFlow<com.luxysiv.puretube.data.model.PlaybackPlan?>(null)
    val plan: StateFlow<com.luxysiv.puretube.data.model.PlaybackPlan?> = _plan.asStateFlow()

    private val _details = MutableStateFlow<VideoDetails?>(null)
    val details: StateFlow<VideoDetails?> = _details.asStateFlow()

    private val _libraryFlags = MutableStateFlow(LibraryFlags())
    val libraryFlags: StateFlow<LibraryFlags> = _libraryFlags.asStateFlow()

    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _isMeteredNetwork = MutableStateFlow(false)
    val isMeteredNetwork: StateFlow<Boolean> = _isMeteredNetwork.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val queueState: StateFlow<QueueUiState> = queue.state
    val sponsorState: StateFlow<SponsorUiState> = sponsor.state

    val player: Player get() = engine.player

    /**
     * Pha phiên phát cấp cao. Mọi lần đổi pha đều đi qua [PlaybackSessionMachine]
     * để chỉ những chuyển đổi hợp lệ mới được áp dụng.
     */
    private val session = PlaybackSessionMachine()
    val sessionPhase: StateFlow<SessionPhase> = session.phase

    private var currentVideo: VideoItem? = null
    private var currentResolved: ResolvedMedia? = null

    private var generation = 0
    private var resolveJob: Job? = null
    private var isResolving = false
    /**
     * Ý định phát/dừng của người dùng trong lúc đang resolve video mới.
     *
     * `null` = chưa bấm gì; `true` = muốn phát; `false` = muốn dừng. Được áp
     * dụng ngay sau khi `queueManager` nạp xong media, tránh việc cú bấm bị
     * nuốt mất và trạng thái UI nhảy về "đang phát".
     */
    private var pendingPlayAfterResolve: Boolean? = null
    private var prefetchJob: Job? = null
    private var mixJob: Job? = null
    private var mixMoreJob: Job? = null
    private var playlistMoreJob: Job? = null
    private var autoRetryJob: Job? = null
    private var autoRetryAttempt = 0
    private var sleepTimerJob: Job? = null
    private var tickerJob: Job? = null
    private var qualityApplyJob: Job? = null

    private var stoppedBySleepTimer = false
    private var suppressAutoAdvance = false
    private var lastKnownPositionMs = 0L
    private var lastHistorySaveAt = 0L
    private var lastEndedMediaId: String? = null
    private var isClosed = false

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _playback.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) {
                // Một lượt phát mới (kể cả lặp ONE) được phép xử lý kết thúc lại.
                lastEndedMediaId = null
                PlaybackService.start(appContext)
                sponsor.onIsPlayingChanged(true)
            } else {
                flushHistory()
                saveNowPlaying()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> _playback.update { it.copy(isBuffering = true) }
                Player.STATE_READY -> _playback.update {
                    it.copy(isBuffering = false, durationMs = engine.durationMs())
                }

                Player.STATE_ENDED -> {
                    _playback.update { it.copy(isBuffering = false) }
                    onPlaybackEnded()
                }

                Player.STATE_IDLE -> _playback.update { it.copy(isBuffering = false) }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady &&
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
            ) {
                // Cùng một media item có thể phát cả STATE_ENDED lẫn sự kiện này;
                // onPlaybackEnded() tự chống xử lý trùng bằng media id.
                onPlaybackEnded()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            lastEndedMediaId = null
            val video = queueManager.videoFor(mediaItem?.mediaId) ?: return
            val auto = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO

            if (video.id == currentVideo?.id) {
                // Cùng một bài quay lại (ví dụ playlist native đổi do prefetch):
                // không trim cửa sổ, nếu không sẽ xoá mất bài kế đã prefetch.
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                    queueManager.updateWindow()
                }
                return
            }

            if (auto && (!settings.value.autoNext || suppressAutoAdvance)) {
                engine.pause()
                return
            }

            adoptVideo(video)
        }

        override fun onPlayerError(error: PlaybackException) {
            val positionMs = maxOf(lastKnownPositionMs, engine.currentPositionMs())
            val mediaId = engine.player.currentMediaItem?.mediaId
            if (queueManager.replaceCandidate(mediaId, positionMs)) {
                currentResolved = queueManager.resolvedFor(mediaId)
                _plan.value = currentResolved?.plan
                // Candidate dự phòng có thể là progressive MP4 dù kế hoạch có
                // DASH/HLS, nên không được giữ giới hạn track của manifest.
                applyEngineQuality(
                    currentResolved?.plan,
                    currentResolved?.candidateIndex ?: 0
                )
                _playback.update { it.copy(failure = null, isBuffering = true) }
                return
            }
            engine.pause()
            val waitingForNetwork = !networkMonitor.isNetworkAvailable()
            onFailure(
                error = if (waitingForNetwork) {
                    AppError.Offline(error)
                } else {
                    AppError.Playback(error)
                },
                detail = describe(error),
                waitingForNetwork = waitingForNetwork
            )
        }
    }

    init {
        val loaded = settings.value
        _playback.value = PlaybackUiState(
            speed = loaded.playbackSpeed,
            pitch = loaded.playbackPitch,
            skipSilence = loaded.skipSilence,
            captionsEnabled = loaded.captionsEnabled,
            captionLanguageTag = loaded.captionLanguageTag,
            isAutoNextEnabled = loaded.autoNext,
            isResumeEnabled = loaded.resumePlayback
        )

        engine.player.repeatMode = Player.REPEAT_MODE_OFF
        engine.player.pauseAtEndOfMediaItems = !loaded.autoNext
        engine.setSpeed(loaded.playbackSpeed)
        engine.setPitch(loaded.playbackPitch)
        engine.setSkipSilenceEnabled(loaded.skipSilence)
        engine.setCaptions(loaded.captionsEnabled, loaded.captionLanguageTag)
        _isMeteredNetwork.value = networkMonitor.isMeteredNetwork()
        applyNetworkQuality()
        engine.player.addListener(listener)

        networkMonitor.start(
            onAvailable = {
                scope.launch {
                    _playback.update { it.copy(isWaitingForNetwork = false) }
                    if (_playback.value.failure != null) {
                        autoRetryJob?.cancel()
                        autoRetryJob = null
                        autoRetryAttempt = 0
                        retryInternal()
                    }
                }
            },
            onLost = {
                scope.launch {
                    applyNetworkQuality()
                    if (_playback.value.failure != null &&
                        !networkMonitor.isNetworkAvailable()
                    ) {
                        autoRetryJob?.cancel()
                        autoRetryJob = null
                        _playback.update { it.copy(isWaitingForNetwork = true) }
                    }
                }
            },
            onCapabilitiesChanged = {
                scope.launch { applyNetworkQuality() }
            }
        )

        restoreSession()
    }

    // ------------------------------------------------------------------
    // Lệnh phát
    // ------------------------------------------------------------------

    fun play(
        video: VideoItem,
        origin: PlaybackOrigin = PlaybackOrigin.USER,
        addToBackStack: Boolean = true,
        startPositionMs: Long? = null,
        clearFailure: Boolean = true,
        isAutoRetry: Boolean = false
    ) {
        // Mở lại trình phát sau khi close() (thoát app rồi quay lại).
        isClosed = false

        if (!isAutoRetry) {
            autoRetryJob?.cancel()
            autoRetryJob = null
            autoRetryAttempt = 0
        }

        resolveJob?.cancel()
        prefetchJob?.cancel()
        mixMoreJob?.cancel()
        playlistMoreJob?.cancel()
        tickerJob?.cancel()

        stoppedBySleepTimer = false
        suppressAutoAdvance = false
        lastEndedMediaId = null
        generation += 1
        val requestGeneration = generation
        isResolving = true
        session.transitionTo(SessionPhase.RESOLVING)
        pendingPlayAfterResolve = null

        if (clearFailure) {
            _playback.update {
                it.copy(failure = null, isWaitingForNetwork = false)
            }
        }
        _plan.value = null
        _details.value = null
        _libraryFlags.value = LibraryFlags()
        _playback.update {
            it.copy(
                video = video,
                isPlaying = true,
                isBuffering = true,
                availableQualities = emptyList(),
                isLive = false
            )
        }
        _position.value = PlaybackPosition()

        // `close()` đưa repeatMode về OFF; đồng bộ lại cờ dừng-cuối-bài theo
        // cài đặt hiện tại và chế độ lặp của hàng đợi trước khi nạp bài mới.
        engine.player.repeatMode = Player.REPEAT_MODE_OFF
        engine.player.pauseAtEndOfMediaItems = !settings.value.autoNext ||
            queue.state.value.repeatMode == RepeatMode.ONE

        engine.pause()
        PlaybackService.start(appContext)

        val previousVideo = currentVideo
        val mixNeedsLoad = queue.onPlayStarted(
            video = video,
            origin = origin,
            previous = previousVideo,
            addToBackStack = addToBackStack
        )
        currentVideo = video
        currentResolved = null

        resolveJob = scope.launch {
            try {
                val start = recordPlaybackStarted(video)
                if (generation != requestGeneration) return@launch

                _libraryFlags.value = LibraryFlags(
                    isFavorite = start.isFavorite,
                    isWatchLater = start.isWatchLater,
                    watchProgressSeconds = start.resumeSeconds
                )
                _position.value = PlaybackPosition(
                    currentSeconds = start.resumeSeconds.toFloat()
                )

                val resumeMs = startPositionMs ?: (start.resumeSeconds * 1000L)
                sponsor.reset(video)
                sponsor.load(video)
                if (mixNeedsLoad) startMixLoad(video.id, requestGeneration)

                if (!networkMonitor.isNetworkAvailable()) {
                    engine.stop()
                    onFailure(
                        error = AppError.Offline(),
                        detail = null,
                        waitingForNetwork = true
                    )
                    return@launch
                }

                val resolution = resolver.resolve(video)
                if (generation != requestGeneration) return@launch

                val plan = resolution.plan
                if (plan == null) {
                    val noNetwork = !networkMonitor.isNetworkAvailable()
                    engine.stop()
                    _plan.value = null
                    _details.value = resolution.details
                    queue.setRelated(resolution.related)
                    onFailure(
                        error = if (noNetwork) {
                            AppError.Offline()
                        } else {
                            resolution.error ?: AppError.Unavailable()
                        },
                        detail = null,
                        waitingForNetwork = noNetwork
                    )
                    return@launch
                }

                _plan.value = plan
                _details.value = resolution.details
                queue.setRelated(resolution.related)
                _playback.update {
                    it.copy(
                        isLive = plan.isLive,
                        availableQualities = plan.qualities
                            .filter { height -> height > 0 }
                            .distinct()
                            .sortedDescending(),
                        selectedQuality = selectedQualityLabel(plan)
                    )
                }

                engine.setSpeed(settings.value.playbackSpeed)
                engine.setPitch(settings.value.playbackPitch)
                engine.setSkipSilenceEnabled(settings.value.skipSilence)
                val candidateIndex = plan.candidateIndexFor(effectiveQualityHeight())
                applyEngineQuality(plan, candidateIndex)

                val resolved = ResolvedMedia(
                    video = video,
                    plan = plan,
                    candidateIndex = candidateIndex,
                    startPositionMs = resumeMs,
                    related = resolution.related,
                    details = resolution.details
                )
                currentResolved = resolved
                val previousResolved = previousVideo?.let { queueManager.resolvedFor(it.id) }
                queueManager.loadCurrent(
                    resolved = resolved,
                    startPositionMs = resumeMs,
                    previous = previousResolved,
                    includePrevious = previousResolved != null,
                    autoPlay = true
                )

                autoRetryJob?.cancel()
                autoRetryJob = null
                autoRetryAttempt = 0
                session.transitionTo(SessionPhase.READY)
                _playback.update {
                    it.copy(failure = null, isWaitingForNetwork = false)
                }
                lastKnownPositionMs = resumeMs
                saveNowPlaying()
                startTicker()
                prefetchNext(requestGeneration)
            } finally {
                if (generation == requestGeneration) {
                    isResolving = false
                    if (session.current == SessionPhase.RESOLVING) {
                        // Resolve bị huỷ/thoát sớm mà chưa vào READY/FAILED: giữ pha trung tính.
                        session.transitionTo(SessionPhase.IDLE)
                    }
                    pendingPlayAfterResolve?.let { desired ->
                        pendingPlayAfterResolve = null
                        if (!desired) {
                            engine.pause()
                            _playback.update { it.copy(isPlaying = false) }
                        }
                    }
                }
            }
        }
    }

    /**
     * Phát một video trong playlist và giữ cả playlist làm nguồn phát kế tiếp.
     *
     * Danh sách đang hiển thị được chuyển nguyên trạng sang hàng đợi; khi chạm
     * cuối trang, [PlaybackQueue] tự tải thêm bằng [nextToken] (continuation).
     * Nhờ vậy Next/Previous và auto-next đi đúng thứ tự playlist thay vì rơi
     * xuống mix/gợi ý ngay sau video đầu.
     */
    fun playPlaylist(
        video: VideoItem,
        playlist: PlaylistItem,
        videos: List<VideoItem>,
        nextToken: String = ""
    ) {
        val playlistVideos = if (videos.any { it.id == video.id }) {
            videos
        } else {
            // Phòng khi danh sách UI chưa kịp chứa video vừa bấm.
            listOf(video) + videos
        }
        queue.setPlaylist(
            PlaybackPlaylistContext(
                id = playlist.id,
                title = playlist.title,
                channelTitle = playlist.channelTitle,
                channelId = playlist.channelId,
                thumbnailUrl = playlist.thumbnailUrl,
                videoCount = playlist.videoCount.takeIf { it > 0 } ?: playlistVideos.size,
                videos = playlistVideos.distinctBy { it.id },
                nextToken = nextToken
            )
        )
        play(video = video, origin = PlaybackOrigin.USER)
    }

    fun retry() {
        val video = currentVideo ?: return
        autoRetryJob?.cancel()
        autoRetryJob = null
        autoRetryAttempt = 0
        _playback.update { it.copy(failure = null, isWaitingForNetwork = false) }
        play(
            video = video,
            origin = PlaybackOrigin.RESTORE,
            addToBackStack = false,
            clearFailure = true
        )
    }

    private fun retryInternal() {
        val video = currentVideo ?: return
        play(
            video = video,
            origin = PlaybackOrigin.RESTORE,
            addToBackStack = false,
            clearFailure = false,
            isAutoRetry = true
        )
    }

    fun next(force: Boolean = false) {
        if (!force && !settings.value.autoNext) return
        if (stoppedBySleepTimer && !force) return

        val requestGeneration = generation
        // Hàng đợi tự quyết định đích đến; coordinator chỉ thi hành. Chỉ coi là
        // "native next" khi đích thực sự đã nằm trong cửa sổ playlist ExoPlayer.
        val nativeNext = queue.nextTarget()?.let { queueManager.hasNext(it.id) } == true
        when (val navigation = queue.nextNavigation(hasNativeNext = nativeNext)) {
            PlaybackNavigation.NativeNext -> {
                engine.player.seekToNext()
                if (!engine.player.isPlaying) engine.play()
            }

            is PlaybackNavigation.Play -> play(
                video = navigation.video,
                origin = navigation.origin,
                addToBackStack = false
            )

            PlaybackNavigation.LoadMoreMix -> {
                if (mixMoreJob?.isActive == true) return
                mixMoreJob = scope.launch {
                    val loaded = queue.loadMoreMix()
                    if (!loaded) queue.markMixExhausted()
                    if (generation != requestGeneration) return@launch
                    when (val retry = queue.nextNavigation(hasNativeNext = false)) {
                        is PlaybackNavigation.Play -> play(
                            video = retry.video,
                            origin = retry.origin,
                            addToBackStack = false
                        )
                        // Trang mới không có bài nào dùng được: đánh dấu hết để
                        // không gọi mạng lại mỗi lần bấm Next.
                        else -> queue.markMixExhausted()
                    }
                }
            }

            PlaybackNavigation.LoadMorePlaylist -> {
                if (playlistMoreJob?.isActive == true) return
                playlistMoreJob = scope.launch {
                    val loaded = queue.loadMorePlaylist()
                    if (!loaded) queue.markPlaylistExhausted()
                    if (generation != requestGeneration) return@launch
                    when (val retry = queue.nextNavigation(hasNativeNext = false)) {
                        is PlaybackNavigation.Play -> play(
                            video = retry.video,
                            origin = retry.origin,
                            addToBackStack = false
                        )
                        else -> queue.markPlaylistExhausted()
                    }
                }
            }

            PlaybackNavigation.NativePrevious,
            PlaybackNavigation.SeekToStart,
            PlaybackNavigation.None -> Unit
        }
    }

    fun previous() {
        val player = engine.player
        val navigation = queue.previousNavigation(
            hasNativePrevious = player.hasPreviousMediaItem(),
            positionMs = player.currentPosition,
            seekThresholdMs = MAX_SEEK_TO_PREVIOUS_POSITION_MS
        )
        when (navigation) {
            PlaybackNavigation.NativePrevious -> {
                val previousIndex = player.currentMediaItemIndex
                player.seekToPrevious()
                // Media3 tự tua về đầu bài hiện tại nếu đã xem quá 3 giây; chỉ
                // phát tiếp khi thực sự chuyển sang bài trước (giống hành vi cũ).
                if (player.currentMediaItemIndex != previousIndex && !player.isPlaying) {
                    engine.play()
                }
            }

            PlaybackNavigation.SeekToStart -> engine.seekTo(0L)

            is PlaybackNavigation.Play -> play(
                video = navigation.video,
                origin = navigation.origin,
                addToBackStack = false
            )

            PlaybackNavigation.NativeNext,
            PlaybackNavigation.LoadMoreMix,
            PlaybackNavigation.LoadMorePlaylist,
            PlaybackNavigation.None -> Unit
        }
    }

    fun togglePlayPause() {
        stoppedBySleepTimer = false
        if (isResolving) {
            // Video mới chưa nạp xong: ghi nhận ý định, áp dụng sau khi resolve.
            val desired = !_playback.value.isPlaying
            pendingPlayAfterResolve = desired
            _playback.update { it.copy(isPlaying = desired) }
            return
        }
        if (engine.player.mediaItemCount == 0) {
            val video = currentVideo ?: return
            play(
                video = video,
                origin = PlaybackOrigin.RESTORE,
                addToBackStack = false
            )
            return
        }
        if (engine.player.isPlaying) {
            engine.pause()
        } else {
            // Người dùng chủ động phát lại sau sleep timer: bỏ cờ chặn auto-next.
            suppressAutoAdvance = false
            engine.play()
            PlaybackService.start(appContext)
        }
    }

    fun close() {
        // Idempotent: HomeActivity/Application/Service có thể cùng báo thoát.
        if (isClosed) return
        isClosed = true
        session.transitionTo(SessionPhase.CLOSED)
        generation += 1
        resolveJob?.cancel()
        resolveJob = null
        isResolving = false
        pendingPlayAfterResolve = null
        prefetchJob?.cancel()
        prefetchJob = null
        mixJob?.cancel()
        mixJob = null
        mixMoreJob?.cancel()
        mixMoreJob = null
        playlistMoreJob?.cancel()
        playlistMoreJob = null
        autoRetryJob?.cancel()
        autoRetryJob = null
        autoRetryAttempt = 0
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        tickerJob?.cancel()
        tickerJob = null
        qualityApplyJob?.cancel()
        qualityApplyJob = null

        flushHistory()
        engine.stop()
        PlaybackService.stop(appContext)
        queueManager.clear()
        queue.onClosed()
        sponsor.reset(null)
        queueStore.clearNowPlaying()

        currentVideo = null
        currentResolved = null
        lastKnownPositionMs = 0L
        lastEndedMediaId = null
        stoppedBySleepTimer = false
        suppressAutoAdvance = false
        _plan.value = null
        _details.value = null
        _libraryFlags.value = LibraryFlags()
        _position.value = PlaybackPosition()
        _playback.update {
            it.copy(
                video = null,
                isPlaying = false,
                isBuffering = false,
                isLive = false,
                durationMs = 0L,
                availableQualities = emptyList(),
                isWaitingForNetwork = false,
                sleepTimerMinutes = 0,
                sleepTimerRemainingSeconds = 0,
                failure = null
            )
        }
    }

    fun onAppBackgrounded() {
        if (!settings.value.backgroundPlay && engine.player.isPlaying) {
            engine.pause()
        }
    }

    fun seekTo(seconds: Float) {
        val duration = _position.value.durationSeconds
        val target = if (duration > 0f) {
            seconds.coerceIn(0f, duration)
        } else {
            seconds.coerceAtLeast(0f)
        }
        val targetMs = (target * 1000f).toLong()
        sponsor.onUserSeek(targetMs)
        lastEndedMediaId = null
        engine.seekTo(targetMs)
        lastKnownPositionMs = targetMs
        _position.value = _position.value.copy(currentSeconds = target)
    }

    fun forward10Seconds() = seekTo(_position.value.currentSeconds + 10f)

    fun rewind10Seconds() = seekTo(_position.value.currentSeconds - 10f)

    // ------------------------------------------------------------------
    // Cài đặt phát
    // ------------------------------------------------------------------

    fun setPlaybackSpeed(speed: Float) {
        updateSettings { it.copy(playbackSpeed = speed) }
        engine.setSpeed(speed)
    }

    fun setPlaybackPitch(pitch: Float) {
        updateSettings { it.copy(playbackPitch = pitch) }
        engine.setPitch(pitch)
    }

    fun toggleSkipSilence() {
        val updated = updateSettings { it.copy(skipSilence = !it.skipSilence) }
        engine.setSkipSilenceEnabled(updated.skipSilence)
    }

    fun resetPlaybackParameters() {
        updateSettings { it.copy(playbackSpeed = 1.0f, playbackPitch = 1.0f) }
        engine.setSpeed(1.0f)
        engine.setPitch(1.0f)
    }

    fun setQuality(height: Int?) {
        val normalized = height?.takeIf { it > 0 }
        updateSettings {
            if (networkMonitor.isMeteredNetwork()) {
                it.copy(qualityMobileHeight = normalized ?: 0)
            } else {
                it.copy(qualityHeight = normalized ?: 0)
            }
        }
    }

    fun setWifiQuality(height: Int?) {
        updateSettings { it.copy(qualityHeight = height?.takeIf { it > 0 } ?: 0) }
    }

    fun setMobileQuality(height: Int?) {
        updateSettings { it.copy(qualityMobileHeight = height?.takeIf { it > 0 } ?: 0) }
    }

    fun toggleDataSaver() {
        updateSettings { it.copy(dataSaver = !it.dataSaver) }
    }

    fun toggleBackgroundPlay() {
        updateSettings { it.copy(backgroundPlay = !it.backgroundPlay) }
    }

    fun toggleAutoNext() {
        val enabled = !settings.value.autoNext
        // Tắt tự động phát cũng phải thoát chế độ "Chỉ 1 bài", nếu không video
        // vẫn tiếp tục lặp dù công tắc đã tắt.
        if (!enabled) queue.setRepeatMode(RepeatMode.OFF)
        val updated = updateSettings { it.copy(autoNext = enabled) }
        engine.player.pauseAtEndOfMediaItems = !updated.autoNext ||
            queue.state.value.repeatMode == RepeatMode.ONE
        refreshPrefetch()
    }

    /**
     * Đặt chế độ tự động phát từ nút trong HUD: tắt hẳn, phát video kế tiếp,
     * lặp toàn bộ danh sách, hoặc chỉ lặp lại video hiện tại.
     */
    fun setAutoPlayMode(mode: AutoPlayMode) {
        // Đổi chế độ lặp trước để cờ dừng-cuối-bài bên dưới tính đúng.
        when (mode) {
            AutoPlayMode.OFF, AutoPlayMode.NEXT ->
                queue.setRepeatMode(RepeatMode.OFF)
            AutoPlayMode.ALL ->
                queue.setRepeatMode(RepeatMode.ALL)
            AutoPlayMode.ONE ->
                queue.setRepeatMode(RepeatMode.ONE)
        }
        val updated = updateSettings {
            it.copy(autoNext = mode != AutoPlayMode.OFF)
        }
        engine.player.pauseAtEndOfMediaItems = !updated.autoNext ||
            queue.state.value.repeatMode == RepeatMode.ONE
        refreshPrefetch()
    }

    fun toggleAutoPiP() {
        updateSettings { it.copy(autoPiP = !it.autoPiP) }
    }

    fun toggleResumePlayback() {
        updateSettings { it.copy(resumePlayback = !it.resumePlayback) }
    }

    fun toggleCaptions() {
        setCaptionsEnabled(!settings.value.captionsEnabled)
    }

    fun setCaptionsEnabled(enabled: Boolean) {
        val updated = updateSettings { it.copy(captionsEnabled = enabled) }
        engine.setCaptions(enabled, updated.captionLanguageTag)
    }

    fun setCaptionLanguage(languageTag: String) {
        val updated = updateSettings {
            it.copy(captionsEnabled = true, captionLanguageTag = languageTag)
        }
        engine.setCaptions(true, updated.captionLanguageTag)
    }

    fun toggleSponsorBlock() {
        updateSettings { it.copy(skipSponsors = !it.skipSponsors) }
        sponsor.onSettingsChanged()
    }

    fun setSponsorCategoryMode(category: SponsorCategory, mode: SponsorMode) {
        updateSettings {
            it.copy(sponsorCategoryModes = it.sponsorCategoryModes + (category.apiName to mode))
        }
        SponsorBlockDataSource.clearCache()
        sponsor.onSettingsChanged()
    }

    fun setSponsorNotify(enabled: Boolean) {
        updateSettings { it.copy(sponsorNotify = enabled) }
    }

    fun setSponsorShowSkipButton(enabled: Boolean) {
        updateSettings { it.copy(sponsorShowSkipButton = enabled) }
        sponsor.onSettingsChanged()
    }

    fun setSponsorGracefulRewind(enabled: Boolean) {
        updateSettings { it.copy(sponsorGracefulRewind = enabled) }
        sponsor.onSettingsChanged()
    }

    fun setSponsorApiUrl(url: String) {
        val normalized = url.trim().ifBlank { SponsorBlockDataSource.DEFAULT_API_URL }
        updateSettings { it.copy(sponsorApiUrl = normalized) }
        SponsorBlockDataSource.clearCache()
        sponsor.onSettingsChanged()
    }

    fun toggleCurrentChannelSponsorWhitelist() {
        val video = currentVideo ?: return
        val key = sponsorChannelKey(video)
        if (key.isBlank()) return
        val whitelist = if (key in settings.value.sponsorWhitelist) {
            settings.value.sponsorWhitelist - key
        } else {
            settings.value.sponsorWhitelist + key
        }
        updateSettings { it.copy(sponsorWhitelist = whitelist) }
        sponsor.onSettingsChanged()
        _messages.tryEmit(
            appContext.getString(
                if (key in whitelist) {
                    R.string.player_sponsor_whitelisted
                } else {
                    R.string.player_sponsor_applied
                }
            )
        )
    }

    fun clearSponsorWhitelist() {
        updateSettings { it.copy(sponsorWhitelist = emptySet()) }
        sponsor.onSettingsChanged()
    }

    /** Bỏ qua đoạn Thủ công đang đi qua (nút "Bỏ qua" trên trình phát). */
    fun skipCurrentSponsorSegment() = sponsor.skipCurrent()

    /** Hoàn tác lần bỏ qua gần nhất (nút "Hoàn tác"). */
    fun unskipCurrentSponsorSegment() = sponsor.unskip()

    fun setHideComments(enabled: Boolean) {
        updateSettings { it.copy(hideComments = enabled) }
    }

    fun setHideRelated(enabled: Boolean) {
        updateSettings { it.copy(hideRelated = enabled) }
    }

    fun setHideDescription(enabled: Boolean) {
        updateSettings { it.copy(hideDescription = enabled) }
    }

    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        stoppedBySleepTimer = false
        _playback.update {
            it.copy(
                sleepTimerMinutes = minutes,
                sleepTimerRemainingSeconds = if (minutes > 0) minutes * 60 else 0
            )
        }

        if (minutes > 0) {
            sleepTimerJob = scope.launch {
                var remainingMs = minutes * 60 * 1000L
                while (remainingMs > 0L) {
                    _playback.update {
                        it.copy(
                            sleepTimerRemainingSeconds =
                                ((remainingMs + 999L) / 1000L).toInt()
                        )
                    }
                    val step = minOf(1000L, remainingMs)
                    delay(step)
                    remainingMs -= step
                }
                stoppedBySleepTimer = true
                suppressAutoAdvance = true
                engine.pause()
                _playback.update {
                    it.copy(
                        isPlaying = false,
                        sleepTimerMinutes = 0,
                        sleepTimerRemainingSeconds = 0
                    )
                }
                _messages.tryEmit(appContext.getString(R.string.player_sleep_finished))
            }
        }
    }

    fun replaceSettings(newSettings: AppSettings) {
        _settings.value = newSettings
        settingsStore.save(newSettings)
        publishSettings(newSettings)
        engine.setSpeed(newSettings.playbackSpeed)
        engine.setPitch(newSettings.playbackPitch)
        engine.setSkipSilenceEnabled(newSettings.skipSilence)
        engine.setCaptions(newSettings.captionsEnabled, newSettings.captionLanguageTag)
        engine.player.pauseAtEndOfMediaItems = !newSettings.autoNext ||
            queue.state.value.repeatMode == RepeatMode.ONE
        SponsorBlockDataSource.clearCache()
        sponsor.onSettingsChanged()
        applyNetworkQuality()
    }

    // ------------------------------------------------------------------
    // Hàng đợi
    // ------------------------------------------------------------------

    fun addToQueue(video: VideoItem) {
        when (queue.addToQueue(video)) {
            QueueAddResult.ADDED -> _messages.tryEmit(
                appContext.getString(R.string.player_queue_added)
            )

            QueueAddResult.ALREADY_PLAYING -> _messages.tryEmit(
                appContext.getString(R.string.player_queue_already_playing)
            )

            QueueAddResult.ALREADY_QUEUED -> _messages.tryEmit(
                appContext.getString(R.string.player_queue_already_queued)
            )
        }
        refreshPrefetch()
    }

    fun playNextInQueue(video: VideoItem) {
        when (queue.playNextInQueue(video)) {
            QueueAddResult.ADDED -> _messages.tryEmit(
                appContext.getString(R.string.player_queue_play_next)
            )

            QueueAddResult.ALREADY_PLAYING -> _messages.tryEmit(
                appContext.getString(R.string.player_queue_already_playing)
            )

            QueueAddResult.ALREADY_QUEUED -> _messages.tryEmit(
                appContext.getString(R.string.player_queue_already_queued)
            )
        }
        refreshPrefetch()
    }

    fun cycleRepeatMode() {
        val currentMode = currentAutoPlayMode()
        setAutoPlayMode(currentMode.next())
    }

    private fun currentAutoPlayMode(): AutoPlayMode {
        val autoNext = settings.value.autoNext
        return when (queue.state.value.repeatMode) {
            RepeatMode.ONE -> AutoPlayMode.ONE
            RepeatMode.ALL -> AutoPlayMode.ALL
            RepeatMode.OFF -> if (autoNext) AutoPlayMode.NEXT else AutoPlayMode.OFF
        }
    }

    fun setTrending(videos: List<VideoItem>) {
        queue.setTrending(videos)
        refreshPrefetch()
    }

    fun refreshLibraryFlags(videoId: String) {
        scope.launch {
            val entity = videoDao.getVideoById(videoId) ?: return@launch
            if (currentVideo?.id == videoId) {
                _libraryFlags.value = LibraryFlags(
                    isFavorite = entity.isFavorite,
                    isWatchLater = entity.isWatchLater,
                    watchProgressSeconds = entity.watchProgressSeconds
                )
            }
        }
    }

    fun refreshDetails(video: VideoItem) {
        scope.launch {
            val details = YouTubeDataRepository.getVideoDetails(video.id)
                .getOrNull() ?: return@launch
            if (currentVideo?.id == video.id) _details.value = details
        }
    }

    // ------------------------------------------------------------------
    // Nội bộ
    // ------------------------------------------------------------------

    private fun adoptVideo(video: VideoItem) {
        val previous = currentVideo
        flushHistory(previous)
        val mixNeedsLoad = queue.onAdvancedTo(video)
        currentVideo = video
        lastKnownPositionMs = 0L

        val resolved = queueManager.resolvedFor(video.id)
        currentResolved = resolved
        _plan.value = resolved?.plan
        // Bài kế có thể dùng loại nguồn khác bài trước (progressive ↔ DASH/HLS),
        // nên phải áp lại giới hạn chất lượng cho track selector.
        applyEngineQuality(resolved?.plan, resolved?.candidateIndex ?: 0)
        if (resolved != null) {
            _details.value = resolved.details
            queue.setRelated(resolved.related)
        }
        _playback.update {
            it.copy(
                video = video,
                isLive = resolved?.plan?.isLive == true,
                availableQualities = resolved?.plan?.qualities
                    ?.filter { height -> height > 0 }
                    ?.distinct()
                    ?.sortedDescending()
                    .orEmpty(),
                selectedQuality = selectedQualityLabel(resolved?.plan),
                isBuffering = false,
                isWaitingForNetwork = false,
                failure = null
            )
        }
        _position.value = PlaybackPosition()

        sponsor.reset(video)
        sponsor.load(video)
        startTicker()
        queueManager.updateWindow()

        val requestGeneration = generation
        if (mixNeedsLoad) startMixLoad(video.id, requestGeneration)
        scope.launch {
            val start = recordPlaybackStarted(video)
            if (generation != requestGeneration || currentVideo?.id != video.id) {
                return@launch
            }
            _libraryFlags.value = LibraryFlags(
                isFavorite = start.isFavorite,
                isWatchLater = start.isWatchLater,
                watchProgressSeconds = start.resumeSeconds
            )
            if (start.resumeSeconds > 0L) {
                engine.seekTo(start.resumeSeconds * 1000L)
                _position.value = PlaybackPosition(
                    currentSeconds = start.resumeSeconds.toFloat()
                )
            }
            prefetchNext(requestGeneration)
        }
    }

    private fun startMixLoad(videoId: String, requestGeneration: Int) {
        mixJob?.cancel()
        mixMoreJob?.cancel()
        mixJob = scope.launch {
            queue.loadMix(videoId)
            if (generation != requestGeneration) return@launch
            prefetchNext(requestGeneration)
        }
    }

    private fun prefetchNext(requestGeneration: Int) {
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            if (currentResolved?.plan?.isLive == true) {
                queueManager.removeNext()
                return@launch
            }

            var target = queue.nextTarget()
            if (target == null && queue.playlistHasMore) {
                val loaded = queue.loadMorePlaylist()
                if (generation != requestGeneration) return@launch
                if (!loaded || queue.nextTarget() == null) {
                    queue.markPlaylistExhausted()
                }
                target = queue.nextTarget()
            }
            if (target == null && queue.state.value.mix?.hasMore == true) {
                val loaded = queue.loadMoreMix()
                if (generation != requestGeneration) return@launch
                if (!loaded || queue.nextTarget() == null) {
                    queue.markMixExhausted()
                }
                target = queue.nextTarget()
            }

            if (target == null) {
                queueManager.removeNext()
                return@launch
            }
            if (queueManager.hasNext(target.id)) return@launch

            val resolution = resolver.resolve(target)
            if (generation != requestGeneration) return@launch
            if (currentVideo?.id != queue.current?.id) return@launch
            val plan = resolution.plan ?: return@launch
            queueManager.prefetchNext(
                ResolvedMedia(
                    video = target,
                    plan = plan,
                    candidateIndex = plan.candidateIndexFor(effectiveQualityHeight()),
                    related = resolution.related,
                    details = resolution.details
                )
            )
        }
    }

    /** Đồng bộ lại bài kế trong playlist native sau khi hàng đợi đổi. */
    private fun refreshPrefetch() {
        if (currentVideo == null) return
        prefetchNext(generation)
    }

    private fun onPlaybackEnded() {
        // STATE_ENDED và PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM có thể
        // cùng bắn cho một media item. Chốt theo media id thay vì debounce thời
        // gian để không bỏ sót/lặp khi mạng chậm hoặc người dùng seek.
        val mediaId = engine.player.currentMediaItem?.mediaId ?: return
        if (mediaId == lastEndedMediaId) return
        lastEndedMediaId = mediaId

        if (stoppedBySleepTimer || suppressAutoAdvance) return

        if (queue.state.value.repeatMode == RepeatMode.ONE) {
            engine.seekTo(0L)
            lastKnownPositionMs = 0L
            _position.value = _position.value.copy(currentSeconds = 0f)
            engine.play()
            return
        }

        // Auto-next tắt thì dừng hẳn ở cuối bài (kể cả khi đang Lặp tất cả),
        // giống hành vi cũ. Repeat ONE đã được xử lý ở trên.
        if (settings.value.autoNext) {
            next(force = true)
        }
    }

    private fun onFailure(error: AppError, detail: String?, waitingForNetwork: Boolean) {
        suppressAutoAdvance = true
        session.transitionTo(SessionPhase.FAILED)
        _playback.update {
            it.copy(
                isPlaying = false,
                isBuffering = false,
                isWaitingForNetwork = waitingForNetwork,
                failure = PlaybackFailure(
                    error = error,
                    message = error.userMessage(appContext),
                    detail = detail,
                    canRetry = true
                )
            )
        }
        if (waitingForNetwork) {
            autoRetryJob?.cancel()
            autoRetryJob = null
        } else {
            scheduleAutoRetry()
        }
    }

    private fun scheduleAutoRetry() {
        if (autoRetryAttempt >= AUTO_RETRY_DELAYS_MS.size) return
        autoRetryJob?.cancel()
        val delayMs = AUTO_RETRY_DELAYS_MS[autoRetryAttempt]
        autoRetryAttempt += 1
        val requestGeneration = generation
        val video = currentVideo ?: return
        autoRetryJob = scope.launch {
            delay(delayMs)
            if (generation != requestGeneration) return@launch
            if (currentVideo?.id != video.id) return@launch
            if (_playback.value.failure == null) return@launch
            autoRetryJob = null
            retryInternal()
        }
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                val video = currentVideo ?: break
                // Một lỗi lẻ (ghi lịch sử, SponsorBlock, ...) không được phép
                // giết cả ticker. Nếu ticker chết, thanh tiến trình và thời
                // gian phát đứng yên vĩnh viễn dù âm thanh vẫn chạy.
                try {
                    publishPosition(video)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    // Bỏ qua nhịp lỗi, ticker tiếp tục ở nhịp sau.
                }
                delay(TICKER_INTERVAL_MS)
            }
        }
    }

    /** Đẩy vị trí hiện tại của player lên [position] và lưu lịch sử định kỳ. */
    private suspend fun publishPosition(video: VideoItem) {
        val positionMs = engine.currentPositionMs()
        lastKnownPositionMs = positionMs
        val durationMs = engine.durationMs()
        val bufferedFraction = if (durationMs > 0L) {
            (engine.bufferedPositionMs().toFloat() / durationMs).coerceIn(0f, 1f)
        } else {
            0f
        }

        _position.value = PlaybackPosition(
            currentSeconds = positionMs / 1000f,
            durationSeconds = if (durationMs > 0L) {
                durationMs / 1000f
            } else {
                _position.value.durationSeconds
            },
            bufferedFraction = bufferedFraction
        )
        if (_playback.value.durationMs != durationMs) {
            _playback.update { it.copy(durationMs = durationMs) }
        }

        sponsor.onPosition(positionMs, engine.player.isPlaying)

        val now = System.currentTimeMillis()
        if (positionMs > 5_000L && now - lastHistorySaveAt > HISTORY_SAVE_INTERVAL_MS) {
            lastHistorySaveAt = now
            history.saveProgress(video.id, positionMs)
            // Playlist chỉ ghi tiến độ khi video này vẫn là video cuối trong
            // lịch sử playlist (tránh ticker cũ đè vị trí video mới).
            queue.currentPlaylist?.let { playlist ->
                history.savePlaylistProgress(playlist.id, video.id, positionMs)
            }
            queueStore.saveNowPlaying(video, positionMs)
        }
    }

    /**
     * App quay lại foreground (mở khoá màn hình, quay lại từ recents...).
     * Khởi động lại ticker nếu nó từng chết và đẩy ngay vị trí hiện tại để
     * thanh tiến trình không đứng ở giá trị cũ.
     */
    fun onAppForegrounded() {
        val video = currentVideo ?: return
        if (tickerJob?.isActive != true) startTicker()
        // Không chờ nhịp tick kế tiếp: cập nhật ngay vị trí sau khi resume.
        scope.launch {
            try {
                publishPosition(video)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // Không để lỗi cập nhật vị trí làm hỏng luồng resume.
            }
        }
    }

    /**
     * Ghi lịch sử video và (nếu đang phát trong playlist) lịch sử playlist.
     *
     * Hàng đợi đã cập nhật ngữ cảnh trước khi hàm này chạy, nên phát video
     * ngoài playlist sẽ không ghi nhầm vào playlist cũ.
     */
    private suspend fun recordPlaybackStarted(video: VideoItem): HistoryStart {
        val start = history.onStarted(video)
        queue.currentPlaylist?.let { playlist ->
            history.onPlaylistStarted(
                playlist = playlist,
                video = video,
                positionSeconds = start.resumeSeconds
            )
        }
        return start
    }

    private fun flushHistory(video: VideoItem? = currentVideo) {
        val id = video?.id ?: return
        val positionMs = if (video.id == currentVideo?.id) {
            maxOf(lastKnownPositionMs, engine.currentPositionMs())
        } else {
            lastKnownPositionMs
        }
        if (positionMs <= 0L) return
        val playlistId = queue.currentPlaylist
            ?.takeIf { context -> context.videos.any { it.id == id } }
            ?.id
        scope.launch {
            history.flush(id, positionMs)
            history.flushPlaylist(playlistId, id, positionMs)
        }
    }

    private fun saveNowPlaying() {
        val video = currentVideo ?: return
        val positionMs = maxOf(lastKnownPositionMs, engine.currentPositionMs())
        queueStore.saveNowPlaying(video, positionMs)
    }

    private fun restoreSession() {
        if (engine.player.mediaItemCount > 0) return
        val saved: NowPlayingState = queueStore.loadNowPlaying() ?: return
        currentVideo = saved.video
        lastKnownPositionMs = saved.positionMs
        _playback.update {
            it.copy(
                video = saved.video,
                isPlaying = false,
                isBuffering = false
            )
        }
        _position.value = PlaybackPosition(
            currentSeconds = saved.positionMs / 1000f
        )
        refreshLibraryFlags(saved.video.id)
        refreshDetails(saved.video)
    }

    private fun updateSettings(transform: (AppSettings) -> AppSettings): AppSettings {
        val previous = _settings.value
        val updated = transform(previous)
        _settings.value = updated
        settingsStore.save(updated)
        publishSettings(updated)
        engine.player.pauseAtEndOfMediaItems = !updated.autoNext ||
            queue.state.value.repeatMode == RepeatMode.ONE
        // Chất lượng progressive phải thay MediaItem (tốn kém). Chỉ áp lại khi
        // các mức chất lượng/Data Saver thực sự đổi; bật tắt phụ đề hay tốc độ
        // không được kéo theo việc dựng lại playlist.
        if (previous.qualityHeight != updated.qualityHeight ||
            previous.qualityMobileHeight != updated.qualityMobileHeight ||
            previous.dataSaver != updated.dataSaver
        ) {
            applyNetworkQuality()
        }
        return updated
    }

    private fun publishSettings(settings: AppSettings) {
        _playback.update {
            it.copy(
                speed = settings.playbackSpeed,
                pitch = settings.playbackPitch,
                skipSilence = settings.skipSilence,
                isAutoNextEnabled = settings.autoNext,
                isResumeEnabled = settings.resumePlayback,
                captionsEnabled = settings.captionsEnabled,
                captionLanguageTag = settings.captionLanguageTag
            )
        }
    }

    private fun applyNetworkQuality() {
        val metered = networkMonitor.isMeteredNetwork()
        _isMeteredNetwork.value = metered
        applyEngineQuality(
            currentResolved?.plan,
            currentResolved?.candidateIndex ?: 0
        )
        val resolved = currentResolved ?: run {
            _playback.update { it.copy(selectedQuality = selectedQualityLabel(null)) }
            return
        }
        val candidate = resolved.plan.candidates.getOrNull(resolved.candidateIndex)
        if (candidate?.isAdaptive == true) {
            qualityApplyJob?.cancel()
            qualityApplyJob = null
            _playback.update {
                it.copy(selectedQuality = selectedQualityLabel(resolved.plan))
            }
            return
        }
        // Nguồn progressive MP4 chỉ có một chất lượng mỗi file: đổi mức chất
        // lượng phải thay MediaItem. Debounce để tránh rebuild lặp khi mạng/cài đặt đổi nhanh.
        qualityApplyJob?.cancel()
        val requestGeneration = generation
        qualityApplyJob = scope.launch {
            delay(PROGRESSIVE_QUALITY_APPLY_DEBOUNCE_MS)
            if (generation != requestGeneration) return@launch
            queueManager.applyProgressiveQuality(effectiveQualityHeight())?.let {
                currentResolved = it
            }
            _playback.update {
                it.copy(selectedQuality = selectedQualityLabel(currentResolved?.plan))
            }
            qualityApplyJob = null
        }
    }

    /**
     * Đặt chiều cao đích cho track selector.
     *
     * DASH/HLS có nhiều track nên engine khoá đúng chiều cao đã chọn (min = max)
     * để mức chọn có hiệu lực cả khi tăng lẫn khi giảm. Với progressive MP4 chỉ
     * có một track, đặt giới hạn thấp hơn chiều cao track có thể khiến track
     * selector tắt hẳn hình — khi đó chất lượng đã được chọn bằng
     * [PlaybackPlan.candidateIndexFor], nên bỏ giới hạn track.
     *
     * Mức yêu cầu được quy đổi qua [PlaybackPlan.resolveHeight] trước khi đưa
     * xuống engine, để không bao giờ đặt một chiều cao mà nguồn không có.
     */
    private fun applyEngineQuality(
        plan: PlaybackPlan?,
        candidateIndex: Int
    ) {
        val configured = effectiveQualityHeight().takeIf { it > 0 }
        val target = if (configured == null) null else plan?.resolveHeight(configured) ?: configured
        val sourceIsAdaptive =
            plan?.candidates?.getOrNull(candidateIndex)?.isAdaptive == true
        engine.setQualityHeight(
            height = if (plan != null && !sourceIsAdaptive) null else target,
            // Có plan nghĩa là target đã được resolveHeight() quy đổi về mức có
            // thật trong manifest, nên khoá đúng mức ngay từ trước khi nạp
            // track; tránh phải đổi lại (và dựng lại sample stream) sau đó.
            exact = target != null && sourceIsAdaptive
        )
    }

    /** Mức chất lượng người dùng đã chọn cho loại mạng hiện tại (0 = tự động). */
    private fun configuredQualityHeight(): Int =
        if (networkMonitor.isMeteredNetwork()) {
            settings.value.qualityMobileHeight
        } else {
            settings.value.qualityHeight
        }

    private fun effectiveQualityHeight(): Int {
        val configured = configuredQualityHeight()
        return if (networkMonitor.isMeteredNetwork() &&
            configured <= 0 &&
            settings.value.dataSaver
        ) {
            480
        } else {
            configured
        }
    }

    private fun describe(error: PlaybackException): String {
        val resolved = currentResolved
        val mime = resolved?.plan?.candidates?.getOrNull(resolved.candidateIndex)?.mimeType
        val cause = generateSequence(error.cause) { it.cause }
            .mapNotNull { it.message?.takeIf { message -> message.isNotBlank() } }
            .firstOrNull()
        return buildString {
            append(error.errorCodeName)
            cause?.let { append(" • ").append(it.take(120)) }
            mime?.let { append(" • ").append(it) }
        }
    }

    /**
     * Nhãn chất lượng hiển thị trên UI.
     *
     * Mức đã lưu là *mong muốn* (ví dụ 1080p), nhưng video hiện tại có thể chỉ
     * có tối đa 720p. Quy đổi về mức thật để chip đang chọn luôn khớp với
     * chất lượng trình phát thực sự dùng.
     */
    private fun selectedQualityLabel(plan: PlaybackPlan?): String {
        val configured = configuredQualityHeight()
        val effective = effectiveQualityHeight()
        if (effective <= 0) return appContext.getString(R.string.player_auto)
        val resolved = plan?.resolveHeight(effective) ?: effective
        // "Tự động" nhưng bị Data Saver hạ trần xuống 480p: vẫn nói rõ mức
        // đang dùng thay vì chỉ ghi "Tự động" gây hiểu nhầm.
        val autoLabel = appContext.getString(R.string.player_auto)
        return if (configured <= 0) {
            appContext.getString(
                R.string.player_auto_with_height,
                Formatters.quality(resolved, autoLabel)
            )
        } else {
            Formatters.quality(resolved, autoLabel)
        }
    }

    private companion object {
        const val TICKER_INTERVAL_MS = 500L
        const val HISTORY_SAVE_INTERVAL_MS = 5_000L
        const val MAX_SEEK_TO_PREVIOUS_POSITION_MS = 3_000L
        const val PROGRESSIVE_QUALITY_APPLY_DEBOUNCE_MS = 350L
        val AUTO_RETRY_DELAYS_MS = longArrayOf(1_000L, 3_000L, 6_000L, 12_000L)
    }
}

/**
 * Singleton cấp process. Service và ViewModel dùng chung một coordinator để
 * logic phát không phụ thuộc vòng đời UI.
 */
object PlaybackCoordinatorProvider {
    @Volatile
    private var instance: PlaybackCoordinator? = null

    fun get(context: Context): PlaybackCoordinator =
        instance ?: synchronized(this) {
            instance ?: PlaybackCoordinator(
                context = context.applicationContext,
                engine = PlaybackEngineProvider.get(context),
                settingsStore = SettingsStore(context),
                queueStore = QueueStore(context),
                videoDao = AppDatabase.getDatabase(context).videoDao()
            ).also { instance = it }
        }

    /** Trả coordinator đang có mà không khởi tạo (dùng trong service lifecycle). */
    fun peek(): PlaybackCoordinator? = instance
}

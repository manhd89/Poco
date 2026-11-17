package com.luxysiv.puretube.player

import com.luxysiv.puretube.data.model.MixPlaylist
import com.luxysiv.puretube.data.model.PlaylistPage
import com.luxysiv.puretube.data.model.VideoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Kết quả thêm video vào hàng đợi, dùng cho thông báo ngắn trên UI. */
enum class QueueAddResult {
    ADDED,
    ALREADY_PLAYING,
    ALREADY_QUEUED
}

/**
 * Playlist người dùng đang phát, mở từ màn hình chi tiết playlist.
 *
 * Khác `mix` ở chỗ đây là danh sách hữu hạn, giữ đúng thứ tự người dùng thấy và
 * có continuation riêng để tải thêm khi chạm cuối trang.
 */
data class PlaybackPlaylistContext(
    val id: String,
    val title: String = "",
    val channelTitle: String = "",
    val channelId: String = "",
    val thumbnailUrl: String = "",
    val videoCount: Int = 0,
    val videos: List<VideoItem> = emptyList(),
    val nextToken: String = ""
) {
    val hasMore: Boolean get() = nextToken.isNotBlank()
}

/**
 * Hành động điều hướng đã chuẩn hoá cho coordinator.
 *
 * Hàng đợi quyết định "đi đâu" (native playlist, playStack/mix hay tua về đầu)
 * và coordinator chỉ thi hành. Nhờ vậy previous/next có cùng một contract cho
 * mọi nguồn (người dùng bấm, auto-next, lỗi mix...).
 */
sealed interface PlaybackNavigation {
    data object NativeNext : PlaybackNavigation
    data object NativePrevious : PlaybackNavigation
    data object SeekToStart : PlaybackNavigation
    data object LoadMoreMix : PlaybackNavigation
    data object LoadMorePlaylist : PlaybackNavigation
    data object None : PlaybackNavigation
    data class Play(
        val video: VideoItem,
        val origin: PlaybackOrigin
    ) : PlaybackNavigation
}

/**
 * Hàng đợi LOGIC của trình phát.
 *
 * - `userQueue`: hàng đợi người dùng tạo, ưu tiên cao nhất.
 * - `playlist`: danh sách phát hữu hạn người dùng đang xem, ưu tiên hơn mix.
 * - `mix`: danh sách kết hợp YouTube của video đang phát.
 * - `related` / `trending`: phương án dự phòng.
 * - `queueCycle`: các video đã phát, dùng cho "Lặp tất cả".
 * - `playStack`: video trước đó, dùng khi không có previous trong playlist.
 *
 * Lớp này không giữ MediaItem và không gọi ExoPlayer.
 */
class PlaybackQueue(
    initialQueue: List<VideoItem> = emptyList(),
    private val mixLoader: suspend (String) -> MixPlaylist? = { null },
    private val moreMixLoader: suspend (String) -> MixPlaylist? = { null },
    private val playlistMoreLoader: suspend (String, String) -> PlaylistPage? = { _, _ -> null },
    private val persistQueue: (List<VideoItem>) -> Unit = {}
) {

    private val _state = MutableStateFlow(QueueUiState(queue = initialQueue))
    val state: StateFlow<QueueUiState> = _state.asStateFlow()

    private var userQueue: List<VideoItem> = initialQueue
    private var mix: MixPlaylist? = null
    private var playlist: PlaybackPlaylistContext? = null
    private var related: List<VideoItem> = emptyList()
    private var trending: List<VideoItem> = emptyList()
    private var repeatMode: RepeatMode = RepeatMode.OFF
    private var currentId: String? = null
    private var currentVideo: VideoItem? = null

    private val queueCycle = mutableListOf<VideoItem>()
    private val playStack = ArrayDeque<VideoItem>()

    init {
        initialQueue.forEach(::rememberQueueItem)
        publish()
    }

    val current: VideoItem? get() = currentVideo

    /** Playlist đang phát, `null` khi phát từ mix/gợi ý. */
    val currentPlaylist: PlaybackPlaylistContext? get() = playlist

    /** Playlist còn trang continuation chưa tải. */
    val playlistHasMore: Boolean get() = playlist?.hasMore == true

    /** Đặt playlist làm nguồn phát kế tiếp; `null` để quay lại mix/gợi ý. */
    fun setPlaylist(context: PlaybackPlaylistContext?) {
        playlist = context
        publish()
    }

    fun clearPlaylist() = setPlaylist(null)

    /** Video kế tiếp theo thứ tự ưu tiên, không thay đổi hàng đợi. */
    fun nextTarget(): VideoItem? {
        userQueue.firstOrNull()?.let { return it }

        // Playlist người dùng ưu tiên hơn lặp/mix: đi đúng thứ tự họ đang xem.
        playlist?.let { context ->
            val index = context.videos.indexOfFirst { it.id == currentId }
            val next = if (index >= 0) {
                context.videos.getOrNull(index + 1)
            } else {
                context.videos.firstOrNull { it.id != currentId }
            }
            if (next != null) return next
        }

        // "Lặp tất cả" quay vòng các video đã phát trước khi sang mix/gợi ý,
        // giữ đúng hành vi của trình phát cũ (chỉ áp dụng khi playlist đã hết).
        if (repeatMode == RepeatMode.ALL && queueCycle.isNotEmpty()) {
            val replay = if (queueCycle.size == 1 && queueCycle.first().id == currentId) {
                queueCycle.toList()
            } else {
                queueCycle.filter { it.id != currentId }
            }
            replay.firstOrNull()?.let { return it }
        }

        mix?.let { mixPlaylist ->
            if (mixPlaylist.videos.isNotEmpty()) {
                val index = mixPlaylist.videos.indexOfFirst { it.id == currentId }
                val next = if (index >= 0) {
                    mixPlaylist.videos.getOrNull(index + 1)
                } else {
                    mixPlaylist.videos.firstOrNull { it.id != currentId }
                }
                if (next != null) return next
            }
        }

        val mixIds = mix?.videos?.mapTo(mutableSetOf()) { it.id } ?: emptySet()
        related.firstOrNull { it.id != currentId && it.id !in mixIds }?.let { return it }
        related.firstOrNull { it.id != currentId }?.let { return it }
        trending.firstOrNull { it.id != currentId && it.id !in mixIds }?.let { return it }
        trending.firstOrNull { it.id != currentId }?.let { return it }
        return null
    }

    /** Video trước đó: ưu tiên playlist/mix index - 1, sau đó playStack. */
    fun previousTarget(): VideoItem? {
        playlist?.let { context ->
            val index = context.videos.indexOfFirst { it.id == currentId }
            if (index > 0) return context.videos[index - 1]
        }
        val mixPlaylist = mix
        val index = mixPlaylist?.videos?.indexOfFirst { it.id == currentId } ?: -1
        if (mixPlaylist != null && index > 0) return mixPlaylist.videos[index - 1]
        return playStack.lastOrNull()
    }

    /**
     * Quyết định hành động Next duy nhất cho coordinator.
     *
     * [hasNativeNext] = ExoPlayer đã prefetch đúng bài kế tiếp. Khi chưa có
     * nhưng playlist/mix còn trang, trả [PlaybackNavigation.LoadMorePlaylist]
     * hoặc [PlaybackNavigation.LoadMoreMix] để coordinator tải thêm rồi hỏi
     * lại; khi hết mọi nguồn trả [PlaybackNavigation.None].
     */
    fun nextNavigation(hasNativeNext: Boolean): PlaybackNavigation {
        if (hasNativeNext) return PlaybackNavigation.NativeNext
        nextTarget()?.let { return PlaybackNavigation.Play(it, PlaybackOrigin.NEXT) }
        return when {
            // Playlist hữu hạn nhưng còn continuation: tải trang kế trước khi
            // rơi xuống mix/gợi ý.
            playlistHasMore -> PlaybackNavigation.LoadMorePlaylist
            mix?.hasMore == true -> PlaybackNavigation.LoadMoreMix
            else -> PlaybackNavigation.None
        }
    }

    /**
     * Quyết định hành động Previous duy nhất cho coordinator.
     *
     * Thứ tự giống YouTube: playlist native (nếu có) -> tua về đầu khi đã xem
     * quá [seekThresholdMs] -> playStack/mix -> tua về đầu.
     */
    fun previousNavigation(
        hasNativePrevious: Boolean,
        positionMs: Long,
        seekThresholdMs: Long
    ): PlaybackNavigation {
        if (hasNativePrevious) return PlaybackNavigation.NativePrevious
        if (positionMs > seekThresholdMs) return PlaybackNavigation.SeekToStart
        val target = previousTarget()
        return if (target != null) {
            PlaybackNavigation.Play(target, PlaybackOrigin.PREVIOUS)
        } else {
            PlaybackNavigation.SeekToStart
        }
    }

    /** Ghi nhận video sắp phát từ playlist native (không qua play() của coordinator). */
    fun consume(video: VideoItem) {
        if (userQueue.any { it.id == video.id }) {
            userQueue = userQueue.filterNot { it.id == video.id }
            persistQueue(userQueue)
        }
        rememberQueueItem(video)
        publish()
    }

    /** Ghi nhận video đã chuyển sang do auto-next/seek native. */
    fun onAdvancedTo(video: VideoItem): Boolean {
        val previous = currentVideo
        // Nếu bài mới chính là bài vừa được lấy từ playStack thì bỏ khỏi stack
        // để bấm "bài trước" lần nữa không quay lại đúng bài đó.
        if (playStack.lastOrNull()?.id == video.id) playStack.removeLast()
        val videoInCurrentPlaylist = playlist?.videos?.any { it.id == video.id } == true
        // Rời khỏi playlist (sang mix/related): bỏ context cũ để next không quay
        // lại đầu danh sách.
        if (playlist != null && !videoInCurrentPlaylist) playlist = null
        val videoInCurrentMix = mix?.videos?.any { it.id == video.id } == true
        // Giữ lịch sử quay lại cho các lần chuyển ngoài playlist/mix; trong cùng
        // danh sách ưu tiên previous theo index.
        if (previous != null &&
            previous.id != video.id &&
            !videoInCurrentPlaylist &&
            !videoInCurrentMix
        ) {
            playStack.addLast(previous)
            while (playStack.size > 50) playStack.removeFirst()
        }
        consume(video)
        currentId = video.id
        currentVideo = video
        if (videoInCurrentPlaylist || !videoInCurrentMix) {
            // Playlist tự cung cấp bài kế nên không cần mix; nếu chuyển ra ngoài
            // mix thì mix cũ không còn đúng.
            mix = null
        }
        publish()
        return !videoInCurrentPlaylist && !videoInCurrentMix
    }

    /**
     * Bắt đầu một lệnh phát mới. Trả về true nếu mix hiện tại bị xoá (coordinator
     * cần tải mix mới).
     */
    fun onPlayStarted(
        video: VideoItem,
        origin: PlaybackOrigin,
        previous: VideoItem?,
        addToBackStack: Boolean = true
    ): Boolean {
        if (userQueue.any { it.id == video.id }) {
            userQueue = userQueue.filterNot { it.id == video.id }
            persistQueue(userQueue)
        }

        val videoInCurrentPlaylist = playlist?.videos?.any { it.id == video.id } == true
        // Người dùng phát một video ngoài playlist: bỏ playlist để next/previous
        // quay về hành vi mix/gợi ý như trước.
        if (playlist != null && !videoInCurrentPlaylist) playlist = null
        val videoInCurrentMix = mix?.videos?.any { it.id == video.id } == true
        if (origin == PlaybackOrigin.PREVIOUS) {
            // Bài trước vừa được lấy khỏi playStack: tiêu thụ luôn để không lặp.
            if (playStack.lastOrNull()?.id == video.id) playStack.removeLast()
        } else if (origin != PlaybackOrigin.NEXT &&
            origin != PlaybackOrigin.AUTO &&
            addToBackStack &&
            previous != null &&
            previous.id != video.id
        ) {
            playStack.addLast(previous)
            while (playStack.size > 50) playStack.removeFirst()
        }

        if (!videoInCurrentMix || videoInCurrentPlaylist) {
            // Trong playlist không cần mix; ngoài mix cũ thì mix cũ đã sai.
            mix = null
        }
        related = emptyList()
        currentId = video.id
        currentVideo = video
        rememberQueueItem(video)
        publish()
        return !videoInCurrentPlaylist && !videoInCurrentMix
    }

    /** Đóng phiên phát: giữ user queue, xoá playlist/mix/related/playStack/repeat. */
    fun onClosed() {
        playlist = null
        mix = null
        related = emptyList()
        playStack.clear()
        repeatMode = RepeatMode.OFF
        currentId = null
        currentVideo = null
        publish()
    }

    /** Tải mix cho video hiện tại. Bỏ kết quả nếu người dùng đã đổi video. */
    suspend fun loadMix(videoId: String): Boolean {
        val loaded = mixLoader(videoId) ?: return false
        if (currentId != videoId) return false
        if (loaded.videos.isEmpty()) return false
        mix = loaded
        publish()
        return true
    }

    /** Tải thêm một trang mix. Trả true nếu mix đã được cập nhật. */
    suspend fun loadMoreMix(): Boolean {
        val current = mix ?: return false
        val page = moreMixLoader(current.id) ?: return false
        val knownIds = current.videos.mapTo(mutableSetOf()) { it.id }
        val merged = current.copy(
            videos = current.videos + page.videos.filter { it.id !in knownIds },
            hasMore = page.hasMore
        )
        mix = merged
        publish()
        return true
    }

    /**
     * Tải thêm một trang playlist bằng continuation hiện tại.
     *
     * Trả false khi không còn token hoặc request lỗi; danh sách đang phát được
     * giữ nguyên. Video trùng giữa hai trang bị loại bỏ.
     */
    suspend fun loadMorePlaylist(): Boolean {
        val current = playlist ?: return false
        if (current.nextToken.isBlank()) return false
        val page = playlistMoreLoader(current.id, current.nextToken) ?: return false
        // Người dùng đã đổi playlist trong lúc chờ mạng.
        if (playlist?.id != current.id) return false
        val knownIds = current.videos.mapTo(mutableSetOf()) { it.id }
        playlist = current.copy(
            videos = current.videos + page.videos.filter { it.id !in knownIds },
            nextToken = page.nextToken
        )
        publish()
        return true
    }

    fun hasMixFor(videoId: String): Boolean =
        mix?.videos?.any { it.id == videoId } == true

    /** Đánh dấu mix đã hết trang để không thử tải lại mỗi lần bấm Next. */
    fun markMixExhausted() {
        mix = mix?.copy(hasMore = false)
        publish()
    }

    /** Đánh dấu playlist đã hết trang (hoặc trang lỗi) để không gọi lại liên tục. */
    fun markPlaylistExhausted() {
        playlist = playlist?.copy(nextToken = "")
        publish()
    }

    fun setRelated(videos: List<VideoItem>) {
        related = videos
        publish()
    }

    fun setTrending(videos: List<VideoItem>) {
        trending = videos
        publish()
    }

    fun addToQueue(video: VideoItem, currentId: String? = this.currentId): QueueAddResult {
        if (video.id == currentId) return QueueAddResult.ALREADY_PLAYING
        if (userQueue.any { it.id == video.id }) return QueueAddResult.ALREADY_QUEUED
        userQueue = userQueue + video
        rememberQueueItem(video)
        persistQueue(userQueue)
        publish()
        return QueueAddResult.ADDED
    }

    fun playNextInQueue(video: VideoItem, currentId: String? = this.currentId): QueueAddResult {
        if (video.id == currentId) return QueueAddResult.ALREADY_PLAYING
        userQueue = listOf(video) + userQueue.filterNot { it.id == video.id }
        rememberQueueItem(video)
        persistQueue(userQueue)
        publish()
        return QueueAddResult.ADDED
    }

    fun cycleRepeatMode(): RepeatMode {
        repeatMode = repeatMode.next()
        publish()
        return repeatMode
    }

    fun setRepeatMode(mode: RepeatMode) {
        repeatMode = mode
        publish()
    }

    private fun rememberQueueItem(video: VideoItem) {
        if (queueCycle.none { it.id == video.id }) queueCycle += video
    }

    private fun publish() {
        _state.value = QueueUiState(
            queue = userQueue,
            mix = mix,
            playlist = playlist,
            related = related,
            repeatMode = repeatMode,
            hasNext = nextTarget() != null || playlistHasMore || mix?.hasMore == true,
            // Có video hiện tại thì "bài trước" luôn khả dụng: coordinator sẽ tua về
            // đầu hoặc phát mục trước trong playStack/mix khi không có item native.
            hasPrevious = previousTarget() != null || currentId != null
        )
    }
}

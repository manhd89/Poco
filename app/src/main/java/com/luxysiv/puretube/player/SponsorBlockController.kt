package com.luxysiv.puretube.player

import android.content.Context
import android.util.Log
import com.luxysiv.puretube.R
import com.luxysiv.puretube.data.local.AppSettings
import com.luxysiv.puretube.data.model.SponsorCategory
import com.luxysiv.puretube.data.model.SponsorMode
import com.luxysiv.puretube.data.model.SponsorSegment
import com.luxysiv.puretube.data.model.VideoItem
import com.luxysiv.puretube.data.model.sponsorChannelKey
import com.luxysiv.puretube.data.repository.SponsorBlockDataSource
import com.luxysiv.puretube.data.repository.findSegmentAt
import com.luxysiv.puretube.data.repository.findSkipTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Toàn bộ logic SponsorBlock, chạy trong scope cấp process.
 *
 * Không phụ thuộc ViewModel/Compose nên auto skip, nút "Bỏ qua", "Hoàn tác" và
 * grace rewind vẫn hoạt động khi màn hình tắt hoặc UI đã bị huỷ.
 */
class SponsorBlockController(
    private val context: Context,
    private val engine: PlaybackEngine,
    private val scope: CoroutineScope,
    private val settingsProvider: () -> AppSettings,
    private val onPositionSeek: (Long) -> Unit,
    private val onMessage: (String) -> Unit
) {

    private val _state = MutableStateFlow(SponsorUiState())
    val state: StateFlow<SponsorUiState> = _state.asStateFlow()

    private var currentVideo: VideoItem? = null
    private var currentVideoId: String? = null
    private var segments: List<SponsorSegment> = emptyList()
    private val skippedSegments = mutableSetOf<String>()
    private val gracedSegments = mutableSetOf<String>()
    private var lastSkipKeys: Set<String> = emptySet()
    private var lastPositionMs = 0L
    private var loadJob: Job? = null
    private var unskipJob: Job? = null
    private var pendingFreshCheck = false

    /** Đổi video: xoá toàn bộ trạng thái cũ để không skip nhầm. */
    fun reset(video: VideoItem?) {
        loadJob?.cancel()
        loadJob = null
        unskipJob?.cancel()
        unskipJob = null
        currentVideo = video
        currentVideoId = video?.id
        segments = emptyList()
        skippedSegments.clear()
        gracedSegments.clear()
        lastSkipKeys = emptySet()
        lastPositionMs = 0L
        pendingFreshCheck = false
        _state.value = SponsorUiState(
            isCurrentChannelWhitelisted = isWhitelisted(video)
        )
    }

    /** Tải đoạn của [video]. Không làm gì nếu người dùng đã tắt SponsorBlock. */
    fun load(video: VideoItem) {
        loadJob?.cancel()
        loadJob = null
        unskipJob?.cancel()
        unskipJob = null
        currentVideo = video
        currentVideoId = video.id
        skippedSegments.clear()
        gracedSegments.clear()
        lastSkipKeys = emptySet()
        pendingFreshCheck = false

        val settings = settingsProvider()
        val whitelisted = isWhitelisted(video)
        _state.value = _state.value.copy(
            isCurrentChannelWhitelisted = whitelisted
        )
        if (!settings.skipSponsors || whitelisted) {
            segments = emptyList()
            _state.value = _state.value.copy(
                segments = emptyList(),
                manualSegment = null,
                lastSkipped = null,
                canUnskip = false,
                isCurrentChannelWhitelisted = whitelisted
            )
            return
        }

        pendingFreshCheck = true
        loadJob = scope.launch {
            val categories = SponsorCategory.apiNamesFor(settings.sponsorCategoryModes)
            val loaded = SponsorBlockDataSource.segments(
                videoId = video.id,
                apiUrl = settings.sponsorApiUrl,
                categories = categories
            )
            applySegments(video.id, loaded)
        }
    }

    /** Gọi mỗi 500 ms từ ticker của coordinator. */
    fun onPosition(positionMs: Long, isPlaying: Boolean) {
        if (!settingsProvider().skipSponsors) return
        updateUi(positionMs)
        skipIfNeeded(positionMs, isPlaying = isPlaying)
    }

    fun onIsPlayingChanged(isPlaying: Boolean) {
        if (!settingsProvider().skipSponsors) return
        if (!isPlaying || !pendingFreshCheck) return
        pendingFreshCheck = false
        val positionMs = engine.currentPositionMs()
        updateUi(positionMs)
        skipIfNeeded(positionMs, isPlaying = true, fromFreshData = true)
    }

    /**
     * Người dùng vừa tua. Nếu rơi vào đoạn đã bỏ qua và bật "tôn trọng tua lại"
     * thì tạm ngừng auto skip cho đoạn đó.
     */
    fun onUserSeek(targetMs: Long) {
        val settings = settingsProvider()
        if (!settings.skipSponsors || !settings.sponsorGracefulRewind) return
        val atTarget = segments.filter { targetMs >= it.startMs && targetMs < it.endMs }
        if (atTarget.isEmpty()) return
        val keys = atTarget.mapTo(mutableSetOf()) { it.key }
        skippedSegments -= keys
        gracedSegments += keys
        unskipJob?.cancel()
        _state.value = _state.value.copy(lastSkipped = null, canUnskip = false)
    }

    /** Nút "Bỏ qua" ở chế độ Thủ công. */
    fun skipCurrent() {
        if (!settingsProvider().skipSponsors) return
        val segment = _state.value.manualSegment ?: return
        performSkip(
            segment = segment,
            keys = setOf(segment.key),
            automatic = false,
            positionMs = segment.startMs.coerceAtLeast(0L)
        )
    }

    /** Hoàn tác lần bỏ qua gần nhất. */
    fun unskip() {
        if (!settingsProvider().skipSponsors) return
        val segment = _state.value.lastSkipped ?: return
        val keys = lastSkipKeys.ifEmpty { setOf(segment.key) }
        skippedSegments -= keys
        gracedSegments += keys
        unskipJob?.cancel()
        engine.seekToExact(segment.startMs)
        onPositionSeek(segment.startMs)
        _state.value = _state.value.copy(
            lastSkipped = null,
            canUnskip = false,
            manualSegment = null
        )
        if (settingsProvider().sponsorNotify) {
            onMessage(context.getString(R.string.player_sponsor_undo_done))
        }
    }

    /** Áp lại cấu hình sau khi người dùng đổi tuỳ chọn SponsorBlock. */
    fun onSettingsChanged() {
        val settings = settingsProvider()
        if (!settings.sponsorShowSkipButton) {
            unskipJob?.cancel()
            _state.value = _state.value.copy(canUnskip = false, lastSkipped = null)
        }
        if (!settings.sponsorGracefulRewind) {
            gracedSegments.clear()
        }
        val video = currentVideo
        if (video == null) {
            reset(null)
            return
        }
        if (!settings.skipSponsors || isWhitelisted(video)) {
            reset(video)
        } else {
            load(video)
        }
    }

    private fun applySegments(videoId: String, loaded: List<SponsorSegment>) {
        if (currentVideoId != videoId) return
        val settings = settingsProvider()
        val video = currentVideo
        if (!settings.skipSponsors || video == null || isWhitelisted(video)) {
            pendingFreshCheck = false
            segments = emptyList()
            _state.value = _state.value.copy(
                segments = emptyList(),
                manualSegment = null,
                lastSkipped = null,
                canUnskip = false,
                isCurrentChannelWhitelisted = isWhitelisted(video)
            )
            return
        }

        segments = loaded
        _state.value = _state.value.copy(
            segments = loaded,
            isCurrentChannelWhitelisted = isWhitelisted(video)
        )
        Log.d(TAG, "SponsorBlock: ${loaded.size} đoạn cho $videoId")

        // Dữ liệu có thể về sau khi video đã phát qua đầu đoạn. Chỉ kiểm tra khi
        // media đúng video này đã nạp để không dùng vị trí của video cũ.
        if (engine.player.currentMediaItem?.mediaId == videoId) {
            val positionMs = engine.currentPositionMs()
            updateUi(positionMs)
            skipIfNeeded(positionMs, isPlaying = engine.player.isPlaying, fromFreshData = true)
            pendingFreshCheck = false
        }
    }

    private fun updateUi(positionMs: Long) {
        if (gracedSegments.isNotEmpty()) {
            val insideGraced = segments.any { segment ->
                segment.key in gracedSegments &&
                    positionMs >= segment.startMs && positionMs < segment.endMs
            }
            if (!insideGraced) gracedSegments.clear()
        }

        val active = findSegmentAt(segments, positionMs)
        val manual = active?.takeIf {
            it.isSkip &&
                it.key !in skippedSegments &&
                sponsorModeFor(it.category) == SponsorMode.MANUAL
        }
        if (_state.value.manualSegment?.key != manual?.key) {
            _state.value = _state.value.copy(manualSegment = manual)
        }
        lastPositionMs = positionMs
    }

    private fun skipIfNeeded(
        positionMs: Long,
        isPlaying: Boolean,
        fromFreshData: Boolean = false
    ) {
        val settings = settingsProvider()
        if (!settings.skipSponsors || segments.isEmpty()) return
        val video = currentVideo ?: return
        if (isWhitelisted(video)) return
        if (!fromFreshData && !isPlaying) return

        if (segments.any {
                positionMs >= it.startMs && positionMs < it.endMs &&
                    it.key in gracedSegments
            }
        ) {
            return
        }

        val autoSegments = segments.filter {
            it.isSkip && sponsorModeFor(it.category) == SponsorMode.AUTO
        }
        if (autoSegments.isEmpty()) return

        val target = findSkipTarget(
            segments = autoSegments,
            positionMs = positionMs,
            toleranceMs = SPONSOR_ENTER_TOLERANCE_MS,
            skipped = skippedSegments,
            ignoreTolerance = fromFreshData
        ) ?: return

        performSkip(
            segment = target.segment,
            keys = target.keys,
            automatic = true,
            positionMs = positionMs
        )
    }

    private fun performSkip(
        segment: SponsorSegment,
        keys: Set<String>,
        automatic: Boolean,
        positionMs: Long
    ) {
        skippedSegments += keys
        gracedSegments -= keys
        lastSkipKeys = keys

        engine.seekToExact(segment.endMs)
        onPositionSeek(segment.endMs)
        _state.value = _state.value.copy(
            lastSkipped = segment,
            canUnskip = settingsProvider().sponsorShowSkipButton,
            manualSegment = null
        )
        scheduleUnskipTimeout()

        Log.d(
            TAG,
            "SponsorBlock: ${if (automatic) "tự" else "thủ công"} bỏ qua " +
                "${segment.category} ${segment.startMs / 1000}s-${segment.endMs / 1000}s " +
                "(từ ${positionMs / 1000}s)"
        )
        if (settingsProvider().sponsorNotify) {
            val label = segment.categoryInfo?.label ?: segment.category
            onMessage(
                context.getString(
                    if (automatic) {
                        R.string.player_sponsor_skipped
                    } else {
                        R.string.player_sponsor_skipped_undoable
                    },
                    label
                )
            )
        }
    }

    private fun scheduleUnskipTimeout() {
        unskipJob?.cancel()
        if (!settingsProvider().sponsorShowSkipButton) return
        unskipJob = scope.launch {
            delay(SPONSOR_UNSKIP_WINDOW_MS)
            _state.value = _state.value.copy(lastSkipped = null, canUnskip = false)
        }
    }

    private fun sponsorModeFor(category: String): SponsorMode =
        settingsProvider().sponsorCategoryModes[category]
            ?: SponsorCategory.fromApiName(category)?.defaultMode
            ?: SponsorMode.DISABLED

    private fun isWhitelisted(video: VideoItem?): Boolean {
        if (video == null) return false
        val key = sponsorChannelKey(video)
        return key.isNotBlank() && key in settingsProvider().sponsorWhitelist
    }

    private companion object {
        const val TAG = "PureTube"
        const val SPONSOR_ENTER_TOLERANCE_MS = 2_000L
        const val SPONSOR_UNSKIP_WINDOW_MS = 5_000L
    }
}

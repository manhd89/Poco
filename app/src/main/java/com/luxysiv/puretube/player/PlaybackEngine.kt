@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.luxysiv.puretube.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * Lớp sở hữu ExoPlayer DUY NHẤT của app.
 *
 * Engine chỉ cấu hình player (audio attributes, HTTP data source, seek, tốc độ,
 * phụ đề, chất lượng). Mọi quyết định phát/queue/nổi dung nằm ở
 * [PlaybackCoordinator]; nhờ vậy engine không biết gì về mix/UI.
 */
class PlaybackEngine(context: Context) {

    /**
     * Nguồn HTTP cho stream YouTube.
     *
     * - Gửi User-Agent giống trình duyệt: một số CDN của YouTube trả 403 với UA
     *   mặc định của ExoPlayer trên một số thiết bị/nhà mạng.
     * - Cho phép chuyển hướng chéo giao thức (https -> http) vì googlevideo có
     *   lúc chuyển hướng như vậy.
     */
    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)

    /**
     * Bọc HTTP factory bằng [DefaultDataSource]: manifest DASH tự dựng được
     * truyền qua URI `data:application/dash+xml;base64,...`, còn segment vẫn
     * tải bằng HTTPS. Chỉ dùng mỗi [DefaultHttpDataSource] sẽ không đọc được
     * URI `data:` và mất toàn bộ nguồn adaptive.
     */
    private val dataSourceFactory = DefaultDataSource.Factory(
        context.applicationContext,
        httpDataSourceFactory
    )

    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true
        )
        .setHandleAudioBecomingNoisy(true)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()

    // Lựa chọn phụ đề được nhớ qua các video và các phiên (đồng bộ với SettingsStore).
    private var captionsEnabled = false
    private var captionLanguageTag = ""

    // Tốc độ/cao độ dùng chung cho mọi video; Media3 cần cả hai trong PlaybackParameters.
    private var playbackSpeed = 1.0f
    private var playbackPitch = 1.0f
    private var skipSilence = false

    // Mức chất lượng người dùng đã chọn, đã quy đổi về chiều cao có thật trong
    // nguồn đang phát (null = tự động). Giữ lại để áp đúng cho video kế tiếp.
    private var requestedVideoHeight: Int? = null

    // Mức đã áp, dùng để tránh đặt lại vô ích (mỗi lần đặt lại đều khiến track
    // selector chạy lại và có thể gây khựng).
    private var appliedVideoHeight: Int? = null

    // true: đang khoá đúng một chiều cao (min = max). false: chỉ đặt mức trần
    // vì nguồn hiện tại không có đúng chiều cao đã chọn (ví dụ HLS).
    private var exactVideoConstraints = false

    // Media item đã áp giới hạn. Đổi bài phải kiểm tra lại vì thang chất lượng
    // của nguồn mới có thể khác.
    private var constraintsMediaId: String? = null

    /** Trả seek parameters về mặc định sau khi SponsorBlock tua chính xác. */
    private val exactSeekHandler = Handler(Looper.getMainLooper())

    private val tracksListener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            // Track phụ đề chỉ có sau khi media item nạp xong. Nếu người dùng đã
            // bật CC từ trước, chọn lại đúng track cho video mới.
            syncTextTrackSelection()
            // Track video cũng chỉ có sau khi nạp xong: kiểm tra lại xem nguồn
            // mới có đúng chiều cao đã chọn không.
            syncVideoConstraints(tracks)
        }
    }

    init {
        player.addListener(tracksListener)
    }

    fun play() {
        // Ở trạng thái ENDED, play() không tự phát lại: cần tua về đầu trước.
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0L)
        }
        player.play()
    }

    fun pause() {
        player.pause()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0L))
    }

    /**
     * Tua chính xác cho SponsorBlock.
     *
     * Media3 mặc định có thể nhảy về keyframe gần nhất, khiến vị trí rơi lại
     * vào đầu đoạn và bị tua lặp. Tạm đặt [SeekParameters.EXACT] cho lần tua
     * này rồi trả lại chế độ mặc định sau 800 ms để không ảnh hưởng thao tác
     * tua thường (và không làm chậm seek trên video dài).
     */
    fun seekToExact(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        // Media3 1.11 khai báo setter với tham số @Nullable nên Kotlin không
        // tổng hợp thành property `var`; phải gọi hàm setter trực tiếp.
        player.setSeekParameters(SeekParameters.EXACT)
        player.seekTo(target)
        exactSeekHandler.removeCallbacksAndMessages(null)
        exactSeekHandler.postDelayed(
            { player.setSeekParameters(SeekParameters.DEFAULT) },
            EXACT_SEEK_RESTORE_MS
        )
    }

    fun setSpeed(speed: Float) {
        playbackSpeed = speed
        applyPlaybackParameters()
    }

    /** Cao độ 1.0 = giữ nguyên; 0.5–2.0 giống dải tốc độ của Media3. */
    fun setPitch(pitch: Float) {
        playbackPitch = pitch
        applyPlaybackParameters()
    }

    /**
     * Bật/tắt bỏ qua khoảng lặng. Media3 tự tăng tốc qua đoạn im lặng, giữ
     * nguyên hình ảnh nên không làm lệch phụ đề.
     */
    fun setSkipSilenceEnabled(enabled: Boolean) {
        skipSilence = enabled
        player.skipSilenceEnabled = enabled
    }

    private fun applyPlaybackParameters() {
        player.playbackParameters = PlaybackParameters(playbackSpeed, playbackPitch)
        player.skipSilenceEnabled = skipSilence
    }

    /**
     * Chọn chất lượng video theo chiều cao đã quy đổi về mức có thật
     * ([com.luxysiv.puretube.data.model.PlaybackPlan.resolveHeight]).
     * `null` hoặc <= 0 = tự động (tối đa).
     *
     * Mức người dùng chọn là **đích**, không phải mức trần: đặt
     * `min = max = chiều cao` để track selector chọn đúng representation.
     * Cách cũ chỉ đặt `maxVideoSize` nên khi đang phát 720p mà chọn 1080p/
     * 1440p/2160p thì adaptive vẫn ở 720p (chỉ là giới hạn trên), người dùng
     * thấy "chọn chất lượng mà video không đổi".
     *
     * Đổi selection trong cùng `TrackGroup` không cần resolve lại URL hay dựng
     * lại media source. Media3 thay sample stream video tại đúng vị trí đang
     * phát (luồng audio giữ nguyên), nên có thể khựng ngắn trong lúc tải
     * initialization/segment của representation mới, nhưng vị trí phát không
     * bị tua lại.
     *
     * Nếu nguồn không có đúng chiều cao (HLS khác thang DASH, hoặc decoder
     * không hỗ trợ độ phân giải đó), [syncVideoConstraints] tự nới về chỉ giới
     * hạn `maxVideoSize` để track selector chọn mức gần nhất thay vì tắt hình.
     *
     * @param exact true khi biết chắc nguồn có đúng [height] (coordinator đã
     *   quy đổi qua `PlaybackPlan.resolveHeight`). Khi đó áp `min = max` ngay
     *   từ trước lúc nạp track, tránh phải đổi lại sau khi track nạp xong —
     *   mỗi lần đổi lại đều làm Media3 dựng lại sample stream video.
     */
    fun setQualityHeight(height: Int?, exact: Boolean = false) {
        val normalized = height?.takeIf { it > 0 }
        val wantExact = normalized != null && exact
        // Cùng mức và cùng kiểu giới hạn: không đặt lại parameters.
        if (
            normalized == requestedVideoHeight &&
            appliedVideoHeight == normalized &&
            exactVideoConstraints == wantExact
        ) {
            return
        }
        requestedVideoHeight = normalized
        applyVideoParameters(wantExact)
    }

    /**
     * Áp lựa chọn chiều cao cho track selector.
     *
     * - Tự động: bỏ mọi giới hạn để adaptive chạy như YouTube.
     * - [exact] true: `min = max = chiều cao` để ép đúng representation.
     * - [exact] false: chỉ `maxVideoSize`, dùng khi nguồn không có đúng mức.
     */
    private fun applyVideoParameters(exact: Boolean) {
        // Giữ nguyên các lựa chọn track khác (phụ đề, ngôn ngữ...) khi đổi chất lượng.
        // Lưu ý: clearVideoSizeConstraints() của Media3 chỉ đặt lại max, KHÔNG
        // xoá min, nên phải tự đặt min = 0 trước khi áp mức mới.
        val builder = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .clearVideoSizeConstraints()
            .setMinVideoSize(0, 0)
        val height = requestedVideoHeight
        if (height != null) {
            if (exact) builder.setMinVideoSize(0, height)
            builder.setMaxVideoSize(Int.MAX_VALUE, height)
        }
        appliedVideoHeight = height
        exactVideoConstraints = height != null && exact
        constraintsMediaId = player.currentMediaItem?.mediaId
        player.trackSelectionParameters = builder.build()
    }

    /**
     * Đồng bộ giới hạn chất lượng với danh sách track thật của bài đang phát.
     *
     * Track video chỉ có sau khi media item nạp xong, và thang chất lượng của
     * bài mới có thể khác bài trước. Nếu nguồn có đúng chiều cao đã chọn thì
     * khoá chính xác; nếu không (HLS, decoder không hỗ trợ...) thì nới về mức
     * trần để không mất hình.
     */
    private fun syncVideoConstraints(tracks: Tracks) {
        val mediaId = player.currentMediaItem?.mediaId
        if (mediaId != constraintsMediaId) {
            // Bài mới: đánh giá lại từ đầu thay vì dùng trạng thái của bài cũ.
            appliedVideoHeight = null
        }
        // Track đã được kiểm tra cho media item hiện tại; lần onTracksChanged
        // sau không cần coi là bài mới nữa.
        constraintsMediaId = mediaId
        val height = requestedVideoHeight ?: return
        // Chưa có track video (Media3 báo tracks rỗng lúc bắt đầu nạp): giữ
        // nguyên cấu hình đã đặt trước khi prepare. Nếu nới về max-only ở đây
        // rồi áp lại exact sau khi track nạp xong, sample stream bị dựng lại
        // hai lần và gây khựng đúng lúc mở bài.
        if (tracks.groups.none { it.type == C.TRACK_TYPE_VIDEO }) return
        val exact = hasVideoHeight(tracks, height)
        if (exact != exactVideoConstraints) {
            applyVideoParameters(exact)
        } else {
            appliedVideoHeight = height
        }
    }

    /** Nguồn hiện tại có track video đúng chiều cao và giải mã được không. */
    private fun hasVideoHeight(tracks: Tracks, height: Int): Boolean =
        tracks.groups.any { group ->
            group.type == C.TRACK_TYPE_VIDEO &&
                (0 until group.length).any { index ->
                    group.isTrackSupported(index) &&
                        group.getTrackFormat(index).height == height
                }
        }

    /**
     * Nhớ và áp dụng lựa chọn phụ đề (bật/tắt + ngôn ngữ) cho mọi video.
     *
     * [languageTag] rỗng nghĩa là dùng track mặc định của video/ngôn ngữ thiết bị.
     *
     * Track text luôn được giữ trong `currentTracks` kể cả khi phụ đề đang tắt:
     * UI nhờ đó vẫn liệt kê được ngôn ngữ có sẵn và nút CC xuất hiện đúng lúc,
     * không phụ thuộc trạng thái bật/tắt hiện tại.
     */
    fun setCaptions(enabled: Boolean, languageTag: String) {
        captionsEnabled = enabled
        captionLanguageTag = languageTag
        applyTextTrackSelection()
    }

    private fun syncTextTrackSelection() {
        if (!captionsEnabled) return
        val selected = selectedTextLanguage()
        if (selected == null) {
            applyTextTrackSelection()
            return
        }
        // Chỉ đổi khi ngôn ngữ đã chọn thực sự có trong video. Nếu video không
        // có ngôn ngữ đó, giữ track mặc định thay vì override lặp vô hạn.
        if (
            captionLanguageTag.isNotBlank() &&
            !selected.equals(captionLanguageTag, ignoreCase = true) &&
            hasTextLanguage(captionLanguageTag)
        ) {
            applyTextTrackSelection()
        }
    }

    private fun applyTextTrackSelection() {
        // Không bao giờ disable hẳn TRACK_TYPE_TEXT: làm vậy Media3 loại track
        // text khỏi `currentTracks` và menu phụ đề trống dù video có phụ đề.
        val builder = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)

        if (captionsEnabled) {
            builder
                .setIgnoredTextSelectionFlags(0)
                .setPreferredTextLanguageAndRoleFlagsToCaptioningManagerSettings()
                .setSelectTextByDefault(true)
            preferredTextGroup()?.let { group ->
                val trackIndex = (0 until group.length)
                    .firstOrNull { group.isTrackSupported(it) }
                if (trackIndex != null) {
                    builder.setOverrideForType(
                        TrackSelectionOverride(group.mediaTrackGroup, trackIndex)
                    )
                }
            }
        } else {
            // Tắt phụ đề nhưng vẫn giữ track để UI đọc danh sách ngôn ngữ:
            //  - bỏ override;
            //  - bỏ preference lấy từ CaptioningManager (tránh hệ thống tự
            //    chọn một track khi phụ đề đang tắt);
            //  - bỏ qua cờ DEFAULT của track để không track nào được chọn.
            builder
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setPreferredTextLanguages()
                .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .setSelectTextByDefault(false)
        }
        player.trackSelectionParameters = builder.build()
    }

    private fun selectedTextLanguage(): String? {
        val group = player.currentTracks.groups.firstOrNull {
            it.type == C.TRACK_TYPE_TEXT && it.isSelected
        } ?: return null
        val trackIndex = (0 until group.length).firstOrNull { group.isTrackSelected(it) }
            ?: return null
        return group.getTrackFormat(trackIndex).language
    }

    private fun hasTextLanguage(languageTag: String): Boolean =
        player.currentTracks.groups.any { group ->
            group.type == C.TRACK_TYPE_TEXT && group.isSupported &&
                (0 until group.length).any { index ->
                    textKey(group.getTrackFormat(index))
                        .equals(languageTag, ignoreCase = true)
                }
        }

    private fun preferredTextGroup(): Tracks.Group? {
        val groups = player.currentTracks.groups.filter {
            it.type == C.TRACK_TYPE_TEXT && it.isSupported
        }
        if (groups.isEmpty()) return null

        if (captionLanguageTag.isNotBlank()) {
            groups.firstOrNull { group ->
                (0 until group.length).any { index ->
                    textKey(group.getTrackFormat(index))
                        .equals(captionLanguageTag, ignoreCase = true)
                }
            }?.let { return it }
        }

        // Ưu tiên track được đánh dấu mặc định (bản không tự động, cùng ngôn ngữ
        // thiết bị); nếu không có mới lấy track phụ đề đầu tiên.
        return groups.firstOrNull { group ->
            (0 until group.length).any { index ->
                (group.getTrackFormat(index).selectionFlags and
                    C.SELECTION_FLAG_DEFAULT) != 0
            }
        } ?: groups.first()
    }

    private fun textKey(format: androidx.media3.common.Format): String =
        format.language?.takeIf { it.isNotBlank() }
            ?: format.id?.takeIf { it.isNotBlank() }
            ?: format.label?.toString().orEmpty()

    fun stop() {
        player.stop()
        player.clearMediaItems()
    }

    fun release() {
        player.removeListener(tracksListener)
        player.release()
    }

    fun currentPositionMs(): Long = player.currentPosition.coerceAtLeast(0L)

    fun durationMs(): Long = player.duration.takeIf { it > 0L } ?: 0L

    fun bufferedPositionMs(): Long = player.bufferedPosition.coerceAtLeast(0L)

    private companion object {
        const val EXACT_SEEK_RESTORE_MS = 800L

        /** UA trình duyệt: tránh việc CDN YouTube trả 403 với UA mặc định của ExoPlayer. */
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}

/**
 * Singleton để coordinator và foreground service dùng chung một ExoPlayer.
 * Không release ở đây: vòng đời player gắn với process, service có thể đang phát.
 */
object PlaybackEngineProvider {
    @Volatile
    private var instance: PlaybackEngine? = null

    fun get(context: Context): PlaybackEngine =
        instance ?: synchronized(this) {
            instance ?: PlaybackEngine(context.applicationContext).also { instance = it }
        }
}

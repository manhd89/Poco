package com.luxysiv.puretube.player

import com.luxysiv.puretube.data.model.MixPlaylist
import com.luxysiv.puretube.data.model.PlaybackPlan
import com.luxysiv.puretube.data.model.SponsorSegment
import com.luxysiv.puretube.data.model.VideoDetails
import com.luxysiv.puretube.data.model.VideoItem
import com.luxysiv.puretube.error.AppError

/**
 * Trạng thái phát mà UI đọc trực tiếp.
 *
 * Chỉ chứa dữ liệu thuộc vòng đời phát. Cấu hình người dùng nằm trong
 * `AppSettings`, thư viện (yêu thích/xem sau) nằm trong [LibraryFlags], còn
 * bình luận/mô tả thuộc `WatchPageState` của ViewModel.
 */
data class PlaybackUiState(
    val video: VideoItem? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isLive: Boolean = false,
    val durationMs: Long = 0L,
    val availableQualities: List<Int> = emptyList(),
    val selectedQuality: String = "Tự động",
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val skipSilence: Boolean = false,
    val captionsEnabled: Boolean = false,
    val captionLanguageTag: String = "",
    val isWaitingForNetwork: Boolean = false,
    val isAutoNextEnabled: Boolean = true,
    val isResumeEnabled: Boolean = true,
    val sleepTimerMinutes: Int = 0,
    /** Số giây còn lại của hẹn giờ ngủ (0 = không đếm ngược). */
    val sleepTimerRemainingSeconds: Int = 0,
    val failure: PlaybackFailure? = null
)

/** Lỗi phát đã được chuẩn hoá để UI hiện thông điệp + nút "Thử lại". */
data class PlaybackFailure(
    val error: AppError,
    val message: String,
    val detail: String? = null,
    val canRetry: Boolean = true
)

/** Hàng đợi logic + mix/related/trending. Không chứa MediaItem. */
data class QueueUiState(
    val queue: List<VideoItem> = emptyList(),
    val mix: MixPlaylist? = null,
    /** Playlist người dùng đang phát (mở từ màn chi tiết playlist), nếu có. */
    val playlist: PlaybackPlaylistContext? = null,
    val related: List<VideoItem> = emptyList(),
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false
)

/** Trạng thái SponsorBlock tách khỏi cấu hình (cấu hình nằm trong AppSettings). */
data class SponsorUiState(
    val segments: List<SponsorSegment> = emptyList(),
    val manualSegment: SponsorSegment? = null,
    val lastSkipped: SponsorSegment? = null,
    val canUnskip: Boolean = false,
    val isCurrentChannelWhitelisted: Boolean = false
)

/** Cờ thư viện của video đang phát (nguồn: Room). */
data class LibraryFlags(
    val isFavorite: Boolean = false,
    val isWatchLater: Boolean = false,
    val watchProgressSeconds: Long = 0L
)

/**
 * Một video đã resolve xong URL + candidate hiện tại.
 *
 * [candidateIndex] cho phép thử DASH → HLS → progressive khi stream lỗi mà
 * không cần resolve lại từ đầu.
 */
data class ResolvedMedia(
    val video: VideoItem,
    val plan: PlaybackPlan,
    val candidateIndex: Int = 0,
    val startPositionMs: Long = 0L,
    /** Video liên quan lấy trong cùng lần trích xuất (dùng cho auto-next native). */
    val related: List<VideoItem> = emptyList(),
    /** Mô tả/lượt xem của video (dùng cho trang xem khi auto-next native). */
    val details: VideoDetails? = null
)

/** Kết quả resolve một video: kế hoạch phát + dữ liệu trang xem. */
data class PlaybackResolution(
    val plan: PlaybackPlan?,
    val related: List<VideoItem> = emptyList(),
    val details: VideoDetails? = null,
    val error: AppError? = null
)

/** Nguồn gốc của lệnh phát, dùng để quyết định playStack và mix. */
enum class PlaybackOrigin {
    USER,
    NEXT,
    PREVIOUS,
    AUTO,
    RESTORE
}

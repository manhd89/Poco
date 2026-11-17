package com.luxysiv.puretube.player

import com.luxysiv.puretube.data.model.SponsorMode
import com.luxysiv.puretube.data.model.SponsorSegment
import com.luxysiv.puretube.data.model.VideoItem
import com.luxysiv.puretube.data.model.defaultSponsorCategoryModes
import com.luxysiv.puretube.data.repository.SponsorBlockDataSource
import com.luxysiv.puretube.util.Formatters

/**
 * Trạng thái "cấu trúc" của trình phát (video nào, đang phát hay dừng, cờ tính năng...).
 *
 * Thời gian phát được tách riêng sang [PlaybackPosition] để không làm recompose
 * toàn bộ màn hình mỗi 500 ms.
 */
data class PurePlayerState(
    val currentVideo: VideoItem? = null,
    val isPlaying: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val playbackPitch: Float = 1.0f,
    val isSkipSilenceEnabled: Boolean = false,
    val isFullscreen: Boolean = false,
    val isBuffering: Boolean = false,
    val isBackgroundPlayEnabled: Boolean = true,
    val isAutoNextEnabled: Boolean = true,
    val isAutoPiPEnabled: Boolean = false,
    val isResumeEnabled: Boolean = true,
    val isSponsorBlockEnabled: Boolean = true,
    /** Các đoạn SponsorBlock của video hiện tại, đã lọc theo category bật. */
    val sponsorSegments: List<SponsorSegment> = emptyList(),
    /** Đoạn vừa được tự tua/bỏ qua, dùng cho nút "Hoàn tác". */
    val sponsorLastSkipped: SponsorSegment? = null,
    /** Còn trong cửa sổ 5 giây để hoàn tác lần bỏ qua gần nhất. */
    val sponsorCanUnskip: Boolean = false,
    /** Đoạn chế độ Thủ công đang đi qua, để hiện nút "Bỏ qua". */
    val sponsorManualSegment: SponsorSegment? = null,
    /** Chế độ xử lý từng category SponsorBlock (apiName -> SponsorMode). */
    val sponsorCategoryModes: Map<String, SponsorMode> = defaultSponsorCategoryModes(),
    /** Hiện thông báo ngắn mỗi lần tự tua. */
    val sponsorNotify: Boolean = false,
    /** Hiện nút Bỏ qua/Hoàn tác trên trình phát. */
    val sponsorShowSkipButton: Boolean = false,
    /** Tua lại vào đoạn đã bỏ qua thì tạm ngừng tự tua. */
    val sponsorGracefulRewind: Boolean = true,
    /** API SponsorBlock đang dùng (cho phép self-host). */
    val sponsorApiUrl: String = SponsorBlockDataSource.DEFAULT_API_URL,
    /** Số kênh đang được miễn trừ SponsorBlock. */
    val sponsorWhitelistCount: Int = 0,
    /** Kênh của video hiện tại nằm trong danh sách miễn trừ. */
    val isCurrentChannelSponsorWhitelisted: Boolean = false,
    val sleepTimerMinutes: Int = 0,
    /** Số giây còn lại của hẹn giờ ngủ (0 = không đếm ngược). */
    val sleepTimerRemainingSeconds: Int = 0,
    val isFavorite: Boolean = false,
    val isWatchLater: Boolean = false,
    /** Nhãn chất lượng hiệu lực đang dùng, ví dụ "720p" hoặc "Tự động · 480p". */
    val selectedQuality: String = "Tự động",
    /**
     * Mức chất lượng người dùng đã chọn cho loại mạng hiện tại (0 = tự động).
     *
     * Tách khỏi [selectedQuality] vì video hiện tại có thể chỉ có mức thấp hơn
     * lựa chọn; hộp thoại vẫn phải đánh dấu đúng mức người dùng đã chọn.
     */
    val qualityPreferenceHeight: Int = 0,
    /** Mức chất lượng đã chọn riêng cho Wi-Fi (0 = Tự động). */
    val wifiQualityHeight: Int = 0,
    /** Mức chất lượng đã chọn riêng cho dữ liệu di động (0 = Tự động). */
    val mobileQualityHeight: Int = 0,
    /** Nhãn chất lượng đã chọn riêng cho Wi-Fi (0 = Tự động). */
    val wifiQualityLabel: String = "Tự động",
    /** Nhãn chất lượng đã chọn riêng cho dữ liệu di động (0 = Tự động). */
    val mobileQualityLabel: String = "Tự động",
    val isDataSaverEnabled: Boolean = false,
    /** Đang dùng mạng tính lưu lượng (dữ liệu di động / hotspot). */
    val isMeteredNetwork: Boolean = false,
    val availableQualities: List<Int> = emptyList(),
    /** Đang bật phụ đề (nhớ qua các video/phiên). */
    val captionsEnabled: Boolean = false,
    /** Ngôn ngữ phụ đề đã chọn; rỗng = track mặc định/ngôn ngữ thiết bị. */
    val captionLanguageTag: String = "",
    /** Mất mạng: hiện "Đang chờ mạng" và tự thử lại khi kết nối trở lại. */
    val isWaitingForNetwork: Boolean = false,
    /** Ẩn bình luận/video liên quan/mô tả theo cài đặt nội dung. */
    val hideComments: Boolean = false,
    val hideRelated: Boolean = false,
    val hideDescription: Boolean = false
)

/** Chế độ lặp của hàng đợi do người dùng tạo. */
enum class RepeatMode {
    OFF,
    ALL,
    ONE;

    fun next(): RepeatMode = when (this) {
        OFF -> ALL
        ALL -> ONE
        ONE -> OFF
    }
}

/**
 * Chế độ tự động phát của trình phát.
 *
 * - [OFF]: dừng khi hết video hiện tại;
 * - [NEXT]: tự chuyển sang video kế tiếp;
 * - [ALL]: tự chuyển tiếp và lặp lại toàn bộ danh sách khi hết;
 * - [ONE]: lặp lại chính video hiện tại.
 */
enum class AutoPlayMode {
    OFF,
    NEXT,
    ALL,
    ONE;

    /** Chạm nút tự động phát sẽ xoay vòng qua 4 chế độ. */
    fun next(): AutoPlayMode = when (this) {
        OFF -> NEXT
        NEXT -> ALL
        ALL -> ONE
        ONE -> OFF
    }
}

/** Tiến độ phát, cập nhật 2 lần/giây và chỉ được collect ở player/mini player. */
data class PlaybackPosition(
    val currentSeconds: Float = 0f,
    val durationSeconds: Float = 0f,
    val bufferedFraction: Float = 0f
) {
    val progressFraction: Float
        get() = if (durationSeconds > 0f) {
            (currentSeconds / durationSeconds).coerceIn(0f, 1f)
        } else {
            0f
        }

    val formattedCurrentTime: String
        get() = formatTime(currentSeconds.toLong())

    val formattedDuration: String
        get() = formatTime(durationSeconds.toLong())

    companion object {
        fun formatTime(seconds: Long): String = Formatters.playbackTime(seconds)
    }
}

package com.luxysiv.puretube.data.repository

/**
 * Các luồng media lấy trực tiếp từ InnerTube `player`.
 *
 * YouTube trả hai nhóm tách rời: video-only (nhiều độ phân giải) và audio-only.
 * Mỗi luồng DASH có sẵn `initRange`/`indexRange` dạng byte nên ta dựng được
 * manifest `SegmentBase` trỏ thẳng tới googlevideo.
 */
data class YouTubeDashVideo(
    val itag: Int,
    val url: String,
    val mimeType: String,
    val codec: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val initStart: Long,
    val initEnd: Long,
    val indexStart: Long,
    val indexEnd: Long,
    val approxDurationMs: Long = -1L
)

data class YouTubeDashAudio(
    val itag: Int,
    val url: String,
    val mimeType: String,
    val codec: String,
    val bitrate: Int,
    val averageBitrate: Int,
    val sampleRate: Int,
    val channels: Int,
    val isDrc: Boolean,
    val isOriginal: Boolean,
    val initStart: Long,
    val initEnd: Long,
    val indexStart: Long,
    val indexEnd: Long,
    val approxDurationMs: Long = -1L
)

/** File MP4 ghép sẵn video+audio (mỗi file một chất lượng cố định). */
data class YouTubeProgressiveStream(
    val url: String,
    val mimeType: String,
    val height: Int
)

/**
 * Toàn bộ luồng phát được của một video, kèm metadata tối thiểu và phụ đề.
 *
 * [dashVideos]/[dashAudios] có thể rỗng khi client chỉ trả progressive (ví dụ
 * ANDROID); khi đó [progressive] là nguồn duy nhất. [hlsUrl] dùng cho livestream
 * hoặc khi manifest tự dựng không khả dụng.
 */
data class YouTubeMediaStreams(
    val dashVideos: List<YouTubeDashVideo> = emptyList(),
    val dashAudios: List<YouTubeDashAudio> = emptyList(),
    val progressive: List<YouTubeProgressiveStream> = emptyList(),
    val hlsUrl: String? = null,
    val durationSeconds: Long = 0,
    val isLive: Boolean = false
)

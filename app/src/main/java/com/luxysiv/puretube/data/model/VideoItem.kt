package com.luxysiv.puretube.data.model

data class VideoItem(
    val id: String,
    val title: String,
    val channelTitle: String,
    val channelId: String = "",
    val description: String = "",
    val duration: String = "00:00",
    val viewCount: String = "0",
    val publishedTime: String = "",
    val thumbnailUrl: String = videoThumbnailUrl(id),
    val category: String = "General",
    /** Avatar thật của kênh (nếu nguồn dữ liệu trả về), dùng cho thẻ video. */
    val channelAvatarUrl: String = ""
) {
    val shareUrl: String
        get() = "https://www.youtube.com/watch?v=$id"

    /** Bản sao đã điền thumbnail dự phòng khi nguồn dữ liệu trả về chuỗi rỗng. */
    fun withMappedDefaults(): VideoItem =
        if (thumbnailUrl.isBlank()) copy(thumbnailUrl = videoThumbnailUrl(id)) else this
}

/** URL thumbnail mặc định dùng chung cho mọi mapper video (domain/entity/JSON). */
fun videoThumbnailUrl(videoId: String): String =
    "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

/** "01:02:03" / "4:05" -> tổng giây; 0 nếu không parse được (live, --:--...). */
fun parseDurationSeconds(duration: String): Int = parseDurationSecondsOrNull(duration) ?: 0

/** Parse thời lượng `HH:MM:SS` / `MM:SS` / `SS` thành giây; `null` nếu không hợp lệ. */
fun parseDurationSecondsOrNull(duration: String?): Int? {
    val parts = duration?.trim()?.split(":")?.mapNotNull { it.toIntOrNull() } ?: return null
    return when (parts.size) {
        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        2 -> parts[0] * 60 + parts[1]
        1 -> parts[0]
        else -> null
    }?.takeIf { it >= 0 }
}

/**
 * Các tab Trang chủ có feed công khai từ YouTube.
 *
 * Chỉ giữ những danh mục có endpoint `browse` thật (xem
 * `YouTubeDataRepository.CATEGORY_FEEDS`). Các danh mục từng phải giả lập
 * bằng tìm kiếm — Công nghệ, Lofi, Podcast — đã được bỏ vì YouTube không
 * cung cấp feed công khai tương ứng.
 */
enum class VideoCategory(val displayName: String) {
    ALL("Tất cả"),
    MUSIC("Âm nhạc"),
    GAMING("Trò chơi"),
    NEWS("Tin tức"),
    LEARNING("Học tập")
}

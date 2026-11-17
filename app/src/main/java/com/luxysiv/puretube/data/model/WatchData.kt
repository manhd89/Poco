package com.luxysiv.puretube.data.model

/**
 * Thông tin chi tiết của một video, lấy từ InnerTube `player` và `next`.
 *
 * Khác với [VideoItem] (dữ liệu rút gọn dùng cho feed/tìm kiếm), lớp này chứa
 * mô tả đầy đủ, số lượt xem/thích thật và ảnh đại diện kênh để hiển thị trên
 * trang xem giống YouTube.
 */
data class VideoDetails(
    val description: String = "",
    val viewCount: Long = 0,
    val likeCount: Long = 0,
    val publishedTime: String = "",
    val subscriberCount: Long = 0,
    val channelAvatarUrl: String = "",
    /**
     * Kênh của video, lấy từ `videoDetails` của `player` hoặc `videoOwnerRenderer`
     * của `next`.
     *
     * Nhiều nguồn video (bảng xếp hạng Âm nhạc, một số lockup) không trả
     * `channelId`; trang xem dùng giá trị này để nút Đăng ký vẫn hoạt động.
     */
    val channelId: String = "",
    val channelTitle: String = ""
)

/** Một bình luận thật lấy từ YouTube (không mô phỏng). */
data class CommentItem(
    val id: String,
    val author: String,
    val avatarUrl: String = "",
    val text: String,
    val publishedTime: String = "",
    val likeCount: Int = 0,
    val likeText: String = "",
    val replyCount: Int = 0,
    /** Có token mở trang phản hồi cho bình luận này (kể cả khi chưa rõ số lượng). */
    val hasReplies: Boolean = false,
    val isPinned: Boolean = false,
    val isHeartedByUploader: Boolean = false,
    val isVerified: Boolean = false,
    val isChannelOwner: Boolean = false
)

/** Thứ tự bình luận mà YouTube hỗ trợ qua menu sắp xếp của phần bình luận. */
enum class CommentSort {
    /** Bình luận nổi bật (thứ tự mặc định của YouTube). */
    TOP,

    /** Bình luận mới nhất trước. */
    NEWEST
}

/**
 * Một trang bình luận.
 *
 * [hasMore] cho biết còn trang kế tiếp; [totalCount] là tổng số bình luận YouTube
 * báo về (có thể 0 nếu YouTube không trả con số chính xác). [canSortNewest] cho
 * biết YouTube đã trả token cho thứ tự "Mới nhất" nên UI có thể hiện lựa chọn.
 */
data class CommentPage(
    val comments: List<CommentItem> = emptyList(),
    val hasMore: Boolean = false,
    val totalCount: Int = 0,
    val isDisabled: Boolean = false,
    val sort: CommentSort = CommentSort.TOP,
    val canSortNewest: Boolean = false
)

/** Định dạng số kiểu YouTube tiếng Việt: 1,2 N · 3,4 tr · 1,2 tỷ. */
fun formatCompactCount(value: Long): String {
    if (value <= 0) return ""
    fun compact(divisor: Long, suffix: String): String {
        val scaled = value.toDouble() / divisor
        val text = if (scaled >= 100) {
            String.format(java.util.Locale.US, "%.0f", scaled)
        } else {
            String.format(java.util.Locale.US, "%.1f", scaled)
        }
        return text.replace('.', ',') + " " + suffix
    }
    return when {
        value >= 1_000_000_000L -> compact(1_000_000_000L, "tỷ")
        value >= 1_000_000L -> compact(1_000_000L, "tr")
        value >= 1_000L -> compact(1_000L, "N")
        else -> value.toString()
    }
}

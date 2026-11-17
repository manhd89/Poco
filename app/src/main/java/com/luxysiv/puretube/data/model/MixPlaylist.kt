package com.luxysiv.puretube.data.model

/**
 * "Danh sách kết hợp" (mix) do YouTube tự tạo cho một video.
 *
 * Mix có id dạng `RD<videoId>` và là danh sách vô hạn: mỗi lần tải thêm sẽ
 * trả về tối đa 25 video kế tiếp. [videos] giữ đúng thứ tự YouTube đề xuất để
 * trình phát tự chuyển bài theo danh sách này thay vì nhảy sang video liên quan
 * đầu tiên.
 */
data class MixPlaylist(
    val id: String,
    val title: String = "",
    val videos: List<VideoItem> = emptyList(),
    val hasMore: Boolean = false
)

/**
 * Mix gắn với một video vừa xem, dùng cho kệ "Danh sách kết hợp" ở trang chủ.
 *
 * [seed] là video trong lịch sử đã sinh ra mix; giữ lại để hiển thị tiêu đề và
 * mở mix đúng video gốc khi người dùng chạm vào thẻ.
 */
data class HomeMix(
    val seed: VideoItem,
    val playlist: MixPlaylist
)

package com.luxysiv.puretube.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.luxysiv.puretube.data.model.VideoItem
import com.luxysiv.puretube.data.model.videoThumbnailUrl
import com.luxysiv.puretube.util.Formatters

/**
 * Một video mới lấy từ feed công khai của kênh đã đăng ký.
 *
 * Feed được lưu lại để tab Đăng ký vẫn xem được khi mở lại (và khi ngoại tuyến)
 * thay vì gọi mạng lại cho từng kênh mỗi lần chuyển tab. Tên/avatar kênh không
 * lưu trùng ở đây mà đọc từ [SubscriptionEntity] để khi người dùng đổi avatar
 * hoặc tên kênh, toàn bộ feed cập nhật theo.
 */
@Entity(tableName = "subscription_videos")
data class SubscriptionVideoEntity(
    @PrimaryKey val videoId: String,
    val channelId: String,
    val title: String,
    val thumbnailUrl: String = "",
    /** Thời điểm đăng video (epoch millis) để sort feed mới nhất trước. */
    val publishedAt: Long = 0L,
    /** Chuỗi lượt xem đã định dạng sẵn từ feed, ví dụ "12 N". */
    val viewCount: String = "",
    /** Thời lượng đã định dạng; trang kênh InnerTube có, RSS dự phòng thì để trống. */
    val duration: String = "",
    val fetchedAt: Long = 0L
) {
    /** Ghép với thông tin kênh để tạo đúng model mà thẻ video đang dùng. */
    fun toVideoItem(channel: SubscriptionEntity): VideoItem = VideoItem(
        id = videoId,
        title = title,
        channelTitle = channel.channelTitle,
        channelId = channel.channelId,
        duration = duration,
        viewCount = viewCount,
        publishedTime = Formatters.relativeTime(publishedAt),
        thumbnailUrl = thumbnailUrl.ifBlank { videoThumbnailUrl(videoId) },
        category = "Đăng ký",
        channelAvatarUrl = channel.avatarUrl
    )
}

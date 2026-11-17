package com.luxysiv.puretube.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Một kênh người dùng đăng ký cục bộ.
 *
 * PureTube không có tài khoản YouTube nên danh sách này chỉ nằm trên thiết bị
 * (cùng cách NewPipe/PipePipe lưu "local subscriptions"). Khóa chính là
 * [channelId] thay vì URL để so khớp trực tiếp với `VideoItem.channelId`.
 */
@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey val channelId: String,
    val channelTitle: String,
    val avatarUrl: String = "",
    val subscriberCount: Long = 0L,
    val addedAt: Long = 0L,
    /** Lần cuối tải feed thành công; 0 = chưa từng tải. */
    val lastFetchedAt: Long = 0L,
    /**
     * Continuation token của trang kế tiếp trong feed kênh.
     *
     * Rỗng nghĩa là đã tải hết. Token được lưu cùng kênh để lần cuộn sau (kể cả
     * sau khi mở lại app) vẫn nối đúng vào trang cũ thay vì tải lại từ đầu.
     */
    @ColumnInfo(defaultValue = "''")
    val nextPageToken: String = ""
)

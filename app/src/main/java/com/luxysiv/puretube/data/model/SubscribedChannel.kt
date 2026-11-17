package com.luxysiv.puretube.data.model

/**
 * Kênh đã đăng ký, dạng rút gọn để UI hiển thị chip lọc và danh sách quản lý.
 *
 * Tách khỏi entity Room để tầng View không phụ thuộc trực tiếp vào schema lưu trữ.
 */
data class SubscribedChannel(
    val channelId: String,
    val title: String,
    val avatarUrl: String = "",
    val subscriberCount: Long = 0L
)

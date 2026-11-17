package com.luxysiv.puretube.data.model

/**
 * Thông tin trang kênh lấy từ InnerTube `browse`.
 *
 * [videos] là các video mới nhất (tab "Video"). [nextToken] dùng để tải thêm
 * khi YouTube trả continuation.
 */
data class ChannelPage(
    val channelId: String,
    val title: String = "",
    val avatarUrl: String = "",
    val bannerUrl: String = "",
    val subscriberCount: String = "",
    val description: String = "",
    val videos: List<VideoItem> = emptyList(),
    val nextToken: String = ""
) {
    val hasMore: Boolean get() = nextToken.isNotBlank()
}

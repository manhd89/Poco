package com.luxysiv.puretube.data.model

/**
 * Một trang feed của tab Thịnh hành.
 *
 * [hasMore] = true khi YouTube còn continuation cho ít nhất một feed nguồn.
 * Repository tự giữ các token thật; UI chỉ cần biết có nên gọi tải thêm hay
 * không để hiện footer/tiếp tục cuộn vô cực.
 */
data class HomeFeedPage(
    val videos: List<VideoItem> = emptyList(),
    val hasMore: Boolean = false
)

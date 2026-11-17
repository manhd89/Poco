package com.luxysiv.puretube.ui.home

import com.luxysiv.puretube.data.database.PlaylistHistoryEntity
import com.luxysiv.puretube.data.database.VideoEntity
import com.luxysiv.puretube.data.model.HomeMix
import com.luxysiv.puretube.data.model.VideoCategory
import com.luxysiv.puretube.data.model.VideoItem
import com.luxysiv.puretube.error.AppError

/**
 * Ảnh chụp duy nhất của Trang chủ để View render một lần.
 *
 * Trước đây Activity phải `combine` nhiều cụm StateFlow lồng nhau và gọi 6 hàm
 * `set...` khác nhau, nên mỗi nguồn phát lại đều kéo theo render riêng và rất
 * dễ lệch trạng thái (ví dụ feed lỗi nhưng tab Dành cho bạn vẫn đang tải).
 * ViewModel tổng hợp mọi nguồn thành [HomeUiState]; View chỉ đọc state này.
 */
data class HomeUiState(
    val selectedTab: HomeTab = HomeTab.TRENDING,
    val selectedCategory: VideoCategory = VideoCategory.ALL,
    val trendingVideos: List<VideoItem> = emptyList(),
    val isLoadingFeed: Boolean = false,
    val feedError: AppError? = null,
    val isLoadingMoreFeed: Boolean = false,
    val hasMoreFeed: Boolean = false,
    val feedLoadMoreError: AppError? = null,
    val history: List<VideoEntity> = emptyList(),
    val playlistHistory: List<PlaylistHistoryEntity> = emptyList(),
    val homeMixes: List<HomeMix> = emptyList(),
    val forYouVideos: List<VideoItem> = emptyList(),
    val isLoadingMoreForYou: Boolean = false,
    val hasMoreForYou: Boolean = false,
    val forYouLoadMoreError: AppError? = null,
    val isLoadingPersonalization: Boolean = false,
    val historyLoaded: Boolean = false,
    val favoriteIds: Set<String> = emptySet(),
    val watchLaterIds: Set<String> = emptySet(),
    val savedPlaylistIds: Set<String> = emptySet(),
    val currentVideoId: String? = null
)

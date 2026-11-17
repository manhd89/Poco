package com.luxysiv.puretube.data.model

/**
 * Một playlist YouTube hiển thị trong kết quả tìm kiếm hoặc trên Trang chủ.
 *
 * Tách khỏi [VideoItem] vì playlist không có thời lượng/lượt xem và không được
 * lưu vào Room như video. [id] có thể là `PL...` (playlist hữu hạn), `RD...`
 * (mix vô hạn do YouTube tạo) hoặc `OL...` (album YouTube Music).
 */
data class PlaylistItem(
    val id: String,
    val title: String,
    val channelTitle: String = "",
    val channelId: String = "",
    val videoCount: Int = 0,
    val thumbnailUrl: String = "",
    val firstVideoId: String = "",
    val firstVideoTitle: String = "",
    val previewTitles: List<String> = emptyList()
) {
    /** Mix `RD...` do YouTube tự sinh, không phải playlist người dùng tạo. */
    val isMix: Boolean get() = id.startsWith("RD")

    /** browseId dùng cho InnerTube `browse` (playlist detail). */
    val browseId: String get() = if (id.startsWith("VL")) id else "VL$id"

    val shareUrl: String
        get() = "https://www.youtube.com/playlist?list=$id"
}

/** Một dòng kết quả tìm kiếm, giữ đúng thứ tự YouTube trả về. */
sealed interface SearchItem {
    data class Video(val video: VideoItem) : SearchItem

    data class Playlist(val playlist: PlaylistItem) : SearchItem
}

/** Kết quả tìm kiếm đã tách sẵn video/playlist để UI và tầng trên dùng lại. */
data class SearchPage(
    val items: List<SearchItem> = emptyList()
) {
    val videos: List<VideoItem>
        get() = items.mapNotNull { (it as? SearchItem.Video)?.video }

    val playlists: List<PlaylistItem>
        get() = items.mapNotNull { (it as? SearchItem.Playlist)?.playlist }

    fun take(limit: Int): SearchPage =
        if (items.size <= limit) this else copy(items = items.take(limit))
}

/**
 * Một trang video của playlist detail.
 *
 * [nextToken] là continuation của InnerTube; rỗng nghĩa là đã hết danh sách.
 */
data class PlaylistPage(
    val videos: List<VideoItem> = emptyList(),
    val nextToken: String = ""
) {
    val hasMore: Boolean get() = nextToken.isNotBlank()
}

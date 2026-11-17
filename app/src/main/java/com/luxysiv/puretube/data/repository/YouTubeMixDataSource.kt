package com.luxysiv.puretube.data.repository

import com.luxysiv.puretube.data.model.MixPlaylist
import com.luxysiv.puretube.error.runAppCatchingBlocking

/**
 * "Danh sách kết hợp" (mix) của YouTube, lấy từ `ytInitialData` của trang watch.
 *
 * YouTube trả trang đầu tiên (tối đa 25 video) ngay trên trang watch. Các trang
 * sau được tải lại với video cuối đã biết làm mốc; danh sách trả về luôn đặt
 * video mốc ở đầu nên ta bỏ nó đi.
 */
object YouTubeMixDataSource {

    private class MixSession(
        val id: String,
        val title: String,
        /** Video cuối đã biết, dùng làm mốc để tải trang kế tiếp. */
        var lastVideoId: String,
        /** Id đã trả cho trình phát, tránh tải lại cùng một vòng mix. */
        val seenIds: MutableSet<String>
    )

    private val lock = Any()

    private var session: MixSession? = null

    /** Mix id của lần tải gần nhất; chặn kết quả cũ ghi đè khi đổi video nhanh. */
    private var requestedMixId: String? = null

    /** Mix của video đang phát (có phiên để tải trang kế tiếp). */
    fun mixPlaylistBlocking(videoId: String): MixPlaylist? {
        if (videoId.isBlank()) return null
        val mixId = "RD$videoId"
        synchronized(lock) { requestedMixId = mixId }

        val page = runAppCatchingBlocking {
            YouTubeWebDataSource.mixPageBlocking(videoId, mixId, includeAnchor = true)
        }.getOrNull() ?: return null

        // Mix thật luôn có nhiều video; một dòng là dữ liệu lỗi, đừng hiện.
        if (page.videos.size <= 1) return null

        synchronized(lock) {
            if (requestedMixId != mixId) return null
            session = MixSession(
                id = mixId,
                title = page.title,
                lastVideoId = page.videos.last().id,
                seenIds = page.videos.mapTo(mutableSetOf()) { it.id }
            )
        }
        return page.copy(id = mixId)
    }

    /**
     * Mix dùng cho kệ gợi ý ở trang chủ.
     *
     * Không ghi vào [session] để việc tải nền không làm lệch nút Next/Previous
     * của video đang phát.
     */
    fun homeMixBlocking(videoId: String): MixPlaylist? {
        if (videoId.isBlank()) return null
        val mixId = "RD$videoId"
        val page = runAppCatchingBlocking {
            YouTubeWebDataSource.mixPageBlocking(videoId, mixId, includeAnchor = true)
        }.getOrNull() ?: return null
        if (page.videos.size <= 1) return null
        return page.copy(id = mixId)
    }

    /**
     * Trang kế tiếp của một mix dùng cho kệ gợi ý ở trang chủ.
     *
     * Giống [moreMixBlocking] nhưng không đọc/ghi [session] của trình phát, nên
     * tab Dành cho bạn có thể cuộn vô hạn mà không làm lệch nút Next/Previous
     * của video đang phát. Trả trang rỗng khi mix đã hết thay vì coi là lỗi.
     */
    fun moreHomeMixBlocking(mixId: String, anchorVideoId: String): MixPlaylist? {
        if (mixId.isBlank() || anchorVideoId.isBlank()) return null

        val page = runAppCatchingBlocking {
            YouTubeWebDataSource.mixPageBlocking(
                anchorVideoId = anchorVideoId,
                playlistId = mixId,
                includeAnchor = false
            )
        }.getOrNull() ?: return null

        return page.copy(
            id = mixId,
            // Trang rỗng nghĩa là mix đã quay vòng/hết video mới.
            hasMore = page.videos.isNotEmpty()
        )
    }

    /** Trang kế tiếp của mix đang phát (tối đa 25 video). */
    fun moreMixBlocking(mixId: String): MixPlaylist? {
        val current = synchronized(lock) {
            session?.takeIf { it.id == mixId }
        } ?: return null

        val page = runAppCatchingBlocking {
            YouTubeWebDataSource.mixPageBlocking(
                anchorVideoId = current.lastVideoId,
                playlistId = current.id,
                includeAnchor = false
            )
        }.getOrNull() ?: return null

        return synchronized(lock) {
            if (session?.id != current.id) return null
            val videos = page.videos.filter { current.seenIds.add(it.id) }
            page.videos.lastOrNull()?.let { current.lastVideoId = it.id }
            MixPlaylist(
                id = current.id,
                title = current.title.ifBlank { page.title },
                videos = videos,
                // Không còn video mới = mix đã quay vòng, dừng tải thêm.
                hasMore = videos.isNotEmpty()
            )
        }
    }
}

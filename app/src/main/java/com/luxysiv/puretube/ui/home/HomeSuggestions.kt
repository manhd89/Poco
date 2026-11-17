package com.luxysiv.puretube.ui.home

import com.luxysiv.puretube.data.model.VideoItem

/**
 * Ghép video gợi ý từ nhiều mix theo kiểu chia đều (round-robin).
 *
 * Lấy lần lượt video thứ 0 của từng mix, rồi video thứ 1... để kệ "Đề xuất cho
 * bạn" không bị chiếm hết bởi một mix. Bỏ video đã xem/trùng và giới hạn
 * [limit]. Hàm thuần để test được mà không cần DB/Android.
 */
internal fun buildHomeSuggestions(
    mixVideos: List<List<VideoItem>>,
    excludedIds: Set<String>,
    limit: Int
): List<VideoItem> = interleaveHomeVideos(mixVideos, excludedIds, limit)

/**
 * Trộn đều nhiều danh sách video và bỏ mọi id đã có trong [excludedIds].
 *
 * Dùng cho cả trang đầu của tab Dành cho bạn lẫn các trang tải thêm: mỗi lần
 * chỉ cần truyền danh sách video đã hiển thị vào [excludedIds] là các trang
 * nối tiếp sẽ không lặp lại video cũ.
 *
 * Không giới hạn khi [limit] âm hoặc bằng [Int.MAX_VALUE]; vòng lặp tự dừng
 * khi mọi danh sách đã cạn.
 */
internal fun interleaveHomeVideos(
    mixVideos: List<List<VideoItem>>,
    excludedIds: Set<String>,
    limit: Int = Int.MAX_VALUE
): List<VideoItem> {
    if (mixVideos.isEmpty() || limit <= 0) return emptyList()

    val lists = mixVideos.map { videos -> videos.filter { it.id !in excludedIds } }
    val suggestions = ArrayList<VideoItem>()
    val seen = HashSet<String>(excludedIds)
    var index = 0
    while (suggestions.size < limit) {
        var hasMoreAtThisIndex = false
        for (list in lists) {
            if (index < list.size) hasMoreAtThisIndex = true
            val video = list.getOrNull(index) ?: continue
            if (seen.add(video.id)) {
                suggestions += video
                if (suggestions.size == limit) break
            }
        }
        if (!hasMoreAtThisIndex) break
        index++
    }
    return suggestions
}

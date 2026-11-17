package com.luxysiv.puretube.ui.components

import com.luxysiv.puretube.data.database.VideoEntity
import com.luxysiv.puretube.data.model.parseDurationSeconds

/**
 * Phần trăm đã xem của một video trong lịch sử, dùng cho thanh tiến trình đỏ
 * dưới thumbnail giống YouTube. Trả về 0 khi chưa xem hoặc chưa rõ thời lượng.
 */
fun watchProgressFraction(entity: VideoEntity): Float {
    if (entity.watchProgressSeconds <= 0L) return 0f
    val total = parseDurationSeconds(entity.duration)
    if (total <= 0) return 0f
    return (entity.watchProgressSeconds.toFloat() / total).coerceIn(0f, 1f)
}

/** Map `videoId -> tiến độ` để tra cứu nhanh khi dựng các danh sách video. */
fun watchProgressMap(history: List<VideoEntity>): Map<String, Float> =
    history.mapNotNull { entity ->
        val fraction = watchProgressFraction(entity)
        if (fraction > 0f) entity.videoId to fraction else null
    }.toMap()

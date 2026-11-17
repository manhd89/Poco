package com.luxysiv.puretube.data.local

import com.luxysiv.puretube.data.database.VideoEntity
import com.luxysiv.puretube.data.model.VideoItem
import com.luxysiv.puretube.data.model.videoThumbnailUrl
import org.json.JSONObject

/**
 * Codec JSON dùng chung cho video.
 *
 * Hàng đợi, "đang phát" và bản sao lưu đều lưu metadata video dưới dạng JSON.
 * Trước đây mỗi nơi tự viết lại danh sách trường nên rất dễ lệch khi thêm một
 * trường mới (ví dụ `channelAvatarUrl`). Mọi đường đọc/ghi giờ đi qua
 * [VideoEntity] như một schema trung tâm:
 *
 * - `VideoItem` -> `VideoEntity` -> JSON (`videoId`, trạng thái thư viện...).
 * - JSON -> `VideoEntity` -> `VideoItem`.
 *
 * Bộ đọc vẫn chấp nhận khóa `id` cũ của các bản lưu trước đây, nên nâng cấp
 * ứng dụng không làm mất hàng đợi/now-playing đã ghi.
 */

private const val DEFAULT_CHANNEL = "YouTube"
private const val DEFAULT_DURATION = "00:00"
private const val DEFAULT_VIEW_COUNT = "0"
private const val DEFAULT_CATEGORY = "General"

internal fun VideoItem.toJson(): JSONObject =
    VideoEntity.fromVideoItem(this).toJson()

internal fun JSONObject.toVideoItem(): VideoItem? = toVideoEntity()?.toVideoItem()

internal fun VideoEntity.toJson(): JSONObject = JSONObject().apply {
    put("videoId", videoId)
    put("title", title)
    put("channelTitle", channelTitle)
    put("channelId", channelId)
    put("description", description)
    put("duration", duration)
    put("viewCount", viewCount)
    put("publishedTime", publishedTime)
    put("thumbnailUrl", thumbnailUrl)
    put("category", category)
    put("channelAvatarUrl", channelAvatarUrl)
    put("isFavorite", isFavorite)
    put("isWatchLater", isWatchLater)
    put("lastWatchedTimestamp", lastWatchedTimestamp)
    put("watchProgressSeconds", watchProgressSeconds)
    put("favoriteAddedAt", favoriteAddedAt)
    put("watchLaterAddedAt", watchLaterAddedAt)
}

/**
 * Đọc entity từ JSON. Chấp nhận cả schema mới (`videoId`) và schema cũ (`id`)
 * để hàng đợi/lịch sử đã lưu trước bản cập nhật vẫn đọc được.
 */
internal fun JSONObject.toVideoEntity(): VideoEntity? {
    val videoId = optString("videoId").ifBlank { optString("id") }.trim()
    val title = optString("title").trim()
    if (videoId.isEmpty() || title.isEmpty()) return null
    return VideoEntity(
        videoId = videoId,
        title = title,
        channelTitle = optString("channelTitle", DEFAULT_CHANNEL),
        channelId = optString("channelId"),
        description = optString("description"),
        duration = optString("duration", DEFAULT_DURATION),
        viewCount = optString("viewCount", DEFAULT_VIEW_COUNT),
        publishedTime = optString("publishedTime"),
        thumbnailUrl = optString("thumbnailUrl").ifBlank { videoThumbnailUrl(videoId) },
        category = optString("category", DEFAULT_CATEGORY),
        channelAvatarUrl = optString("channelAvatarUrl"),
        isFavorite = optBoolean("isFavorite"),
        isWatchLater = optBoolean("isWatchLater"),
        lastWatchedTimestamp = optLong("lastWatchedTimestamp"),
        watchProgressSeconds = optLong("watchProgressSeconds"),
        favoriteAddedAt = optLong("favoriteAddedAt"),
        watchLaterAddedAt = optLong("watchLaterAddedAt")
    )
}

package com.luxysiv.puretube.data.local

import com.luxysiv.puretube.data.database.SubscriptionEntity
import com.luxysiv.puretube.data.database.VideoEntity
import com.luxysiv.puretube.error.runAppCatchingBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Xuất/nhập bản sao lưu dạng JSON.
 *
 * Bản sao lưu chỉ chứa dữ liệu do người dùng tạo trên thiết bị: cài đặt, video
 * đã lưu/lịch sử xem, lịch sử tìm kiếm, danh sách kênh miễn trừ SponsorBlock và
 * các kênh đăng ký cục bộ. Không có tài khoản, cookie hay mã nhận diện cá nhân.
 *
 * v1 vẫn đọc được: thiếu `subscriptions` được coi là danh sách rỗng.
 */
object BackupManager {

    private const val APP_NAME = "PureTube"
    private const val FORMAT_VERSION = 2

    data class BackupPayload(
        val settings: AppSettings,
        val videos: List<VideoEntity>,
        val recentSearches: List<String>,
        val subscriptions: List<SubscriptionEntity>
    )

    fun export(
        settings: AppSettings,
        videos: List<VideoEntity>,
        recentSearches: List<String>,
        subscriptions: List<SubscriptionEntity>
    ): String {
        val root = JSONObject().apply {
            put("app", APP_NAME)
            put("version", FORMAT_VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("settings", SettingsCodec.toJson(settings))
            put("videos", JSONArray().apply { videos.forEach { put(it.toJson()) } })
            put(
                "recentSearches",
                JSONArray().apply { recentSearches.forEach { put(it) } }
            )
            put(
                "subscriptions",
                JSONArray().apply { subscriptions.forEach { put(it.toJson()) } }
            )
        }
        return root.toString(2)
    }

    /** Trả về null nếu tệp không phải bản sao lưu của PureTube. */
    fun parse(raw: String): BackupPayload? {
        val root = runAppCatchingBlocking { JSONTokener(raw).nextValue() as? JSONObject }
            .getOrNull() ?: return null
        if (root.optString("app") != APP_NAME) return null

        val settings = SettingsCodec.fromJson(root.optJSONObject("settings"))

        val videosJson = root.optJSONArray("videos") ?: JSONArray()
        val videos = buildList {
            for (index in 0 until videosJson.length()) {
                videosJson.optJSONObject(index)?.toVideoEntity()?.let(::add)
            }
        }

        val searchesJson = root.optJSONArray("recentSearches") ?: JSONArray()
        val searches = buildList {
            for (index in 0 until searchesJson.length()) {
                searchesJson.optString(index).trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let(::add)
            }
        }

        // Bản sao lưu v1 không có mục này; trả danh sách rỗng thay vì lỗi.
        val subscriptionsJson = root.optJSONArray("subscriptions") ?: JSONArray()
        val subscriptions = buildList {
            for (index in 0 until subscriptionsJson.length()) {
                subscriptionsJson.optJSONObject(index)?.toSubscriptionEntity()?.let(::add)
            }
        }

        return BackupPayload(
            settings = settings,
            videos = videos,
            recentSearches = searches,
            subscriptions = subscriptions
        )
    }

    private fun SubscriptionEntity.toJson(): JSONObject = JSONObject().apply {
        put("channelId", channelId)
        put("channelTitle", channelTitle)
        put("avatarUrl", avatarUrl)
        put("subscriberCount", subscriberCount)
        put("addedAt", addedAt)
        put("lastFetchedAt", lastFetchedAt)
    }

    /** Bỏ qua bản ghi không có channelId; các trường còn lại dùng mặc định an toàn. */
    private fun JSONObject.toSubscriptionEntity(): SubscriptionEntity? {
        val id = optString("channelId").trim()
        if (id.isEmpty()) return null
        return SubscriptionEntity(
            channelId = id,
            channelTitle = optString("channelTitle").trim().ifBlank { "YouTube" },
            avatarUrl = optString("avatarUrl").trim(),
            subscriberCount = optLong("subscriberCount"),
            addedAt = optLong("addedAt"),
            lastFetchedAt = optLong("lastFetchedAt")
        )
    }
}

package com.luxysiv.puretube.data.repository

import com.luxysiv.puretube.data.model.CommentSort
import com.luxysiv.puretube.data.model.VideoDetails
import com.luxysiv.puretube.data.model.VideoItem
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Metadata trang xem lấy từ InnerTube `next(videoId)`. */
data class YouTubeVideoMetadata(
    val details: VideoDetails,
    val related: List<VideoItem>,
    val commentsToken: String?,
    val commentsTotalCount: Int,
    val commentsDisabled: Boolean,
    val commentsSortTokens: Map<CommentSort, String> = emptyMap()
)

/**
 * Đọc trang xem qua InnerTube `next` với chỉ `videoId`.
 *
 * Cache ngắn hạn theo từng video + coalesce các request đồng thời cùng `videoId`
 * để tránh gọi `next` trùng lặp (ví dụ UI và luồng bình luận cùng cần metadata).
 */
object YouTubeMetadataDataSource {

    private const val NEXT_URL = "https://www.youtube.com/youtubei/v1/next?prettyPrint=false"
    private const val CACHE_TTL_MS = 5 * 60 * 1000L
    private const val MAX_CACHE_ENTRIES = 8
    private const val IN_FLIGHT_TIMEOUT_MS = 20_000L

    private class InFlightCall {
        val result = AtomicReference<YouTubeVideoMetadata?>()
        val latch = CountDownLatch(1)
    }

    private val cache = ExpiringLruCache<String, YouTubeVideoMetadata>(
        ttlMs = CACHE_TTL_MS,
        maxEntries = MAX_CACHE_ENTRIES
    )
    private val inFlight = ConcurrentHashMap<String, InFlightCall>()

    /** Gọi `next` thật (hoặc chờ request cùng `videoId` đang chạy); null khi mạng/JSON lỗi. */
    fun fetchBlocking(videoId: String): YouTubeVideoMetadata? {
        if (videoId.isBlank()) return null
        cachedBlocking(videoId)?.let { return it }

        val call = InFlightCall()
        val existing = inFlight.putIfAbsent(videoId, call)
        if (existing != null) {
            runCatching { existing.latch.await(IN_FLIGHT_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            return existing.result.get() ?: cachedBlocking(videoId)
        }

        try {
            val fetched = fetchFromNetwork(videoId)
            if (fetched != null) putCache(videoId, fetched)
            call.result.set(fetched)
            return fetched
        } finally {
            call.latch.countDown()
            inFlight.remove(videoId, call)
        }
    }

    /** Metadata còn hạn cho [videoId], dùng để lấy nhanh token bình luận. */
    fun cachedBlocking(videoId: String): YouTubeVideoMetadata? {
        if (videoId.isBlank()) return null
        return cache.get(videoId)
    }

    private fun putCache(videoId: String, metadata: YouTubeVideoMetadata) {
        cache.put(videoId, metadata)
    }

    private fun fetchFromNetwork(videoId: String): YouTubeVideoMetadata? {
        val context = JSONObject().put(
            "client",
            JSONObject()
                .put("clientName", "WEB")
                .put("clientVersion", "2.20260120.01.00")
                .put("hl", "vi")
                .put("gl", "VN")
                .put("utcOffsetMinutes", 0)
        ).put(
            "request",
            JSONObject()
                .put("internalExperimentFlags", JSONArray())
                .put("useSsl", true)
        ).put("user", JSONObject().put("lockedSafetyMode", false))

        val payload = JSONObject()
            .put("context", context)
            .put("videoId", videoId)

        val raw = InnerTubeHttp.post(
            url = NEXT_URL,
            body = payload.toString(),
            userAgent = InnerTubeHttp.DESKTOP_USER_AGENT,
            referer = "https://www.youtube.com/watch?v=$videoId"
        ) ?: return null

        val root = InnerTubeJson.parseObject(raw) ?: return null
        return parseMetadata(root, videoId)
    }

    internal fun parseMetadata(root: JSONObject, videoId: String): YouTubeVideoMetadata {
        val primary = InnerTubeJson.firstObjectNamed(root, "videoPrimaryInfoRenderer")
        val secondary = InnerTubeJson.firstObjectNamed(root, "videoSecondaryInfoRenderer")
        val owner = secondary?.optJSONObject("owner")
            ?.optJSONObject("videoOwnerRenderer")

        val viewCountText = primary?.optJSONObject("viewCount")
            ?.optJSONObject("videoViewCountRenderer")
            ?.let { renderer ->
                InnerTubeJson.text(renderer.optJSONObject("viewCount"))
                    ?: InnerTubeJson.text(renderer.optJSONObject("shortViewCount"))
            }
        val viewCount = InnerTubeJson.parseCount(viewCountText)

        val likeCount = parseLikeCount(primary)

        val description = secondary?.optJSONObject("attributedDescription")
            ?.optString("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: InnerTubeJson.text(secondary?.optJSONObject("description")).orEmpty()

        val publishedTime = primary?.optJSONObject("dateText")
            ?.let { InnerTubeJson.text(it) }
            ?: primary?.optJSONObject("relativeDateText")
                ?.let { InnerTubeJson.text(it) }
                .orEmpty()

        val subscriberCount = InnerTubeJson.parseCount(
            InnerTubeJson.text(owner?.optJSONObject("subscriberCountText"))
                ?: owner?.optJSONObject("subscriberCountText")
                    ?.optJSONObject("accessibility")
                    ?.optJSONObject("accessibilityData")
                    ?.optString("label")
        )

        // `videoOwnerRenderer` là nguồn channelId đáng tin cậy nhất khi thẻ
        // video mở từ nguồn không có byline (bảng xếp hạng, lockup...).
        val ownerChannelId = owner
            ?.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId")
            .orEmpty()
        val ownerChannelTitle = InnerTubeJson.text(owner?.optJSONObject("title")).orEmpty()

        val comments = parseCommentsInfo(root)

        return YouTubeVideoMetadata(
            details = VideoDetails(
                description = description,
                viewCount = viewCount,
                likeCount = likeCount,
                publishedTime = publishedTime,
                subscriberCount = subscriberCount,
                channelAvatarUrl = InnerTubeJson.bestThumbnailUrl(
                    owner?.optJSONObject("thumbnail")
                ),
                channelId = ownerChannelId,
                channelTitle = ownerChannelTitle
            ),
            related = parseRelated(root, videoId),
            commentsToken = comments.token,
            commentsTotalCount = comments.totalCount,
            commentsDisabled = comments.disabled,
            commentsSortTokens = comments.sortTokens
        )
    }

    /**
     * Lượt thích nằm trong `buttonViewModel` của nút LIKE.
     *
     * `title` là chuỗi rút gọn (`4,1 N`) còn `accessibilityText` chứa con số đầy
     * đủ (`cùng 4.183 người khác thích video này`). Ưu tiên con số đầy đủ.
     */
    private fun parseLikeCount(primary: JSONObject?): Long {
        val buttons = InnerTubeJson.objectsNamed(primary ?: JSONObject(), "buttonViewModel")
        val likeButtons = buttons.filter { button ->
            button.optString("iconName").equals("LIKE", ignoreCase = true) ||
                button.optString("accessibilityId") == "id.video.like.button"
        }.ifEmpty { buttons }

        return likeButtons.mapNotNull { button ->
            val exact = InnerTubeJson.parseCount(button.optString("accessibilityText"))
            if (exact > 0) {
                exact
            } else {
                when (val title = button.opt("title")) {
                    is String -> InnerTubeJson.parseCount(title)
                    is JSONObject -> InnerTubeJson.parseCount(InnerTubeJson.text(title))
                    else -> 0L
                }
            }
        }.filter { it > 0 }.maxOrNull() ?: 0L
    }

    private data class CommentsInfo(
        val token: String?,
        val totalCount: Int,
        val disabled: Boolean,
        val sortTokens: Map<CommentSort, String>
    )

    private fun parseCommentsInfo(root: JSONObject): CommentsInfo {
        var totalCount = 0
        var panelToken: String? = null
        var foundPanel = false
        var sortTokens: Map<CommentSort, String> = emptyMap()

        val panels = root.optJSONArray("engagementPanels")
        if (panels != null) {
            for (i in 0 until panels.length()) {
                val renderer = panels.optJSONObject(i)
                    ?.optJSONObject("engagementPanelSectionListRenderer")
                    ?: continue
                if (renderer.optString("panelIdentifier") != "engagement-panel-comments-section") {
                    continue
                }
                foundPanel = true
                val header = renderer.optJSONObject("header")
                    ?.optJSONObject("engagementPanelTitleHeaderRenderer")
                val countText = InnerTubeJson.text(header?.optJSONObject("contextualInfo"))
                if (countText != null) totalCount = InnerTubeJson.parseCount(countText).toInt()
                val content = renderer.optJSONObject("content")
                if (sortTokens.isEmpty()) {
                    sortTokens = YouTubeCommentsDataSource.parseSortMenu(content ?: renderer)
                }
                panelToken = commentTokenFromSection(content)
                if (panelToken != null) break
            }
        }

        var sectionToken: String? = null
        var foundSection = false
        for (node in InnerTubeJson.objectsNamed(root, "itemSectionRenderer")) {
            if (node.optString("sectionIdentifier") != "comment-item-section") continue
            foundSection = true
            if (sortTokens.isEmpty()) {
                sortTokens = YouTubeCommentsDataSource.parseSortMenu(node)
            }
            sectionToken = commentTokenFromSection(node)
            if (sectionToken != null) break
        }

        val token = sectionToken ?: panelToken
        return CommentsInfo(
            token = token,
            totalCount = totalCount,
            disabled = token == null && !foundSection && !foundPanel,
            sortTokens = sortTokens
        )
    }

    /** Tìm `continuationCommand.token` trong section chứa danh sách bình luận. */
    private fun commentTokenFromSection(section: JSONObject?): String? {
        section ?: return null
        val contents = section.optJSONArray("contents")
            ?: section.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
            ?: return null
        for (i in 0 until contents.length()) {
            val item = contents.optJSONObject(i) ?: continue
            val continuation = item.optJSONObject("continuationItemRenderer")
                ?: item.optJSONObject("itemSectionRenderer")
                    ?.optJSONArray("contents")
                    ?.optJSONObject(0)
                    ?.optJSONObject("continuationItemRenderer")
                ?: continue
            val endpoint = continuation.optJSONObject("continuationEndpoint")
                ?: continuation.optJSONObject("button")
                    ?.optJSONObject("buttonRenderer")
                    ?.optJSONObject("command")
            val token = endpoint?.optJSONObject("continuationCommand")
                ?.optString("token")
            if (!token.isNullOrBlank()) return token
        }
        return null
    }

    private fun parseRelated(root: JSONObject, videoId: String): List<VideoItem> {
        val out = ArrayList<VideoItem>()

        for (lockup in InnerTubeJson.objectsNamed(root, "lockupViewModel")) {
            val item = InnerTubeVideoMapping.lockupVideoItem(lockup) ?: continue
            if (item.id == videoId) continue
            out.add(item)
        }
        if (out.isEmpty()) {
            val renderers = InnerTubeJson.objectsNamed(root, "compactVideoRenderer") +
                InnerTubeJson.objectsNamed(root, "videoRenderer")
            for (renderer in renderers) {
                val item = InnerTubeVideoMapping.rendererVideoItem(renderer) ?: continue
                if (item.id == videoId) continue
                out.add(item)
            }
        }
        return out.distinctBy { it.id }
    }
}

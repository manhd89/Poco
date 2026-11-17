package com.luxysiv.puretube.data.repository

import com.luxysiv.puretube.data.model.formatCompactCount
import org.json.JSONObject
import java.util.Locale

/**
 * Video mới của một kênh lấy từ InnerTube `browse` (tab "Video").
 *
 * RSS `feeds/videos.xml` từng là nguồn chính vì rất nhẹ, nhưng từ cuối 2025
 * máy chủ feed của YouTube thường xuyên trả 404/500 cho mọi kênh trong nhiều
 * giờ. Khi đó tab Đăng ký sẽ trống dù kênh hoàn toàn hợp lệ. InnerTube `browse`
 * dùng đúng dữ liệu trang kênh, trả ~30 video gần nhất và ổn định hơn; RSS được
 * giữ làm dự phòng trong [SubscriptionFeedDataSource].
 */
internal object ChannelVideoDataSource {

    private const val BROWSE_URL = "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false"

    /**
     * Tham số protobuf của tab "Video" trên trang kênh.
     *
     * Đây là chuỗi cố định do YouTube dùng cho `browseId=UC...`; thiếu nó
     * endpoint trả tab "Trang chủ" (playlist/shelf) chứ không phải danh sách
     * video theo thứ tự mới nhất.
     */
    private const val VIDEOS_TAB_PARAMS = "EgZ2aWRlb3PyBgQKAjoA"

    private const val CLIENT_VERSION = "2.20260120.01.00"

    /** Một video mới, chưa gắn với kênh trong database. */
    internal data class Entry(
        val videoId: String,
        val title: String,
        val thumbnailUrl: String,
        val publishedAt: Long,
        val viewCount: String,
        val duration: String = ""
    )

    /** Một trang video của kênh kèm token để tải trang cũ hơn. */
    internal data class Page(
        val entries: List<Entry>,
        val nextToken: String = ""
    ) {
        val hasMore: Boolean get() = nextToken.isNotBlank()
    }

    /** Trang đầu của tab "Video". Trả `null` khi mạng/JSON lỗi. */
    fun fetch(channelId: String): List<Entry>? = fetchPage(channelId)?.entries

    /**
     * Một trang video của kênh.
     *
     * [continuation] rỗng nghĩa là tải trang đầu (kèm `browseId` + params tab
     * Video); có token nghĩa là tải trang cũ hơn của chính kênh đó. Trả `null`
     * khi mạng/JSON lỗi để tầng trên thử RSS rồi mới báo thất bại.
     */
    fun fetchPage(channelId: String, continuation: String? = null): Page? {
        if (channelId.isBlank() && continuation.isNullOrBlank()) return null
        val payload = JSONObject()
            .put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject()
                        .put("clientName", "WEB")
                        .put("clientVersion", CLIENT_VERSION)
                        .put("hl", "vi")
                        .put("gl", "VN")
                        .put("utcOffsetMinutes", 0)
                )
            )
        if (continuation.isNullOrBlank()) {
            payload.put("browseId", channelId)
            payload.put("params", VIDEOS_TAB_PARAMS)
        } else {
            payload.put("continuation", continuation)
        }

        val referer = if (channelId.isNotBlank()) {
            "https://www.youtube.com/channel/$channelId/videos"
        } else {
            "https://www.youtube.com"
        }
        val raw = InnerTubeHttp.post(
            url = BROWSE_URL,
            body = payload.toString(),
            userAgent = InnerTubeHttp.DESKTOP_USER_AGENT,
            referer = referer
        ) ?: return null
        val root = InnerTubeJson.parseObject(raw) ?: return null
        if (root.has("error")) return null
        return parsePage(root)
    }

    /** Tách riêng để test bằng JSON mẫu: video + continuation token. */
    internal fun parsePage(
        root: JSONObject,
        nowMillis: Long = System.currentTimeMillis()
    ): Page = Page(
        entries = parse(root, nowMillis),
        nextToken = ChannelPageDataSource.firstContinuationToken(root)
    )

    /**
     * Parse danh sách video từ response `browse`.
     *
     * Tách khỏi phần mạng để unit test bằng JSON mẫu. Thứ tự trong response là
     * mới nhất trước; video không có mốc thời gian vẫn được giữ đúng thứ tự
     * bằng một mốc giảm dần.
     */
    internal fun parse(root: JSONObject, nowMillis: Long = System.currentTimeMillis()): List<Entry> {
        val out = ArrayList<Entry>()

        for (lockup in InnerTubeJson.objectsNamed(root, "lockupViewModel")) {
            val item = InnerTubeVideoMapping.lockupVideoItem(lockup) ?: continue
            val metadata = lockup.optJSONObject("metadata")
                ?.optJSONObject("lockupMetadataViewModel")
                ?: continue
            val parts = InnerTubeVideoMapping.lockupMetadataRows(metadata).flatten()
            val viewText = parts.firstOrNull(::looksLikeViewCount).orEmpty()
            val publishedText = parts.firstOrNull(::looksLikePublishedTime).orEmpty()

            out += Entry(
                videoId = item.id,
                title = item.title,
                thumbnailUrl = item.thumbnailUrl,
                publishedAt = parseRelativeTime(publishedText, nowMillis),
                viewCount = formatViews(viewText),
                duration = item.duration.takeUnless { it == "--:--" }.orEmpty()
            )
        }

        // Một số kênh cũ vẫn trả schema `videoRenderer` thay vì lockup.
        for (renderer in InnerTubeJson.objectsNamed(root, "videoRenderer")) {
            val item = InnerTubeVideoMapping.rendererVideoItem(renderer) ?: continue
            out += Entry(
                videoId = item.id,
                title = item.title,
                thumbnailUrl = item.thumbnailUrl,
                publishedAt = parseRelativeTime(item.publishedTime, nowMillis),
                viewCount = formatViews(item.viewCount),
                duration = item.duration.takeUnless { it == "--:--" }.orEmpty()
            )
        }

        // Lockup và videoRenderer có thể cùng mô tả một video; giữ bản đầu tiên.
        val distinct = out.distinctBy { it.videoId }
        return distinct.mapIndexed { index, entry ->
            if (entry.publishedAt > 0L) {
                entry
            } else {
                // Không đọc được "x ngày trước": dùng thứ tự response để sort,
                // tránh đẩy toàn bộ video mới xuống cuối bảng.
                entry.copy(publishedAt = (nowMillis - index * 1_000L).coerceAtLeast(1L))
            }
        }
    }

    private fun looksLikeViewCount(text: String): Boolean =
        text.contains("lượt xem", ignoreCase = true) ||
            Regex("\\bviews?\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)

    private fun looksLikePublishedTime(text: String): Boolean =
        text.contains("trước", ignoreCase = true) ||
            Regex("\\bago\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
            text.contains("vừa xong", ignoreCase = true) ||
            text.contains("just now", ignoreCase = true)

    /** `123456` hoặc `1,2 Tr lượt xem` → dạng rút gọn như thẻ video khác. */
    private fun formatViews(text: String): String {
        val value = InnerTubeJson.parseCount(text)
        return if (value > 0) formatCompactCount(value) else ""
    }

    /**
     * `2 ngày trước` / `3 hours ago` → epoch millis; 0 nếu không hiểu.
     *
     * Chỉ cần độ chính xác tương đối vì nguồn đã làm tròn; giao diện hiển thị
     * lại cùng đơn vị bằng `Formatters.relativeTime`.
     */
    internal fun parseRelativeTime(text: String, nowMillis: Long): Long {
        val raw = text.trim().lowercase(Locale.US)
        if (raw.isBlank()) return 0L
        if (raw.contains("vừa xong") || raw.contains("just now")) return nowMillis

        val match = Regex(
            "(\\d+(?:[.,]\\d+)?)\\s*" +
                "(giây|phút|giờ|ngày|tuần|tháng|năm|second|minute|hour|day|week|month|year)s?" +
                "\\s*(?:trước|ago)"
        ).find(raw) ?: return 0L
        val amount = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return 0L
        val unitMillis = when (match.groupValues[2]) {
            "giây", "second" -> 1_000L
            "phút", "minute" -> 60_000L
            "giờ", "hour" -> 3_600_000L
            "ngày", "day" -> 86_400_000L
            "tuần", "week" -> 604_800_000L
            "tháng", "month" -> 2_592_000_000L
            else -> 31_536_000_000L
        }
        return (nowMillis - (amount * unitMillis).toLong()).coerceAtLeast(0L)
    }
}

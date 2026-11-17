package com.luxysiv.puretube.data.repository

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * Nguồn feed cho Đăng ký cục bộ.
 *
 * Hai nguồn, ưu tiên InnerTube:
 *  1. `browse(channelId, tab Video)` — cùng dữ liệu trang kênh, ~30 video gần
 *     nhất và không phụ thuộc máy chủ RSS hay lỗi thời.
 *  2. `feeds/videos.xml?channel_id=...` — RSS/Atom nhẹ, dùng dự phòng khi
 *     InnerTube lỗi. Feed chỉ có ~15 mục và không có thời lượng.
 *
 * Từ cuối 2025 máy chủ RSS của YouTube thường xuyên trả 404/500 hàng loạt,
 * nên không thể là nguồn duy nhất nếu muốn tab Đăng ký luôn có dữ liệu.
 */
internal object SubscriptionFeedDataSource {

    private const val FEED_URL = "https://www.youtube.com/feeds/videos.xml?channel_id="

    /** Một mục video đọc từ feed, chưa gắn với kênh trong database. */
    internal data class FeedEntry(
        val videoId: String,
        val title: String,
        val thumbnailUrl: String,
        val publishedAt: Long,
        val viewCount: String,
        val duration: String = ""
    )

    /**
     * Một trang feed của kênh.
     *
     * [nextToken] rỗng nghĩa là đã hết (hoặc nguồn RSS vốn không có phân trang).
     */
    internal data class FeedPage(
        val entries: List<FeedEntry>,
        val nextToken: String = ""
    ) {
        val hasMore: Boolean get() = nextToken.isNotBlank()
    }

    /** Tải trang đầu của feed kênh (giữ API cũ cho nơi chỉ cần danh sách). */
    fun fetch(channelId: String): List<FeedEntry>? = fetchPage(channelId)?.entries

    /**
     * Tải một trang video của kênh.
     *
     * Trang đầu ưu tiên InnerTube rồi mới đến RSS; các trang sau chỉ InnerTube
     * vì RSS không có continuation. Trả `null` khi không nguồn nào cho dữ liệu
     * dùng được để tầng trên giữ nguyên bộ đệm cũ.
     */
    fun fetchPage(channelId: String, continuation: String? = null): FeedPage? {
        if (channelId.isBlank() && continuation.isNullOrBlank()) return null

        // Trang cũ hơn: RSS không hỗ trợ continuation, chỉ có InnerTube.
        if (!continuation.isNullOrBlank()) {
            return ChannelVideoDataSource.fetchPage(channelId, continuation)?.toFeedPage()
        }

        val fromChannelPage = ChannelVideoDataSource.fetchPage(channelId)
        // Danh sách rỗng từ InnerTube thường là dấu hiệu schema đổi, không phải
        // kênh không có video; thử RSS trước khi kết luận.
        if (!fromChannelPage?.entries.isNullOrEmpty()) {
            return fromChannelPage?.toFeedPage()
        }

        // Dự phòng RSS khi InnerTube lỗi mạng/parse.
        val rss = InnerTubeHttp.get(FEED_URL + channelId)
            ?.let { xml -> runCatching { parse(xml) }.getOrNull() }
        if (rss != null) return FeedPage(entries = rss)

        // RSS cũng lỗi: giữ bộ đệm cũ thay vì xoá trắng feed của kênh.
        return null
    }

    private fun ChannelVideoDataSource.Page.toFeedPage(): FeedPage = FeedPage(
        entries = entries.map { entry ->
            FeedEntry(
                videoId = entry.videoId,
                title = entry.title,
                thumbnailUrl = entry.thumbnailUrl,
                publishedAt = entry.publishedAt,
                viewCount = entry.viewCount,
                duration = entry.duration
            )
        },
        nextToken = nextToken
    )

    /**
     * Parse Atom feed của YouTube.
     *
     * Tách riêng khỏi phần mạng để test bằng XML mẫu. Thẻ có thể có namespace
     * (`yt:videoId`, `media:thumbnail`) nên phần so khớp bỏ tiền tố trước dấu `:`.
     */
    internal fun parse(xml: String): List<FeedEntry> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // Feed công khai nhưng vẫn tắt DOCTYPE để tránh XXE nếu nội dung bị thay đổi.
            runCatching {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val entries = document.getElementsByTagName("entry")
        val result = ArrayList<FeedEntry>(entries.length)

        for (index in 0 until entries.length) {
            val entry = entries.item(index) as? Element ?: continue
            val videoId = childText(entry, "videoId")
            if (videoId.isBlank()) continue
            val title = childText(entry, "title").ifBlank { "Video $videoId" }

            // Thumbnail nằm trong media:group; một số feed cũ đặt trực tiếp
            // dưới entry. Lượt xem nằm sâu hơn: media:group/media:community/
            // media:statistics, nên phải tìm đệ quy thay vì chỉ đọc con trực tiếp.
            val group = childElement(entry, "group")
            val thumbnailUrl = attributeValue(
                childElement(group ?: entry, "thumbnail") ?: childElement(entry, "thumbnail"),
                "url"
            )
            val views = attributeValue(descendantElement(entry, "statistics"), "views")

            result += FeedEntry(
                videoId = videoId,
                title = title,
                thumbnailUrl = InnerTubeJson.normalizeUrl(thumbnailUrl),
                publishedAt = parsePublished(childText(entry, "published")),
                viewCount = formatViews(views)
            )
        }

        return result.distinctBy { it.videoId }
            .sortedByDescending { it.publishedAt }
    }

    private fun childElement(parent: Element, name: String): Element? {
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val node = children.item(index)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val element = node as? Element ?: continue
            if (matches(element.tagName, name)) return element
        }
        return null
    }

    /** Tìm phần tử ở bất kỳ độ sâu nào (media:statistics nằm trong media:community). */
    private fun descendantElement(parent: Element, name: String): Element? {
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val node = children.item(index)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val element = node as? Element ?: continue
            if (matches(element.tagName, name)) return element
            descendantElement(element, name)?.let { return it }
        }
        return null
    }

    private fun childText(parent: Element, name: String): String =
        childElement(parent, name)?.textContent?.trim().orEmpty()

    private fun attributeValue(element: Element?, name: String): String {
        val attributes = element?.attributes ?: return ""
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            if (matches(attribute.nodeName, name)) return attribute.nodeValue.orEmpty()
        }
        return ""
    }

    private fun matches(nodeName: String?, expected: String): Boolean {
        val name = nodeName.orEmpty()
        return name == expected || name.substringAfter(':') == expected
    }

    /**
     * `2024-05-01T12:34:56+00:00` hoặc `...Z` → epoch millis.
     *
     * Không dùng `java.time` vì app hỗ trợ Android 7 (API 24) và chưa bật desugaring.
     */
    private fun parsePublished(value: String): Long {
        if (value.isBlank()) return 0L
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(value)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return 0L
    }

    /** RSS trả số thô (`123456`); đổi sang dạng rút gọn như các thẻ video khác. */
    private fun formatViews(raw: String): String {
        val value = raw.toLongOrNull() ?: return ""
        return com.luxysiv.puretube.data.model.formatCompactCount(value)
    }
}

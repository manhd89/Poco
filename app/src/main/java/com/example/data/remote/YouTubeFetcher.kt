package com.example.data.remote

import android.util.Xml
import com.example.data.model.ChannelInfo
import com.example.data.model.VideoCategory
import com.example.data.model.VideoDetail
import com.example.data.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class YouTubeFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val categories: List<VideoCategory> = listOf(
        VideoCategory(
            id = "all",
            label = "Tất cả",
            searchQuery = "video nổi bật việt nam mới nhất",
            fallbackChannelIds = listOf("UCBJycsmduvYEL83R_U4JriQ", "UCsBjURrPoezykLs9EqgamOA", "UCSJ4gkVC6NrvII8umztf0Ow"),
        ),
        VideoCategory(
            id = "music",
            label = "Âm nhạc",
            searchQuery = "nhạc trẻ vpop hay nhất 2025 2026",
            fallbackChannelIds = listOf("UCSJ4gkVC6NrvII8umztf0Ow"),
        ),
        VideoCategory(
            id = "tech",
            label = "Công nghệ",
            searchQuery = "đánh giá công nghệ điện thoại AI mới nhất",
            fallbackChannelIds = listOf("UCBJycsmduvYEL83R_U4JriQ", "UCsBjURrPoezykLs9EqgamOA"),
        ),
        VideoCategory(
            id = "news",
            label = "Tin tức",
            searchQuery = "tin tức thời sự việt nam hôm nay",
            fallbackChannelIds = listOf("UCLA_DiR1FfKNvjuUpBHmylQ"),
        ),
        VideoCategory(
            id = "entertainment",
            label = "Giải trí",
            searchQuery = "giải trí hài vlog việt nam mới nhất",
            fallbackChannelIds = listOf("UCX6OQ3DkcsbYNE6H8uQQuVA"),
        ),
        VideoCategory(
            id = "sports",
            label = "Thể thao",
            searchQuery = "highlight bóng đá mới nhất",
            fallbackChannelIds = listOf("UCX6OQ3DkcsbYNE6H8uQQuVA"),
        ),
        VideoCategory(
            id = "education",
            label = "Học tập",
            searchQuery = "kiến thức khoa học lập trình hay",
            fallbackChannelIds = listOf("UCsooa4yRKGN_zEE8iknghZA", "UC_x5XG1OV2P6uZZ5FSM9Ttw"),
        ),
        VideoCategory(
            id = "gaming",
            label = "Trò chơi",
            searchQuery = "gaming stream game mới việt nam",
            fallbackChannelIds = listOf("UC-lHJZR3Gqxm24_Vd_AJ5Yw"),
        ),
    )

    val curatedChannels: List<ChannelInfo> = listOf(
        ChannelInfo(
            id = "UCBJycsmduvYEL83R_U4JriQ",
            name = "MKBHD",
            handle = "@mkbhd",
            subscriberText = "Công nghệ",
            category = "Công nghệ",
        ),
        ChannelInfo(
            id = "UCsBjURrPoezykLs9EqgamOA",
            name = "Fireship",
            handle = "@Fireship",
            subscriberText = "Lập trình",
            category = "Học tập",
        ),
        ChannelInfo(
            id = "UCSJ4gkVC6NrvII8umztf0Ow",
            name = "Lofi Girl",
            handle = "@LofiGirl",
            subscriberText = "Âm nhạc",
            category = "Âm nhạc",
        ),
        ChannelInfo(
            id = "UCsooa4yRKGN_zEE8iknghZA",
            name = "TED-Ed",
            handle = "@TEDEd",
            subscriberText = "Giáo dục",
            category = "Học tập",
        ),
        ChannelInfo(
            id = "UCLA_DiR1FfKNvjuUpBHmylQ",
            name = "NASA",
            handle = "@NASA",
            subscriberText = "Khoa học",
            category = "Khoa học",
        ),
        ChannelInfo(
            id = "UC_x5XG1OV2P6uZZ5FSM9Ttw",
            name = "Google Developers",
            handle = "@GoogleDevelopers",
            subscriberText = "Android & AI",
            category = "Công nghệ",
        ),
    )

    suspend fun fetchCategoryFeed(category: VideoCategory): List<VideoItem> = withContext(Dispatchers.IO) {
        val searchResults = runCatching { searchVideos(category.searchQuery) }.getOrDefault(emptyList())
        if (searchResults.isNotEmpty()) {
            return@withContext searchResults
        }
        // Fallback to official YouTube Atom RSS feeds if search scraping is unavailable
        val rssResults = coroutineScope {
            category.fallbackChannelIds.map { cid ->
                async { runCatching { fetchChannelRss(cid) }.getOrDefault(emptyList()) }
            }.awaitAll().flatten()
        }
        if (rssResults.isNotEmpty()) {
            return@withContext rssResults
        }
        throw IllegalStateException("Không thể tải danh sách video từ YouTube")
    }

    suspend fun searchVideos(query: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        val url = "https://www.youtube.com/results?search_query=$encoded&hl=vi&gl=VN"
        val html = executeGet(url)
        val jsonStr = extractBalancedJson(html, "var ytInitialData =")
            ?: extractBalancedJson(html, "window[\"ytInitialData\"] =")
            ?: throw IllegalStateException("Không phân tích được dữ liệu tìm kiếm")

        val root = JSONObject(jsonStr)
        val videos = mutableListOf<VideoItem>()
        val seenIds = mutableSetOf<String>()
        collectVideosFromJson(root, videos, seenIds)
        videos
    }

    suspend fun fetchTrendingVideos(): List<VideoItem> = withContext(Dispatchers.IO) {
        val trendingUrl = "https://www.youtube.com/feed/trending?hl=vi&gl=VN"
        val fromTrending = runCatching {
            val html = executeGet(trendingUrl)
            val jsonStr = extractBalancedJson(html, "var ytInitialData =") ?: return@runCatching emptyList()
            val root = JSONObject(jsonStr)
            val list = mutableListOf<VideoItem>()
            collectVideosFromJson(root, list, mutableSetOf())
            list
        }.getOrDefault(emptyList())

        if (fromTrending.isNotEmpty()) return@withContext fromTrending
        val fromSearch = runCatching { searchVideos("trending music gaming tech vietnam") }.getOrDefault(emptyList())
        if (fromSearch.isNotEmpty()) return@withContext fromSearch

        val rssFallback = coroutineScope {
            curatedChannels.take(4).map { ch ->
                async { runCatching { fetchChannelRss(ch.id) }.getOrDefault(emptyList()) }
            }.awaitAll().flatten()
        }
        if (rssFallback.isNotEmpty()) return@withContext rssFallback
        throw IllegalStateException("Không thể tải danh sách thịnh hành")
    }

    suspend fun fetchChannelRss(channelId: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val url = "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"
        val xml = executeGet(url)
        parseAtomFeed(xml, channelId)
    }

    suspend fun fetchVideoDetail(videoId: String, fallback: VideoItem): VideoDetail = withContext(Dispatchers.IO) {
        val url = "https://www.youtube.com/watch?v=$videoId&hl=vi&gl=VN"
        val parsed = runCatching {
            val html = executeGet(url)
            val initialDataStr = extractBalancedJson(html, "var ytInitialData =")
            val playerRespStr = extractBalancedJson(html, "var ytInitialPlayerResponse =")

            var description = fallback.description
            var subscriberText = ""
            var likeText = ""
            var title = fallback.title
            var channelId = fallback.channelId
            var channelName = fallback.channelName
            var channelAvatar = fallback.channelAvatarUrl
            var viewCount = fallback.viewCountText

            if (playerRespStr != null) {
                val playerJson = JSONObject(playerRespStr)
                val details = playerJson.optJSONObject("videoDetails")
                if (details != null) {
                    title = details.optString("title").ifBlank { title }
                    description = details.optString("shortDescription").ifBlank { description }
                    channelId = details.optString("channelId").ifBlank { channelId }
                    channelName = details.optString("author").ifBlank { channelName }
                    val rawViews = details.optString("viewCount")
                    if (rawViews.isNotBlank() && viewCount.isBlank()) {
                        viewCount = formatCountVi(rawViews.toLongOrNull()) + " lượt xem"
                    }
                }
            }

            val related = mutableListOf<VideoItem>()
            if (initialDataStr != null) {
                val dataJson = JSONObject(initialDataStr)
                // Extract owner info & likes from videoPrimaryInfoRenderer / videoSecondaryInfoRenderer
                val metadata = extractWatchMetadata(dataJson)
                if (metadata.subscriberText.isNotBlank()) subscriberText = metadata.subscriberText
                if (metadata.likeText.isNotBlank()) likeText = metadata.likeText
                if (metadata.channelAvatar.isNotBlank()) channelAvatar = metadata.channelAvatar
                if (metadata.description.isNotBlank() && description.isBlank()) {
                    description = metadata.description
                }

                val secondaryResults = dataJson
                    .optJSONObject("contents")
                    ?.optJSONObject("twoColumnWatchNextResults")
                    ?.optJSONObject("secondaryResults")
                    ?.optJSONObject("secondaryResults")
                if (secondaryResults != null) {
                    val seen = mutableSetOf(videoId)
                    collectVideosFromJson(secondaryResults, related, seen)
                }
            }

            VideoDetail(
                video = fallback.copy(
                    title = title,
                    channelId = channelId,
                    channelName = channelName,
                    channelAvatarUrl = channelAvatar,
                    viewCountText = viewCount,
                    description = description,
                ),
                description = description,
                subscriberCountText = subscriberText,
                likeCountText = likeText,
                relatedVideos = related.take(20),
            )
        }.getOrNull()

        if (parsed != null) return@withContext parsed

        // Fallback: oEmbed + search related
        val related = runCatching {
            searchVideos(fallback.channelName.ifBlank { fallback.title }).filter { it.id != videoId }.take(12)
        }.getOrDefault(emptyList())

        VideoDetail(
            video = fallback,
            description = fallback.description,
            subscriberCountText = "",
            likeCountText = "",
            relatedVideos = related,
        )
    }

    private fun executeGet(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            )
            .header("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Cookie", "CONSENT=YES+cb; PREF=hl=vi&gl=VN")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            return response.body?.string() ?: throw IllegalStateException("Empty response")
        }
    }

    private fun extractBalancedJson(html: String, marker: String): String? {
        val markerIdx = html.indexOf(marker)
        if (markerIdx == -1) return null
        var startIdx = html.indexOf('{', markerIdx + marker.length)
        if (startIdx == -1) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (i in startIdx until html.length) {
            val c = html[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '\\' && inString) {
                escaped = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (c == '{') {
                    depth++
                } else if (c == '}') {
                    depth--
                    if (depth == 0) {
                        return html.substring(startIdx, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun collectVideosFromJson(
        node: Any?,
        out: MutableList<VideoItem>,
        seenIds: MutableSet<String>,
    ) {
        if (out.size >= 40) return
        when (node) {
            is JSONObject -> {
                val videoRenderer = node.optJSONObject("videoRenderer")
                    ?: node.optJSONObject("compactVideoRenderer")
                    ?: node.optJSONObject("gridVideoRenderer")
                if (videoRenderer != null) {
                    parseVideoRenderer(videoRenderer)?.let { item ->
                        if (seenIds.add(item.id)) {
                            out.add(item)
                        }
                    }
                    return
                }

                val lockup = node.optJSONObject("lockupViewModel")
                if (lockup != null) {
                    parseLockupViewModel(lockup)?.let { item ->
                        if (seenIds.add(item.id)) {
                            out.add(item)
                        }
                    }
                    return
                }

                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    collectVideosFromJson(node.opt(key), out, seenIds)
                }
            }

            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectVideosFromJson(node.opt(i), out, seenIds)
                }
            }
        }
    }

    private fun parseVideoRenderer(obj: JSONObject): VideoItem? {
        val videoId = obj.optString("videoId")
        if (videoId.isBlank()) return null

        val title = readText(obj.optJSONObject("title"))
        if (title.isBlank()) return null

        val ownerObj = obj.optJSONObject("ownerText")
            ?: obj.optJSONObject("longBylineText")
            ?: obj.optJSONObject("shortBylineText")
        val channelName = readText(ownerObj)
        val channelId = ownerObj
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId")
            .orEmpty()

        val channelAvatarUrl = obj.optJSONObject("channelThumbnailSupportedRenderers")
            ?.optJSONObject("channelThumbnailWithLinkRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
            ?.let { arr -> if (arr.length() > 0) arr.optJSONObject(0)?.optString("url") else null }
            .orEmpty()

        val thumbs = obj.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val thumbUrl = if (thumbs != null && thumbs.length() > 0) {
            thumbs.optJSONObject(thumbs.length() - 1)?.optString("url")
                ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        } else {
            "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        }

        val viewCountText = readText(obj.optJSONObject("shortViewCountText"))
            .ifBlank { readText(obj.optJSONObject("viewCountText")) }
        val publishedText = readText(obj.optJSONObject("publishedTimeText"))
        val durationText = readText(obj.optJSONObject("lengthText"))

        return VideoItem(
            id = videoId,
            title = title,
            channelId = channelId,
            channelName = channelName.ifBlank { "YouTube" },
            channelAvatarUrl = normalizeUrl(channelAvatarUrl),
            thumbnailUrl = normalizeUrl(thumbUrl),
            viewCountText = viewCountText,
            publishedText = publishedText,
            durationText = durationText,
        )
    }

    private fun parseLockupViewModel(obj: JSONObject): VideoItem? {
        val videoId = obj.optString("contentId")
        if (videoId.isBlank() || videoId.length != 11) return null

        val metadata = obj.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
        val title = metadata?.optJSONObject("title")?.optString("content").orEmpty()
        if (title.isBlank()) return null

        var channelName = ""
        var views = ""
        var published = ""
        val metaRows = metadata?.optJSONObject("metadata")
            ?.optJSONObject("contentMetadataViewModel")
            ?.optJSONArray("metadataRows")
        if (metaRows != null) {
            for (i in 0 until metaRows.length()) {
                val parts = metaRows.optJSONObject(i)?.optJSONArray("metadataParts") ?: continue
                for (j in 0 until parts.length()) {
                    val partText = parts.optJSONObject(j)?.optJSONObject("text")?.optString("content").orEmpty()
                    if (partText.isBlank()) continue
                    if (i == 0 && channelName.isBlank()) {
                        channelName = partText
                    } else if (views.isBlank() && (partText.contains("lượt xem", true) || partText.contains("views", true))) {
                        views = partText
                    } else if (published.isBlank()) {
                        published = partText
                    }
                }
            }
        }

        val channelId = metadata?.optJSONObject("image")
            ?.optJSONObject("decoratedAvatarViewModel")
            ?.optJSONObject("rendererContext")
            ?.optJSONObject("commandContext")
            ?.optJSONObject("onTap")
            ?.optJSONObject("innertubeCommand")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId")
            .orEmpty()

        val avatarUrl = metadata?.optJSONObject("image")
            ?.optJSONObject("decoratedAvatarViewModel")
            ?.optJSONObject("avatar")
            ?.optJSONObject("avatarViewModel")
            ?.optJSONObject("image")
            ?.optJSONArray("sources")
            ?.optJSONObject(0)
            ?.optString("url")
            .orEmpty()

        val thumbUrl = obj.optJSONObject("contentImage")
            ?.optJSONObject("thumbnailViewModel")
            ?.optJSONObject("image")
            ?.optJSONArray("sources")
            ?.let { arr -> if (arr.length() > 0) arr.optJSONObject(arr.length() - 1)?.optString("url") else null }
            ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

        var duration = ""
        val overlays = obj.optJSONObject("contentImage")
            ?.optJSONObject("thumbnailViewModel")
            ?.optJSONArray("overlays")
        if (overlays != null) {
            for (i in 0 until overlays.length()) {
                val badges = overlays.optJSONObject(i)
                    ?.optJSONObject("thumbnailOverlayBadgeViewModel")
                    ?.optJSONArray("thumbnailBadges") ?: continue
                for (j in 0 until badges.length()) {
                    val text = badges.optJSONObject(j)
                        ?.optJSONObject("thumbnailBadgeViewModel")
                        ?.optString("text")
                        .orEmpty()
                    if (text.contains(":")) {
                        duration = text
                    }
                }
            }
        }

        return VideoItem(
            id = videoId,
            title = title,
            channelId = channelId,
            channelName = channelName.ifBlank { "YouTube" },
            channelAvatarUrl = normalizeUrl(avatarUrl),
            thumbnailUrl = normalizeUrl(thumbUrl),
            viewCountText = views,
            publishedText = published,
            durationText = duration,
        )
    }

    private data class WatchMeta(
        val subscriberText: String = "",
        val likeText: String = "",
        val channelAvatar: String = "",
        val description: String = "",
    )

    private fun extractWatchMetadata(root: JSONObject): WatchMeta {
        var subscriber = ""
        var like = ""
        var avatar = ""
        var desc = ""

        val contents = root
            .optJSONObject("contents")
            ?.optJSONObject("twoColumnWatchNextResults")
            ?.optJSONObject("results")
            ?.optJSONObject("results")
            ?.optJSONArray("contents") ?: return WatchMeta()

        for (i in 0 until contents.length()) {
            val item = contents.optJSONObject(i) ?: continue
            val primary = item.optJSONObject("videoPrimaryInfoRenderer")
            if (primary != null) {
                val buttons = primary.optJSONObject("videoActions")
                    ?.optJSONObject("menuRenderer")
                    ?.optJSONArray("topLevelButtons")
                if (buttons != null) {
                    for (b in 0 until buttons.length()) {
                        val likeVm = buttons.optJSONObject(b)
                            ?.optJSONObject("segmentedLikeDislikeButtonViewModel")
                            ?.optJSONObject("likeButtonViewModel")
                            ?.optJSONObject("likeButtonViewModel")
                            ?.optJSONObject("toggleButtonViewModel")
                            ?.optJSONObject("toggleButtonViewModel")
                            ?.optJSONObject("defaultButtonViewModel")
                            ?.optJSONObject("buttonViewModel")
                        val likeTitle = likeVm?.optString("title").orEmpty()
                        if (likeTitle.isNotBlank()) like = likeTitle
                    }
                }
            }
            val secondary = item.optJSONObject("videoSecondaryInfoRenderer")
            if (secondary != null) {
                val owner = secondary.optJSONObject("owner")?.optJSONObject("videoOwnerRenderer")
                if (owner != null) {
                    subscriber = readText(owner.optJSONObject("subscriberCountText"))
                    avatar = owner.optJSONObject("thumbnail")
                        ?.optJSONArray("thumbnails")
                        ?.let { arr -> if (arr.length() > 0) arr.optJSONObject(arr.length() - 1)?.optString("url") else null }
                        .orEmpty()
                }
                val attrDesc = secondary.optJSONObject("attributedDescription")?.optString("content").orEmpty()
                if (attrDesc.isNotBlank()) desc = attrDesc
            }
        }
        return WatchMeta(
            subscriberText = subscriber,
            likeText = like,
            channelAvatar = normalizeUrl(avatar),
            description = desc,
        )
    }

    private fun parseAtomFeed(xml: String, channelId: String): List<VideoItem> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        val items = mutableListOf<VideoItem>()
        var inEntry = false
        var videoId = ""
        var title = ""
        var author = ""
        var published = ""
        var thumb = ""
        var description = ""
        var views = ""
        var currentTag = ""

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    if (currentTag == "entry") {
                        inEntry = true
                        videoId = ""
                        title = ""
                        author = ""
                        published = ""
                        thumb = ""
                        description = ""
                        views = ""
                    } else if (inEntry && currentTag == "media:thumbnail") {
                        thumb = parser.getAttributeValue(null, "url").orEmpty()
                    } else if (inEntry && currentTag == "media:statistics") {
                        val raw = parser.getAttributeValue(null, "views")?.toLongOrNull()
                        if (raw != null) {
                            views = "${formatCountVi(raw)} lượt xem"
                        }
                    }
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (inEntry && text.isNotEmpty()) {
                        when (currentTag) {
                            "yt:videoId" -> videoId = text
                            "title" -> if (title.isEmpty()) title = text
                            "name" -> if (author.isEmpty()) author = text
                            "published" -> published = formatRelativeTimeVi(text)
                            "media:description" -> description = text
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "entry" && inEntry) {
                        inEntry = false
                        if (videoId.isNotBlank() && title.isNotBlank()) {
                            items.add(
                                VideoItem(
                                    id = videoId,
                                    title = title,
                                    channelId = channelId,
                                    channelName = author.ifBlank { "YouTube" },
                                    thumbnailUrl = thumb.ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" },
                                    viewCountText = views,
                                    publishedText = published,
                                    description = description,
                                ),
                            )
                        }
                    }
                    currentTag = ""
                }
            }
            eventType = parser.next()
        }
        return items
    }

    private fun readText(obj: JSONObject?): String {
        if (obj == null) return ""
        val simple = obj.optString("simpleText")
        if (simple.isNotBlank()) return simple
        val runs = obj.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        }
        return sb.toString()
    }

    private fun normalizeUrl(url: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            else -> url
        }
    }

    private fun formatCountVi(count: Long?): String {
        if (count == null) return ""
        return when {
            count >= 1_000_000_000L -> String.format(Locale.US, "%.1f Tỷ", count / 1_000_000_000.0)
            count >= 1_000_000L -> String.format(Locale.US, "%.1f Tr", count / 1_000_000.0)
            count >= 1_000L -> "${count / 1_000L} N"
            else -> count.toString()
        }
    }

    private fun formatRelativeTimeVi(isoDate: String): String {
        return runCatching {
            val clean = isoDate.replace("Z", "+00:00")
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val time = sdf.parse(clean)?.time ?: return isoDate.take(10)
            val diffMinutes = (System.currentTimeMillis() - time) / 60_000L
            when {
                diffMinutes < 60 -> "${diffMinutes.coerceAtLeast(1)} phút trước"
                diffMinutes < 60 * 24 -> "${diffMinutes / 60} giờ trước"
                diffMinutes < 60 * 24 * 30 -> "${diffMinutes / (60 * 24)} ngày trước"
                diffMinutes < 60 * 24 * 365 -> "${diffMinutes / (60 * 24 * 30)} tháng trước"
                else -> "${diffMinutes / (60 * 24 * 365)} năm trước"
            }
        }.getOrElse { isoDate.take(10) }
    }
}

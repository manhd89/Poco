package com.luxysiv.puretube.data.repository

import com.luxysiv.puretube.data.model.HomeFeedPage
import com.luxysiv.puretube.data.model.MixPlaylist
import com.luxysiv.puretube.data.model.PlaylistItem
import com.luxysiv.puretube.data.model.PlaylistPage
import com.luxysiv.puretube.data.model.SearchItem
import com.luxysiv.puretube.data.model.SearchPage
import com.luxysiv.puretube.data.model.VideoItem
import com.luxysiv.puretube.error.getOrElse
import com.luxysiv.puretube.error.runAppCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Feed danh mục công khai của YouTube, lấy bằng InnerTube `browse`
 * — không dùng tìm kiếm và không cần API key.
 */
sealed interface YouTubeBrowseFeed {

    /** Bảng xếp hạng của YouTube Music (khu vực Việt Nam). */
    data object MusicCharts : YouTubeBrowseFeed

    /** Một trang đích/browse id công khai của YouTube. */
    data class Browse(
        val browseId: String,
        val params: String? = null
    ) : YouTubeBrowseFeed
}

/**
 * Một trang feed danh mục kèm token continuation.
 *
 * [HomeFeedPage] chỉ lộ `hasMore` cho UI; repository cần token thật để nối
 * trang kế tiếp nên dùng kiểu nội bộ này.
 */
internal data class CategoryFeedPage(
    val videos: List<VideoItem> = emptyList(),
    val nextToken: String = ""
) {
    val hasMore: Boolean get() = nextToken.isNotBlank()
}

/**
 * Nguồn dữ liệu YouTube TRỰC TUYẾN, không cần API key.
 *
 * Trình tự:
 *  1. Gọi thẳng InnerTube API (cùng endpoint mà youtube.com dùng) -> JSON gọn, ổn định.
 *  2. Dự phòng: tải trang kết quả của YouTube rồi parse `ytInitialData`.
 *  3. Dự phòng cuối: lấy metadata thật của video qua oEmbed.
 *
 * Không còn phụ thuộc Invidious/Piped (hiện tất cả instance đều chết: 403/401/502).
 */
object YouTubeWebDataSource {

    private const val RESULTS_URL = "https://www.youtube.com/results?search_query="

    // Thông tin client của InnerTube, lấy 1 lần từ trang chủ youtube.com
    @Volatile
    private var apiKey: String? = null

    @Volatile
    private var clientVersion: String? = null

    // Client riêng của YouTube Music (WEB_REMIX) cho bảng xếp hạng nhạc
    @Volatile
    private var musicApiKey: String? = null

    @Volatile
    private var musicClientVersion: String? = null

    // Trang chủ tải nhiều feed song song: chỉ request cấu hình client một lần.
    private val configLock = Any()
    private val musicConfigLock = Any()

    // ------------------------------------------------------------------
    // API công khai
    // ------------------------------------------------------------------

    /**
     * Tìm kiếm video trên YouTube (thực sự online).
     *
     * Trả về danh sách (có thể rỗng) khi ít nhất một nguồn parse thành công.
     * Ném lỗi khi mọi nguồn đều thất bại (mạng/cấu hình/parse/response lỗi) để
     * tầng trên phân biệt "không có kết quả" với lỗi.
     */
    suspend fun search(query: String, limit: Int = 25): List<VideoItem> =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return@withContext emptyList()

            val fromApi = runAppCatching { innerTubeSearch(trimmed) }.getOrNull()
            if (fromApi != null) return@withContext fromApi.take(limit)

            val fromPage = runAppCatching { scrapeSearchPage(trimmed) }.getOrNull()
            if (fromPage != null) return@withContext fromPage.take(limit)

            throw IllegalStateException("Search failed: all sources unavailable")
        }

    /**
     * Tìm kiếm đầy đủ, giữ đúng thứ tự video/playlist của YouTube.
     *
     * Cùng quy ước lỗi với [search]: trả về trang (có thể rỗng) khi ít nhất
     * một nguồn parse thành công, ném lỗi khi mọi nguồn đều thất bại.
     */
    suspend fun searchPage(query: String, limit: Int = 25): SearchPage =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return@withContext SearchPage()

            val fromApi = runAppCatching { innerTubeSearchPage(trimmed) }.getOrNull()
            if (fromApi != null) return@withContext fromApi.take(limit)

            val fromPage = runAppCatching { scrapeSearchPageItems(trimmed) }.getOrNull()
            if (fromPage != null) return@withContext fromPage.take(limit)

            throw IllegalStateException("Search failed: all sources unavailable")
        }

    /**
     * Tải một trang video của playlist qua InnerTube `browseId=VL...` hoặc
     * `continuation` cho các trang sau.
     *
     * Trả `null` khi không kết nối được hoặc YouTube trả lỗi.
     */
    suspend fun playlistPage(
        playlistId: String,
        continuation: String? = null
    ): PlaylistPage? = withContext(Dispatchers.IO) {
        playlistPageBlocking(playlistId, continuation)
    }

    /**
     * Tải một feed danh mục bằng InnerTube `browse` (không dùng tìm kiếm).
     *
     * Trả về `null` khi không kết nối được hoặc YouTube trả lỗi; trả về danh
     * sách rỗng khi request thành công nhưng feed không có video.
     */
    suspend fun categoryVideos(feed: YouTubeBrowseFeed): List<VideoItem>? =
        categoryPage(feed)?.videos

    /**
     * Một trang feed danh mục. [continuation] rỗng nghĩa là tải trang đầu; có
     * token nghĩa là tải trang kế tiếp của chính feed đó.
     */
    suspend fun categoryPage(
        feed: YouTubeBrowseFeed,
        continuation: String? = null
    ): HomeFeedPage? = categoryPageWithToken(feed, continuation)?.let { page ->
        HomeFeedPage(videos = page.videos, hasMore = page.hasMore)
    }

    /** Như [categoryPage] nhưng giữ token để repository nối các trang. */
    internal suspend fun categoryPageWithToken(
        feed: YouTubeBrowseFeed,
        continuation: String? = null
    ): CategoryFeedPage? = withContext(Dispatchers.IO) {
        when (feed) {
            YouTubeBrowseFeed.MusicCharts -> musicChartPage(continuation)
            is YouTubeBrowseFeed.Browse -> browsePage(
                browseId = feed.browseId,
                params = feed.params,
                continuation = continuation
            )
        }
    }

    // ------------------------------------------------------------------
    // InnerTube (youtubei/v1/search)
    // ------------------------------------------------------------------

    /** @return danh sách đã parse (có thể rỗng) hoặc `null` nếu nguồn thất bại. */
    private fun innerTubeSearch(query: String): List<VideoItem>? =
        innerTubeSearchRoot(query)?.let { collectVideos(it) }

    /** @return trang kết quả đã parse hoặc `null` nếu nguồn thất bại. */
    private fun innerTubeSearchPage(query: String): SearchPage? =
        innerTubeSearchRoot(query)?.let { SearchPage(collectSearchItems(it)) }

    private fun innerTubeSearchRoot(query: String): JSONObject? {
        if (!ensureClientConfig()) return null
        val key = apiKey ?: return null
        val version = clientVersion ?: return null

        val payload = JSONObject()
            .put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject()
                        .put("clientName", "WEB")
                        .put("clientVersion", version)
                        .put("hl", "vi")
                        .put("gl", "VN")
                )
            )
            .put("query", query)
            .toString()

        val body = InnerTubeHttp.post(
            url = "https://www.youtube.com/youtubei/v1/search?key=$key&prettyPrint=false",
            body = payload,
            userAgent = InnerTubeHttp.DESKTOP_USER_AGENT
        ) ?: return null
        val root = InnerTubeJson.parseObject(body) ?: return null
        if (root.has("error")) return null
        return root
    }

    // ------------------------------------------------------------------
    // Feed danh mục (InnerTube browse)
    // ------------------------------------------------------------------

    private fun browsePage(
        browseId: String,
        params: String?,
        continuation: String?
    ): CategoryFeedPage? {
        if (browseId.isBlank() && continuation.isNullOrBlank()) return null
        if (!ensureClientConfig()) return null
        val key = apiKey ?: return null
        val version = clientVersion ?: return null

        val root = if (continuation.isNullOrBlank()) {
            browseWithClient(
                browseId = browseId,
                params = params,
                key = key,
                clientName = "WEB",
                clientVersion = version,
                clientHeaderId = "1"
            )
        } else {
            browseContinuationWithClient(
                continuation = continuation,
                key = key,
                clientName = "WEB",
                clientVersion = version,
                clientHeaderId = "1"
            )
        } ?: return null
        return parseCategoryPage(root)
    }

    /** Tách riêng để test bằng JSON mẫu: video + continuation token. */
    internal fun parseCategoryPage(root: JSONObject): CategoryFeedPage = CategoryFeedPage(
        videos = parseBrowseVideos(root),
        nextToken = firstContinuationToken(root)
    )

    /**
     * Trang video của một playlist (`browseId=VL...`) hoặc trang continuation.
     *
     * Playlist detail dùng cùng endpoint `browse` với feed danh mục nhưng trả
     * `lockupViewModel` video và một `continuationCommand.token` ở cuối.
     */
    internal fun playlistPageBlocking(
        playlistId: String,
        continuation: String? = null
    ): PlaylistPage? {
        if (playlistId.isBlank() && continuation.isNullOrBlank()) return null
        if (!ensureClientConfig()) return null
        val key = apiKey ?: return null
        val version = clientVersion ?: return null

        val payload = JSONObject()
            .put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject()
                        .put("clientName", "WEB")
                        .put("clientVersion", version)
                        .put("hl", "vi")
                        .put("gl", "VN")
                )
            )
        if (!continuation.isNullOrBlank()) {
            payload.put("continuation", continuation)
        } else {
            payload.put("browseId", if (playlistId.startsWith("VL")) playlistId else "VL$playlistId")
        }

        val body = postInnerTube(
            path = "/youtubei/v1/browse",
            key = key,
            payload = payload,
            clientHeaderId = "1",
            clientVersion = version
        ) ?: return null
        val root = InnerTubeJson.parseObject(body) ?: return null
        if (root.has("error")) return null
        return parsePlaylistPage(root)
    }

    /** Tách riêng để test bằng JSON mẫu: video lockup + continuation token. */
    internal fun parsePlaylistPage(root: JSONObject): PlaylistPage {
        val videos = ArrayList<VideoItem>()

        fun scan(node: Any?) {
            when (node) {
                is JSONObject -> {
                    val lockup = node.optJSONObject("lockupViewModel")
                    if (lockup != null) {
                        InnerTubeVideoMapping.lockupVideoItem(lockup)?.let(videos::add)
                        return
                    }
                    val renderer = node.optJSONObject("playlistVideoRenderer")
                        ?: node.optJSONObject("videoRenderer")
                        ?: node.optJSONObject("gridVideoRenderer")
                    if (renderer != null) {
                        InnerTubeVideoMapping.rendererVideoItem(renderer)?.let(videos::add)
                        return
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) scan(node.opt(keys.next()))
                }
                is JSONArray -> for (i in 0 until node.length()) scan(node.opt(i))
            }
        }

        scan(root)
        return PlaylistPage(
            videos = videos.distinctBy { it.id },
            nextToken = firstContinuationToken(root)
        )
    }

    /**
     * Token continuation đầu tiên trong cây JSON.
     *
     * Ưu tiên `continuationItemRenderer` (nút "tải thêm" của danh sách) rồi
     * mới đến mọi `continuationCommand.token` khác.
     */
    private fun firstContinuationToken(root: JSONObject): String {
        InnerTubeJson.firstObjectNamed(root, "continuationItemRenderer")
            ?.optJSONObject("continuationEndpoint")
            ?.optJSONObject("continuationCommand")
            ?.optString("token")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return InnerTubeJson.firstObjectNamed(root, "continuationCommand")
            ?.optString("token")
            .orEmpty()
    }

    /**
     * Bảng xếp hạng nhạc: đọc hub `FEmusic_charts`, lấy playlist chart đầu tiên
     * (Trending của khu vực) rồi tải danh sách bài hát trong playlist đó.
     */
    private fun musicChartPage(continuation: String?): CategoryFeedPage? {
        if (!ensureMusicClientConfig()) return null
        val key = musicApiKey ?: return null
        val version = musicClientVersion ?: return null

        if (!continuation.isNullOrBlank()) {
            val root = browseContinuationWithClient(
                continuation = continuation,
                key = key,
                clientName = "WEB_REMIX",
                clientVersion = version,
                clientHeaderId = "67"
            ) ?: return null
            return parseMusicCategoryPage(root)
        }

        val charts = browseWithClient(
            browseId = "FEmusic_charts",
            params = null,
            key = key,
            clientName = "WEB_REMIX",
            clientVersion = version,
            clientHeaderId = "67"
        ) ?: return null

        val playlistId = findFirstChartPlaylistId(charts) ?: return CategoryFeedPage()
        val playlist = browseWithClient(
            browseId = playlistId,
            params = null,
            key = key,
            clientName = "WEB_REMIX",
            clientVersion = version,
            clientHeaderId = "67"
        ) ?: return null
        return parseMusicCategoryPage(playlist)
    }

    /** Tách riêng để test bằng JSON mẫu. */
    internal fun parseMusicCategoryPage(root: JSONObject): CategoryFeedPage = CategoryFeedPage(
        videos = parseMusicChart(root),
        nextToken = firstContinuationToken(root)
    )

    private fun browseWithClient(
        browseId: String,
        params: String?,
        key: String,
        clientName: String,
        clientVersion: String,
        clientHeaderId: String
    ): JSONObject? {
        val payload = browsePayload(clientName, clientVersion)
            .put("browseId", browseId)
        if (!params.isNullOrBlank()) payload.put("params", params)
        return postBrowse(payload, key, clientVersion, clientHeaderId)
    }

    /** Trang kế tiếp của một feed: chỉ gửi `continuation`, không gửi `browseId`. */
    private fun browseContinuationWithClient(
        continuation: String,
        key: String,
        clientName: String,
        clientVersion: String,
        clientHeaderId: String
    ): JSONObject? {
        val payload = browsePayload(clientName, clientVersion)
            .put("continuation", continuation)
        return postBrowse(payload, key, clientVersion, clientHeaderId)
    }

    private fun browsePayload(clientName: String, clientVersion: String): JSONObject =
        JSONObject().put(
            "context",
            JSONObject().put(
                "client",
                JSONObject()
                    .put("clientName", clientName)
                    .put("clientVersion", clientVersion)
                    .put("hl", "vi")
                    .put("gl", "VN")
            )
        )

    private fun postBrowse(
        payload: JSONObject,
        key: String,
        clientVersion: String,
        clientHeaderId: String
    ): JSONObject? {
        val body = postInnerTube(
            path = "/youtubei/v1/browse",
            key = key,
            payload = payload,
            clientHeaderId = clientHeaderId,
            clientVersion = clientVersion
        ) ?: return null

        val root = InnerTubeJson.parseObject(body) ?: return null
        if (root.has("error")) return null
        return root
    }

    private fun postInnerTube(
        path: String,
        key: String,
        payload: JSONObject,
        clientHeaderId: String,
        clientVersion: String
    ): String? = InnerTubeHttp.post(
        url = "https://www.youtube.com$path?key=$key&prettyPrint=false",
        body = payload.toString(),
        userAgent = InnerTubeHttp.DESKTOP_USER_AGENT,
        extraHeaders = mapOf(
            "X-Youtube-Client-Name" to clientHeaderId,
            "X-Youtube-Client-Version" to clientVersion
        )
    )

    /** Playlist chart đầu tiên trong hub charts (browseId dạng `VL...`). */
    private fun findFirstChartPlaylistId(root: JSONObject): String? {
        var found: String? = null

        fun scan(node: Any?) {
            if (found != null) return
            when (node) {
                is JSONObject -> {
                    val item = node.optJSONObject("musicTwoRowItemRenderer")
                    if (item != null) {
                        val title = InnerTubeJson.text(item.optJSONObject("title")).orEmpty()
                        val browseId = item.optJSONObject("navigationEndpoint")
                            ?.optJSONObject("browseEndpoint")
                            ?.optString("browseId")
                            .orEmpty()
                        if (title.isNotBlank() && browseId.startsWith("VL")) {
                            found = browseId
                            return
                        }
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) scan(node.opt(keys.next()))
                }
                is JSONArray -> for (i in 0 until node.length()) scan(node.opt(i))
            }
        }

        scan(root)
        return found
    }

    /** Tách riêng để test được bằng JSON mẫu (không cần mạng). */
    internal fun parseBrowseVideos(root: JSONObject): List<VideoItem> = collectVideos(root)

    /** Parse danh sách bài hát của một playlist chart YouTube Music. */
    internal fun parseMusicChart(root: JSONObject): List<VideoItem> {
        val out = ArrayList<VideoItem>()

        fun scan(node: Any?) {
            when (node) {
                is JSONObject -> {
                    node.optJSONObject("musicResponsiveListItemRenderer")
                        ?.let { toMusicVideoItem(it) }
                        ?.let(out::add)
                    val keys = node.keys()
                    while (keys.hasNext()) scan(node.opt(keys.next()))
                }
                is JSONArray -> for (i in 0 until node.length()) scan(node.opt(i))
            }
        }

        scan(root)
        return out.distinctBy { it.id }
    }

    private fun toMusicVideoItem(renderer: JSONObject): VideoItem? {
        val watchEndpoint = renderer.optJSONObject("overlay")
            ?.optJSONObject("musicItemThumbnailOverlayRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("musicPlayButtonRenderer")
            ?.optJSONObject("playNavigationEndpoint")
            ?.optJSONObject("watchEndpoint")
        val id = watchEndpoint?.optString("videoId").orEmpty()
        if (id.length != 11) return null

        val title = flexColumnText(renderer, 0) ?: return null
        val channel = flexColumnText(renderer, 1) ?: "YouTube"
        val duration = renderer.optJSONArray("fixedColumns")
            ?.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
            ?.let { InnerTubeJson.text(it.optJSONObject("text")) }
            ?.takeIf { it.isNotBlank() }
            ?: "--:--"
        val thumbnailUrl = renderer.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.let { InnerTubeJson.bestThumbnailUrl(it) }
            .orEmpty()

        return VideoItem(
            id = id,
            title = title,
            channelTitle = channel,
            duration = duration,
            thumbnailUrl = thumbnailUrl.ifBlank {
                "https://i.ytimg.com/vi/$id/hqdefault.jpg"
            },
            category = "Âm nhạc"
        )
    }

    private fun flexColumnText(renderer: JSONObject, index: Int): String? =
        renderer.optJSONArray("flexColumns")
            ?.optJSONObject(index)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.let { InnerTubeJson.text(it.optJSONObject("text")) }
            ?.takeIf { it.isNotBlank() }

    /** Lấy API key + client version từ trang youtube.com (chỉ cần làm 1 lần). */
    private fun ensureClientConfig(): Boolean {
        apiKey?.let { key ->
            if (!clientVersion.isNullOrBlank() && key.isNotBlank()) return true
        }
        synchronized(configLock) {
            apiKey?.let { key ->
                if (!clientVersion.isNullOrBlank() && key.isNotBlank()) return true
            }
            val html = InnerTubeHttp.get("https://www.youtube.com/") ?: return false
            val key = Regex("\"INNERTUBE_API_KEY\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            val version = Regex("\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)\"")
                .find(html)?.groupValues?.get(1)
            if (key.isNullOrBlank() || version.isNullOrBlank()) return false
            apiKey = key
            clientVersion = version
            return true
        }
    }

    /** Lấy API key + client version của YouTube Music (chỉ cần làm 1 lần). */
    private fun ensureMusicClientConfig(): Boolean {
        musicApiKey?.let { key ->
            if (!musicClientVersion.isNullOrBlank() && key.isNotBlank()) return true
        }
        synchronized(musicConfigLock) {
            musicApiKey?.let { key ->
                if (!musicClientVersion.isNullOrBlank() && key.isNotBlank()) return true
            }
            val html = InnerTubeHttp.get("https://music.youtube.com/") ?: return false
            val key = Regex("\"INNERTUBE_API_KEY\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            val version = Regex("\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)\"")
                .find(html)?.groupValues?.get(1)
            if (key.isNullOrBlank() || version.isNullOrBlank()) return false
            musicApiKey = key
            musicClientVersion = version
            return true
        }
    }

    // ------------------------------------------------------------------
    // Trang watch của YouTube - Danh sách kết hợp
    // ------------------------------------------------------------------

    /**
     * Lấy một trang "Danh sách kết hợp" bằng cách tải trang watch của YouTube
     * và đọc `ytInitialData` — cùng nguồn dữ liệu mà trình phát web dùng.
     *
     * Không gọi InnerTube, không cần API key. Trang đầu dùng video đang phát
     * làm mốc; trang sau dùng video cuối đã biết. YouTube đặt video mốc ở đầu
     * danh sách nên bỏ nó đi ([includeAnchor] = false) để lấy 24 video kế tiếp.
     */
    internal fun mixPageBlocking(
        anchorVideoId: String,
        playlistId: String,
        includeAnchor: Boolean
    ): MixPlaylist? {
        if (anchorVideoId.isBlank() || playlistId.isBlank()) return null

        val url = "https://www.youtube.com/watch?v=$anchorVideoId&list=$playlistId"
        val html = InnerTubeHttp.get(url) ?: return null
        val json = extractInitialData(html) ?: return null
        return parseMixPlaylist(json, anchorVideoId, playlistId, includeAnchor)
    }

    /**
     * Tách riêng phần parse để test được bằng JSON mẫu (không cần mạng).
     *
     * [json] là nguyên `ytInitialData` của trang watch. Danh sách kết hợp nằm ở
     * `contents.twoColumnWatchNextResults.playlist.playlist`.
     */
    internal fun parseMixPlaylist(
        json: String,
        anchorVideoId: String,
        playlistId: String,
        includeAnchor: Boolean
    ): MixPlaylist? {
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return null
        }

        val playlist = root.optJSONObject("contents")
            ?.optJSONObject("twoColumnWatchNextResults")
            ?.optJSONObject("playlist")
            ?.optJSONObject("playlist")
            ?: return null

        val contents = playlist.optJSONArray("contents") ?: return null
        val items = ArrayList<VideoItem>(contents.length())
        for (i in 0 until contents.length()) {
            val renderer = contents.optJSONObject(i)
                ?.optJSONObject("playlistPanelVideoRenderer")
                ?: continue
            InnerTubeVideoMapping.playlistPanelVideoItem(renderer)?.let { items.add(it) }
        }

        val distinct = items.distinctBy { it.id }
        val anchorIndex = distinct.indexOfFirst { it.id == anchorVideoId }
        val videos = when {
            includeAnchor -> distinct
            anchorIndex >= 0 -> distinct.drop(anchorIndex + 1)
            else -> distinct
        }

        return MixPlaylist(
            id = playlist.optString("playlistId").ifBlank { playlistId },
            title = playlist.optString("title"),
            videos = videos,
            // Mix luôn "vô hạn"; dừng thật sự khi một trang không còn video mới.
            hasMore = videos.isNotEmpty()
        )
    }

    // ------------------------------------------------------------------
    // Dự phòng: parse trang kết quả của YouTube
    // ------------------------------------------------------------------

    /** @return danh sách đã parse (có thể rỗng) hoặc `null` nếu nguồn thất bại. */
    private fun scrapeSearchPage(query: String): List<VideoItem>? =
        scrapeSearchRoot(query)?.let { collectVideos(it) }

    private fun scrapeSearchPageItems(query: String): SearchPage? =
        scrapeSearchRoot(query)?.let { SearchPage(collectSearchItems(it)) }

    private fun scrapeSearchRoot(query: String): JSONObject? {
        val url = RESULTS_URL + URLEncoder.encode(query, "UTF-8")
        val html = InnerTubeHttp.get(url) ?: return null
        val json = extractInitialData(html) ?: return null
        return try {
            JSONObject(json)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Trích phần JSON của `var ytInitialData = {...}` (hoặc `window["ytInitialData"] = {...}`)
     * bằng cách đếm ngoặc vuông, có xét đến chuỗi ký tự và ký tự escape.
     */
    private fun extractInitialData(html: String): String? {
        val markerIndex = html.indexOf("ytInitialData")
        if (markerIndex < 0) return null
        val start = html.indexOf('{', markerIndex)
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '\\') {
                if (inString) escaped = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (inString) continue
            when (c) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return html.substring(start, i + 1)
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // Parse JSON -> List<VideoItem>
    // ------------------------------------------------------------------

    /**
     * Duyệt đệ quy và gom cả video lẫn playlist, giữ đúng thứ tự YouTube.
     *
     * Playlist nằm trong `lockupViewModel` (schema mới) hoặc
     * `playlistRenderer`/`gridPlaylistRenderer` (schema cũ). Khi gặp playlist,
     * không đi tiếp vào bên trong để các video xem trước (`childVideoRenderer`)
     * không bị thêm thành kết quả độc lập.
     */
    internal fun collectSearchItems(root: Any?): List<SearchItem> {
        val items = ArrayList<SearchItem>()
        val seen = HashSet<String>()

        fun addVideo(video: VideoItem) {
            if (video.id.isBlank()) return
            if (seen.add("video:${video.id}")) items.add(SearchItem.Video(video))
        }

        fun addPlaylist(playlist: PlaylistItem) {
            if (playlist.id.isBlank()) return
            if (seen.add("playlist:${playlist.id}")) items.add(SearchItem.Playlist(playlist))
        }

        fun scan(node: Any?) {
            when (node) {
                is JSONObject -> {
                    val lockup = node.optJSONObject("lockupViewModel")
                    if (lockup != null) {
                        val playlist = InnerTubeVideoMapping.playlistLockup(lockup)
                        if (playlist != null) {
                            addPlaylist(playlist)
                        } else {
                            InnerTubeVideoMapping.lockupVideoItem(lockup)?.let { addVideo(it) }
                        }
                        return
                    }

                    val playlistRenderer = node.optJSONObject("playlistRenderer")
                        ?: node.optJSONObject("gridPlaylistRenderer")
                    if (playlistRenderer != null) {
                        InnerTubeVideoMapping.playlistRendererItem(playlistRenderer)
                            ?.let { addPlaylist(it) }
                        return
                    }

                    val renderer = node.optJSONObject("videoRenderer")
                        ?: node.optJSONObject("videoWithContextRenderer")
                        ?: node.optJSONObject("gridVideoRenderer")
                        ?: node.optJSONObject("playlistVideoRenderer")
                    if (renderer != null) {
                        InnerTubeVideoMapping.rendererVideoItem(renderer)?.let { addVideo(it) }
                        return
                    }

                    val keys = node.keys()
                    while (keys.hasNext()) scan(node.opt(keys.next()))
                }
                is JSONArray -> for (i in 0 until node.length()) scan(node.opt(i))
            }
        }

        scan(root)
        return items
    }

    /**
     * Duyệt đệ quy toàn bộ JSON và gom mọi renderer chứa video: `videoRenderer`,
     * `videoWithContextRenderer`, `gridVideoRenderer` (Trò chơi) và
     * `playlistVideoRenderer`. Bọc rộng như vậy để không vỡ khi YouTube đổi
     * cấu trúc cây (mobile, shorts, shelf...).
     */
    private fun collectVideos(root: Any?): List<VideoItem> {
        val out = ArrayList<VideoItem>()
        walk(root, out)
        return out.filter { it.id.isNotBlank() }.distinctBy { it.id }
    }

    private fun walk(node: Any?, out: MutableList<VideoItem>) {
        when (node) {
            is JSONObject -> {
                val renderer = node.optJSONObject("videoRenderer")
                    ?: node.optJSONObject("videoWithContextRenderer")
                    ?: node.optJSONObject("gridVideoRenderer")
                    ?: node.optJSONObject("playlistVideoRenderer")
                if (renderer != null) {
                    InnerTubeVideoMapping.rendererVideoItem(renderer)?.let { out.add(it) }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    walk(node.opt(keys.next()), out)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    walk(node.opt(i), out)
                }
            }
        }
    }
}

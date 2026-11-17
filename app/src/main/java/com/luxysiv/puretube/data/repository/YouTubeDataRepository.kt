package com.luxysiv.puretube.data.repository

import com.luxysiv.puretube.data.model.CommentPage
import com.luxysiv.puretube.data.model.CommentSort
import com.luxysiv.puretube.data.model.HomeFeedPage
import com.luxysiv.puretube.data.model.MixPlaylist
import com.luxysiv.puretube.data.model.PlaybackData
import com.luxysiv.puretube.data.model.PlaylistPage
import com.luxysiv.puretube.data.model.SearchPage
import com.luxysiv.puretube.data.model.VideoCategory
import com.luxysiv.puretube.data.model.VideoDetails
import com.luxysiv.puretube.data.model.VideoItem
import com.luxysiv.puretube.error.AppError
import com.luxysiv.puretube.error.AppResult
import com.luxysiv.puretube.error.runAppCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Nguồn dữ liệu video của app.
 *
 * Nguồn dữ liệu:
 *  1. InnerTube `player` — luồng phát DASH/HLS/progressive và metadata cơ bản.
 *  2. InnerTube `next` — chi tiết video, video liên quan, bình luận.
 *  3. InnerTube `browse` — feed danh mục Trang chủ (không dùng tìm kiếm).
 *  4. InnerTube `search` + parse trang youtube.com — tìm kiếm dự phòng.
 *
 * Không còn danh sách video hard-code: khi ngoại tuyến, feed trả về lỗi để UI
 * hiển thị trạng thái ngoại tuyến và nút thử lại.
 *
 * Mọi API công khai trả [AppResult] thay vì `null`/danh sách rỗng, nhờ đó tầng
 * UI phân biệt được "không có dữ liệu" với lỗi mạng/dữ liệu hỏng.
 */
object YouTubeDataRepository {

    private const val FEED_CACHE_TTL_MS = 5 * 60 * 1000L
    private const val FEED_CACHE_MAX_ENTRIES = 8
    private val feedCache = ExpiringLruCache<VideoCategory, List<VideoItem>>(
        ttlMs = FEED_CACHE_TTL_MS,
        maxEntries = FEED_CACHE_MAX_ENTRIES
    )

    /**
     * Mỗi danh mục có một lock riêng để coalesce request đồng thời: tab Trang
     * chủ và các luồng khác có thể cùng xin một feed, chỉ request đầu tiên đi
     * mạng, các request sau dùng kết quả vừa cache.
     */
    private val feedLocks = ConcurrentHashMap<VideoCategory, Mutex>()

    /** Kết quả tải feed nội bộ: giữ cả trường hợp request thành công nhưng rỗng. */
    private data class FeedFetch(
        val videos: List<VideoItem>,
        val sessions: List<FeedSession>,
        val loaded: Boolean
    )

    /** Một feed nguồn còn trang kế tiếp; "Tất cả" giữ nhiều phiên cùng lúc. */
    private data class FeedSession(
        val feed: YouTubeBrowseFeed,
        val token: String
    )

    /**
     * Token continuation theo từng danh mục.
     *
     * Cache video ở [feedCache] chỉ giữ trang đầu; token nằm riêng để lần tải
     * thêm vẫn nối đúng vào feed đã tải, kể cả khi người dùng cuộn rất lâu.
     */
    private val feedSessions = ConcurrentHashMap<VideoCategory, List<FeedSession>>()

    /**
     * Feed công khai của từng tab Trang chủ.
     *
     * Tất cả đều là endpoint `browse` thật của YouTube, không dùng tìm kiếm:
     * - Âm nhạc: bảng xếp hạng YouTube Music (FEmusic_charts → playlist chart).
     * - Trò chơi: trang Gaming của YouTube, tab Thịnh hành.
     * - Tin tức / Học tập: trang đích `FEnews_destination` / `FElearning_destination`.
     *
     * Các danh mục không có feed công khai tương ứng (Công nghệ, Lofi, Podcast)
     * không còn trong [VideoCategory] để tránh giả lập bằng tìm kiếm.
     */
    private val CATEGORY_FEEDS: Map<VideoCategory, List<YouTubeBrowseFeed>> = mapOf(
        VideoCategory.MUSIC to listOf(YouTubeBrowseFeed.MusicCharts),
        VideoCategory.GAMING to listOf(
            YouTubeBrowseFeed.Browse(
                browseId = "FEgaming_destination",
                params = "Egh0cmVuZGluZw=="
            )
        ),
        VideoCategory.NEWS to listOf(
            YouTubeBrowseFeed.Browse(browseId = "FEnews_destination")
        ),
        VideoCategory.LEARNING to listOf(
            YouTubeBrowseFeed.Browse(browseId = "FElearning_destination")
        )
    )

    /**
     * API cũ: chỉ lấy danh sách video của trang đầu (dùng cho tìm kiếm/gợi ý).
     *
     * Không cập nhật cache/token phân trang của Trang chủ: màn hình tìm kiếm
     * gọi hàm này ở nền, nếu ghi đè thì feed đang cuộn dở sẽ bị rút về trang đầu.
     */
    suspend fun loadTrendingVideos(
        category: VideoCategory = VideoCategory.ALL,
        forceRefresh: Boolean = false
    ): AppResult<List<VideoItem>> =
        loadTrendingFeed(category, forceRefresh, updatePagination = false).map { it.videos }

    /**
     * Trang đầu feed Trang chủ — lấy từ các feed `browse` công khai của
     * YouTube, có cache 5 phút. "Tất cả" trộn mọi danh mục có feed.
     *
     * [updatePagination] = false dùng cho nơi chỉ cần danh sách video (tìm
     * kiếm/gợi ý); khi đó cache và token của feed Trang chủ được giữ nguyên.
     */
    suspend fun loadTrendingFeed(
        category: VideoCategory = VideoCategory.ALL,
        forceRefresh: Boolean = false,
        updatePagination: Boolean = true
    ): AppResult<HomeFeedPage> = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            cachedFeedPage(category)?.let { return@withContext AppResult.Success(it) }
        }

        // Coalesce các request đồng thời cho cùng danh mục. Kiểm tra cache lại
        // bên trong lock: request thứ hai (kể cả forceRefresh) dùng kết quả mà
        // request đầu vừa tải thay vì gọi mạng lần nữa.
        val lock = feedLocks.getOrPut(category) { Mutex() }
        lock.withLock {
            cachedFeedPage(category)?.let { return@withLock AppResult.Success(it) }

            val fetched = runAppCatching { fetchCategoryPages(category) }
            val fetch = fetched.getOrNull()
                ?: return@withLock AppResult.Failure(
                    fetched.errorOrNull() ?: AppError.Unknown()
                )

            if (!fetch.loaded) {
                return@withLock AppResult.Failure(AppError.Network())
            }
            if (updatePagination) {
                if (fetch.videos.isNotEmpty()) {
                    putFeedCache(category, fetch.videos)
                }
                feedSessions[category] = fetch.sessions
            }
            AppResult.Success(
                HomeFeedPage(
                    videos = fetch.videos,
                    hasMore = fetch.sessions.any { it.token.isNotBlank() }
                )
            )
        }
    }

    /**
     * Trang kế tiếp của feed Trang chủ.
     *
     * "Tất cả" tải continuation của mọi feed nguồn rồi trộn lại; danh mục đơn
     * chỉ có một token. Token của feed lỗi được giữ nguyên để lần thử lại sau
     * không bỏ sót kênh/danh mục.
     */
    suspend fun loadMoreTrendingFeed(
        category: VideoCategory = VideoCategory.ALL
    ): AppResult<HomeFeedPage> = withContext(Dispatchers.IO) {
        val lock = feedLocks.getOrPut(category) { Mutex() }
        lock.withLock {
            val sessions = feedSessions[category].orEmpty().filter { it.token.isNotBlank() }
            if (sessions.isEmpty()) {
                return@withLock AppResult.Success(HomeFeedPage())
            }

            val fetched = runAppCatching { fetchMoreCategoryPages(category, sessions) }
            val fetch = fetched.getOrNull()
                ?: return@withLock AppResult.Failure(
                    fetched.errorOrNull() ?: AppError.Unknown()
                )
            if (!fetch.loaded) {
                return@withLock AppResult.Failure(AppError.Network())
            }

            // Giữ cả các trang đã tải trong cache: khi người dùng đổi danh mục
            // rồi quay lại, UI không bị rút về trang đầu trong khi token đã đi
            // trước, tránh bỏ sót video giữa hai trang.
            val cached = cachedFeed(category).orEmpty()
            if (cached.isNotEmpty() && fetch.videos.isNotEmpty()) {
                putFeedCache(
                    category,
                    (cached + fetch.videos).distinctBy { it.id }
                )
            }
            feedSessions[category] = fetch.sessions
            AppResult.Success(
                HomeFeedPage(
                    videos = fetch.videos,
                    hasMore = fetch.sessions.any { it.token.isNotBlank() }
                )
            )
        }
    }

    /**
     * Tìm kiếm video.
     *
     * - Query rỗng: `Success(emptyList())`.
     * - Hoàn tất không có exception: `Success(videos)` (kể cả rỗng = không có kết quả khớp).
     * - Exception không bắt được từ tầng dưới: `Failure(...)`.
     *
     * Tránh gán nhãn sai "lỗi mạng" cho trường hợp tìm kiếm hợp lệ nhưng không có kết quả.
     */
    suspend fun searchVideosWithStatus(query: String): AppResult<List<VideoItem>> =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) {
                return@withContext AppResult.Success(emptyList())
            }

            // InnerTube `search`; nếu rỗng/lỗi đã xử lý bên trong thì parse trang youtube.com.
            runAppCatching { YouTubeWebDataSource.search(trimmed) }.fold(
                onSuccess = { videos -> AppResult.Success(videos) },
                onFailure = { AppResult.Failure(it) }
            )
        }

    /**
     * Tìm kiếm đầy đủ, giữ cả video lẫn playlist và đúng thứ tự YouTube.
     *
     * Cùng quy ước lỗi với [searchVideosWithStatus]: query rỗng và "không có
     * kết quả" đều là `Success`, chỉ lỗi mạng/parse mới là `Failure`.
     */
    suspend fun searchItemsWithStatus(query: String): AppResult<SearchPage> =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) {
                return@withContext AppResult.Success(SearchPage())
            }

            runAppCatching { YouTubeWebDataSource.searchPage(trimmed) }.fold(
                onSuccess = { page -> AppResult.Success(page) },
                onFailure = { AppResult.Failure(it) }
            )
        }

    /**
     * Một trang video của playlist (`browseId=VL<id>`) hoặc trang continuation.
     * Lỗi khi không tải được để UI phân biệt với playlist rỗng.
     */
    suspend fun getPlaylistPage(
        playlistId: String,
        continuation: String = ""
    ): AppResult<PlaylistPage> = withContext(Dispatchers.IO) {
        load { YouTubeWebDataSource.playlistPageBlocking(playlistId, continuation) }
    }

    /** Thông tin kênh và video mới nhất (tab "Video"). */
    suspend fun getChannelPage(channelId: String): AppResult<com.luxysiv.puretube.data.model.ChannelPage> =
        withContext(Dispatchers.IO) {
            load { ChannelPageDataSource.fetch(channelId) }
        }

    /** Tải thêm video của kênh bằng continuation token. */
    suspend fun getChannelPageMore(
        channelId: String,
        continuation: String
    ): AppResult<com.luxysiv.puretube.data.model.ChannelPage> = withContext(Dispatchers.IO) {
        load { ChannelPageDataSource.fetchMore(channelId, continuation) }
    }

    private fun cachedFeed(category: VideoCategory): List<VideoItem>? =
        feedCache.get(category)

    private fun putFeedCache(category: VideoCategory, videos: List<VideoItem>) {
        feedCache.put(category, videos)
    }

    /** Gợi ý tìm kiếm THẬT (YouTube autocomplete) — danh sách chữ như khi gõ trên youtube.com. */
    suspend fun getSearchSuggestions(query: String, limit: Int = 10): AppResult<List<String>> =
        runAppCatching { SearchSuggestionDataSource.suggestions(query, limit) }

    /**
     * Lấy kế hoạch phát (DASH tự dựng/HLS/progressive) và danh sách video liên
     * quan của video bằng InnerTube `player` + `next`.
     * Trả lỗi khi mọi client player đều không trả luồng dùng được.
     */
    suspend fun getPlaybackData(video: VideoItem): AppResult<PlaybackData> =
        withContext(Dispatchers.IO) {
            load {
                YouTubePlayerDataSource.playbackBlocking(video.id)?.let { player ->
                    // Metadata `next` là phần phụ: lỗi ở đây không làm mất luồng phát.
                    val metadata = runAppCatching {
                        YouTubeMetadataDataSource.fetchBlocking(video.id)
                    }.getOrNull()
                    PlaybackData(
                        plan = player.plan,
                        related = metadata?.related.orEmpty(),
                        details = mergeDetails(player.details, metadata?.details)
                    )
                }
            }
        }

    /** Thông tin chi tiết của video (mô tả đầy đủ...) khi khôi phục phiên xem. */
    suspend fun getVideoDetails(videoId: String): AppResult<VideoDetails> =
        withContext(Dispatchers.IO) {
            load {
                YouTubeMetadataDataSource.cachedBlocking(videoId)?.details
                    ?: YouTubeMetadataDataSource.fetchBlocking(videoId)?.details
            }
        }

    /** "Danh sách kết hợp" YouTube của video; lỗi khi không lấy được (dùng related làm dự phòng). */
    suspend fun getMixPlaylist(videoId: String): AppResult<MixPlaylist> =
        withContext(Dispatchers.IO) {
            load { YouTubeMixDataSource.mixPlaylistBlocking(videoId) }
        }

    /**
     * Mix của một video vừa xem để dựng kệ gợi ý ở trang chủ.
     *
     * Không đụng tới phiên mix của trình phát, nhờ vậy trang chủ có thể tải
     * nhiều mix nền mà không làm lệch nút Next/Previous của video đang phát.
     */
    suspend fun getHomeMix(videoId: String): AppResult<MixPlaylist> =
        withContext(Dispatchers.IO) {
            load { YouTubeMixDataSource.homeMixBlocking(videoId) }
        }

    /** Trang kế tiếp của mix đang phát (tối đa 25 video). */
    suspend fun getMoreMix(mixId: String): AppResult<MixPlaylist> =
        withContext(Dispatchers.IO) {
            load { YouTubeMixDataSource.moreMixBlocking(mixId) }
        }

    /**
     * Trang kế tiếp của một mix trong tab Dành cho bạn.
     *
     * Không dùng chung phiên mix của trình phát để cuộn trang chủ không làm
     * đổi danh sách mà nút Next/Previous đang phát.
     */
    suspend fun getMoreHomeMix(mixId: String, anchorVideoId: String): AppResult<MixPlaylist> =
        withContext(Dispatchers.IO) {
            load { YouTubeMixDataSource.moreHomeMixBlocking(mixId, anchorVideoId) }
        }

    /**
     * Một trang bình luận thật; lỗi khi không tải được (mạng/JSON).
     *
     * [sort] = null giữ thứ tự hiện tại của phiên; truyền [CommentSort] để đổi
     * cách sắp xếp và tải lại từ trang đầu.
     */
    suspend fun getComments(
        videoId: String,
        loadMore: Boolean,
        sort: CommentSort? = null
    ): AppResult<CommentPage> =
        withContext(Dispatchers.IO) {
            load { YouTubeCommentsDataSource.commentsBlocking(videoId, loadMore, sort) }
        }

    /** Phản hồi thật của một bình luận; lỗi khi không tải được (mạng/JSON). */
    suspend fun getCommentReplies(
        videoId: String,
        commentId: String,
        loadMore: Boolean
    ): AppResult<CommentPage> = withContext(Dispatchers.IO) {
        load {
            YouTubeCommentsDataSource.commentRepliesBlocking(videoId, commentId, loadMore)
        }
    }

    /**
     * Gọi một nguồn trả nullable rồi chuẩn hoá thành [AppResult].
     *
     * `null` nghĩa là nguồn đã trả lời nhưng không có dữ liệu dùng được; exception
     * được quy về [AppError] tương ứng. Cancellation luôn được ném lại.
     */
    private suspend fun <T : Any> load(block: suspend () -> T?): AppResult<T> =
        runAppCatching { block() }.fold(
            onSuccess = { value ->
                if (value == null) {
                    AppResult.Failure(AppError.Unavailable())
                } else {
                    AppResult.Success(value)
                }
            },
            onFailure = { AppResult.Failure(it) }
        )

    private fun cachedFeedPage(category: VideoCategory): HomeFeedPage? {
        val videos = cachedFeed(category) ?: return null
        return HomeFeedPage(
            videos = videos,
            hasMore = feedSessions[category].orEmpty().any { it.token.isNotBlank() }
        )
    }

    /** Tải trang đầu của một danh mục; `loaded = false` khi request thất bại. */
    private suspend fun fetchCategoryPages(category: VideoCategory): FeedFetch =
        if (category == VideoCategory.ALL) {
            fetchAllCategoryPages()
        } else {
            fetchSingleCategoryPage(category)
        }

    private suspend fun fetchSingleCategoryPage(category: VideoCategory): FeedFetch {
        val feeds = CATEGORY_FEEDS[category].orEmpty()
        if (feeds.isEmpty()) return FeedFetch(emptyList(), emptyList(), loaded = false)

        val pages = feeds.map { feed ->
            feed to YouTubeWebDataSource.categoryPageWithToken(feed)
        }
        return FeedFetch(
            videos = pages.mapNotNull { it.second?.videos }.flatten().distinctBy { it.id },
            sessions = pages.toSessions(),
            loaded = pages.any { it.second != null }
        )
    }

    /** "Tất cả": tải song song các feed danh mục rồi trộn xen kẽ. */
    private suspend fun fetchAllCategoryPages(): FeedFetch = coroutineScope {
        val feeds = CATEGORY_FEEDS.values.flatten()
        val pages = feeds.map { feed ->
            async { feed to YouTubeWebDataSource.categoryPageWithToken(feed) }
        }.awaitAll()

        FeedFetch(
            videos = interleave(pages.mapNotNull { it.second?.videos }).distinctBy { it.id },
            sessions = pages.toSessions(),
            loaded = pages.any { it.second != null }
        )
    }

    private suspend fun fetchMoreCategoryPages(
        category: VideoCategory,
        sessions: List<FeedSession>
    ): FeedFetch = coroutineScope {
        val pages = sessions.map { session ->
            async {
                session to YouTubeWebDataSource.categoryPageWithToken(
                    feed = session.feed,
                    continuation = session.token
                )
            }
        }.awaitAll()

        val nextSessions = ArrayList<FeedSession>(pages.size)
        val loadedPages = ArrayList<List<VideoItem>>(pages.size)
        for ((session, page) in pages) {
            if (page == null) {
                // Feed lỗi: giữ token cũ để lần tải sau thử lại đúng trang.
                nextSessions += session
                continue
            }
            loadedPages += page.videos
            page.nextToken.takeIf { it.isNotBlank() }?.let { token ->
                // Token không đổi mà trang rỗng: dừng để scroll không gọi lặp
                // lại cùng một trang mãi.
                if (token != session.token || page.videos.isNotEmpty()) {
                    nextSessions += FeedSession(session.feed, token)
                }
            }
        }

        val videos = if (category == VideoCategory.ALL) {
            interleave(loadedPages)
        } else {
            loadedPages.flatten()
        }
        FeedFetch(
            videos = videos.distinctBy { it.id },
            sessions = nextSessions,
            loaded = pages.any { it.second != null }
        )
    }

    private fun List<Pair<YouTubeBrowseFeed, CategoryFeedPage?>>.toSessions(): List<FeedSession> =
        mapNotNull { (feed, page) ->
            page?.nextToken?.takeIf { it.isNotBlank() }?.let { token ->
                FeedSession(feed, token)
            }
        }

    /**
     * Ghép metadata của `player` (mô tả, lượt xem) với `next` (ngày đăng,
     * lượt thích, người đăng ký, avatar). Trường nào nguồn phụ có dữ liệu thì
     * ưu tiên nguồn phụ.
     */
    private fun mergeDetails(base: VideoDetails?, extra: VideoDetails?): VideoDetails? {
        if (base == null) return extra
        if (extra == null) return base
        return VideoDetails(
            description = extra.description.ifBlank { base.description },
            viewCount = extra.viewCount.takeIf { it > 0 } ?: base.viewCount,
            likeCount = extra.likeCount.takeIf { it > 0 } ?: base.likeCount,
            publishedTime = extra.publishedTime.ifBlank { base.publishedTime },
            subscriberCount = extra.subscriberCount.takeIf { it > 0 } ?: base.subscriberCount,
            channelAvatarUrl = extra.channelAvatarUrl.ifBlank { base.channelAvatarUrl },
            channelId = extra.channelId.ifBlank { base.channelId },
            channelTitle = extra.channelTitle.ifBlank { base.channelTitle }
        )
    }

    private fun interleave(lists: List<List<VideoItem>>): List<VideoItem> {
        val merged = ArrayList<VideoItem>()
        val maxSize = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until maxSize) {
            for (list in lists) {
                if (i < list.size) merged.add(list[i])
            }
        }
        return merged
    }
}

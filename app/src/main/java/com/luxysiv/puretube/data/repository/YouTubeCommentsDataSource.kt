package com.luxysiv.puretube.data.repository

import com.luxysiv.puretube.data.model.CommentItem
import com.luxysiv.puretube.data.model.CommentPage
import com.luxysiv.puretube.data.model.CommentSort
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bình luận YouTube lấy trực tiếp bằng InnerTube `next` continuation.
 *
 * Session được tách theo `videoId` để tránh việc tải/browse bình luận của video
 * khác làm mất continuation/reply token của video đang xem.
 */
object YouTubeCommentsDataSource {

    private const val NEXT_URL = "https://www.youtube.com/youtubei/v1/next?prettyPrint=false"
    private const val HEARTED = "TOOLBAR_HEART_STATE_HEARTED"
    private const val MAX_SESSIONS = 4

    private class CommentsSession(
        val videoId: String,
        var nextToken: String?,
        val totalCount: Int,
        /** Token mở trang đầu tiên, giữ lại để đọc lại menu sắp xếp khi cần. */
        val firstToken: String? = nextToken,
        /** Token của từng lựa chọn sắp xếp do YouTube trả trong menu bình luận. */
        val sortTokens: MutableMap<CommentSort, String> = mutableMapOf(),
        var sort: CommentSort = CommentSort.TOP,
        /** Token mở trang phản hồi đầu tiên của từng bình luận. */
        val replyTokens: MutableMap<String, String> = mutableMapOf(),
        /** Token cho trang phản hồi kế tiếp sau khi đã mở rộng. */
        val replyNextTokens: MutableMap<String, String> = mutableMapOf(),
        var lastAccessMs: Long = System.currentTimeMillis()
    )

    private val lock = Any()
    private val sessions = LinkedHashMap<String, CommentsSession>()

    private fun touch(session: CommentsSession): CommentsSession {
        session.lastAccessMs = System.currentTimeMillis()
        sessions.remove(session.videoId)
        sessions[session.videoId] = session
        while (sessions.size > MAX_SESSIONS) {
            val eldestKey = sessions.entries.firstOrNull()?.key ?: break
            sessions.remove(eldestKey)
        }
        return session
    }

    private fun getOrCreateSession(videoId: String, forceRefresh: Boolean): CommentsSession? {
        val existing = sessions[videoId]
        if (!forceRefresh && existing != null) return touch(existing)

        val metadata = YouTubeMetadataDataSource.cachedBlocking(videoId)
            ?: YouTubeMetadataDataSource.fetchBlocking(videoId)
            ?: return null
        val token = metadata.commentsToken
        val created = CommentsSession(
            videoId = videoId,
            nextToken = token,
            totalCount = metadata.commentsTotalCount,
            sortTokens = mutableMapOf<CommentSort, String>().apply {
                putAll(metadata.commentsSortTokens)
                token?.takeIf { it.isNotBlank() }?.let { put(CommentSort.TOP, it) }
            }
        )
        return touch(created)
    }

    /**
     * Tải một trang bình luận.
     *
     * [loadMore] = false: trang đầu (tạo/refresh phiên của video).
     * [loadMore] = true: trang kế tiếp của phiên video đó.
     * [sort] = null: giữ thứ tự hiện tại của phiên. Khác null: đổi sang thứ tự
     * đó và tải lại từ trang đầu (dùng token lấy từ menu sắp xếp của YouTube).
     * Trả về null khi mạng lỗi/không lấy được metadata để UI hiện nút thử lại.
     */
    fun commentsBlocking(
        videoId: String,
        loadMore: Boolean,
        sort: CommentSort? = null
    ): CommentPage? {
        if (videoId.isBlank()) return null
        return synchronized(lock) {
            val active = if (sort != null) {
                // Đổi thứ tự không được làm mất menu/token đã học của phiên.
                sessions[videoId] ?: getOrCreateSession(videoId, forceRefresh = true)
            } else {
                getOrCreateSession(videoId, forceRefresh = !loadMore)
            } ?: return@synchronized null

            if (sort != null && sort != active.sort) {
                val target = sortToken(active, sort)
                    ?: return@synchronized active.currentPage()
                active.nextToken = target
                active.sort = sort
                // Token phản hồi không phụ thuộc thứ tự sắp xếp: giữ lại để
                // các câu trả lời đang mở không bị mất khi đổi Nổi bật/Mới nhất.
            }

            if (active.nextToken == null && sort == null && !loadMore) {
                val metadata = YouTubeMetadataDataSource.cachedBlocking(videoId)
                    ?: YouTubeMetadataDataSource.fetchBlocking(videoId)
                return@synchronized CommentPage(
                    totalCount = metadata?.commentsTotalCount ?: active.totalCount,
                    isDisabled = metadata?.commentsDisabled == true,
                    sort = active.sort,
                    canSortNewest = active.canSortNewest
                )
            }

            val token = active.nextToken
                ?: return@synchronized active.currentPage()
            val root = fetchContinuation(token) ?: return@synchronized null
            val page = parsePage(root)

            active.sortTokens.putAll(page.sortTokens)
            active.nextToken = page.nextToken
            active.replyTokens.putAll(page.replyTokens)
            touch(active)
            CommentPage(
                comments = page.comments,
                hasMore = page.nextToken != null,
                totalCount = active.totalCount,
                sort = active.sort,
                canSortNewest = active.canSortNewest
            )
        }
    }

    /** Trang rỗng nhưng vẫn giữ đúng thứ tự/menu cho UI. */
    private fun CommentsSession.currentPage(): CommentPage = CommentPage(
        totalCount = totalCount,
        sort = sort,
        canSortNewest = canSortNewest
    )

    private val CommentsSession.canSortNewest: Boolean
        get() = sortTokens.containsKey(CommentSort.NEWEST)

    /**
     * Token của [sort]. Lần đầu đổi thứ tự, menu sắp xếp có thể chưa được đọc
     * (phiên cũ hoặc trang đầu không kèm header), nên tải lại trang đầu để học
     * token trước khi chuyển.
     */
    private fun sortToken(session: CommentsSession, sort: CommentSort): String? {
        session.sortTokens[sort]?.let { return it }
        val token = session.firstToken ?: return null
        val root = fetchContinuation(token) ?: return null
        session.sortTokens.putAll(parsePage(root).sortTokens)
        return session.sortTokens[sort]
    }

    /**
     * Tải phản hồi của một bình luận.
     *
     * [loadMore] = false mở trang đầu; [loadMore] = true lấy trang kế tiếp của
     * chính bình luận đó. Chỉ refresh session của [videoId], không ảnh hưởng video khác.
     */
    fun commentRepliesBlocking(
        videoId: String,
        commentId: String,
        loadMore: Boolean
    ): CommentPage? {
        if (videoId.isBlank() || commentId.isBlank()) return null
        return synchronized(lock) {
            var active = getOrCreateSession(videoId, forceRefresh = false) ?: return@synchronized null

            var token = if (loadMore) {
                active.replyNextTokens[commentId]
            } else {
                active.replyTokens[commentId]
            }
            if (token == null && !loadMore) {
                // Token trả lời có thể chưa có trong session hiện tại của video này.
                active = getOrCreateSession(videoId, forceRefresh = true) ?: return@synchronized null
                token = active.replyTokens[commentId]
            }
            token ?: return@synchronized CommentPage()

            val root = fetchContinuation(token) ?: return@synchronized null
            val page = parsePage(root)
            if (page.nextToken == null) {
                active.replyNextTokens.remove(commentId)
            } else {
                active.replyNextTokens[commentId] = page.nextToken
            }
            touch(active)
            CommentPage(
                comments = page.comments,
                hasMore = page.nextToken != null
            )
        }
    }

    private fun fetchContinuation(token: String): JSONObject? {
        val payload = JSONObject()
            .put(
                "context",
                JSONObject()
                    .put(
                        "client",
                        JSONObject()
                            .put("clientName", "WEB")
                            .put("clientVersion", "2.20260120.01.00")
                            .put("hl", "vi")
                            .put("gl", "VN")
                            .put("utcOffsetMinutes", 0)
                    )
                    .put(
                        "request",
                        JSONObject()
                            .put("internalExperimentFlags", JSONArray())
                            .put("useSsl", true)
                    )
                    .put("user", JSONObject().put("lockedSafetyMode", false))
            )
            .put("continuation", token)

        val raw = InnerTubeHttp.post(
            url = NEXT_URL,
            body = payload.toString(),
            userAgent = InnerTubeHttp.DESKTOP_USER_AGENT,
            referer = "https://www.youtube.com/"
        ) ?: return null
        return InnerTubeJson.parseObject(raw)
    }

    private data class ParsedPage(
        val comments: List<CommentItem>,
        val nextToken: String?,
        val replyTokens: Map<String, String>,
        val sortTokens: Map<CommentSort, String> = emptyMap()
    )

    private fun parsePage(root: JSONObject): ParsedPage {
        val mutations = root.optJSONObject("frameworkUpdates")
            ?.optJSONObject("entityBatchUpdate")
            ?.optJSONArray("mutations")

        val heartStates = HashMap<String, String>()
        val commentPayloads = ArrayList<JSONObject>()
        if (mutations != null) {
            for (i in 0 until mutations.length()) {
                val payload = mutations.optJSONObject(i)?.optJSONObject("payload")
                    ?: continue
                payload.optJSONObject("commentEntityPayload")?.let(commentPayloads::add)
                val toolbarState = payload.optJSONObject("engagementToolbarStateEntityPayload")
                if (toolbarState != null) {
                    val key = toolbarState.optString("key")
                    if (key.isNotBlank()) {
                        heartStates[key] = toolbarState.optString("heartState")
                    }
                }
            }
        }

        val items = collectContinuationItems(root)
        val threadInfo = HashMap<String, ThreadInfo>()
        val replyTokens = HashMap<String, String>()
        var nextToken: String? = null

        for (item in items) {
            val thread = item.optJSONObject("commentThreadRenderer")
            if (thread != null) {
                val commentId = threadCommentId(thread)
                if (commentId != null) {
                    val token = threadReplyToken(thread)
                    if (token != null) replyTokens[commentId] = token
                    threadInfo[commentId] = ThreadInfo(
                        replyToken = token,
                        isPinned = thread.optBoolean("isPinned") ||
                            thread.optString("pinnedText").isNotBlank()
                    )
                }
            }
            val continuation = item.optJSONObject("continuationItemRenderer")
            if (continuation != null) {
                continuationToken(continuation)?.let { nextToken = it }
            }
        }

        val comments = commentPayloads.mapNotNull { payload ->
            toCommentItem(payload, heartStates, threadInfo)
        }.distinctBy { it.id }

        return ParsedPage(
            comments = comments,
            nextToken = nextToken,
            replyTokens = replyTokens,
            sortTokens = parseSortTokens(root)
        )
    }

    /**
     * Token sắp xếp đọc được từ trang xem (metadata) hoặc một trang bình luận.
     * Metadata có thể chứa sẵn menu này trước khi trang bình luận đầu tiên về.
     */
    internal fun parseSortMenu(root: JSONObject): Map<CommentSort, String> =
        parseSortTokens(root)

    /**
     * Đọc menu "Sắp xếp bình luận" (`sortFilterSubMenuRenderer`) để lấy token
     * cho từng thứ tự. YouTube hay đổi nhãn theo ngôn ngữ, nên nhận diện theo
     * từ khoá và có phương án dự phòng theo đúng thứ tự menu (Nổi bật trước,
     * Mới nhất sau).
     */
    private fun parseSortTokens(root: JSONObject): Map<CommentSort, String> {
        val menu = InnerTubeJson.firstObjectNamed(root, "sortFilterSubMenuRenderer")
            ?: return emptyMap()
        val items = menu.optJSONArray("subMenuItems") ?: return emptyMap()

        val tokens = mutableMapOf<CommentSort, String>()
        val unknown = mutableListOf<Pair<String, String>>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val label = textOf(item.opt("title")).orEmpty()
            val token = sortItemToken(item) ?: continue
            when (sortFromLabel(label)) {
                CommentSort.NEWEST -> tokens[CommentSort.NEWEST] = token
                CommentSort.TOP -> tokens[CommentSort.TOP] = token
                null -> unknown += label to token
            }
        }

        if (unknown.size == 2) {
            tokens.putIfAbsent(CommentSort.TOP, unknown[0].second)
            tokens.putIfAbsent(CommentSort.NEWEST, unknown[1].second)
        } else if (unknown.size == 1) {
            // Một nhãn đã nhận ra, nhãn còn lại dùng phần tử duy nhất.
            val token = unknown[0].second
            if (CommentSort.TOP in tokens && CommentSort.NEWEST !in tokens) {
                tokens[CommentSort.NEWEST] = token
            } else if (CommentSort.NEWEST in tokens && CommentSort.TOP !in tokens) {
                tokens[CommentSort.TOP] = token
            }
        }
        return tokens
    }

    private fun sortFromLabel(label: String): CommentSort? {
        if (label.isBlank()) return null
        val normalized = label.lowercase()
        return when {
            normalized.contains("newest") ||
                normalized.contains("mới nhất") ||
                normalized.contains("最新") -> CommentSort.NEWEST

            normalized.contains("top") ||
                normalized.contains("hàng đầu") ||
                normalized.contains("nổi bật") ||
                normalized.contains("phổ biến") -> CommentSort.TOP

            else -> null
        }
    }

    private fun sortItemToken(item: JSONObject): String? {
        val endpoint = item.optJSONObject("serviceEndpoint")
            ?: item.optJSONObject("navigationEndpoint")
            ?: item.optJSONObject("continuationEndpoint")
            ?: item
        endpoint.optJSONObject("continuationCommand")
            ?.optString("token")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        // Một số phiên bản bọc lệnh trong commandExecutorCommand.commands[].
        val commands = endpoint.optJSONObject("commandExecutorCommand")
            ?.optJSONArray("commands")
            ?: return null
        for (i in 0 until commands.length()) {
            commands.optJSONObject(i)
                ?.optJSONObject("continuationCommand")
                ?.optString("token")
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return null
    }

    /** Tiêu đề menu có thể là `simpleText`, `runs` hoặc chuỗi thuần. */
    private fun textOf(value: Any?): String? = when (value) {
        is String -> value.trim().ifBlank { null }
        is JSONObject -> InnerTubeJson.text(value)
        else -> null
    }

    private data class ThreadInfo(
        val replyToken: String?,
        val isPinned: Boolean
    )

    private fun collectContinuationItems(root: JSONObject): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        fun scan(node: Any?) {
            when (node) {
                is JSONObject -> {
                    node.optJSONArray("continuationItems")?.let { array ->
                        for (i in 0 until array.length()) {
                            array.optJSONObject(i)?.let(out::add)
                        }
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) scan(node.opt(keys.next()))
                }
                is JSONArray -> for (i in 0 until node.length()) scan(node.opt(i))
            }
        }
        scan(root)
        return out
    }

    private fun continuationToken(renderer: JSONObject): String? {
        renderer.optJSONObject("continuationCommand")
            ?.optString("token")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        val endpoint = renderer.optJSONObject("continuationEndpoint")
            ?: renderer.optJSONObject("button")
                ?.optJSONObject("buttonRenderer")
                ?.optJSONObject("command")
            ?: renderer.optJSONObject("command")
        return endpoint?.optJSONObject("continuationCommand")
            ?.optString("token")
            ?.takeIf { it.isNotBlank() }
    }

    private fun threadCommentId(thread: JSONObject): String? {
        val viewModel = thread.optJSONObject("commentViewModel") ?: return null
        val inner = viewModel.optJSONObject("commentViewModel") ?: viewModel
        return inner.optString("commentId").takeIf { it.isNotBlank() }
    }

    private fun threadReplyToken(thread: JSONObject): String? {
        val replies = thread.optJSONObject("replies")
            ?.optJSONObject("commentRepliesRenderer")
            ?: return null
        val contents = replies.optJSONArray("contents")
        if (contents != null) {
            for (i in 0 until contents.length()) {
                val continuation = contents.optJSONObject(i)
                    ?.optJSONObject("continuationItemRenderer")
                    ?: continue
                continuationToken(continuation)?.let { return it }
            }
        }
        val viewReplies = replies.optJSONObject("viewReplies")
            ?.optJSONObject("buttonRenderer")
        return viewReplies?.optJSONObject("command")
            ?.let(::continuationToken)
            ?: viewReplies?.optJSONObject("navigationEndpoint")
                ?.optJSONObject("continuationCommand")
                ?.optString("token")
                ?.takeIf { it.isNotBlank() }
    }

    private fun toCommentItem(
        payload: JSONObject,
        heartStates: Map<String, String>,
        threads: Map<String, ThreadInfo>
    ): CommentItem? {
        val properties = payload.optJSONObject("properties") ?: return null
        val text = properties.optJSONObject("content")
            ?.optString("content")
            .orEmpty()
        if (text.isBlank()) return null

        val author = payload.optJSONObject("author")
        val toolbar = payload.optJSONObject("toolbar")
        val commentId = properties.optString("commentId")
            .takeIf { it.isNotBlank() }
            ?: "${author?.optString("displayName").orEmpty()}#$text".hashCode().toString()

        val notLiked = toolbar?.optString("likeCountNotliked").orEmpty()
        val liked = toolbar?.optString("likeCountLiked").orEmpty()
        val likeRaw = if (notLiked.any { it.isDigit() }) notLiked else liked
        val likeCount = InnerTubeJson.parseCount(likeRaw).toInt()
        val replyCount = InnerTubeJson.parseCount(
            toolbar?.optString("replyCount")
        ).toInt()

        val thread = threads[commentId]
        val hearted = heartStates[properties.optString("toolbarStateKey")] == HEARTED

        val rawAvatar = author?.optString("avatarThumbnailUrl")?.takeIf { it.isNotBlank() }
            ?: author?.optJSONObject("avatar")?.let { InnerTubeJson.bestThumbnailUrl(it) }
            ?: author?.optJSONObject("authorThumbnail")?.let { InnerTubeJson.bestThumbnailUrl(it) }
            ?: ""
        val avatarUrl = rawAvatar.let { url ->
            val trimmed = url.trim()
            when {
                trimmed.startsWith("//") -> "https:$trimmed"
                trimmed.startsWith("http://") -> "https://" + trimmed.removePrefix("http://")
                else -> trimmed
            }
        }

        return CommentItem(
            id = commentId,
            author = author?.optString("displayName")
                ?.takeIf { it.isNotBlank() }
                ?: "Người xem",
            avatarUrl = avatarUrl,
            text = text,
            publishedTime = properties.optString("publishedTime"),
            likeCount = likeCount,
            likeText = "",
            replyCount = replyCount,
            hasReplies = thread?.replyToken != null || replyCount > 0,
            isPinned = thread?.isPinned == true ||
                properties.optString("pinnedText").isNotBlank(),
            isHeartedByUploader = hearted,
            isVerified = author?.optBoolean("isVerified") == true,
            isChannelOwner = author?.optBoolean("isCreator") == true
        )
    }
}

package com.luxysiv.puretube.data.repository

import com.luxysiv.puretube.data.model.ChannelPage
import com.luxysiv.puretube.data.model.VideoItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tải trang kênh qua InnerTube `browse`.
 *
 * Trang đầu dùng tab "Video" để lấy danh sách upload mới nhất (giống
 * [ChannelVideoDataSource] nhưng kèm thông tin header kênh). Các trang sau dùng
 * continuation token.
 */
internal object ChannelPageDataSource {

    private const val BROWSE_URL = "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false"
    private const val VIDEOS_TAB_PARAMS = "EgZ2aWRlb3PyBgQKAjoA"
    private const val CLIENT_VERSION = "2.20260120.01.00"

    fun fetch(channelId: String): ChannelPage? {
        if (channelId.isBlank()) return null
        val payload = basePayload()
            .put("browseId", channelId)
            .put("params", VIDEOS_TAB_PARAMS)

        val root = post(payload, referer = "https://www.youtube.com/channel/$channelId/videos")
            ?: return null
        return parse(channelId, root)
    }

    fun fetchMore(channelId: String, continuation: String): ChannelPage? {
        if (continuation.isBlank()) return null
        val payload = basePayload().put("continuation", continuation)
        val root = post(payload, referer = "https://www.youtube.com/channel/$channelId/videos")
            ?: return null
        val videos = parseVideos(root)
        return ChannelPage(
            channelId = channelId,
            videos = videos,
            nextToken = firstContinuationToken(root)
        )
    }

    private fun basePayload(): JSONObject = JSONObject()
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

    private fun post(payload: JSONObject, referer: String): JSONObject? {
        val raw = InnerTubeHttp.post(
            url = BROWSE_URL,
            body = payload.toString(),
            userAgent = InnerTubeHttp.DESKTOP_USER_AGENT,
            referer = referer
        ) ?: return null
        val root = InnerTubeJson.parseObject(raw) ?: return null
        if (root.has("error")) return null
        return root
    }

    internal fun parse(channelId: String, root: JSONObject): ChannelPage {
        val header = parseHeader(root)
        val videos = parseVideos(root).map { video ->
            // Đảm bảo video thuộc đúng kênh và có tên kênh nếu response thiếu.
            if (video.channelId.isBlank() || video.channelTitle.isBlank() || video.channelTitle == "YouTube") {
                video.copy(
                    channelId = video.channelId.ifBlank { channelId },
                    channelTitle = if (video.channelTitle.isBlank() || video.channelTitle == "YouTube") {
                        header.title.ifBlank { video.channelTitle }
                    } else {
                        video.channelTitle
                    },
                    channelAvatarUrl = video.channelAvatarUrl.ifBlank { header.avatarUrl }
                )
            } else {
                video
            }
        }
        return ChannelPage(
            channelId = channelId,
            title = header.title,
            avatarUrl = header.avatarUrl,
            bannerUrl = header.bannerUrl,
            subscriberCount = header.subscriberCount,
            description = header.description,
            videos = videos,
            nextToken = firstContinuationToken(root)
        )
    }

    private data class Header(
        val title: String = "",
        val avatarUrl: String = "",
        val bannerUrl: String = "",
        val subscriberCount: String = "",
        val description: String = ""
    )

    private fun parseHeader(root: JSONObject): Header {
        // YouTube dùng nhiều schema header khác nhau tùy client/phiên bản.
        // Cố gắng đọc từ pageHeaderRenderer (mới), c4TabbedHeaderRenderer, rồi metadata.
        val pageHeader = InnerTubeJson.firstObjectNamed(root, "pageHeaderRenderer")
        if (pageHeader != null) {
            val fromPage = parsePageHeader(pageHeader)
            if (fromPage.title.isNotBlank()) {
                return fromPage.copy(
                    subscriberCount = fromPage.subscriberCount.ifBlank { metadataSubscriber(root) },
                    description = fromPage.description.ifBlank { metadataDescription(root) }
                )
            }
        }

        val c4 = InnerTubeJson.firstObjectNamed(root, "c4TabbedHeaderRenderer")
        if (c4 != null) {
            val title = c4.optString("title").ifBlank { InnerTubeJson.text(c4.optJSONObject("title")).orEmpty() }
            val avatar = InnerTubeJson.bestThumbnailUrl(c4.optJSONObject("avatar"))
            val banner = InnerTubeJson.bestThumbnailUrl(c4.optJSONObject("banner"))
                .ifBlank { InnerTubeJson.bestThumbnailUrl(c4.optJSONObject("tvBanner")) }
            val subs = InnerTubeJson.text(c4.optJSONObject("subscriberCountText")).orEmpty()
            return Header(
                title = title,
                avatarUrl = avatar,
                bannerUrl = banner,
                subscriberCount = subs.ifBlank { metadataSubscriber(root) },
                description = metadataDescription(root)
            )
        }

        val meta = InnerTubeJson.firstObjectNamed(root, "channelMetadataRenderer")
        val micro = InnerTubeJson.firstObjectNamed(root, "microformatDataRenderer")
        val title = meta?.optString("title").orEmpty().ifBlank { micro?.optString("title").orEmpty() }
        val avatar = InnerTubeJson.bestThumbnailUrl(meta?.optJSONObject("avatar"))
            .ifBlank { InnerTubeJson.bestThumbnailUrl(micro?.optJSONObject("thumbnail")) }
        val desc = meta?.optString("description").orEmpty().ifBlank { micro?.optString("description").orEmpty() }
        return Header(
            title = title,
            avatarUrl = avatar,
            subscriberCount = metadataSubscriber(root),
            description = desc
        )
    }

    private fun parsePageHeader(header: JSONObject): Header {
        // pageHeaderViewModel (mới) nằm trong content/pageHeaderViewModel
        val viewModel = header.optJSONObject("content")
            ?.optJSONObject("pageHeaderViewModel")
            ?: header.optJSONObject("pageHeaderViewModel")

        var title = ""
        var avatar = ""
        var banner = ""
        var subs = ""
        var desc = ""

        if (viewModel != null) {
            title = textOf(viewModel.opt("title"))
                .ifBlank { InnerTubeJson.text(viewModel.optJSONObject("title")).orEmpty() }
            avatar = listOfNotNull(
                viewModel.optJSONObject("image")
                    ?.optJSONObject("decoratedAvatarViewModel")
                    ?.optJSONObject("avatar")
                    ?.optJSONObject("avatarViewModel")
                    ?.optJSONObject("image"),
                viewModel.optJSONObject("image")
                    ?.optJSONObject("decoratedAvatarViewModel")
                    ?.optJSONObject("avatar")
                    ?.optJSONObject("avatarViewModel"),
                viewModel.optJSONObject("image")
            ).firstNotNullOfOrNull { obj ->
                InnerTubeJson.bestThumbnailUrl(obj).takeIf { it.isNotBlank() }
            }.orEmpty()
            banner = InnerTubeJson.bestThumbnailUrl(
                viewModel.optJSONObject("banner")
                    ?.optJSONObject("imageBannerViewModel")
                    ?.optJSONObject("image")
            )
            // subscriber count thường là một dòng metadata text
            val metadataRows = viewModel.optJSONObject("metadata")
                ?.optJSONObject("contentMetadataViewModel")
                ?.optJSONArray("metadataRows")
            if (metadataRows != null) {
                val texts = collectContentTexts(metadataRows)
                subs = texts.firstOrNull { looksLikeSubscriberCount(it) }.orEmpty()
            }
            desc = textOf(viewModel.opt("description"))
                .ifBlank {
                    viewModel.optJSONObject("description")
                        ?.optJSONObject("descriptionPreviewViewModel")
                        ?.optJSONObject("description")
                        ?.let { InnerTubeJson.text(it) }
                        .orEmpty()
                }
        }

        if (title.isBlank()) {
            title = textOf(header.opt("pageTitle"))
                .ifBlank { InnerTubeJson.text(header.optJSONObject("pageTitle")).orEmpty() }
        }
        return Header(title = title, avatarUrl = avatar, bannerUrl = banner, subscriberCount = subs, description = desc)
    }

    private fun textOf(value: Any?): String = when (value) {
        is JSONObject -> {
            // dynamicTextViewModel.text.content hoặc plain text runs
            val dyn = value.optJSONObject("dynamicTextViewModel")?.optJSONObject("text")
            InnerTubeJson.text(dyn ?: value).orEmpty().ifBlank { value.optString("content") }
        }
        is String -> value
        else -> ""
    }

    private fun collectContentTexts(array: JSONArray): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until array.length()) {
            collectTexts(array.opt(i), out)
        }
        return out.filter { it.isNotBlank() }
    }

    private fun collectTexts(node: Any?, out: MutableList<String>) {
        when (node) {
            is JSONObject -> {
                if (node.has("content")) out += node.optString("content")
                val simple = node.optString("simpleText")
                if (simple.isNotBlank()) out += simple
                val keys = node.keys()
                while (keys.hasNext()) collectTexts(node.opt(keys.next()), out)
            }
            is JSONArray -> for (i in 0 until node.length()) collectTexts(node.opt(i), out)
            is String -> if (node.isNotBlank()) out += node
        }
    }

    private fun looksLikeSubscriberCount(text: String): Boolean =
        text.contains("người đăng ký", ignoreCase = true) ||
            Regex("\\bsubscribers?\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)

    private fun metadataSubscriber(root: JSONObject): String {
        // videoOwnerRenderer trong header phụ (nếu có) hoặc metadata
        val owner = InnerTubeJson.firstObjectNamed(root, "videoOwnerRenderer")
        val fromOwner = owner?.optJSONObject("subscriberCountText")?.let { InnerTubeJson.text(it) }
        if (!fromOwner.isNullOrBlank()) return fromOwner
        val micro = InnerTubeJson.firstObjectNamed(root, "microformatDataRenderer")
        return micro?.optString("subscriberCountText").orEmpty()
    }

    private fun metadataDescription(root: JSONObject): String {
        val meta = InnerTubeJson.firstObjectNamed(root, "channelMetadataRenderer")
        return meta?.optString("description").orEmpty()
    }

    internal fun parseVideos(root: JSONObject): List<VideoItem> {
        val out = ArrayList<VideoItem>()
        for (lockup in InnerTubeJson.objectsNamed(root, "lockupViewModel")) {
            InnerTubeVideoMapping.lockupVideoItem(lockup)?.let(out::add)
        }
        for (renderer in InnerTubeJson.objectsNamed(root, "videoRenderer")) {
            InnerTubeVideoMapping.rendererVideoItem(renderer)?.let(out::add)
        }
        for (renderer in InnerTubeJson.objectsNamed(root, "gridVideoRenderer")) {
            InnerTubeVideoMapping.rendererVideoItem(renderer)?.let(out::add)
        }
        return out.distinctBy { it.id }
    }

    internal fun firstContinuationToken(root: JSONObject): String {
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
}

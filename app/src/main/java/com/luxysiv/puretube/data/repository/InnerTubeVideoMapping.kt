package com.luxysiv.puretube.data.repository

import com.luxysiv.puretube.data.model.PlaylistItem
import com.luxysiv.puretube.data.model.VideoItem
import com.luxysiv.puretube.data.model.videoThumbnailUrl
import org.json.JSONObject

/**
 * Mapping dùng chung cho mọi renderer video của InnerTube/YouTube.
 *
 * Cùng một video có thể xuất hiện dưới nhiều schema (`videoRenderer`,
 * `gridVideoRenderer`, `playlistPanelVideoRenderer`, `lockupViewModel`...).
 * Trước đây mỗi parser tự đọc byline/thumbnail/thời lượng với thứ tự fallback
 * hơi khác nhau; gom về đây để search, feed, mix và trang xem luôn cho ra cùng
 * một [VideoItem].
 */
internal object InnerTubeVideoMapping {

    /** browseId của kênh nằm trong run đầu tiên có `navigationEndpoint`. */
    fun channelId(byline: JSONObject?): String {
        val runs = byline?.optJSONArray("runs") ?: return ""
        for (i in 0 until runs.length()) {
            val run = runs.optJSONObject(i) ?: continue
            val browseId = run.optJSONObject("navigationEndpoint")
                ?.optJSONObject("browseEndpoint")
                ?.optString("browseId")
            if (!browseId.isNullOrBlank()) return browseId
        }
        return ""
    }

    fun channelTitle(byline: JSONObject?): String =
        InnerTubeJson.text(byline)?.takeIf { it.isNotBlank() } ?: "YouTube"

    fun thumbnail(renderer: JSONObject): String =
        renderer.optJSONObject("thumbnail")
            ?.let { InnerTubeJson.bestThumbnailUrl(it) }
            .orEmpty()

    fun channelAvatar(renderer: JSONObject): String {
        val candidates = listOfNotNull(
            renderer.optJSONObject("channelThumbnailSupportedRenderers")
                ?.optJSONObject("channelThumbnailWithLinkRenderer")
                ?.optJSONObject("thumbnail"),
            renderer.optJSONObject("channelThumbnail"),
            renderer.optJSONObject("owner")
                ?.optJSONObject("thumbnail"),
            renderer.optJSONObject("videoOwnerRenderer")
                ?.optJSONObject("thumbnail")
        )
        for (candidate in candidates) {
            val url = InnerTubeJson.bestThumbnailUrl(candidate)
            if (url.isNotBlank()) return url
        }
        return ""
    }

    /** `lengthText`, hoặc overlay thời lượng của grid/shorts. */
    fun duration(renderer: JSONObject): String {
        InnerTubeJson.text(renderer.optJSONObject("lengthText"))
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val overlays = renderer.optJSONArray("thumbnailOverlays")
        if (overlays != null) {
            for (i in 0 until overlays.length()) {
                val text = overlays.optJSONObject(i)
                    ?.optJSONObject("thumbnailOverlayTimeStatusRenderer")
                    ?.optJSONObject("text")
                InnerTubeJson.text(text)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return "--:--"
    }

    private fun viewCount(renderer: JSONObject): String =
        renderer.optJSONObject("viewCountText")?.optString("simpleText")
            ?.takeIf { it.isNotBlank() }
            ?: renderer.optJSONObject("shortViewCountText")?.optString("simpleText")
                .orEmpty()

    private fun publishedTime(renderer: JSONObject): String =
        renderer.optJSONObject("publishedTimeText")?.optString("simpleText").orEmpty()

    /** `videoRenderer`/`gridVideoRenderer`/`playlistVideoRenderer`. */
    fun rendererVideoItem(renderer: JSONObject, category: String = "YouTube"): VideoItem? {
        val id = renderer.optString("videoId")
        if (id.isBlank()) return null
        val byline = renderer.optJSONObject("ownerText")
            ?: renderer.optJSONObject("longBylineText")
            ?: renderer.optJSONObject("shortBylineText")
        val thumbnailUrl = thumbnail(renderer).ifBlank { videoThumbnailUrl(id) }

        return VideoItem(
            id = id,
            title = InnerTubeJson.text(renderer.optJSONObject("title"))
                ?: renderer.optString("title", "Video YouTube"),
            channelTitle = channelTitle(byline),
            channelId = channelId(byline),
            description = InnerTubeJson.text(renderer.optJSONObject("descriptionSnippet"))
                .orEmpty(),
            duration = duration(renderer),
            viewCount = viewCount(renderer),
            publishedTime = publishedTime(renderer),
            thumbnailUrl = thumbnailUrl,
            category = category,
            channelAvatarUrl = channelAvatar(renderer)
        )
    }

    /** `playlistPanelVideoRenderer` của Danh sách kết hợp. */
    fun playlistPanelVideoItem(renderer: JSONObject): VideoItem? {
        val id = renderer.optString("videoId")
        if (id.length != 11) return null
        val title = InnerTubeJson.text(renderer.optJSONObject("title"))
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val byline = renderer.optJSONObject("longBylineText")
            ?: renderer.optJSONObject("shortBylineText")
        val thumbnailUrl = thumbnail(renderer).ifBlank { videoThumbnailUrl(id) }

        return VideoItem(
            id = id,
            title = title,
            channelTitle = channelTitle(byline),
            channelId = channelId(byline),
            duration = duration(renderer),
            viewCount = viewCount(renderer),
            thumbnailUrl = thumbnailUrl,
            category = "YouTube"
        )
    }

    /** `lockupViewModel` dùng ở trang xem/gợi ý mới của YouTube. */
    fun lockupVideoItem(lockup: JSONObject, category: String = "YouTube"): VideoItem? {
        val id = lockup.optString("contentId")
        if (id.length != 11) return null
        val type = lockup.optString("contentType")
        if (type.isNotBlank() && !type.contains("VIDEO")) return null

        val metadata = lockup.optJSONObject("metadata")
            ?.optJSONObject("lockupMetadataViewModel")
            ?: return null
        val title = InnerTubeJson.text(metadata.optJSONObject("title")) ?: return null

        val rowTexts = lockupMetadataRows(metadata)

        val channelTitle = rowTexts.firstOrNull()?.firstOrNull() ?: "YouTube"
        val secondRow = rowTexts.getOrNull(1).orEmpty()
        val viewCount = secondRow.firstOrNull { InnerTubeJson.parseCount(it) > 0 }.orEmpty()
        val publishedTime = secondRow.firstOrNull { it != viewCount }.orEmpty()

        val avatarModel = metadata.optJSONObject("image")
            ?.optJSONObject("decoratedAvatarViewModel")
        val browseId = avatarModel
            ?.optJSONObject("rendererContext")
            ?.optJSONObject("commandContext")
            ?.optJSONObject("onTap")
            ?.optJSONObject("innertubeCommand")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId")
            .orEmpty()
        val channelAvatarUrl = listOfNotNull(
            avatarModel?.optJSONObject("avatar")
                ?.optJSONObject("avatarViewModel")
                ?.optJSONObject("image"),
            avatarModel?.optJSONObject("avatar")
                ?.optJSONObject("avatarViewModel"),
            metadata.optJSONObject("image")
        ).firstNotNullOfOrNull { obj ->
            InnerTubeJson.bestThumbnailUrl(obj).takeIf { it.isNotBlank() }
        }.orEmpty()

        val thumbnailViewModel = lockup.optJSONObject("contentImage")
            ?.optJSONObject("thumbnailViewModel")
        val thumbnailUrl = InnerTubeJson.bestThumbnailUrl(
            thumbnailViewModel?.optJSONObject("image")
        ).ifBlank { videoThumbnailUrl(id) }

        var duration = "--:--"
        val overlays = thumbnailViewModel?.optJSONArray("overlays")
        if (overlays != null) {
            for (i in 0 until overlays.length()) {
                if (duration != "--:--") break
                val badges = overlays.optJSONObject(i)
                    ?.optJSONObject("thumbnailBottomOverlayViewModel")
                    ?.optJSONArray("badges")
                    ?: continue
                for (j in 0 until badges.length()) {
                    val text = badges.optJSONObject(j)
                        ?.optJSONObject("thumbnailBadgeViewModel")
                        ?.optString("text")
                        .orEmpty()
                    if (Regex("^\\d+(:\\d{2}){1,2}$").matches(text)) {
                        duration = text
                        break
                    }
                }
            }
        }

        return VideoItem(
            id = id,
            title = title,
            channelTitle = channelTitle,
            channelId = browseId,
            description = "",
            duration = duration,
            viewCount = viewCount,
            publishedTime = publishedTime,
            thumbnailUrl = thumbnailUrl,
            category = category,
            channelAvatarUrl = channelAvatarUrl
        )
    }

    /**
     * Playlist trong `lockupViewModel` — schema hiện tại của kết quả tìm kiếm.
     *
     * Trả `null` cho lockup video hoặc khi thiếu id/tiêu đề. Playlist có thể là
     * `PL...` (hữu hạn) hoặc `RD...` (mix); tầng UI quyết định cách xử lý dựa
     * trên [PlaylistItem.isMix].
     */
    fun playlistLockup(lockup: JSONObject): PlaylistItem? {
        val type = lockup.optString("contentType")
        if (type.isNotBlank() && !type.contains("PLAYLIST")) return null

        val metadata = lockup.optJSONObject("metadata")
            ?.optJSONObject("lockupMetadataViewModel")
            ?: return null
        val title = InnerTubeJson.text(metadata.optJSONObject("title"))
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val watchEndpoint = lockup.optJSONObject("rendererContext")
            ?.optJSONObject("commandContext")
            ?.optJSONObject("onTap")
            ?.optJSONObject("innertubeCommand")
            ?.optJSONObject("watchEndpoint")
        val id = lockup.optString("contentId")
            .ifBlank { watchEndpoint?.optString("playlistId").orEmpty() }
        if (id.isBlank()) return null

        val contentImage = lockup.optJSONObject("contentImage")
        val collectionThumbnail = contentImage?.optJSONObject("collectionThumbnailViewModel")
        // Lockup không ghi contentType vẫn có thể là video; chỉ nhận khi có dấu
        // hiệu playlist rõ ràng để không biến video thành playlist.
        if (type.isBlank() &&
            collectionThumbnail == null &&
            !id.startsWith("PL") &&
            !id.startsWith("RD") &&
            !id.startsWith("OL") &&
            !id.startsWith("VL")
        ) {
            return null
        }
        val thumbnailViewModel = collectionThumbnail
            ?.optJSONObject("primaryThumbnail")
            ?.optJSONObject("thumbnailViewModel")
            ?: contentImage?.optJSONObject("thumbnailViewModel")
        val thumbnailUrl = InnerTubeJson.bestThumbnailUrl(
            thumbnailViewModel?.optJSONObject("image")
        )

        val firstVideoId = watchEndpoint?.optString("videoId").orEmpty()
            .ifBlank {
                // Một số biến thể bọc lệnh trong commandExecutorCommand.
                InnerTubeJson.firstObjectNamed(lockup, "watchEndpoint")
                    ?.optString("videoId")
                    .orEmpty()
            }

        val rowTexts = lockupMetadataRows(metadata)
        val parts = rowTexts.flatten()
        val videoCount = parsePlaylistVideoCount(playlistBadgeText(thumbnailViewModel))
            .takeIf { it > 0 }
            ?: parts.firstNotNullOfOrNull { text ->
                parsePlaylistVideoCount(text).takeIf { it > 0 }
            }
            ?: 0
        val channelTitle = rowTexts.firstOrNull()?.firstOrNull {
            parsePlaylistVideoCount(it) == 0 && !isPlaylistLabel(it)
        }.orEmpty()
        val previewTitles = rowTexts.drop(1)
            .flatten()
            .filter { parsePlaylistVideoCount(it) == 0 }
            .map { it.substringBefore(" · ").trim() }
            .filter { it.isNotBlank() }

        return PlaylistItem(
            id = id,
            title = title,
            channelTitle = channelTitle,
            channelId = "",
            videoCount = videoCount,
            thumbnailUrl = thumbnailUrl.ifBlank {
                if (firstVideoId.length == 11) videoThumbnailUrl(firstVideoId) else ""
            },
            firstVideoId = firstVideoId,
            firstVideoTitle = previewTitles.firstOrNull().orEmpty(),
            previewTitles = previewTitles
        )
    }

    /** `playlistRenderer`/`gridPlaylistRenderer` — schema cũ, vẫn giữ để phòng hờ. */
    fun playlistRendererItem(renderer: JSONObject): PlaylistItem? {
        val watchEndpoint = renderer.optJSONObject("navigationEndpoint")
            ?.optJSONObject("watchEndpoint")
        val id = renderer.optString("playlistId")
            .ifBlank { watchEndpoint?.optString("playlistId").orEmpty() }
        if (id.isBlank()) return null

        val title = InnerTubeJson.text(renderer.optJSONObject("title"))
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val byline = renderer.optJSONObject("ownerText")
            ?: renderer.optJSONObject("longBylineText")
            ?: renderer.optJSONObject("shortBylineText")

        val firstVideoId = watchEndpoint?.optString("videoId").orEmpty()
        val previews = ArrayList<String>()
        val videos = renderer.optJSONArray("videos")
        if (videos != null) {
            for (i in 0 until videos.length()) {
                val child = videos.optJSONObject(i)
                    ?.optJSONObject("childVideoRenderer")
                    ?: continue
                InnerTubeJson.text(child.optJSONObject("title"))
                    ?.takeIf { it.isNotBlank() }
                    ?.let(previews::add)
            }
        }

        val countText = InnerTubeJson.text(renderer.optJSONObject("videoCountText"))
            ?: InnerTubeJson.text(renderer.optJSONObject("videoCountShortText"))
        val videoCount = parsePlaylistVideoCount(countText)
            .takeIf { it > 0 }
            ?: renderer.optInt("videoCount", 0)

        return PlaylistItem(
            id = id,
            title = title,
            channelTitle = channelTitle(byline),
            channelId = channelId(byline),
            videoCount = videoCount,
            thumbnailUrl = thumbnail(renderer).ifBlank {
                if (firstVideoId.length == 11) videoThumbnailUrl(firstVideoId) else ""
            },
            firstVideoId = firstVideoId,
            firstVideoTitle = previews.firstOrNull().orEmpty(),
            previewTitles = previews
        )
    }

    /** Các hàng metadata của `lockupMetadataViewModel`, bỏ hàng rỗng/spacer. */
    internal fun lockupMetadataRows(metadata: JSONObject): List<List<String>> {
        val rows = metadata.optJSONObject("metadata")
            ?.optJSONObject("contentMetadataViewModel")
            ?.optJSONArray("metadataRows")
            ?: return emptyList()
        val result = ArrayList<List<String>>(rows.length())
        for (i in 0 until rows.length()) {
            val parts = rows.optJSONObject(i)?.optJSONArray("metadataParts") ?: continue
            val values = ArrayList<String>()
            for (j in 0 until parts.length()) {
                val value = parts.optJSONObject(j)
                    ?.optJSONObject("text")
                    ?.optString("content")
                    .orEmpty()
                if (value.isNotBlank()) values.add(value)
            }
            if (values.isNotEmpty()) result.add(values)
        }
        return result
    }

    /** Badge số video nằm trên thumbnail của playlist lockup. */
    private fun playlistBadgeText(thumbnailViewModel: JSONObject?): String? {
        val overlays = thumbnailViewModel?.optJSONArray("overlays") ?: return null
        for (i in 0 until overlays.length()) {
            val badges = overlays.optJSONObject(i)
                ?.optJSONObject("thumbnailOverlayBadgeViewModel")
                ?.optJSONArray("thumbnailBadges")
                ?: continue
            for (j in 0 until badges.length()) {
                val text = badges.optJSONObject(j)
                    ?.optJSONObject("thumbnailBadgeViewModel")
                    ?.optString("text")
                    .orEmpty()
                if (parsePlaylistVideoCount(text) > 0) return text
            }
        }
        return null
    }

    /** Đọc `279 video`, `1.234 videos`, `1,2 N video`; 0 nếu không phải số video. */
    private fun parsePlaylistVideoCount(text: String?): Int {
        val raw = text?.trim().orEmpty()
        if (!raw.contains("video", ignoreCase = true)) return 0
        return InnerTubeJson.parseCount(raw).toInt()
    }

    private fun isPlaylistLabel(text: String): Boolean =
        text.trim().lowercase() == "danh sách phát" || text.trim().equals("playlist", true)
}

package com.luxysiv.puretube.data.repository

import android.util.Base64
import com.luxysiv.puretube.data.model.PlaybackPlan
import com.luxysiv.puretube.data.model.PlaybackSource
import com.luxysiv.puretube.data.model.PlaybackSubtitle
import com.luxysiv.puretube.data.model.VideoDetails
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Kết quả một lần gọi InnerTube `player`: kế hoạch phát + metadata cơ bản. */
data class YouTubePlayerResult(
    val plan: PlaybackPlan,
    val details: VideoDetails,
    val title: String = ""
)

/**
 * Lấy luồng phát trực tiếp từ InnerTube `player`.
 *
 * Thứ tự client (đã kiểm chứng với YouTube):
 *  1. `VISIONOS` — client Apple mới. Không cần PO token/JS player, trả đủ
 *     adaptive video/audio kèm URL trực tiếp và `hlsManifestUrl`. Đây là nguồn
 *     ổn định nhất hiện nay, nên thử trước.
 *  2. `IOS` — trả adaptive + HLS; dự phòng khi visionOS bị từ chối.
 *  3. `ANDROID` — cùng dữ liệu adaptive và một progressive MP4 dự phòng.
 *  4. `ANDROID_VR` — dự phòng cuối khi các client trên bị từ chối.
 *
 * Luồng video-only + audio-only được ghép thành manifest DASH tự dựng để đổi
 * chất lượng không phải nạp lại video. Khi không dựng được DASH, ta vẫn còn
 * HLS và progressive.
 */
object YouTubePlayerDataSource {

    /**
     * `params=2AMB` bật nhánh thử nghiệm "testsuite" của YouTube.
     *
     * Nếu thiếu tham số này, googlevideo vẫn trả `playabilityStatus = OK` và
     * URL adaptive, nhưng mỗi URL chỉ tải được khoảng 60–80 giây đầu; mọi
     * `Range` xa hơn trả `403` khiến video dừng giữa chừng rồi retry liên tục.
     * Có tham số này, URL dùng được cho toàn bộ thời lượng và response còn kèm
     * `hlsManifestUrl` làm nguồn dự phòng.
     */
    private const val PLAYER_URL =
        "https://www.youtube.com/youtubei/v1/player?prettyPrint=false&params=2AMB"

    /** Dùng khi YouTube tạm thời từ chối tham số thử nghiệm (HTTP 400). */
    private const val PLAYER_URL_FALLBACK =
        "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"

    /**
     * Endpoint InnerTube dành cho app di động/Apple. Client `VISIONOS` chỉ hoạt
     * động qua host này, kèm `t` (nonce 12 ký tự) và `id` (videoId) trên URL.
     */
    private const val GAPIS_PLAYER_URL =
        "https://youtubei.googleapis.com/youtubei/v1/player"

    /**
     * Lấy `visitorData` ẩn danh. Client visionOS bắt buộc phải có: thiếu trường
     * này YouTube trả response không có `streamingData` dù trạng thái là OK.
     */
    private const val VISITOR_ID_URL =
        "https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false"

    private const val VISIONOS_API_FORMAT_VERSION = "2"

    /** Sau lỗi lấy visitorData, tạm dùng client dự phòng trong 5 phút. */
    private const val VISITOR_RETRY_MS = 5 * 60_000L

    private const val MIME_DASH = "application/dash+xml"
    private const val MIME_HLS = "application/x-mpegURL"
    private const val MIME_MP4 = "video/mp4"
    private const val MIME_VTT = "text/vtt"
    private const val DATA_URI_PREFIX = "data:application/dash+xml;base64,"

    private data class PlayerClient(
        val name: String,
        val version: String,
        val userAgent: String,
        val extras: Map<String, Any>,
        /** Endpoint GAPIS + visitorData + `cpn` trong body (client Apple mới). */
        val isVisionOs: Boolean = false
    )

    private val VISIONOS_CLIENT = PlayerClient(
        name = "VISIONOS",
        version = "1.04",
        userAgent = InnerTubeHttp.VISIONOS_USER_AGENT,
        extras = mapOf(
            "clientScreen" to "WATCH",
            "platform" to "MOBILE",
            "deviceMake" to "Apple",
            "deviceModel" to "RealityDevice17,1",
            "osName" to "visionOS",
            "osVersion" to "26.6.0.23O770"
        ),
        isVisionOs = true
    )

    private val CLIENTS = listOf(
        VISIONOS_CLIENT,
        PlayerClient(
            name = "IOS",
            version = "21.03.2",
            userAgent = InnerTubeHttp.IOS_USER_AGENT,
            extras = mapOf(
                "clientScreen" to "WATCH",
                "platform" to "MOBILE",
                "deviceMake" to "Apple",
                "deviceModel" to "iPhone16,2",
                "osName" to "iPhone",
                "osVersion" to "18.3.2.22D82"
            )
        ),
        PlayerClient(
            name = "ANDROID",
            version = "21.03.36",
            userAgent = InnerTubeHttp.ANDROID_USER_AGENT,
            extras = mapOf(
                "clientScreen" to "WATCH",
                "platform" to "MOBILE",
                "osName" to "Android",
                "osVersion" to "16",
                "androidSdkVersion" to 36
            )
        ),
        PlayerClient(
            name = "ANDROID_VR",
            version = "1.60.19",
            userAgent = InnerTubeHttp.ANDROID_USER_AGENT,
            extras = mapOf(
                "clientScreen" to "WATCH",
                "platform" to "MOBILE",
                "deviceMake" to "Oculus",
                "deviceModel" to "Quest 3",
                "osName" to "Android",
                "osVersion" to "12",
                "androidSdkVersion" to 32
            )
        )
    )

    /** Cache visitorData của phiên visionOS (xem [resolveVisitorData]). */
    @Volatile
    private var cachedVisitorData: String? = null

    /** Mốc thử lại visitorData sau lần lỗi gần nhất (tránh chờ mạng mỗi video). */
    @Volatile
    private var visitorDataRetryAt = 0L
    private val visitorLock = Any()

    /**
     * Client đã trả luồng thành công gần nhất.
     *
     * YouTube chặn theo từng client và thường chặn luôn cả phiên: nếu video
     * trước phải rơi xuống client dự phòng thì video sau cũng nên thử client
     * đó trước, tránh lặp lại 1–2 request chắc chắn lỗi mỗi lần mở video.
     */
    @Volatile
    private var preferredClientName: String? = null

    /** Lấy kế hoạch phát; null khi mọi client đều không trả luồng dùng được. */
    fun playbackBlocking(videoId: String): YouTubePlayerResult? {
        if (videoId.isBlank()) return null
        // `sortedBy` ổn định nên thứ tự dự phòng gốc được giữ nguyên.
        val clients = CLIENTS.sortedBy { if (it.name == preferredClientName) 0 else 1 }
        for (client in clients) {
            // `cpn` liên kết request `player` với các URL trả về. Dùng đúng một
            // nonce cho mỗi lần thử để YouTube coi body + URL là cùng phiên.
            val cpn = InnerTubeHttp.generateContentPlaybackNonce()
            val visitorData = if (client.isVisionOs) resolveVisitorData() else null
            if (client.isVisionOs && visitorData.isNullOrBlank()) continue

            val root = playerUrls(client, videoId)
                .firstNotNullOfOrNull { url ->
                    fetchPlayerResponse(videoId, client, url, cpn, visitorData)
                }
                ?: continue
            val status = root.optJSONObject("playabilityStatus")
                ?.optString("status")
                .orEmpty()
            if (status.isNotBlank() && status != "OK") continue
            var result = buildResult(root, cpn) ?: continue
            if (result.plan.subtitles.isEmpty()) {
                val captions = fetchCaptionTracks(videoId)
                if (captions.isNotEmpty()) {
                    result = result.copy(plan = result.plan.copy(subtitles = captions))
                }
            }
            preferredClientName = client.name
            return result
        }
        return null
    }

    /**
     * Một số client phát (VISIONOS/IOS/ANDROID...) không trả danh sách phụ đề.
     * Thử lấy riêng bằng client WEB để vẫn có CC khi video có phụ đề.
     */
    private fun fetchCaptionTracks(videoId: String): List<PlaybackSubtitle> {
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
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
        val raw = InnerTubeHttp.post(
            url = PLAYER_URL_FALLBACK,
            body = payload.toString(),
            userAgent = InnerTubeHttp.DESKTOP_USER_AGENT,
            referer = "https://www.youtube.com/watch?v=$videoId",
            webHeaders = true
        ) ?: return emptyList()
        val root = InnerTubeJson.parseObject(raw) ?: return emptyList()
        val status = root.optJSONObject("playabilityStatus")
            ?.optString("status")
            .orEmpty()
        if (status.isNotBlank() && status != "OK") return emptyList()
        return parseSubtitles(root)
    }

    /**
     * URL `player` theo từng client.
     *
     * Client `VISIONOS` dùng GAPIS + `t`/`id`; các client cũ dùng
     * `www.youtube.com` và tham số thử nghiệm `params=2AMB` (nếu YouTube tạm
     * thời trả 400 thì thử lại URL không tham số để vẫn phát được phần đầu).
     */
    private fun playerUrls(client: PlayerClient, videoId: String): List<String> =
        if (client.isVisionOs) {
            listOf(
                "$GAPIS_PLAYER_URL?prettyPrint=false" +
                    "&t=${InnerTubeHttp.generateTParameter()}" +
                    "&id=$videoId"
            )
        } else {
            listOf(PLAYER_URL, PLAYER_URL_FALLBACK)
        }

    private fun fetchPlayerResponse(
        videoId: String,
        client: PlayerClient,
        url: String,
        cpn: String,
        visitorData: String?
    ): JSONObject? {
        val payload = JSONObject()
            .put("context", buildContext(client, visitorData))
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
        if (client.isVisionOs) payload.put("cpn", cpn)
        val raw = InnerTubeHttp.post(
            url = url,
            body = payload.toString(),
            userAgent = client.userAgent,
            referer = "https://www.youtube.com/watch?v=$videoId",
            extraHeaders = if (client.isVisionOs) {
                mapOf("X-Goog-Api-Format-Version" to VISIONOS_API_FORMAT_VERSION)
            } else {
                emptyMap()
            },
            webHeaders = !client.isVisionOs
        ) ?: return null
        return InnerTubeJson.parseObject(raw)
    }

    /**
     * `visitorData` ẩn danh, lấy một lần rồi dùng cho mọi request visionOS.
     *
     * Giá trị này gắn với phiên thiết bị; xin lại mỗi video vừa tốn một request
     * vừa dễ bị YouTube coi là bất thường.
     */
    private fun resolveVisitorData(): String? {
        cachedVisitorData?.let { return it }
        if (System.currentTimeMillis() < visitorDataRetryAt) return null
        return synchronized(visitorLock) {
            cachedVisitorData ?: run {
                val payload = JSONObject()
                    .put("context", buildContext(VISIONOS_CLIENT, null))
                    .toString()
                val raw = InnerTubeHttp.post(
                    url = VISITOR_ID_URL,
                    body = payload,
                    userAgent = VISIONOS_CLIENT.userAgent,
                    extraHeaders = mapOf(
                        "X-Goog-Api-Format-Version" to VISIONOS_API_FORMAT_VERSION
                    ),
                    webHeaders = false
                )
                val value = InnerTubeJson.parseObject(raw)
                    ?.optJSONObject("responseContext")
                    ?.optString("visitorData")
                    ?.takeIf { it.isNotBlank() }
                if (value != null) {
                    cachedVisitorData = value
                } else {
                    visitorDataRetryAt = System.currentTimeMillis() + VISITOR_RETRY_MS
                }
                value
            }
        }
    }

    private fun buildContext(client: PlayerClient, visitorData: String?): JSONObject {
        val clientJson = JSONObject()
            .put("clientName", client.name)
            .put("clientVersion", client.version)
            .put("hl", "vi")
            .put("gl", "VN")
            .put("utcOffsetMinutes", 0)
        if (!visitorData.isNullOrBlank()) clientJson.put("visitorData", visitorData)
        client.extras.forEach { (key, value) -> clientJson.put(key, value) }

        return JSONObject()
            .put("client", clientJson)
            .put(
                "request",
                JSONObject()
                    .put("internalExperimentFlags", JSONArray())
                    .put("useSsl", true)
            )
            .put("user", JSONObject().put("lockedSafetyMode", false))
    }

    private fun buildResult(root: JSONObject, cpn: String): YouTubePlayerResult? {
        val streams = parseMediaStreams(root, cpn)
        val subtitles = parseSubtitles(root)
        val plan = buildPlaybackPlan(streams, subtitles) ?: return null
        val videoDetails = root.optJSONObject("videoDetails")
        val details = VideoDetails(
            description = videoDetails?.optString("shortDescription").orEmpty().trim(),
            viewCount = videoDetails?.optString("viewCount")?.toLongOrNull() ?: 0L,
            channelId = videoDetails?.optString("channelId").orEmpty(),
            channelTitle = videoDetails?.optString("author").orEmpty()
        )
        return YouTubePlayerResult(
            plan = plan,
            details = details,
            title = videoDetails?.optString("title").orEmpty()
        )
    }

    internal fun parseMediaStreams(
        root: JSONObject,
        cpn: String? = null
    ): YouTubeMediaStreams {
        val videoDetails = root.optJSONObject("videoDetails")
        val streamingData = root.optJSONObject("streamingData")
            ?: return YouTubeMediaStreams()

        val videos = ArrayList<YouTubeDashVideo>()
        val audios = ArrayList<YouTubeDashAudio>()
        val adaptive = streamingData.optJSONArray("adaptiveFormats")
        if (adaptive != null) {
            for (i in 0 until adaptive.length()) {
                val format = adaptive.optJSONObject(i) ?: continue
                when {
                    isVideoMime(format) -> parseDashVideo(format, cpn)?.let(videos::add)
                    isAudioMime(format) -> parseDashAudio(format, cpn)?.let(audios::add)
                }
            }
        }

        val progressive = ArrayList<YouTubeProgressiveStream>()
        val formats = streamingData.optJSONArray("formats")
        if (formats != null) {
            for (i in 0 until formats.length()) {
                val format = formats.optJSONObject(i) ?: continue
                val url = appendContentPlaybackNonce(format.optString("url"), cpn)
                if (url.isBlank() || !isVideoMime(format)) continue
                val mimeType = InnerTubeJson.mimeBase(format.optString("mimeType"))
                    .ifBlank { MIME_MP4 }
                progressive.add(
                    YouTubeProgressiveStream(
                        url = url,
                        mimeType = mimeType,
                        height = format.optInt("height", 0)
                    )
                )
            }
        }

        val hlsUrl = streamingData.optString("hlsManifestUrl")
            .takeIf { it.isNotBlank() }

        val isLive = videoDetails?.optBoolean("isLive") == true
        var duration = videoDetails?.optString("lengthSeconds")?.toLongOrNull() ?: 0L
        if (duration <= 0) {
            duration = (videos.map { it.approxDurationMs } + audios.map { it.approxDurationMs })
                .filter { it > 0 }
                .maxOrNull()
                ?.div(1000L)
                ?: 0L
        }

        return YouTubeMediaStreams(
            dashVideos = videos,
            dashAudios = audios,
            progressive = progressive,
            hlsUrl = hlsUrl,
            durationSeconds = duration,
            isLive = isLive
        )
    }

    /**
     * Gắn `cpn` vào URL `googlevideo`.
     *
     * URL đã có sẵn query (`?itag=...`), nên chỉ cần thêm `&cpn=...`. Nếu
     * YouTube lỡ trả URL đã có `cpn` thì giữ nguyên để không tạo tham số trùng.
     */
    private fun appendContentPlaybackNonce(url: String, cpn: String?): String {
        if (url.isBlank() || cpn.isNullOrBlank()) return url
        if (url.contains("?cpn=") || url.contains("&cpn=")) return url
        val separator = if (url.contains('?')) "&" else "?"
        return "$url$separator" + "cpn=$cpn"
    }

    private fun isVideoMime(format: JSONObject): Boolean =
        InnerTubeJson.mimeBase(format.optString("mimeType")).startsWith("video/")

    private fun isAudioMime(format: JSONObject): Boolean =
        InnerTubeJson.mimeBase(format.optString("mimeType")).startsWith("audio/")

    private fun parseDashVideo(format: JSONObject, cpn: String?): YouTubeDashVideo? {
        val url = appendContentPlaybackNonce(format.optString("url"), cpn)
        if (url.isBlank()) return null
        val init = InnerTubeJson.range(format, "initRange") ?: return null
        val index = InnerTubeJson.range(format, "indexRange") ?: return null
        val mimeType = InnerTubeJson.mimeBase(format.optString("mimeType"))
        val codec = InnerTubeJson.mimeCodec(format.optString("mimeType"))
        val height = format.optInt("height", 0)
        val width = format.optInt("width", 0)
        val bitrate = format.optInt("bitrate", 0)
        if (height <= 0 || width <= 0 || bitrate <= 0 || codec.isBlank()) return null

        return YouTubeDashVideo(
            itag = format.optInt("itag", 0),
            url = url,
            mimeType = mimeType,
            codec = codec,
            width = width,
            height = height,
            fps = format.optInt("fps", 0),
            bitrate = bitrate,
            initStart = init.first,
            initEnd = init.second,
            indexStart = index.first,
            indexEnd = index.second,
            approxDurationMs = format.longValue("approxDurationMs")
        )
    }

    private fun parseDashAudio(format: JSONObject, cpn: String?): YouTubeDashAudio? {
        val url = appendContentPlaybackNonce(format.optString("url"), cpn)
        if (url.isBlank()) return null
        val init = InnerTubeJson.range(format, "initRange") ?: return null
        val index = InnerTubeJson.range(format, "indexRange") ?: return null
        val mimeType = InnerTubeJson.mimeBase(format.optString("mimeType"))
        val codec = InnerTubeJson.mimeCodec(format.optString("mimeType"))
        val bitrate = format.optInt("bitrate", 0)
        if (bitrate <= 0 || codec.isBlank()) return null

        val audioTrack = format.optJSONObject("audioTrack")
        return YouTubeDashAudio(
            itag = format.optInt("itag", 0),
            url = url,
            mimeType = mimeType,
            codec = codec,
            bitrate = bitrate,
            averageBitrate = format.optInt("averageBitrate", 0),
            sampleRate = format.intValue("audioSampleRate"),
            channels = format.intValue("audioChannels"),
            // YouTube không đánh dấu DRC trong response mobile; mặc định tắt.
            isDrc = false,
            isOriginal = audioTrack == null || audioTrack.optBoolean("audioIsDefault", true),
            initStart = init.first,
            initEnd = init.second,
            indexStart = index.first,
            indexEnd = index.second,
            approxDurationMs = format.longValue("approxDurationMs")
        )
    }

    private fun buildPlaybackPlan(
        streams: YouTubeMediaStreams,
        subtitles: List<PlaybackSubtitle>
    ): PlaybackPlan? {
        val generated = if (streams.isLive) {
            null
        } else {
            DashManifestBuilder.build(
                videoStreams = streams.dashVideos,
                audioStreams = streams.dashAudios,
                durationSeconds = streams.durationSeconds,
                supportedCodecFamilies = DeviceVideoCodecs.supportedFamilies
            )
        }
        val generatedSource = generated?.let { manifest ->
            PlaybackSource(
                uri = DATA_URI_PREFIX + Base64.encodeToString(
                    manifest.xml.toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP
                ),
                mimeType = MIME_DASH,
                isAdaptive = true
            )
        }
        val hls = streams.hlsUrl?.let {
            PlaybackSource(it, MIME_HLS, isAdaptive = true)
        }
        val progressive = streams.progressive
            .sortedByDescending { it.height }
            .map { stream ->
                PlaybackSource(
                    uri = stream.url,
                    mimeType = stream.mimeType.ifBlank { MIME_MP4 },
                    height = stream.height.takeIf { it > 0 }
                )
            }

        val candidates = listOfNotNull(generatedSource, hls) + progressive
        if (candidates.isEmpty()) return null

        val supportedFamilies = DeviceVideoCodecs.supportedFamilies
        val adaptiveHeights = streams.dashVideos
            .filter { stream ->
                supportedFamilies.isEmpty() ||
                    DashManifestBuilder.codecFamilyOf(stream.codec, stream.mimeType) in supportedFamilies
            }
            .map { it.height }

        val qualities = when {
            generated != null -> generated.videoHeights
            hls != null -> (adaptiveHeights + streams.progressive.map { it.height })
                .filter { it > 0 }
                .distinct()
                .sortedDescending()
            else -> streams.progressive.map { it.height }
                .filter { it > 0 }
                .distinct()
                .sortedDescending()
        }

        return PlaybackPlan(
            candidates = candidates,
            isLive = streams.isLive,
            qualities = qualities,
            subtitles = subtitles
        )
    }

    private fun parseSubtitles(root: JSONObject): List<PlaybackSubtitle> {
        val tracks = root.optJSONObject("captions")
            ?.optJSONObject("playerCaptionsTracklistRenderer")
            ?.optJSONArray("captionTracks")
            ?: return emptyList()

        val deviceLanguage = Locale.getDefault().language.lowercase(Locale.US)
        fun matchesDevice(languageTag: String): Boolean {
            if (deviceLanguage.isBlank()) return false
            val tag = languageTag.lowercase(Locale.US)
            return tag == deviceLanguage || tag.startsWith("$deviceLanguage-")
        }

        val mapped = ArrayList<PlaybackSubtitle>()
        for (i in 0 until tracks.length()) {
            val track = tracks.optJSONObject(i) ?: continue
            val baseUrl = track.optString("baseUrl")
            if (baseUrl.isBlank()) continue
            val languageTag = track.optString("languageCode")
            val isAuto = track.optString("kind") == "asr"
            val label = InnerTubeJson.text(track.optJSONObject("name"))
                ?: languageTag
            mapped.add(
                PlaybackSubtitle(
                    uri = forceVttFormat(baseUrl),
                    mimeType = MIME_VTT,
                    languageTag = languageTag,
                    label = if (isAuto) "$label (tự động)" else label,
                    isAutoGenerated = isAuto
                )
            )
        }

        val deduped = mapped
            .sortedWith(
                compareBy<PlaybackSubtitle> { it.isAutoGenerated }
                    .thenByDescending { matchesDevice(it.languageTag) }
            )
            .distinctBy { it.languageTag }

        val defaultTag = deduped.firstOrNull {
            !it.isAutoGenerated && matchesDevice(it.languageTag)
        }?.languageTag
            ?: deduped.firstOrNull { matchesDevice(it.languageTag) }?.languageTag
            ?: deduped.firstOrNull { !it.isAutoGenerated }?.languageTag

        return deduped
            .map { it.copy(isDefault = it.languageTag == defaultTag) }
            .sortedWith(
                compareByDescending<PlaybackSubtitle> { it.isDefault }
                    .thenBy { it.isAutoGenerated }
                    .thenBy { it.label }
            )
    }

    /**
     * YouTube trả `baseUrl` của phụ đề kèm `fmt=srv3` tuỳ client; Media3 chỉ
     * hiểu WebVTT nên ta thay tham số định dạng bằng `fmt=vtt`.
     */
    private fun forceVttFormat(baseUrl: String): String {
        val stripped = baseUrl.replace(Regex("([?&])fmt=[^&]*&?"), "$1")
        val separator = when {
            stripped.endsWith("?") || stripped.endsWith("&") -> ""
            stripped.contains('?') -> "&"
            else -> "?"
        }
        return "${stripped}${separator}fmt=vtt"
    }

    private fun JSONObject.intValue(key: String): Int {
        val raw = opt(key)
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull() ?: 0
            else -> 0
        }
    }

    private fun JSONObject.longValue(key: String): Long {
        val raw = opt(key)
        return when (raw) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull() ?: -1L
            else -> -1L
        }
    }
}

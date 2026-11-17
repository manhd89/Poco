package com.luxysiv.puretube.data.repository

import java.util.Locale

/**
 * Kết quả dựng manifest DASH từ các luồng đã trích xuất.
 *
 * [xml] là nội dung MPD; [videoHeights] là các chiều cao thật có trong manifest
 * (đã bỏ trùng, giảm dần) để UI hiển thị đúng những mức chọn được.
 */
data class GeneratedDashManifest(
    val xml: String,
    val videoHeights: List<Int>
)

/**
 * Dựng manifest DASH nhiều chất lượng từ luồng video-only + audio-only của
 * InnerTube.
 *
 * YouTube chỉ trả các luồng video nhiều độ phân giải ở nhóm video-only, nên
 * nếu chỉ dùng progressive MP4 thì nút chọn chất lượng gần như vô tác dụng.
 * Ta tự ghép các luồng đó thành một MPD hoàn chỉnh bằng `SegmentBase` (range
 * request), đúng cấu trúc YouTube dùng cho manifest tự sinh. ExoPlayer nhờ đó
 * thấy toàn bộ track và đổi chất lượng bằng track selection thay vì phải thay
 * MediaItem.
 *
 * Manifest chỉ chứa **một họ codec** (H.264, hoặc VP9/AV1 nếu họ đó có dải
 * chất lượng cao hơn). Nhiều `<AdaptationSet>` video sẽ khiến việc đổi chất
 * lượng nhảy TrackGroup và dựng lại toàn bộ sample stream, gây khựng.
 *
 * Các luồng không có `initRange`/`indexRange` hoặc URL không dùng được sẽ bị
 * bỏ qua. Trả về null khi thiếu video hoặc audio để caller quay về nguồn khác.
 */
object DashManifestBuilder {

    private const val MPD_NAMESPACE = "urn:mpeg:dash:schema:MPD:2011"
    private const val MPD_PROFILE = "urn:mpeg:dash:profile:full:2011"
    private const val ROLE_SCHEME = "urn:mpeg:DASH:role:2011"
    private const val AUDIO_CHANNEL_SCHEME =
        "urn:mpeg:dash:23003:3:audio_channel_configuration:2011"

    private val VIDEO_MIME_TYPES = setOf("video/mp4", "video/webm")

    /**
     * Thứ tự ưu tiên khi hai họ codec có cùng độ phân giải cao nhất / số mức
     * chất lượng. H.264 tương thích rộng nhất, sau đó VP9, AV1, HEVC.
     */
    private val CODEC_FAMILY_ORDER = listOf("avc", "vp9", "av01", "hevc")

    /**
     * [durationSeconds] null/<= 0 sẽ lấy từ `approxDurationMs` của luồng.
     *
     * [supportedCodecFamilies] là các họ codec thiết bị giải mã được. Rỗng/null
     * nghĩa là không dò được (unit test) và giữ tất cả như trước. Nếu lọc xong
     * không còn luồng nào (dữ liệu lạ), quay về danh sách gốc để không mất hình.
     */
    fun build(
        videoStreams: List<YouTubeDashVideo>,
        audioStreams: List<YouTubeDashAudio>,
        durationSeconds: Long? = null,
        supportedCodecFamilies: Set<String>? = null
    ): GeneratedDashManifest? {
        val usable = videoStreams.filter { it.isUsable() }
        val decodable = if (supportedCodecFamilies.isNullOrEmpty()) {
            usable
        } else {
            usable.filter { it.codecFamily() in supportedCodecFamilies }
        }
        val videos = decodable.ifEmpty { usable }
            .distinctBy { "${it.itag}|${it.height}|${it.fps}|${it.bitrate}|${it.codec}" }

        val audio = pickAudio(audioStreams) ?: return null
        if (videos.isEmpty()) return null

        val duration = durationSeconds?.takeIf { it > 0 }
            ?: resolveDurationSeconds(videos, audio)
            ?: return null

        // Media3 coi mỗi <AdaptationSet> là một TrackGroup và chỉ chọn MỘT
        // TrackGroup cho video. Nếu ta đưa cả mp4 lẫn webm vào manifest, việc
        // đổi chất lượng có thể nhảy sang AdaptationSet khác → toàn bộ
        // ChunkSampleStream bị dựng lại, gây khựng rõ rệt. Vì vậy chỉ xuất
        // một họ codec, chọn họ có dải chất lượng cao nhất để vẫn giữ được
        // các mức 1440p/2160p nếu video có.
        val chosenFamily = videos
            .groupBy { it.codecFamily() }
            .entries
            .maxWithOrNull(
                compareBy<Map.Entry<String, List<YouTubeDashVideo>>> { entry ->
                    entry.value.maxOf { it.height }
                }
                    .thenBy { entry -> entry.value.map { it.height }.distinct().size }
                    .thenByDescending { entry -> codecFamilyPriority(entry.key) }
            )
            ?: return null
        val chosenVideos = chosenFamily.value
        val videoMimeType = chosenVideos.first().mimeType

        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<MPD")
            appendAttribute("xmlns", MPD_NAMESPACE)
            appendAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")
            appendAttribute(
                "xsi:schemaLocation",
                "$MPD_NAMESPACE DASH-MPD.xsd"
            )
            appendAttribute("profiles", MPD_PROFILE)
            appendAttribute("type", "static")
            appendAttribute("minBufferTime", "PT1.500S")
            appendAttribute(
                "mediaPresentationDuration",
                String.format(Locale.US, "PT%.3fS", duration.toDouble())
            )
            append(">\n")
            append("  <Period>\n")

            appendVideoAdaptationSet(0, videoMimeType, chosenVideos)
            appendAudioAdaptationSet(1, audio)

            append("  </Period>\n")
            append("</MPD>\n")
        }

        val heights = chosenVideos.map { it.height }.distinct().sortedDescending()
        return GeneratedDashManifest(xml = xml, videoHeights = heights)
    }

    private fun StringBuilder.appendVideoAdaptationSet(
        id: Int,
        mimeType: String,
        streams: List<YouTubeDashVideo>
    ) {
        append("    <AdaptationSet")
        appendAttribute("id", id.toString())
        appendAttribute("mimeType", mimeType)
        appendAttribute("subsegmentAlignment", "true")
        appendAttribute("startWithSAP", "1")
        append(">\n")
        append("      <Role")
        appendAttribute("schemeIdUri", ROLE_SCHEME)
        appendAttribute("value", "main")
        append("/>\n")

        streams
            .sortedWith(
                compareByDescending<YouTubeDashVideo> { it.height }
                    .thenByDescending { it.fps }
                    .thenByDescending { it.bitrate }
            )
            .forEach { appendVideoRepresentation(it) }

        append("    </AdaptationSet>\n")
    }

    private fun StringBuilder.appendVideoRepresentation(stream: YouTubeDashVideo) {
        append("      <Representation")
        appendAttribute("id", representationId(stream.itag, stream.height, stream.bitrate))
        appendAttribute("codecs", stream.codec)
        appendAttribute("bandwidth", stream.bitrate.toString())
        appendAttribute("width", stream.width.toString())
        appendAttribute("height", stream.height.toString())
        if (stream.fps > 0) appendAttribute("frameRate", stream.fps.toString())
        append(">\n")
        appendBaseUrlAndSegmentBase(stream)
        append("      </Representation>\n")
    }

    private fun StringBuilder.appendAudioAdaptationSet(id: Int, stream: YouTubeDashAudio) {
        append("    <AdaptationSet")
        appendAttribute("id", id.toString())
        appendAttribute("mimeType", stream.mimeType)
        appendAttribute("subsegmentAlignment", "true")
        appendAttribute("startWithSAP", "1")
        append(">\n")
        append("      <Role")
        appendAttribute("schemeIdUri", ROLE_SCHEME)
        appendAttribute("value", "main")
        append("/>\n")
        append("      <Representation")
        appendAttribute("id", representationId(stream.itag, 0, stream.bitrate))
        appendAttribute("codecs", stream.codec)
        appendAttribute("bandwidth", stream.effectiveBitrate().toString())
        if (stream.sampleRate > 0) {
            appendAttribute("audioSamplingRate", stream.sampleRate.toString())
        }
        append(">\n")

        if (stream.channels > 0) {
            append("        <AudioChannelConfiguration")
            appendAttribute("schemeIdUri", AUDIO_CHANNEL_SCHEME)
            appendAttribute("value", stream.channels.toString())
            append("/>\n")
        }

        appendBaseUrlAndSegmentBase(stream)
        append("      </Representation>\n")
        append("    </AdaptationSet>\n")
    }

    private fun StringBuilder.appendBaseUrlAndSegmentBase(stream: YouTubeDashVideo) {
        appendBaseUrlAndSegmentBase(
            url = stream.url,
            initStart = stream.initStart,
            initEnd = stream.initEnd,
            indexStart = stream.indexStart,
            indexEnd = stream.indexEnd
        )
    }

    private fun StringBuilder.appendBaseUrlAndSegmentBase(stream: YouTubeDashAudio) {
        appendBaseUrlAndSegmentBase(
            url = stream.url,
            initStart = stream.initStart,
            initEnd = stream.initEnd,
            indexStart = stream.indexStart,
            indexEnd = stream.indexEnd
        )
    }

    private fun StringBuilder.appendBaseUrlAndSegmentBase(
        url: String,
        initStart: Long,
        initEnd: Long,
        indexStart: Long,
        indexEnd: Long
    ) {
        append("        <BaseURL>").append(xmlEscape(url)).append("</BaseURL>\n")
        append("        <SegmentBase")
        appendAttribute("indexRange", "$indexStart-$indexEnd")
        append(">\n")
        append("          <Initialization")
        appendAttribute("range", "$initStart-$initEnd")
        append("/>\n")
        append("        </SegmentBase>\n")
    }

    private fun StringBuilder.appendAttribute(name: String, value: String?) {
        if (value.isNullOrEmpty()) return
        append(' ').append(name).append("=\"").append(xmlEscape(value)).append('"')
    }

    private fun representationId(itag: Int, height: Int, bitrate: Int): String =
        if (itag > 0) itag.toString() else "v$height-$bitrate"

    private fun YouTubeDashVideo.isUsable(): Boolean =
        url.isNotBlank() &&
            height > 0 &&
            width > 0 &&
            bitrate > 0 &&
            codec.isNotBlank() &&
            mimeType in VIDEO_MIME_TYPES &&
            initStart >= 0 &&
            initEnd > initStart &&
            indexStart >= 0 &&
            indexEnd > indexStart

    private fun YouTubeDashAudio.isUsable(): Boolean =
        url.isNotBlank() &&
            bitrate > 0 &&
            codec.isNotBlank() &&
            mimeType.startsWith("audio/") &&
            initStart >= 0 &&
            initEnd > initStart &&
            indexStart >= 0 &&
            indexEnd > indexStart

    private fun YouTubeDashAudio.effectiveBitrate(): Int =
        averageBitrate.takeIf { it > 0 } ?: bitrate

    /**
     * Họ codec dùng để gom các representation có thể đổi qua lại liền mạch.
     * Đổi codec (ví dụ H.264 → VP9) buộc decoder khởi tạo lại nên ta tách
     * riêng; chỉ một họ được đưa vào manifest.
     */
    private fun YouTubeDashVideo.codecFamily(): String = codecFamilyOf(codec, mimeType)

    /** Họ codec của một chuỗi `codecs` MPD (dùng cả cho danh sách chất lượng HLS). */
    internal fun codecFamilyOf(codec: String, mimeType: String): String {
        val value = codec.lowercase(Locale.US)
        return when {
            value.startsWith("avc1") || value.startsWith("avc3") -> "avc"
            value.startsWith("hev1") || value.startsWith("hvc1") -> "hevc"
            value.startsWith("av01") -> "av01"
            value.startsWith("vp09") || value.startsWith("vp9") -> "vp9"
            value.isBlank() -> mimeType
            else -> value.substringBefore('.')
        }
    }

    private fun codecFamilyPriority(family: String): Int =
        CODEC_FAMILY_ORDER.indexOf(family).let { if (it < 0) Int.MAX_VALUE else it }

    /**
     * Chọn một track âm thanh để ghép vào manifest.
     *
     * Ưu tiên bản gốc (không lồng tiếng/mô tả), không bật DRC, codec AAC
     * (`mp4a`) vì tương thích rộng nhất; sau đó mới đến Opus/Vorbis. Trong
     * cùng nhóm chọn bitrate cao nhất.
     */
    private fun pickAudio(streams: List<YouTubeDashAudio>): YouTubeDashAudio? {
        val usable = streams.filter { it.isUsable() }
        if (usable.isEmpty()) return null

        val original = usable.filter { it.isOriginal }
        val withoutDrc = original.filter { !it.isDrc }
        val pool = withoutDrc.ifEmpty { original }.ifEmpty { usable }

        val aac = pool.filter {
            it.codec.startsWith("mp4a") || it.mimeType == "audio/mp4"
        }
        val preferred = aac.ifEmpty { pool }
        return preferred.maxByOrNull { it.effectiveBitrate() }
    }

    private fun resolveDurationSeconds(
        videos: List<YouTubeDashVideo>,
        audio: YouTubeDashAudio
    ): Long? {
        // approxDurationMs = -1 nghĩa là không rõ, không được để nó "thắng"
        // video/audio còn lại (maxOrNull của -1 luôn là -1).
        val approxMs = (
            videos.map { it.approxDurationMs } + audio.approxDurationMs
            )
            .filter { it > 0 }
            .maxOrNull()
            ?: return null
        return (approxMs / 1000L).coerceAtLeast(1L)
    }

    /** Escape ký tự XML; URL googlevideo luôn chứa `&` nên bước này bắt buộc. */
    private fun xmlEscape(value: String): String = buildString(value.length + 16) {
        value.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(char)
            }
        }
    }
}

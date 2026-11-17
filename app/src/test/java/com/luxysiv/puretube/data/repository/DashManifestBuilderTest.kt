package com.luxysiv.puretube.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kiểm tra manifest DASH tự dựng — nguồn duy nhất chứa đủ track video nhiều
 * độ phân giải để nút chọn chất lượng hoạt động.
 */
class DashManifestBuilderTest {

    private fun video(
        itag: Int,
        height: Int,
        mimeType: String = "video/mp4",
        codec: String = "avc1.640028",
        bitrate: Int = 2_000_000,
        fps: Int = 30,
        initStart: Long = 0,
        initEnd: Long = 799,
        indexStart: Long = 800,
        indexEnd: Long = 1_999,
        url: String = "https://rr1.googlevideo.com/videoplayback?itag=$itag&mime=video%2Fmp4"
    ): YouTubeDashVideo = YouTubeDashVideo(
        itag = itag,
        url = url,
        mimeType = mimeType,
        codec = codec,
        width = height * 16 / 9,
        height = height,
        fps = fps,
        bitrate = bitrate,
        initStart = initStart,
        initEnd = initEnd,
        indexStart = indexStart,
        indexEnd = indexEnd
    )

    private fun audio(
        itag: Int,
        mimeType: String = "audio/mp4",
        codec: String = "mp4a.40.2",
        bitrate: Int = 128_000,
        averageBitrate: Int = bitrate,
        isOriginal: Boolean = true,
        isDrc: Boolean = false,
        approxDurationMs: Long = -1,
        url: String = "https://rr1.googlevideo.com/videoplayback?itag=$itag&mime=audio%2Fmp4"
    ): YouTubeDashAudio = YouTubeDashAudio(
        itag = itag,
        url = url,
        mimeType = mimeType,
        codec = codec,
        bitrate = bitrate,
        averageBitrate = averageBitrate,
        sampleRate = 48_000,
        channels = 2,
        isDrc = isDrc,
        isOriginal = isOriginal,
        initStart = 0,
        initEnd = 599,
        indexStart = 600,
        indexEnd = 1_199,
        approxDurationMs = approxDurationMs
    )

    @Test
    fun `builds one representation per video track plus audio`() {
        val manifest = DashManifestBuilder.build(
            videoStreams = listOf(
                video(137, height = 1080, bitrate = 4_000_000),
                video(136, height = 720, bitrate = 2_000_000)
            ),
            audioStreams = listOf(audio(140)),
            durationSeconds = 213
        )

        requireNotNull(manifest)
        assertEquals(listOf(1080, 720), manifest.videoHeights)
        assertEquals(3, Regex("<Representation").findAll(manifest.xml).count())
        assertTrue(manifest.xml.contains("mediaPresentationDuration=\"PT213.000S\""))
        assertTrue(manifest.xml.contains("indexRange=\"800-1999\""))
        assertTrue(manifest.xml.contains("<Initialization range=\"0-799\""))
        assertTrue(manifest.xml.contains("mimeType=\"audio/mp4\""))
        assertTrue(manifest.xml.contains("<AudioChannelConfiguration"))
    }

    @Test
    fun `escapes ampersands in googlevideo urls`() {
        val manifest = DashManifestBuilder.build(
            videoStreams = listOf(video(137, height = 1080)),
            audioStreams = listOf(audio(140)),
            durationSeconds = 60
        )

        requireNotNull(manifest)
        assertTrue(manifest.xml.contains("&amp;mime=video%2Fmp4"))
        assertFalse(manifest.xml.contains("?itag=137&mime="))
    }

    @Test
    fun `emits a single video adaptation set`() {
        val manifest = DashManifestBuilder.build(
            videoStreams = listOf(
                video(248, mimeType = "video/webm", height = 1080, codec = "vp9"),
                video(137, height = 1080)
            ),
            audioStreams = listOf(audio(140)),
            durationSeconds = 60
        )

        requireNotNull(manifest)
        // Hai AdaptationSet video sẽ khiến Media3 đổi TrackGroup khi đổi chất
        // lượng; trên cùng độ phân giải, H.264 được ưu tiên.
        assertEquals(1, Regex("mimeType=\"video/").findAll(manifest.xml).count())
        assertTrue(manifest.xml.contains("mimeType=\"video/mp4\""))
        assertFalse(manifest.xml.contains("mimeType=\"video/webm\""))
    }

    @Test
    fun `picks the codec family with the highest available quality`() {
        val manifest = DashManifestBuilder.build(
            videoStreams = listOf(
                video(137, height = 1080),
                video(313, mimeType = "video/webm", height = 2160, codec = "vp09.00.51.08")
            ),
            audioStreams = listOf(audio(140)),
            durationSeconds = 60
        )

        requireNotNull(manifest)
        assertEquals(listOf(2160), manifest.videoHeights)
        assertTrue(manifest.xml.contains("mimeType=\"video/webm\""))
        assertFalse(manifest.xml.contains("mimeType=\"video/mp4\""))
    }

    @Test
    fun `prefers original non-DRC AAC with the highest bitrate`() {
        val manifest = DashManifestBuilder.build(
            videoStreams = listOf(video(137, height = 1080)),
            audioStreams = listOf(
                audio(251, mimeType = "audio/webm", bitrate = 160_000, codec = "opus"),
                audio(140, bitrate = 128_000),
                audio(141, bitrate = 256_000, isDrc = true),
                audio(139, bitrate = 48_000, isOriginal = false)
            ),
            durationSeconds = 60
        )

        requireNotNull(manifest)
        assertTrue(manifest.xml.contains("<Representation id=\"140\""))
        assertFalse(manifest.xml.contains("<Representation id=\"141\""))
        assertFalse(manifest.xml.contains("<Representation id=\"251\""))
        assertFalse(manifest.xml.contains("<Representation id=\"139\""))
    }

    @Test
    fun `keeps only the chosen codec family`() {
        val manifest = DashManifestBuilder.build(
            videoStreams = listOf(
                video(137, height = 1080, codec = "avc1"),
                video(248, mimeType = "video/webm", height = 1080, codec = "vp9"),
                video(136, height = 720, codec = "avc1")
            ),
            audioStreams = listOf(audio(140)),
            durationSeconds = 60
        )

        requireNotNull(manifest)
        assertEquals(listOf(1080, 720), manifest.videoHeights)
        // 2 representation video H.264 + 1 audio; representation VP9 bị loại.
        assertEquals(3, Regex("<Representation").findAll(manifest.xml).count())
        assertFalse(manifest.xml.contains("itag=248"))
    }

    @Test
    fun `filters out codec families the device cannot decode`() {
        val manifest = DashManifestBuilder.build(
            videoStreams = listOf(
                video(137, height = 1080),
                video(313, mimeType = "video/webm", height = 2160, codec = "vp09.00.51.08")
            ),
            audioStreams = listOf(audio(140)),
            durationSeconds = 60,
            supportedCodecFamilies = setOf("avc")
        )

        requireNotNull(manifest)
        // VP9 2160p bị loại vì máy chỉ giải mã H.264; không được chọn nó chỉ
        // vì độ phân giải cao hơn.
        assertEquals(listOf(1080), manifest.videoHeights)
        assertTrue(manifest.xml.contains("mimeType=\"video/mp4\""))
        assertFalse(manifest.xml.contains("mimeType=\"video/webm\""))
    }

    @Test
    fun `keeps every family when device codec support is unknown`() {
        val manifest = DashManifestBuilder.build(
            videoStreams = listOf(
                video(137, height = 1080),
                video(313, mimeType = "video/webm", height = 2160, codec = "vp09.00.51.08")
            ),
            audioStreams = listOf(audio(140)),
            durationSeconds = 60,
            supportedCodecFamilies = emptySet()
        )

        requireNotNull(manifest)
        assertEquals(listOf(2160), manifest.videoHeights)
        assertTrue(manifest.xml.contains("mimeType=\"video/webm\""))
    }

    @Test
    fun `drops streams without byte ranges`() {
        val manifest = DashManifestBuilder.build(
            videoStreams = listOf(video(137, height = 1080, indexStart = -1, indexEnd = -1)),
            audioStreams = listOf(audio(140)),
            durationSeconds = 60
        )

        assertNull(manifest)
    }

    @Test
    fun `needs both video and audio`() {
        assertNull(
            DashManifestBuilder.build(
                videoStreams = emptyList(),
                audioStreams = listOf(audio(140)),
                durationSeconds = 60
            )
        )
        assertNull(
            DashManifestBuilder.build(
                videoStreams = listOf(video(137, height = 1080)),
                audioStreams = emptyList(),
                durationSeconds = 60
            )
        )
    }

    @Test
    fun `falls back to approx duration from the streams`() {
        val manifest = DashManifestBuilder.build(
            videoStreams = listOf(video(137, height = 1080)),
            audioStreams = listOf(audio(140, approxDurationMs = 90_000))
        )

        requireNotNull(manifest)
        assertTrue(manifest.xml.contains("mediaPresentationDuration=\"PT90.000S\""))
    }

    @Test
    fun `returns null when duration is unknown`() {
        assertNull(
            DashManifestBuilder.build(
                videoStreams = listOf(video(137, height = 1080)),
                audioStreams = listOf(audio(140))
            )
        )
    }
}

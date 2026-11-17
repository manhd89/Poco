package com.luxysiv.puretube.data.repository

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Kiểm tra các tiện ích InnerTube dùng chung: đọc số kiểu Việt Nam, MIME/codec
 * và tách luồng phát từ response `player`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InnerTubeSupportTest {

    @Test
    fun `parses Vietnamese compact counts`() {
        assertEquals(1_834_597_073L, InnerTubeJson.parseCount("1.834.597.073 lượt xem"))
        assertEquals(671_000L, InnerTubeJson.parseCount("671 N"))
        assertEquals(2_400L, InnerTubeJson.parseCount("2,4 N"))
        assertEquals(2_400_000L, InnerTubeJson.parseCount("2,4 Tr"))
        assertEquals(1_500_000L, InnerTubeJson.parseCount("1,5 triệu"))
        assertEquals(1_800_000_000L, InnerTubeJson.parseCount("1,8 T"))
        assertEquals(1_234L, InnerTubeJson.parseCount("1.234"))
        assertEquals(0L, InnerTubeJson.parseCount("Không có"))
        assertEquals(0L, InnerTubeJson.parseCount(null))
    }

    @Test
    fun `picks the largest thumbnail and adds the https scheme`() {
        val thumbnail = JSONObject(
            """
            {
              "thumbnails": [
                { "url": "//yt3.googleusercontent.com/a=s48", "width": 48, "height": 48 },
                { "url": "//yt3.googleusercontent.com/a=s208", "width": 208, "height": 208 }
              ]
            }
            """.trimIndent()
        )

        assertEquals(
            "https://yt3.googleusercontent.com/a=s208",
            InnerTubeJson.bestThumbnailUrl(thumbnail)
        )
        assertEquals(
            "https://yt3.googleusercontent.com/a=s88",
            InnerTubeJson.bestThumbnailUrl(
                JSONObject().put("url", "//yt3.googleusercontent.com/a=s88")
            )
        )
    }

    @Test
    fun `splits mime type and codec`() {
        val mime = "video/mp4; codecs=\"avc1.640028\""
        assertEquals("video/mp4", InnerTubeJson.mimeBase(mime))
        assertEquals("avc1.640028", InnerTubeJson.mimeCodec(mime))
    }

    @Test
    fun `parses adaptive streams, progressive fallback and duration`() {
        val root = JSONObject(
            """
            {
              "playabilityStatus": { "status": "OK" },
              "videoDetails": { "lengthSeconds": "213", "isLive": false },
              "streamingData": {
                "adaptiveFormats": [
                  {
                    "itag": 137,
                    "url": "https://rr1.googlevideo.com/videoplayback?itag=137",
                    "mimeType": "video/mp4; codecs=\"avc1.640028\"",
                    "width": 1920,
                    "height": 1080,
                    "fps": 30,
                    "bitrate": 4000000,
                    "initRange": { "start": "0", "end": "799" },
                    "indexRange": { "start": "800", "end": "1999" },
                    "approxDurationMs": "213000"
                  },
                  {
                    "itag": 140,
                    "url": "https://rr1.googlevideo.com/videoplayback?itag=140",
                    "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                    "bitrate": 128000,
                    "averageBitrate": 127000,
                    "audioSampleRate": "48000",
                    "audioChannels": 2,
                    "initRange": { "start": "0", "end": "599" },
                    "indexRange": { "start": "600", "end": "1199" },
                    "approxDurationMs": "213000"
                  }
                ],
                "formats": [
                  {
                    "itag": 18,
                    "url": "https://rr1.googlevideo.com/videoplayback?itag=18",
                    "mimeType": "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"",
                    "width": 640,
                    "height": 360
                  }
                ],
                "hlsManifestUrl": "https://manifest.googlevideo.com/api/manifest/hls_variant/id"
              }
            }
            """.trimIndent()
        )

        val streams = YouTubePlayerDataSource.parseMediaStreams(root)

        assertEquals(1, streams.dashVideos.size)
        assertEquals(1080, streams.dashVideos[0].height)
        assertEquals(1, streams.dashAudios.size)
        assertEquals(48_000, streams.dashAudios[0].sampleRate)
        assertEquals(1, streams.progressive.size)
        assertEquals(360, streams.progressive[0].height)
        assertEquals(213L, streams.durationSeconds)
        assertFalse(streams.isLive)
        assertTrue(streams.hlsUrl!!.startsWith("https://manifest.googlevideo.com"))
    }

    @Test
    fun `generates url safe content playback nonce`() {
        val cpn = InnerTubeHttp.generateContentPlaybackNonce()

        assertEquals(16, cpn.length)
        assertTrue(cpn.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertEquals(12, InnerTubeHttp.generateTParameter().length)
    }

    @Test
    fun `appends content playback nonce to playable urls only once`() {
        val root = JSONObject(
            """
            {
              "videoDetails": { "lengthSeconds": "60" },
              "streamingData": {
                "adaptiveFormats": [
                  {
                    "itag": 137,
                    "url": "https://rr1.googlevideo.com/videoplayback?itag=137",
                    "mimeType": "video/mp4; codecs=\"avc1.640028\"",
                    "width": 1920,
                    "height": 1080,
                    "bitrate": 4000000,
                    "initRange": { "start": "0", "end": "799" },
                    "indexRange": { "start": "800", "end": "1999" }
                  },
                  {
                    "itag": 140,
                    "url": "https://rr1.googlevideo.com/videoplayback?itag=140&cpn=OLD",
                    "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                    "bitrate": 128000,
                    "initRange": { "start": "0", "end": "599" },
                    "indexRange": { "start": "600", "end": "1199" }
                  }
                ],
                "formats": [
                  {
                    "itag": 18,
                    "url": "https://rr1.googlevideo.com/videoplayback?itag=18",
                    "mimeType": "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"",
                    "height": 360
                  }
                ],
                "hlsManifestUrl": "https://manifest.googlevideo.com/api/manifest/hls_variant/id"
              }
            }
            """.trimIndent()
        )

        val streams = YouTubePlayerDataSource.parseMediaStreams(root, cpn = "TEST_CPN-123456")

        assertTrue(streams.dashVideos[0].url.endsWith("&cpn=TEST_CPN-123456"))
        assertEquals(
            "https://rr1.googlevideo.com/videoplayback?itag=140&cpn=OLD",
            streams.dashAudios[0].url
        )
        assertTrue(streams.progressive[0].url.endsWith("&cpn=TEST_CPN-123456"))
        assertFalse(streams.hlsUrl!!.contains("cpn="))
    }
}

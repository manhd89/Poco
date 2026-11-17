package com.luxysiv.puretube.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Kiểm tra parse `ytInitialData` của trang watch cho "Danh sách kết hợp". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YouTubeWebDataSourceMixTest {

    private val mixJson = """
        {
          "contents": {
            "twoColumnWatchNextResults": {
              "playlist": {
                "playlist": {
                  "title": "Danh sách kết hợp – Seed",
                  "playlistId": "RDseed0000000",
                  "contents": [
                    { "playlistPanelVideoRenderer": {
                        "videoId": "seed0000000",
                        "title": { "simpleText": "Seed video" },
                        "longBylineText": { "runs": [ { "text": "Kênh A" } ] },
                        "lengthText": { "simpleText": "3:34" },
                        "thumbnail": { "thumbnails": [
                          { "url": "https://img.test/seed.jpg", "width": 320, "height": 180 }
                        ] }
                    } },
                    { "playlistPanelVideoRenderer": {
                        "videoId": "next0000000",
                        "title": { "simpleText": "Next video" },
                        "longBylineText": { "runs": [ { "text": "Kênh B" } ] },
                        "lengthText": { "simpleText": "4:05" }
                    } },
                    { "playlistPanelVideoRenderer": {
                        "videoId": "last0000000",
                        "title": { "simpleText": "Last video" },
                        "longBylineText": { "runs": [ { "text": "Kênh C" } ] },
                        "lengthText": { "simpleText": "2:01" }
                    } },
                    { "playlistPanelVideoRenderer": {
                        "videoId": "bad00000000",
                        "title": { "simpleText": "" }
                    } }
                  ]
                }
              }
            }
          }
        }
    """.trimIndent()

    @Test
    fun `parses first page and keeps the seed video`() {
        val mix = YouTubeWebDataSource.parseMixPlaylist(
            json = mixJson,
            anchorVideoId = "seed0000000",
            playlistId = "RDseed0000000",
            includeAnchor = true
        )

        assertEquals("RDseed0000000", mix?.id)
        assertEquals("Danh sách kết hợp – Seed", mix?.title)
        assertEquals(
            listOf("seed0000000", "next0000000", "last0000000"),
            mix?.videos?.map { it.id }
        )
        assertEquals("Kênh A", mix?.videos?.first()?.channelTitle)
        assertEquals("3:34", mix?.videos?.first()?.duration)
        assertEquals("https://img.test/seed.jpg", mix?.videos?.first()?.thumbnailUrl)
        // Renderer thiếu title bị bỏ, không tạo dòng rỗng trong sheet.
        assertEquals(3, mix?.videos?.size)
    }

    @Test
    fun `next page drops the anchor and everything before it`() {
        val mix = YouTubeWebDataSource.parseMixPlaylist(
            json = mixJson,
            anchorVideoId = "next0000000",
            playlistId = "RDseed0000000",
            includeAnchor = false
        )

        assertEquals(listOf("last0000000"), mix?.videos?.map { it.id })
    }

    @Test
    fun `missing playlist returns null instead of a one item mix`() {
        assertNull(
            YouTubeWebDataSource.parseMixPlaylist(
                json = """{ "contents": {} }""",
                anchorVideoId = "seed0000000",
                playlistId = "RDseed0000000",
                includeAnchor = true
            )
        )
    }
}

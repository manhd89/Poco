package com.luxysiv.puretube.data.repository

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Kiểm tra parse feed danh mục (không cần mạng). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YouTubeWebDataSourceFeedTest {

    @Test
    fun `parses grid video renderer used by gaming feed`() {
        val json = """
            {
              "contents": {
                "gridVideoRenderer": {
                  "videoId": "abcdefghijk",
                  "title": { "runs": [ { "text": "Video trò chơi" } ] },
                  "shortBylineText": { "runs": [
                    {
                      "text": "Kênh Game",
                      "navigationEndpoint": { "browseEndpoint": { "browseId": "UCgame" } }
                    }
                  ] },
                  "viewCountText": { "simpleText": "1.234 lượt xem" },
                  "publishedTimeText": { "simpleText": "2 ngày trước" },
                  "thumbnailOverlays": [
                    {
                      "thumbnailOverlayTimeStatusRenderer": {
                        "text": { "simpleText": "12:34" }
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val videos = YouTubeWebDataSource.parseBrowseVideos(JSONObject(json))

        assertEquals(1, videos.size)
        assertEquals("abcdefghijk", videos[0].id)
        assertEquals("Video trò chơi", videos[0].title)
        assertEquals("Kênh Game", videos[0].channelTitle)
        assertEquals("UCgame", videos[0].channelId)
        assertEquals("12:34", videos[0].duration)
        assertEquals("1.234 lượt xem", videos[0].viewCount)
    }

    @Test
    fun `parses category continuation token for infinite scroll`() {
        val json = JSONObject(
            """
            {
              "contents": {
                "continuationItemRenderer": {
                  "continuationEndpoint": {
                    "continuationCommand": { "token": "next-category-page" }
                  }
                }
              }
            }
            """.trimIndent()
        )

        val page = YouTubeWebDataSource.parseCategoryPage(json)

        assertEquals("next-category-page", page.nextToken)
        assertTrue(page.hasMore)
        assertTrue(page.videos.isEmpty())
    }

    @Test
    fun `parses music chart list item`() {
        val json = """
            {
              "contents": {
                "musicResponsiveListItemRenderer": {
                  "overlay": {
                    "musicItemThumbnailOverlayRenderer": {
                      "content": {
                        "musicPlayButtonRenderer": {
                          "playNavigationEndpoint": {
                            "watchEndpoint": { "videoId": "abcdefghijk" }
                          }
                        }
                      }
                    }
                  },
                  "flexColumns": [
                    {
                      "musicResponsiveListItemFlexColumnRenderer": {
                        "text": { "runs": [ { "text": "Bài hát" } ] }
                      }
                    },
                    {
                      "musicResponsiveListItemFlexColumnRenderer": {
                        "text": { "runs": [ { "text": "Ca sĩ" } ] }
                      }
                    }
                  ],
                  "fixedColumns": [
                    {
                      "musicResponsiveListItemFixedColumnRenderer": {
                        "text": { "runs": [ { "text": "3:21" } ] }
                      }
                    }
                  ],
                  "thumbnail": {
                    "musicThumbnailRenderer": {
                      "thumbnail": { "thumbnails": [
                        { "url": "https://img.test/music.jpg", "width": 400, "height": 225 }
                      ] }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val videos = YouTubeWebDataSource.parseMusicChart(JSONObject(json))

        assertEquals(1, videos.size)
        assertEquals("abcdefghijk", videos[0].id)
        assertEquals("Bài hát", videos[0].title)
        assertEquals("Ca sĩ", videos[0].channelTitle)
        assertEquals("3:21", videos[0].duration)
        assertEquals("https://img.test/music.jpg", videos[0].thumbnailUrl)
    }
}

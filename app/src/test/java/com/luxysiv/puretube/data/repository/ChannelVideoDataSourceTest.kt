package com.luxysiv.puretube.data.repository

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Kiểm tra parse trang "Video" của kênh (InnerTube browse) không cần mạng. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChannelVideoDataSourceTest {

    private val now = 1_700_000_000_000L

    private val browse = JSONObject(
        """
        {
          "contents": {
            "richGridRenderer": {
              "contents": [
                {
                  "richItemRenderer": {
                    "content": {
                      "lockupViewModel": {
                        "contentId": "newVideo001",
                        "contentType": "LOCKUP_CONTENT_TYPE_VIDEO",
                        "contentImage": {
                          "thumbnailViewModel": {
                            "image": {
                              "sources": [
                                { "url": "https://i.ytimg.com/vi/newVideo001/hq720.jpg", "width": 720, "height": 404 }
                              ]
                            },
                            "overlays": [
                              {
                                "thumbnailBottomOverlayViewModel": {
                                  "badges": [
                                    { "thumbnailBadgeViewModel": { "text": "12:34" } }
                                  ]
                                }
                              }
                            ]
                          }
                        },
                        "metadata": {
                          "lockupMetadataViewModel": {
                            "title": { "content": "Video mới nhất" },
                            "metadata": {
                              "contentMetadataViewModel": {
                                "metadataRows": [
                                  {
                                    "metadataParts": [
                                      { "text": { "content": "1,2 Tr lượt xem" } }
                                    ]
                                  },
                                  {
                                    "metadataParts": [
                                      { "text": { "content": "2 ngày trước" } }
                                    ]
                                  }
                                ]
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                },
                {
                  "richItemRenderer": {
                    "content": {
                      "lockupViewModel": {
                        "contentId": "oldVideo002",
                        "contentType": "LOCKUP_CONTENT_TYPE_VIDEO",
                        "metadata": {
                          "lockupMetadataViewModel": {
                            "title": { "content": "Video cũ" },
                            "metadata": {
                              "contentMetadataViewModel": {
                                "metadataRows": [
                                  {
                                    "metadataParts": [
                                      { "text": { "content": "850 N lượt xem" } }
                                    ]
                                  },
                                  {
                                    "metadataParts": [
                                      { "text": { "content": "3 tuần trước" } }
                                    ]
                                  }
                                ]
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()
    )

    @Test
    fun `parses channel videos with title duration views and published time`() {
        val entries = ChannelVideoDataSource.parse(browse, now)

        assertEquals(listOf("newVideo001", "oldVideo002"), entries.map { it.videoId })
        val newest = entries.first()
        assertEquals("Video mới nhất", newest.title)
        assertEquals("12:34", newest.duration)
        assertEquals("1,2 tr", newest.viewCount)
        assertEquals("https://i.ytimg.com/vi/newVideo001/hq720.jpg", newest.thumbnailUrl)
        assertEquals(now - 2 * 86_400_000L, newest.publishedAt)
    }

    @Test
    fun `falls back to response order when published text is missing`() {
        val entries = ChannelVideoDataSource.parse(browse, now)
        val oldest = entries.last()

        // `3 tuần trước` đọc được nên vẫn có mốc thật.
        assertEquals(now - 3 * 604_800_000L, oldest.publishedAt)
    }

    @Test
    fun `parseRelativeTime understands Vietnamese and English units`() {
        assertEquals(now - 30_000L, ChannelVideoDataSource.parseRelativeTime("30 giây trước", now))
        assertEquals(now - 2 * 3_600_000L, ChannelVideoDataSource.parseRelativeTime("2 giờ trước", now))
        assertEquals(now - 3 * 86_400_000L, ChannelVideoDataSource.parseRelativeTime("3 days ago", now))
        assertEquals(now, ChannelVideoDataSource.parseRelativeTime("vừa xong", now))
        assertEquals(0L, ChannelVideoDataSource.parseRelativeTime("Đang phát trực tiếp", now))
    }

    @Test
    fun `returns empty list for channel without videos`() {
        val empty = ChannelVideoDataSource.parse(JSONObject("{}"), now)

        assertTrue(empty.isEmpty())
    }

    @Test
    fun `extracts continuation token for older channel videos`() {
        val json = JSONObject(
            """
            {
              "contents": {
                "continuationItemRenderer": {
                  "continuationEndpoint": {
                    "continuationCommand": { "token": "older-videos-token" }
                  }
                }
              }
            }
            """.trimIndent()
        )

        val page = ChannelVideoDataSource.parsePage(json, now)

        assertEquals("older-videos-token", page.nextToken)
        assertTrue(page.hasMore)
        assertTrue(page.entries.isEmpty())
    }

    @Test
    fun `first page without continuation has no more pages`() {
        val page = ChannelVideoDataSource.parsePage(browse, now)

        assertEquals("", page.nextToken)
        assertTrue(!page.hasMore)
        assertEquals(2, page.entries.size)
    }
}

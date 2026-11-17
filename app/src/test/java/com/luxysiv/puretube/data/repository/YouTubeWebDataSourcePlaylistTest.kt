package com.luxysiv.puretube.data.repository

import com.luxysiv.puretube.data.model.SearchItem
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Parse playlist trong tìm kiếm và playlist detail bằng JSON mẫu (không cần mạng). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YouTubeWebDataSourcePlaylistTest {

    @Test
    fun `parses playlist lockup with badge channel and first video`() {
        val items = YouTubeWebDataSource.collectSearchItems(JSONObject(playlistLockupJson()))

        assertEquals(1, items.size)
        val playlist = (items[0] as SearchItem.Playlist).playlist
        assertEquals("PL8F6B0753B2CCA128", playlist.id)
        assertEquals("Top 100+ Jazz Classics Playlist", playlist.title)
        assertEquals("Electronics USA", playlist.channelTitle)
        assertEquals(279, playlist.videoCount)
        assertEquals("https://img.test/playlist.jpg", playlist.thumbnailUrl)
        assertEquals("ZZcuSBouhVA", playlist.firstVideoId)
        assertEquals("Miles Davis - Freddie Freeloader", playlist.firstVideoTitle)
        assertFalse(playlist.isMix)
        assertEquals("VLPL8F6B0753B2CCA128", playlist.browseId)
    }

    @Test
    fun `mixed search keeps order and skips preview child videos`() {
        val json = """
            {
              "contents": [
                {
                  "videoRenderer": {
                    "videoId": "vid00000001",
                    "title": { "runs": [ { "text": "Video đầu" } ] }
                  }
                },
                ${playlistLockupJson()},
                {
                  "playlistRenderer": {
                    "playlistId": "PLlegacy123456",
                    "title": { "simpleText": "Playlist cũ" },
                    "videoCountText": { "simpleText": "12 video" },
                    "videos": [
                      {
                        "childVideoRenderer": {
                          "videoId": "child000001",
                          "title": { "simpleText": "Video xem trước" }
                        }
                      }
                    ]
                  }
                },
                {
                  "videoRenderer": {
                    "videoId": "vid00000001",
                    "title": { "runs": [ { "text": "Video trùng" } ] }
                  }
                }
              ]
            }
        """.trimIndent()

        val items = YouTubeWebDataSource.collectSearchItems(JSONObject(json))

        assertEquals(3, items.size)
        assertEquals("vid00000001", (items[0] as SearchItem.Video).video.id)
        assertEquals("PL8F6B0753B2CCA128", (items[1] as SearchItem.Playlist).playlist.id)
        assertEquals("PLlegacy123456", (items[2] as SearchItem.Playlist).playlist.id)
        assertEquals(12, (items[2] as SearchItem.Playlist).playlist.videoCount)
        assertTrue(items.none { it is SearchItem.Video && it.video.id == "child000001" })
    }

    @Test
    fun `playlist lockup without badge uses first video thumbnail fallback`() {
        val json = """
            {
              "lockupViewModel": {
                "contentId": "PLmissingFields123",
                "contentType": "LOCKUP_CONTENT_TYPE_PLAYLIST",
                "metadata": {
                  "lockupMetadataViewModel": {
                    "title": { "content": "Playlist tối giản" },
                    "metadata": { "contentMetadataViewModel": { "metadataRows": [] } }
                  }
                },
                "rendererContext": {
                  "commandContext": {
                    "onTap": {
                      "innertubeCommand": {
                        "watchEndpoint": {
                          "videoId": "vid00000009",
                          "playlistId": "PLmissingFields123"
                        }
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val playlist = (YouTubeWebDataSource.collectSearchItems(JSONObject(json))
            .single() as SearchItem.Playlist).playlist

        assertEquals(0, playlist.videoCount)
        assertEquals("", playlist.channelTitle)
        assertTrue(playlist.thumbnailUrl.contains("vid00000009"))
    }

    @Test
    fun `course lockup is not a search result`() {
        val json = """
            {
              "lockupViewModel": {
                "contentId": "UCcourse123",
                "contentType": "LOCKUP_CONTENT_TYPE_COURSE",
                "metadata": {
                  "lockupMetadataViewModel": {
                    "title": { "content": "Khoá học" }
                  }
                }
              }
            }
        """.trimIndent()

        assertTrue(YouTubeWebDataSource.collectSearchItems(JSONObject(json)).isEmpty())
    }

    @Test
    fun `parses playlist browse page with continuation token`() {
        val json = """
            {
              "contents": {
                "singleColumnBrowseResultsRenderer": {
                  "tabs": [
                    {
                      "tabRenderer": {
                        "content": {
                          "sectionListRenderer": {
                            "contents": [
                              {
                                "itemSectionRenderer": {
                                  "contents": [
                                    ${videoLockup("vid00000001", "Bài hát 1")},
                                    ${videoLockup("vid00000002", "Bài hát 2")}
                                  ]
                                }
                              },
                              {
                                "continuationItemRenderer": {
                                  "continuationEndpoint": {
                                    "continuationCommand": { "token": "TOKEN-NEXT" }
                                  }
                                }
                              }
                            ]
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val page = YouTubeWebDataSource.parsePlaylistPage(JSONObject(json))

        assertEquals(listOf("vid00000001", "vid00000002"), page.videos.map { it.id })
        assertEquals("TOKEN-NEXT", page.nextToken)
        assertTrue(page.hasMore)
    }

    private fun videoLockup(id: String, title: String): String = """
        {
          "lockupViewModel": {
            "contentId": "$id",
            "contentType": "LOCKUP_CONTENT_TYPE_VIDEO",
            "metadata": {
              "lockupMetadataViewModel": {
                "title": { "content": "$title" },
                "metadata": {
                  "contentMetadataViewModel": {
                    "metadataRows": [
                      { "metadataParts": [ { "text": { "content": "Kênh nhạc" } } ] }
                    ]
                  }
                }
              }
            }
          }
        }
    """.trimIndent()

    private fun playlistLockupJson(): String = """
        {
          "lockupViewModel": {
            "contentId": "PL8F6B0753B2CCA128",
            "contentType": "LOCKUP_CONTENT_TYPE_PLAYLIST",
            "contentImage": {
              "collectionThumbnailViewModel": {
                "primaryThumbnail": {
                  "thumbnailViewModel": {
                    "image": {
                      "sources": [
                        { "url": "https://img.test/playlist.jpg", "width": 720, "height": 404 }
                      ]
                    },
                    "overlays": [
                      {
                        "thumbnailOverlayBadgeViewModel": {
                          "thumbnailBadges": [
                            { "thumbnailBadgeViewModel": { "text": "279 video" } }
                          ]
                        }
                      }
                    ]
                  }
                }
              }
            },
            "metadata": {
              "lockupMetadataViewModel": {
                "title": { "content": "Top 100+ Jazz Classics Playlist" },
                "metadata": {
                  "contentMetadataViewModel": {
                    "metadataRows": [
                      {
                        "metadataParts": [
                          { "text": { "content": "Electronics USA" } },
                          { "text": { "content": "Danh sách phát" } }
                        ]
                      },
                      { "isSpacerRow": true },
                      {
                        "metadataParts": [
                          { "text": { "content": "Miles Davis - Freddie Freeloader · 9:48" } }
                        ]
                      }
                    ]
                  }
                }
              }
            },
            "rendererContext": {
              "commandContext": {
                "onTap": {
                  "innertubeCommand": {
                    "watchEndpoint": {
                      "videoId": "ZZcuSBouhVA",
                      "playlistId": "PL8F6B0753B2CCA128"
                    }
                  }
                }
              }
            }
          }
        }
    """.trimIndent()
}

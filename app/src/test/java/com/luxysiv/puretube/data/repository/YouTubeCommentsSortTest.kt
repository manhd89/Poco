package com.luxysiv.puretube.data.repository

import com.luxysiv.puretube.data.model.CommentSort
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Đọc menu sắp xếp bình luận mà YouTube trả trong header/continuation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YouTubeCommentsSortTest {

    @Test
    fun `reads top and newest tokens from localized labels`() {
        val root = JSONObject(
            """
            {
              "commentsHeaderRenderer": {
                "sortMenu": {
                  "sortFilterSubMenuRenderer": {
                    "subMenuItems": [
                      {
                        "title": { "simpleText": "Bình luận hàng đầu" },
                        "selected": true,
                        "serviceEndpoint": {
                          "continuationCommand": { "token": "top-token" }
                        }
                      },
                      {
                        "title": { "simpleText": "Mới nhất" },
                        "serviceEndpoint": {
                          "continuationCommand": { "token": "newest-token" }
                        }
                      }
                    ]
                  }
                }
              }
            }
            """.trimIndent()
        )

        val tokens = YouTubeCommentsDataSource.parseSortMenu(root)

        assertEquals("top-token", tokens[CommentSort.TOP])
        assertEquals("newest-token", tokens[CommentSort.NEWEST])
    }

    @Test
    fun `falls back to menu order for unknown labels`() {
        val root = JSONObject(
            """
            {
              "sortFilterSubMenuRenderer": {
                "subMenuItems": [
                  {
                    "title": { "simpleText": "Option A" },
                    "serviceEndpoint": { "continuationCommand": { "token": "a" } }
                  },
                  {
                    "title": { "simpleText": "Option B" },
                    "serviceEndpoint": {
                      "commandExecutorCommand": {
                        "commands": [
                          { "continuationCommand": { "token": "b" } }
                        ]
                      }
                    }
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val tokens = YouTubeCommentsDataSource.parseSortMenu(root)

        assertEquals("a", tokens[CommentSort.TOP])
        assertEquals("b", tokens[CommentSort.NEWEST])
    }
}

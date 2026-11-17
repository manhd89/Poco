package com.luxysiv.puretube.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Kiểm tra parser Atom/RSS dùng cho tab Đăng ký (không cần mạng). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubscriptionFeedDataSourceTest {

    private val feed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom"
              xmlns:yt="http://www.youtube.com/xml/schemas/2015"
              xmlns:media="http://search.yahoo.com/mrss/">
          <title>Kênh thử</title>
          <entry>
            <id>yt:video:old111</id>
            <yt:videoId>old111</yt:videoId>
            <title>Video cũ</title>
            <published>2024-04-01T08:00:00+00:00</published>
            <media:group>
              <media:thumbnail url="//i.ytimg.com/vi/old111/hqdefault.jpg" />
              <media:community>
                <media:statistics views="1500" />
              </media:community>
            </media:group>
          </entry>
          <entry>
            <id>yt:video:new222</id>
            <yt:videoId>new222</yt:videoId>
            <title>Video mới</title>
            <published>2024-05-01T12:34:56Z</published>
            <media:group>
              <media:thumbnail url="https://i.ytimg.com/vi/new222/hqdefault.jpg" />
              <media:community>
                <media:statistics views="123456" />
              </media:community>
            </media:group>
          </entry>
          <entry>
            <id>yt:video:new222</id>
            <yt:videoId>new222</yt:videoId>
            <title>Video mới (bản trùng)</title>
            <published>2024-05-01T12:34:56Z</published>
            <media:group>
              <media:statistics views="123456" />
            </media:group>
          </entry>
          <entry>
            <title>Mục không có videoId</title>
            <published>2024-05-02T00:00:00Z</published>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun `parses entries newest first and drops duplicates`() {
        val entries = SubscriptionFeedDataSource.parse(feed)

        assertEquals(listOf("new222", "old111"), entries.map { it.videoId })
        assertEquals("Video mới", entries.first().title)
    }

    @Test
    fun `normalizes protocol relative thumbnails and formats views`() {
        val old = SubscriptionFeedDataSource.parse(feed).first { it.videoId == "old111" }

        assertEquals("https://i.ytimg.com/vi/old111/hqdefault.jpg", old.thumbnailUrl)
        assertEquals("1,5 N", old.viewCount)
    }

    @Test
    fun `parses RFC 3339 published timestamps`() {
        val newest = SubscriptionFeedDataSource.parse(feed).first()

        // 2024-05-01T12:34:56Z
        assertEquals(1_714_566_896_000L, newest.publishedAt)
    }

    @Test
    fun `returns empty list for feed without entries`() {
        val empty = SubscriptionFeedDataSource.parse(
            "<feed xmlns=\"http://www.w3.org/2005/Atom\"></feed>"
        )

        assertTrue(empty.isEmpty())
    }

    @Test
    fun `keeps malformed date as zero instead of failing`() {
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom"
                  xmlns:yt="http://www.youtube.com/xml/schemas/2015">
              <entry>
                <yt:videoId>abc</yt:videoId>
                <title>Không rõ ngày</title>
                <published>không phải ngày</published>
              </entry>
            </feed>
        """.trimIndent()

        val entry = SubscriptionFeedDataSource.parse(xml).single()

        assertEquals(0L, entry.publishedAt)
    }
}

package com.luxysiv.puretube.data.local

import com.luxysiv.puretube.data.model.VideoItem
import com.luxysiv.puretube.data.model.videoThumbnailUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Kiểm tra codec JSON dùng chung cho video (dùng org.json thật của Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoJsonTest {

    private val video = VideoItem(
        id = "abc123",
        title = "Tiêu đề",
        channelTitle = "Kênh",
        channelId = "channel-1",
        description = "Mô tả",
        duration = "12:34",
        viewCount = "1.2K",
        publishedTime = "2 ngày trước",
        thumbnailUrl = "https://example.com/thumb.jpg",
        category = "Music",
        channelAvatarUrl = "https://example.com/avatar.jpg"
    )

    @Test
    fun `video item round trips through json`() {
        val decoded = video.toJson().toVideoItem()

        assertEquals(video, decoded)
    }

    @Test
    fun `json uses videoId as the canonical key`() {
        val json = video.toJson()

        assertEquals("abc123", json.getString("videoId"))
        assertTrue(json.isNull("id") || !json.has("id"))
    }

    @Test
    fun `legacy id key is still readable`() {
        val json = JSONObject()
            .put("id", "legacy")
            .put("title", "Video cũ")

        val decoded = json.toVideoItem()

        assertEquals("legacy", decoded?.id)
        assertEquals("Video cũ", decoded?.title)
    }

    @Test
    fun `missing thumbnail falls back to the shared default`() {
        val json = JSONObject()
            .put("videoId", "no-thumb")
            .put("title", "Không thumbnail")

        val decoded = json.toVideoItem()

        assertEquals(videoThumbnailUrl("no-thumb"), decoded?.thumbnailUrl)
    }

    @Test
    fun `items without id or title are rejected`() {
        assertNull(JSONObject().put("title", "Thiếu id").toVideoItem())
        assertNull(JSONObject().put("videoId", "abc").toVideoItem())
    }
}

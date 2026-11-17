package com.luxysiv.puretube.data.database

import com.luxysiv.puretube.data.model.PlaylistItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** Kiểm tra mapping thuần giữa playlist đã lưu và model hiển thị. */
class SavedPlaylistEntityTest {

    @Test
    fun `fromPlaylistItem keeps metadata used by cards`() {
        val item = PlaylistItem(
            id = "PL123",
            title = "Danh sách của tôi",
            channelTitle = "Kênh",
            channelId = "UC1",
            videoCount = 12,
            thumbnailUrl = "https://example.com/thumb.jpg",
            firstVideoId = "video-1",
            firstVideoTitle = "Video đầu"
        )

        val entity = SavedPlaylistEntity.fromPlaylistItem(item, savedAt = 1234L)

        assertEquals("PL123", entity.playlistId)
        assertEquals("Danh sách của tôi", entity.title)
        assertEquals("Kênh", entity.channelTitle)
        assertEquals("UC1", entity.channelId)
        assertEquals(12, entity.videoCount)
        assertEquals("https://example.com/thumb.jpg", entity.thumbnailUrl)
        assertEquals("video-1", entity.firstVideoId)
        assertEquals("Video đầu", entity.firstVideoTitle)
        assertEquals(1234L, entity.savedAt)
    }

    @Test
    fun `toPlaylistItem round trips the fields the UI renders`() {
        val entity = SavedPlaylistEntity(
            playlistId = "PL456",
            title = "Playlist",
            channelTitle = "Tác giả",
            videoCount = 3,
            thumbnailUrl = "thumb",
            firstVideoId = "v1",
            firstVideoTitle = "Một video",
            savedAt = 99L
        )

        val item = entity.toPlaylistItem()

        assertEquals(entity.playlistId, item.id)
        assertEquals(entity.title, item.title)
        assertEquals(entity.channelTitle, item.channelTitle)
        assertEquals(entity.videoCount, item.videoCount)
        assertEquals(entity.thumbnailUrl, item.thumbnailUrl)
        assertEquals(entity.firstVideoId, item.firstVideoId)
        assertEquals(entity.firstVideoTitle, item.firstVideoTitle)
    }
}

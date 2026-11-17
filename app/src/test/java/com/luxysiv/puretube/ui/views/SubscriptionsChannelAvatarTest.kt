package com.luxysiv.puretube.ui.views

import com.luxysiv.puretube.data.model.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Kiểm tra avatar của dải kênh trên trang Đăng ký (không cần Android). */
class SubscriptionsChannelAvatarTest {

    private fun video(
        id: String,
        channelTitle: String,
        avatarUrl: String = ""
    ) = VideoItem(
        id = id,
        title = "Video $id",
        channelTitle = channelTitle,
        channelAvatarUrl = avatarUrl
    )

    @Test
    fun `keeps the first non-blank avatar for each channel`() {
        val avatars = channelAvatarsByTitle(
            listOf(
                video("a", "Kênh A", ""),
                video("b", "Kênh A", "https://img.test/a.jpg"),
                video("c", "Kênh A", "https://img.test/a-other.jpg"),
                video("d", "Kênh B", "https://img.test/b.jpg")
            )
        )

        assertEquals("https://img.test/a.jpg", avatars["Kênh A"])
        assertEquals("https://img.test/b.jpg", avatars["Kênh B"])
    }

    @Test
    fun `ignores videos without a channel name or avatar`() {
        val avatars = channelAvatarsByTitle(
            listOf(
                video("a", "", "https://img.test/a.jpg"),
                video("b", "Kênh B", ""),
                video("c", "Kênh C", "https://img.test/c.jpg")
            )
        )

        assertFalse(avatars.containsKey(""))
        assertFalse(avatars.containsKey("Kênh B"))
        assertEquals(setOf("Kênh C"), avatars.keys)
    }
}

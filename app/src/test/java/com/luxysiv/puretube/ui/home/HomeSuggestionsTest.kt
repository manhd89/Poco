package com.luxysiv.puretube.ui.home

import com.luxysiv.puretube.data.model.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** Kiểm tra thuật toán chia đều video gợi ý giữa các mix. */
class HomeSuggestionsTest {

    private fun video(id: String) = VideoItem(
        id = id,
        title = id,
        channelTitle = "channel"
    )

    @Test
    fun `round robin chia deu giua cac mix`() {
        val result = buildHomeSuggestions(
            mixVideos = listOf(
                listOf(video("a1"), video("a2")),
                listOf(video("b1")),
                listOf(video("c1"))
            ),
            excludedIds = emptySet(),
            limit = 4
        )

        assertEquals(listOf("a1", "b1", "c1", "a2"), result.map { it.id })
    }

    @Test
    fun `bo qua video da xem va video trung`() {
        val result = buildHomeSuggestions(
            mixVideos = listOf(
                listOf(video("seen"), video("a1"), video("a1")),
                listOf(video("a1"), video("b1"))
            ),
            excludedIds = setOf("seen"),
            limit = 10
        )

        assertEquals(listOf("a1", "b1"), result.map { it.id })
    }

    @Test
    fun `dung lai khi het video hoac limit khong hop le`() {
        assertEquals(
            listOf("a1"),
            buildHomeSuggestions(
                mixVideos = listOf(listOf(video("a1"))),
                excludedIds = emptySet(),
                limit = 5
            ).map { it.id }
        )
        assertEquals(
            emptyList<VideoItem>(),
            buildHomeSuggestions(emptyList(), emptySet(), 5)
        )
        assertEquals(
            emptyList<VideoItem>(),
            buildHomeSuggestions(listOf(listOf(video("a1"))), emptySet(), 0)
        )
    }

    @Test
    fun `tron toan bo danh sach khi khong gioi han`() {
        val result = interleaveHomeVideos(
            mixVideos = listOf(
                listOf(video("a1"), video("a2"), video("a3")),
                listOf(video("b1"), video("b2"))
            ),
            excludedIds = setOf("a2")
        )

        assertEquals(listOf("a1", "b1", "a3", "b2"), result.map { it.id })
    }

    @Test
    fun `khong lap vo han khi moi video deu bi loai`() {
        val result = interleaveHomeVideos(
            mixVideos = listOf(
                listOf(video("seen"), video("seen")),
                listOf(video("seen"))
            ),
            excludedIds = setOf("seen")
        )

        assertEquals(emptyList<VideoItem>(), result)
    }
}

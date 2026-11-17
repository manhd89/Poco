package com.luxysiv.puretube.ui.components

import com.luxysiv.puretube.data.database.VideoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressTest {

    private fun entity(
        id: String,
        duration: String,
        progressSeconds: Long
    ) = VideoEntity(
        videoId = id,
        title = "Video $id",
        channelTitle = "Kênh",
        duration = duration,
        watchProgressSeconds = progressSeconds,
        lastWatchedTimestamp = 1L
    )

    @Test
    fun `computes fraction from watched seconds and duration`() {
        assertEquals(0.2f, watchProgressFraction(entity("a", "10:00", 120L)), 0.001f)
    }

    @Test
    fun `clamps progress at one`() {
        assertEquals(1f, watchProgressFraction(entity("a", "1:00", 120L)), 0.001f)
    }

    @Test
    fun `returns zero without progress or duration`() {
        assertEquals(0f, watchProgressFraction(entity("a", "10:00", 0L)), 0.001f)
        assertEquals(0f, watchProgressFraction(entity("b", "00:00", 30L)), 0.001f)
    }

    @Test
    fun `map only contains videos with visible progress`() {
        val map = watchProgressMap(
            listOf(
                entity("watched", "10:00", 120L),
                entity("fresh", "10:00", 0L)
            )
        )
        assertTrue(map.containsKey("watched"))
        assertTrue(!map.containsKey("fresh"))
    }
}

package com.luxysiv.puretube.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** Kiểm tra định dạng thời gian và tỉ lệ tiến độ của thanh tua. */
class PlaybackPositionTest {

    @Test
    fun `formatTime handles minutes hours and invalid values`() {
        assertEquals("00:00", PlaybackPosition.formatTime(0))
        assertEquals("00:00", PlaybackPosition.formatTime(-5))
        assertEquals("00:07", PlaybackPosition.formatTime(7))
        assertEquals("04:05", PlaybackPosition.formatTime(245))
        assertEquals("1:02:03", PlaybackPosition.formatTime(3723))
    }

    @Test
    fun `progressFraction is clamped and safe when duration is unknown`() {
        assertEquals(0.5f, PlaybackPosition(currentSeconds = 30f, durationSeconds = 60f).progressFraction, 0.0001f)
        assertEquals(1f, PlaybackPosition(currentSeconds = 90f, durationSeconds = 60f).progressFraction, 0.0001f)
        assertEquals(0f, PlaybackPosition(currentSeconds = 30f, durationSeconds = 0f).progressFraction, 0.0001f)
    }

    @Test
    fun `formatted labels use the current position`() {
        val position = PlaybackPosition(currentSeconds = 125f, durationSeconds = 3723f)
        assertEquals("02:05", position.formattedCurrentTime)
        assertEquals("1:02:03", position.formattedDuration)
    }
}

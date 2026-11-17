package com.luxysiv.puretube.data

import com.luxysiv.puretube.data.model.parseDurationSeconds
import org.junit.Assert.assertEquals
import org.junit.Test

/** Kiểm tra các hàm parse thuần Kotlin (không cần thiết bị/Android). */
class VideoParsingTest {

    @Test
    fun `parseDurationSeconds supports hours minutes and seconds`() {
        assertEquals(3723, parseDurationSeconds("01:02:03"))
        assertEquals(245, parseDurationSeconds("4:05"))
        assertEquals(45, parseDurationSeconds("45"))
        assertEquals(0, parseDurationSeconds("--:--"))
        assertEquals(0, parseDurationSeconds("TRỰC TIẾP"))
        assertEquals(0, parseDurationSeconds(""))
    }
}

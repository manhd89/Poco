package com.luxysiv.puretube.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** Kiểm tra nhãn thời gian tương đối dùng trên thẻ video của feed Đăng ký. */
class FormattersRelativeTimeTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    private fun ago(millis: Long): String = Formatters.relativeTime(now - millis, now)

    @Test
    fun `formats recent timestamps`() {
        assertEquals("vừa xong", ago(30_000L))
        assertEquals("5 phút trước", ago(5 * minute))
        assertEquals("3 giờ trước", ago(3 * hour))
        assertEquals("2 ngày trước", ago(2 * day))
        assertEquals("2 tuần trước", ago(14 * day))
        assertEquals("3 tháng trước", ago(90 * day))
        assertEquals("2 năm trước", ago(730 * day))
    }

    @Test
    fun `clamps future timestamps and leaves unknown date blank`() {
        assertEquals("vừa xong", Formatters.relativeTime(now + hour, now))
        assertEquals("", Formatters.relativeTime(0L, now))
    }
}

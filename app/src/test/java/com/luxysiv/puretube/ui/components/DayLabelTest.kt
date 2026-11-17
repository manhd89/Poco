package com.luxysiv.puretube.ui.components

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

/** Kiểm tra nhãn nhóm ngày trong Thư viện. */
class DayLabelTest {

    @Test
    fun `timestamps without data are grouped as earlier`() {
        assertEquals("Trước đó", dayLabel(0L))
        assertEquals("Trước đó", dayLabel(-1L))
    }

    @Test
    fun `today is labelled Hom nay`() {
        assertEquals("Hôm nay", dayLabel(System.currentTimeMillis()))
    }

    @Test
    fun `yesterday is labelled Hom qua`() {
        val yesterdayNoon = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -1)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals("Hôm qua", dayLabel(yesterdayNoon.timeInMillis))
    }
}

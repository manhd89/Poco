package com.luxysiv.puretube.ui.adaptive

import org.junit.Assert.assertEquals
import org.junit.Test

/** Kiểm tra breakpoint và số cột lưới dùng chung cho giao diện thích ứng. */
class WindowSizeTest {

    @Test
    fun `width classes follow material breakpoints`() {
        assertEquals(WindowWidthClass.Compact, windowWidthClassForWidth(320))
        assertEquals(WindowWidthClass.Compact, windowWidthClassForWidth(599))
        assertEquals(WindowWidthClass.Medium, windowWidthClassForWidth(600))
        assertEquals(WindowWidthClass.Medium, windowWidthClassForWidth(839))
        assertEquals(WindowWidthClass.Expanded, windowWidthClassForWidth(840))
        assertEquals(WindowWidthClass.Expanded, windowWidthClassForWidth(1280))
    }

    @Test
    fun `video grid uses one to four columns`() {
        assertEquals(1, videoGridColumnsForWidth(360))
        assertEquals(2, videoGridColumnsForWidth(600))
        assertEquals(2, videoGridColumnsForWidth(899))
        assertEquals(3, videoGridColumnsForWidth(900))
        assertEquals(3, videoGridColumnsForWidth(1239))
        assertEquals(4, videoGridColumnsForWidth(1240))
    }
}

package com.luxysiv.puretube.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class MixDisplayTitleTest {

    @Test
    fun `strips youtube mix prefix`() {
        assertEquals(
            "Danh sách kết hợp – Bài hát hay",
            mixDisplayTitle("Mix - Bài hát hay", "Bài hát hay")
        )
    }

    @Test
    fun `keeps already localized title`() {
        assertEquals(
            "Danh sách kết hợp – Bài hát hay",
            mixDisplayTitle("Danh sách kết hợp – Bài hát hay", "Bài hát hay")
        )
    }

    @Test
    fun `falls back to video title when mix title is blank`() {
        assertEquals(
            "Danh sách kết hợp – Bài hát hay",
            mixDisplayTitle("", "Bài hát hay")
        )
    }
}

package com.luxysiv.puretube.player

import com.luxysiv.puretube.data.model.PlaybackPlan
import com.luxysiv.puretube.data.model.PlaybackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kiểm tra cách kế hoạch phát chọn nguồn theo mức chất lượng người dùng. */
class PlaybackPlanQualityTest {

    private fun adaptive(height: Int? = null) = PlaybackSource(
        uri = "https://example.com/manifest",
        mimeType = "application/dash+xml",
        height = height,
        isAdaptive = true
    )

    private fun progressive(height: Int) = PlaybackSource(
        uri = "https://example.com/$height.mp4",
        mimeType = "video/mp4",
        height = height
    )

    @Test
    fun `adaptive manifest is used regardless of selected height`() {
        val plan = PlaybackPlan(
            candidates = listOf(adaptive(), progressive(1080), progressive(720))
        )

        assertTrue(plan.hasAdaptiveSource)
        assertEquals(0, plan.candidateIndexFor(720))
        assertEquals(0, plan.candidateIndexFor(1080))
        assertEquals(0, plan.candidateIndexFor(null))
    }

    @Test
    fun `progressive picks highest stream not above the requested height`() {
        // Thứ tự giống dữ liệu thật: cao xuống thấp.
        val plan = PlaybackPlan(
            candidates = listOf(
                progressive(1080),
                progressive(720),
                progressive(480),
                progressive(360)
            )
        )

        assertFalse(plan.hasAdaptiveSource)
        assertEquals(0, plan.candidateIndexFor(null))
        assertEquals(0, plan.candidateIndexFor(0))
        assertEquals(0, plan.candidateIndexFor(2160))
        assertEquals(1, plan.candidateIndexFor(720))
        assertEquals(2, plan.candidateIndexFor(600))
        assertEquals(3, plan.candidateIndexFor(360))
    }

    @Test
    fun `progressive falls back to the lowest stream when requested is smaller`() {
        val plan = PlaybackPlan(
            candidates = listOf(progressive(720), progressive(480))
        )

        assertEquals(1, plan.candidateIndexFor(144))
    }

    @Test
    fun `empty plan keeps index zero`() {
        assertEquals(0, PlaybackPlan(candidates = emptyList()).candidateIndexFor(720))
    }

    @Test
    fun `resolveHeight picks the highest available level not above the request`() {
        val plan = PlaybackPlan(
            candidates = listOf(adaptive()),
            qualities = listOf(1080, 720, 480, 360)
        )

        assertEquals(720, plan.resolveHeight(720))
        assertEquals(480, plan.resolveHeight(600))
        assertEquals(1080, plan.resolveHeight(2160))
        assertEquals(1080, plan.resolveHeight(null))
        assertEquals(1080, plan.resolveHeight(0))
    }

    @Test
    fun `resolveHeight falls back to the lowest level when request is smaller`() {
        val plan = PlaybackPlan(
            candidates = listOf(adaptive()),
            qualities = listOf(720, 480)
        )

        assertEquals(480, plan.resolveHeight(144))
    }

    @Test
    fun `resolveHeight keeps request when the source does not expose qualities`() {
        // Nguồn HLS/live không có danh sách chiều cao: vẫn giữ mức người dùng
        // chọn để engine giới hạn max.
        val plan = PlaybackPlan(candidates = listOf(adaptive()))

        assertEquals(720, plan.resolveHeight(720))
        assertNull(plan.resolveHeight(null))
    }
}

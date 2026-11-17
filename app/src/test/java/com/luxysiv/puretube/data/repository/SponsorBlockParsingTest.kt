package com.luxysiv.puretube.data.repository

import com.luxysiv.puretube.data.model.SponsorSegment
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Kiểm tra parse phản hồi SponsorBlock (dùng org.json thật của Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SponsorBlockParsingTest {

    @Test
    fun `parses skip segments and ignores other action types`() {
        val json = """
            [
              {"UUID":"a","segment":[10.5,20.25],"category":"sponsor","actionType":"skip"},
              {"UUID":"b","segment":[30,35],"category":"selfpromo","actionType":"mute"},
              {"UUID":"c","segment":[40,40],"category":"sponsor","actionType":"skip"}
            ]
        """.trimIndent()

        val segments = SponsorBlockDataSource.parseSegments(json)

        assertEquals(1, segments.size)
        assertEquals(10_500L, segments[0].startMs)
        assertEquals(20_250L, segments[0].endMs)
        assertEquals("sponsor", segments[0].category)
        assertEquals("a", segments[0].uuid)
    }

    @Test
    fun `hash prefix uses first four lowercase hex chars of sha256`() {
        assertEquals("5f6b", SponsorBlockDataSource.hashPrefix("dQw4w9WgXcQ"))
        assertEquals("6745", SponsorBlockDataSource.hashPrefix("jNQXAC9IVRw"))
    }

    @Test
    fun `empty json array yields no segments`() {
        assertEquals(0, SponsorBlockDataSource.parseSegments("[]").size)
    }

    @Test
    fun `parses hash prefix groups and filters other videos`() {
        val json = """
            [
              {
                "videoID": "wanted",
                "segments": [
                  {"UUID":"a","segment":[1,4],"category":"sponsor","actionType":"skip"},
                  {"UUID":"b","segment":[5,6],"category":"poi_highlight","actionType":"poi"}
                ]
              },
              {
                "videoID": "other",
                "segments": [
                  {"UUID":"c","segment":[7,8],"category":"sponsor","actionType":"skip"}
                ]
              }
            ]
        """.trimIndent()

        val segments = SponsorBlockDataSource.parseSegments(json, "wanted")

        assertEquals(2, segments.size)
        assertEquals(listOf("a", "b"), segments.map { it.uuid })
        assertEquals(1_000L, segments[0].startMs)
        assertEquals("poi", segments[1].actionType)
    }

    @Test
    fun `skips only the segment that contains the position`() {
        val segments = listOf(
            segment("a", 10_000L, 20_000L),
            segment("b", 40_000L, 50_000L)
        )

        val found = findSegmentToSkip(segments, positionMs = 41_000L, toleranceMs = 2_000L, skipped = emptySet())

        assertEquals("b", found?.uuid)
        assertEquals(null, findSegmentToSkip(segments, positionMs = 30_000L, toleranceMs = 2_000L, skipped = emptySet()))
    }

    @Test
    fun `manual seek deep inside a segment is respected unless tolerance is ignored`() {
        val segments = listOf(segment("a", 10_000L, 20_000L))

        assertEquals(
            null,
            findSegmentToSkip(segments, positionMs = 15_000L, toleranceMs = 2_000L, skipped = emptySet())
        )
        assertEquals(
            "a",
            findSegmentToSkip(
                segments,
                positionMs = 15_000L,
                toleranceMs = 2_000L,
                skipped = emptySet(),
                ignoreTolerance = true
            )?.uuid
        )
    }

    @Test
    fun `already skipped segments are not skipped twice`() {
        val segments = listOf(segment("a", 10_000L, 20_000L))

        assertEquals(
            null,
            findSegmentToSkip(segments, positionMs = 10_500L, toleranceMs = 2_000L, skipped = setOf("a"))
        )
    }

    private fun segment(uuid: String, startMs: Long, endMs: Long) = SponsorSegment(
        startMs = startMs,
        endMs = endMs,
        category = "sponsor",
        uuid = uuid
    )
}

package com.luxysiv.puretube.data.local

import com.luxysiv.puretube.data.database.SubscriptionEntity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Kiểm tra xuất/nhập kênh đăng ký cục bộ trong bản sao lưu JSON. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupSubscriptionsTest {

    private val channel = SubscriptionEntity(
        channelId = "UC-test-1",
        channelTitle = "Kênh thử",
        avatarUrl = "https://example.com/avatar.jpg",
        subscriberCount = 1234L,
        addedAt = 111L,
        lastFetchedAt = 222L
    )

    @Test
    fun `subscriptions survive export and parse`() {
        val json = BackupManager.export(
            settings = AppSettings(),
            videos = emptyList(),
            recentSearches = listOf("nhạc"),
            subscriptions = listOf(channel)
        )

        val payload = BackupManager.parse(json)

        assertEquals(listOf(channel), payload?.subscriptions)
        assertEquals(listOf("nhạc"), payload?.recentSearches)
    }

    @Test
    fun `version 1 backup without subscriptions stays readable`() {
        val legacy = JSONObject()
            .put("app", "PureTube")
            .put("version", 1)
            .put("videos", JSONArray())
            .put("recentSearches", JSONArray().put("cũ"))
            .toString()

        val payload = BackupManager.parse(legacy)

        assertEquals(listOf("cũ"), payload?.recentSearches)
        assertTrue(payload?.subscriptions?.isEmpty() == true)
    }

    @Test
    fun `skips subscription records without a channel id`() {
        val json = JSONObject()
            .put("app", "PureTube")
            .put("version", 2)
            .put(
                "subscriptions",
                JSONArray()
                    .put(JSONObject().put("channelTitle", "Không có id"))
                    .put(JSONObject().put("channelId", "UC-ok").put("channelTitle", ""))
            )
            .toString()

        val subscriptions = BackupManager.parse(json)?.subscriptions.orEmpty()

        assertEquals(1, subscriptions.size)
        assertEquals("UC-ok", subscriptions.single().channelId)
        assertEquals("YouTube", subscriptions.single().channelTitle)
    }
}

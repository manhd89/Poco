package com.luxysiv.puretube.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Kiểm tra TTL/LRU/capacity của cache dùng chung (đồng hồ giả, không cần Android). */
class ExpiringLruCacheTest {

    private var now = 1_000L

    private fun cache(ttlMs: Long = 100L, maxEntries: Int = 2) =
        ExpiringLruCache<String, String>(
            ttlMs = ttlMs,
            maxEntries = maxEntries,
            clock = { now }
        )

    @Test
    fun `entries expire after the ttl`() {
        val cache = cache(ttlMs = 100L)
        cache.put("a", "1")

        now += 100L
        assertEquals("1", cache.get("a"))

        now += 1L
        assertNull(cache.get("a"))
    }

    @Test
    fun `least recently used entry is evicted`() {
        val cache = cache(maxEntries = 2)
        cache.put("a", "1")
        cache.put("b", "2")

        // Đọc "a" để nó thành mục mới nhất, rồi thêm "c".
        assertEquals("1", cache.get("a"))
        cache.put("c", "3")

        assertEquals("1", cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals("3", cache.get("c"))
    }

    @Test
    fun `put replaces the value and refreshes the ttl`() {
        val cache = cache(ttlMs = 100L)
        cache.put("a", "old")

        now += 60L
        cache.put("a", "new")
        now += 60L

        assertEquals("new", cache.get("a"))
    }

    @Test
    fun `remove clears a single entry`() {
        val cache = cache()
        cache.put("a", "1")
        cache.remove("a")

        assertNull(cache.get("a"))
    }

    @Test
    fun `clear empties the cache`() {
        val cache = cache()
        cache.put("a", "1")
        cache.clear()

        assertNull(cache.get("a"))
    }
}

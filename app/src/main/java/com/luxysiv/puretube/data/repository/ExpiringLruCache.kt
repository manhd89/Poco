package com.luxysiv.puretube.data.repository

/**
 * Cache LRU có thời hạn dùng chung cho dữ liệu mạng ngắn hạn.
 *
 * Mọi thao tác đều đồng bộ trên một lock nội bộ: bản đồ `LinkedHashMap` không
 * an toàn khi nhiều coroutine/IO thread đọc/ghi cùng lúc, còn việc "xóa mục cũ
 * nhất" phải diễn ra nguyên tử cùng lúc với `put`.
 */
internal class ExpiringLruCache<K, V>(
    private val ttlMs: Long,
    private val maxEntries: Int,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class Entry<V>(val value: V, val createdAt: Long)

    private val lock = Any()
    private val entries = LinkedHashMap<K, Entry<V>>()

    init {
        require(ttlMs > 0) { "ttlMs must be positive" }
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    fun get(key: K): V? = synchronized(lock) {
        val entry = entries[key] ?: return null
        val age = clock() - entry.createdAt
        if (age !in 0..ttlMs) {
            entries.remove(key)
            return null
        }
        // LRU touch: mục vừa đọc được đưa về cuối.
        entries.remove(key)
        entries[key] = entry
        entry.value
    }

    fun put(key: K, value: V) {
        synchronized(lock) {
            entries.remove(key)
            entries[key] = Entry(value, clock())
            while (entries.size > maxEntries) {
                val eldest = entries.entries.firstOrNull() ?: break
                entries.remove(eldest.key)
            }
        }
    }

    fun remove(key: K) {
        synchronized(lock) { entries.remove(key) }
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }
}

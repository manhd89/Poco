package com.luxysiv.puretube.data.local

import android.content.Context
import com.luxysiv.puretube.error.getOrElse
import com.luxysiv.puretube.error.runAppCatchingBlocking
import org.json.JSONArray

/**
 * Lịch sử tìm kiếm THẬT của người dùng, lưu bền trên thiết bị (SharedPreferences).
 *
 * Trước đây danh sách "tìm kiếm gần đây" bị set cứng trong ViewModel nên không
 * phản ánh việc sử dụng thực tế; lớp này thay bằng dữ liệu do chính người dùng tạo ra.
 */
class SearchHistoryStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    fun load(): List<String> = synchronized(lock) { loadLocked() }

    fun add(query: String, limit: Int = 10): List<String> = synchronized(lock) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@synchronized loadLocked()
        val updated = (listOf(trimmed) + loadLocked().filterNot { it.equals(trimmed, ignoreCase = true) })
            .take(limit)
        saveLocked(updated)
        updated
    }

    /** Xoá một từ khoá khỏi lịch sử (nút X trên từng dòng). */
    fun remove(query: String): List<String> = synchronized(lock) {
        val updated = loadLocked().filterNot { it.equals(query.trim(), ignoreCase = true) }
        saveLocked(updated)
        updated
    }

    fun clear() {
        synchronized(lock) {
            prefs.edit().remove(KEY_HISTORY).apply()
        }
    }

    /** Thay toàn bộ lịch sử (dùng khi khôi phục bản sao lưu). */
    fun replaceAll(items: List<String>) {
        synchronized(lock) {
            saveLocked(items.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(10))
        }
    }

    private fun loadLocked(): List<String> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runAppCatchingBlocking {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optString(index).trim().takeIf { it.isNotEmpty() }
            }
        }.getOrElse(emptyList())
    }

    private fun saveLocked(items: List<String>) {
        val array = JSONArray()
        items.forEach { array.put(it) }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    private companion object {
        const val PREFS_NAME = "puretube_search"
        const val KEY_HISTORY = "recent_searches"
    }
}

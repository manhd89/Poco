package com.luxysiv.puretube.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Gợi ý tìm kiếm THẬT từ YouTube (endpoint autocomplete mà youtube.com sử dụng),
 * trả về danh sách chuỗi — đúng kiểu danh sách chữ YouTube hiện ra khi đang gõ.
 *
 * Không cần API key. Khi lỗi mạng/endpoint đổi, trả về danh sách rỗng để UI
 * dùng tiếp lịch sử tìm kiếm thật của người dùng.
 */
object SearchSuggestionDataSource {

    private const val SUGGEST_URL =
        "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&hl=vi&gl=VN&q="

    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    private val httpClient = InnerTubeHttp.client.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** Cache nhỏ để không gọi lại endpoint khi người dùng xóa/gõ lại cùng từ khóa. */
    private val cache = mutableMapOf<String, Pair<Long, List<String>>>()

    /** Gợi ý cho [query], tối đa [limit] chuỗi, đã loại trùng. */
    suspend fun suggestions(query: String, limit: Int = 10): List<String> =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return@withContext emptyList()

            val key = "${trimmed.lowercase()}|${limit.coerceIn(1, 20)}"
            synchronized(cache) {
                cache[key]?.takeIf { System.currentTimeMillis() - it.first <= CACHE_TTL_MS }
                    ?.let { return@withContext it.second }
            }

            try {
                val url = SUGGEST_URL + URLEncoder.encode(trimmed, "UTF-8")
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", InnerTubeHttp.DESKTOP_USER_AGENT)
                    .header("Accept-Language", "vi-VN,vi;q=0.9,en;q=0.8")
                    .get()
                    .build()

                val body = execute(request) ?: return@withContext emptyList()

                // Định dạng: ["từ khóa", ["gợi ý 1", "gợi ý 2", ...], ...]
                val array = JSONArray(body).optJSONArray(1) ?: return@withContext emptyList()
                val result = ArrayList<String>(limit)
                for (i in 0 until array.length()) {
                    val suggestion = array.optString(i).trim()
                    if (suggestion.isEmpty()) continue
                    if (result.any { it.equals(suggestion, ignoreCase = true) }) continue
                    result.add(suggestion)
                    if (result.size >= limit) break
                }

                synchronized(cache) {
                    cache[key] = System.currentTimeMillis() to result
                    // Giới hạn cache tránh phình bộ nhớ
                    if (cache.size > 64) {
                        val oldest = cache.minByOrNull { it.value.first }?.key
                        if (oldest != null) cache.remove(oldest)
                    }
                }
                result
            } catch (_: Exception) {
                emptyList()
            }
        }

    /**
     * Gọi OkHttp bằng enqueue để coroutine bị cancel (người dùng gõ tiếp) thì
     * request cũng bị cancel, không chiếm thread/IO.
     */
    private suspend fun execute(request: Request): String? =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = response.use {
                        if (it.isSuccessful) it.body?.string() else null
                    }
                    if (continuation.isActive) continuation.resume(body)
                }
            })
        }
}

package com.luxysiv.puretube.data.repository

import com.luxysiv.puretube.data.model.SponsorCategory
import com.luxysiv.puretube.data.model.SponsorSegment
import com.luxysiv.puretube.error.runAppCatching
import com.luxysiv.puretube.error.runAppCatchingBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Lấy dữ liệu SponsorBlock (sponsor.ajay.app) cho video đang xem.
 *
 * Dùng endpoint hash-prefix của SponsorBlock thay vì gửi nguyên video ID:
 * 4 ký tự đầu của SHA-256(videoId) đủ để server trả về một nhóm nhỏ video,
 * sau đó lọc lại đúng video ở phía client. Cách này giảm dữ liệu nhận diện
 * gửi cho bên thứ ba mà vẫn giữ được đầy đủ category/action type.
 *
 * Kết quả được cache trong RAM theo (API, video, category) kể cả video không
 * có đoạn nào, tránh gọi mạng lặp lại khi xoay màn hình hoặc mở lại video.
 */
object SponsorBlockDataSource {

    const val DEFAULT_API_URL = "https://sponsor.ajay.app/api/skipSegments"

    private const val USER_AGENT = "PureTube/1.0 (https://github.com/luxysiv/PureTube)"
    private const val MAX_CACHE_ENTRIES = 200
    private const val CACHE_TTL_MS = 30 * 60 * 1000L
    private const val HASH_PREFIX_LENGTH = 4

    private data class CacheEntry(val expiresAt: Long, val segments: List<SponsorSegment>)

    private val httpClient = InnerTubeHttp.client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * Trả về danh sách đoạn của [videoId].
     *
     * [apiUrl] cho phép người dùng tự host SponsorBlock. [categories] là các
     * category đang bật; category bị tắt hoàn toàn không cần gửi lên server.
     */
    suspend fun segments(
        videoId: String,
        apiUrl: String = DEFAULT_API_URL,
        categories: Set<String> = SponsorCategory.apiNamesFor(SponsorCategory.defaultModes())
    ): List<SponsorSegment> = withContext(Dispatchers.IO) {
        if (videoId.isBlank() || categories.isEmpty()) return@withContext emptyList()

        val cacheKey = buildString {
            append(apiUrl.trim())
            append('|').append(videoId)
            append('|').append(categories.sorted().joinToString(","))
        }
        val now = System.currentTimeMillis()
        cache[cacheKey]?.let { entry ->
            if (entry.expiresAt > now) return@withContext entry.segments
            cache.remove(cacheKey)
        }

        // Lỗi mạng không được cache: nếu cache "rỗng" 30 phút thì khi mạng trở
        // lại video vẫn không có SponsorBlock cho tới khi hết TTL.
        val fetched = runAppCatching { fetch(videoId, apiUrl, categories) }.getOrNull()
            ?: return@withContext emptyList()
        // Lọc lại ở client: server tự host có thể bỏ qua tham số categories.
        val result = fetched.filter { it.category in categories }
        if (cache.size >= MAX_CACHE_ENTRIES) {
            cache.entries.removeAll { it.value.expiresAt <= now }
            if (cache.size >= MAX_CACHE_ENTRIES) cache.clear()
        }
        cache[cacheKey] = CacheEntry(now + CACHE_TTL_MS, result)
        result
    }

    /** Xoá cache khi người dùng đổi API hoặc cấu hình category. */
    fun clearCache() {
        cache.clear()
    }

    private fun fetch(
        videoId: String,
        apiUrl: String,
        categories: Set<String>
    ): List<SponsorSegment> {
        val base = apiUrl.ifBlank { DEFAULT_API_URL }.trim().toHttpUrl()
        val usesHashPrefix = base.encodedPath.trimEnd('/').endsWith("/skipSegments")
        val url = base.newBuilder()
            .apply {
                if (usesHashPrefix) {
                    addPathSegment(hashPrefix(videoId))
                } else {
                    addQueryParameter("videoID", videoId)
                }
                addQueryParameter(
                    "categories",
                    JSONArray(categories.sorted()).toString()
                )
                addQueryParameter("actionTypes", "[\"skip\",\"poi\"]")
            }
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            // 404 = chưa có ai đánh dấu video này (kết quả hợp lệ, được cache).
            if (response.code == 404) return emptyList()
            // Các lỗi còn lại (5xx, 429...) ném ra để không cache kết quả rỗng.
            if (!response.isSuccessful) error("SponsorBlock HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return emptyList()
            return parseSegments(body, videoId)
        }
    }

    /** 4 ký tự hex đầu của SHA-256(videoId), đúng định dạng hash-prefix của SponsorBlock. */
    internal fun hashPrefix(videoId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(videoId.toByteArray(Charsets.UTF_8))
        // HASH_PREFIX_LENGTH tính bằng ký tự hex, không phải byte: mỗi byte = 2 ký tự.
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .take(HASH_PREFIX_LENGTH)
    }

    /**
     * Tách JSON của SponsorBlock thành danh sách đoạn; internal để unit test gọi được.
     *
     * Endpoint hash-prefix trả về mảng nhóm:
     * `[{"videoID": "...", "segments": [ {...}, ... ]}, ...]`.
     * Một số server tự host (và endpoint `?videoID=`) lại trả về mảng phẳng
     * các đoạn, hoặc một object đơn. Hàm này hỗ trợ cả ba dạng. Nếu [videoId]
     * được truyền vào, nhóm/đoạn của video khác sẽ bị bỏ qua.
     */
    internal fun parseSegments(body: String, videoId: String = ""): List<SponsorSegment> {
        val root = runAppCatchingBlocking { JSONTokener(body).nextValue() }.getOrNull()
        val segments = ArrayList<SponsorSegment>()

        fun addFromArray(array: JSONArray) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val nested = item.optJSONArray("segments")
                if (nested != null) {
                    if (videoId.isNotBlank()) {
                        val groupVideoId = item.optString("videoID")
                        if (groupVideoId.isNotBlank() && groupVideoId != videoId) continue
                    }
                    addFromArray(nested)
                } else {
                    parseSegment(item, videoId)?.let(segments::add)
                }
            }
        }

        when (root) {
            is JSONArray -> addFromArray(root)
            is JSONObject -> {
                val nested = root.optJSONArray("segments")
                if (nested != null) {
                    if (videoId.isBlank() ||
                        root.optString("videoID").let { it.isBlank() || it == videoId }
                    ) {
                        addFromArray(nested)
                    }
                } else {
                    parseSegment(root, videoId)?.let(segments::add)
                }
            }
            else -> return emptyList()
        }
        return segments.sortedBy { it.startMs }
    }

    /** Một object đoạn đơn; trả về null nếu thiếu trường hoặc là video khác. */
    private fun parseSegment(item: JSONObject, videoId: String): SponsorSegment? {
        if (videoId.isNotBlank()) {
            val itemVideoId = item.optString("videoID")
            if (itemVideoId.isNotBlank() && itemVideoId != videoId) return null
        }

        val actionType = item.optString("actionType", "skip")
            .takeIf { it == "skip" || it == "poi" }
            ?: return null
        val range = item.optJSONArray("segment") ?: return null
        val startSeconds = range.optDouble(0, 0.0)
        val endSeconds = range.optDouble(1, 0.0)
        if (!startSeconds.isFinite() || !endSeconds.isFinite()) return null
        if (endSeconds <= startSeconds || startSeconds < 0.0) return null

        return SponsorSegment(
            startMs = (startSeconds * 1000).toLong(),
            endMs = (endSeconds * 1000).toLong(),
            category = item.optString("category", "sponsor"),
            uuid = item.optString("UUID"),
            actionType = actionType
        )
    }
}

/**
 * Đoạn tự tua đã chọn, kèm tất cả key của các đoạn liền nhau bị gộp vào lần tua.
 *
 * Ví dụ quảng cáo 10–20s nối liền outro 20–25s: tua một lần từ 10s tới 25s
 * thay vì tua hai lần, tránh giật hình và tránh bỏ sót đoạn thứ hai.
 */
internal data class SponsorSkipTarget(
    val segment: SponsorSegment,
    val keys: Set<String>
)

/**
 * Chọn đoạn cần tự tua tại [positionMs].
 *
 * - Chỉ xét actionType `skip`; `poi` chỉ để đánh dấu.
 * - Chỉ chọn đoạn chứa vị trí hiện tại và chưa được tua (theo key/UUID).
 * - Bình thường chỉ nhận khi vị trí còn trong [toleranceMs] đầu đoạn, để thao
 *   tác tua tay vào giữa đoạn được tôn trọng.
 * - [ignoreTolerance] dùng khi dữ liệu SponsorBlock vừa về hoặc video vừa resume
 *   vào giữa đoạn: lúc đó vẫn phải tua, nếu không người dùng sẽ thấy "không chạy".
 */
internal fun findSkipTarget(
    segments: List<SponsorSegment>,
    positionMs: Long,
    toleranceMs: Long,
    skipped: Set<String>,
    ignoreTolerance: Boolean = false
): SponsorSkipTarget? {
    val segment = segments
        .asSequence()
        .filter { it.isSkip }
        .filter { positionMs >= it.startMs && positionMs < it.endMs }
        .filter { it.key !in skipped }
        .minByOrNull { it.startMs }
        ?: return null

    if (!ignoreTolerance && positionMs - segment.startMs >= toleranceMs) return null

    val keys = linkedSetOf(segment.key)
    var endMs = segment.endMs
    while (true) {
        val next = segments.firstOrNull { candidate ->
            candidate.isSkip &&
                candidate.key !in keys &&
                candidate.startMs <= endMs &&
                candidate.endMs > endMs
        } ?: break
        keys += next.key
        endMs = next.endMs
    }

    return SponsorSkipTarget(
        segment = if (endMs > segment.endMs) segment.copy(endMs = endMs) else segment,
        keys = keys
    )
}

/** Giữ API cũ cho test/code đang dùng: trả về đoạn đã gộp chain. */
internal fun findSegmentToSkip(
    segments: List<SponsorSegment>,
    positionMs: Long,
    toleranceMs: Long,
    skipped: Set<String>,
    ignoreTolerance: Boolean = false
): SponsorSegment? = findSkipTarget(
    segments = segments,
    positionMs = positionMs,
    toleranceMs = toleranceMs,
    skipped = skipped,
    ignoreTolerance = ignoreTolerance
)?.segment

/** Đoạn đang đi qua tại [positionMs] (dùng cho nút Bỏ qua / danh sách đoạn). */
internal fun findSegmentAt(
    segments: List<SponsorSegment>,
    positionMs: Long
): SponsorSegment? = segments
    .asSequence()
    .filter { positionMs >= it.startMs && positionMs < it.endMs }
    .minByOrNull { it.startMs }

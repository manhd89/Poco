package com.luxysiv.puretube.data.repository

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * HTTP tối giản dùng chung cho các nguồn InnerTube trực tiếp.
 *
 * Không cần API key: các endpoint `youtubei/v1/player` và `youtubei/v1/next`
 * chấp nhận request ẩn danh từ `www.youtube.com`.
 */
internal object InnerTubeHttp {

    internal const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    const val IOS_USER_AGENT =
        "com.google.ios.youtube/21.03.2 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"

    const val ANDROID_USER_AGENT =
        "com.google.android.youtube/21.03.36 (Linux; U; Android 15; VN) gzip"

    /**
     * UA của app YouTube trên visionOS.
     *
     * Đây không phải UA trình duyệt: endpoint `youtubei.googleapis.com` của
     * client `VISIONOS` chỉ nhận UA dạng app + header
     * `X-Goog-Api-Format-Version: 2`. Đổi sang UA Chrome sẽ khiến YouTube trả
     * `playabilityStatus` lỗi hoặc thiếu URL adaptive.
     */
    const val VISIONOS_USER_AGENT =
        "com.google.visionos.youtube/1.04(RealityDevice17,1; U; CPU visionOS 26_6_0 " +
            "like Mac OS X; VN)"

    /**
     * Bảng chữ cái của `cpn`/`t` (content playback nonce): URL-safe base64 không
     * có ký tự đệm, đúng như app YouTube gửi.
     */
    private const val NONCE_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * Client dùng chung cho mọi DataSource.
     *
     * Các nguồn cần timeout khác nhau có thể gọi `client.newBuilder()` để tái sử
     * dụng connection pool/dispatcher thay vì tạo OkHttpClient riêng.
     */
    internal val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun post(
        url: String,
        body: String,
        userAgent: String,
        referer: String = "https://www.youtube.com/",
        extraHeaders: Map<String, String> = emptyMap(),
        /**
         * `false` khi gọi endpoint dành riêng cho app (visionOS): không gửi
         * Origin/Referer/Cookie của trình duyệt để request giống app thật.
         */
        webHeaders: Boolean = true
    ): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Accept-Language", "vi-VN,vi;q=0.9,en;q=0.8")
            .apply {
                if (webHeaders) {
                    header("Origin", "https://www.youtube.com")
                    header("Referer", referer)
                    header("Cookie", "SOCS=CAI; CONSENT=YES+1")
                }
                extraHeaders.forEach { (key, value) -> header(key, value) }
            }
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request)
    }

    /**
     * Sinh `cpn` (content playback nonce) 16 ký tự.
     *
     * YouTube dùng nonce này để liên kết request `player` với các URL
     * `googlevideo` trả về. Thiếu `cpn` trên URL, CDN có thể coi là phiên lạ và
     * trả `403` sau một khoảng thời gian ngắn, hoặc chỉ cho tải phần đầu.
     */
    fun generateContentPlaybackNonce(): String = randomToken(16)

    /**
     * Sinh tham số `t` 12 ký tự cho request `player` của client Apple
     * (`visionOS`). App YouTube gửi kèm tham số này như một nonce theo request.
     */
    fun generateTParameter(): String = randomToken(12)

    private fun randomToken(length: Int): String = buildString(length) {
        repeat(length) { append(NONCE_ALPHABET[Random.nextInt(NONCE_ALPHABET.length)]) }
    }

    fun get(url: String, userAgent: String = DESKTOP_USER_AGENT): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept-Language", "vi-VN,vi;q=0.9,en;q=0.8")
            .header("Cookie", "SOCS=CAI; CONSENT=YES+1")
            .get()
            .build()
        return execute(request)
    }

    private fun execute(request: Request): String? = try {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                null
            } else {
                response.body?.string()?.takeIf { it.isNotBlank() }
            }
        }
    } catch (_: Exception) {
        null
    }
}

/** Tiện ích đọc JSON YouTube (runs/simpleText, thumbnail, số kiểu Việt Nam). */
internal object InnerTubeJson {

    fun parseObject(raw: String?): JSONObject? = try {
        raw?.let { JSONObject(it) }
    } catch (_: Exception) {
        null
    }

    fun text(obj: JSONObject?): String? {
        if (obj == null) return null
        val runs = obj.optJSONArray("runs")
        if (runs != null && runs.length() > 0) {
            val sb = StringBuilder()
            for (i in 0 until runs.length()) {
                sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
            }
            val joined = sb.toString().trim()
            if (joined.isNotBlank()) return joined
        }
        // Renderer kiểu mới (lockup/view model) dùng thẳng `content`.
        val content = obj.optString("content").trim()
        if (content.isNotBlank()) return content
        return obj.optString("simpleText").trim().ifBlank { null }
    }

    /** Ảnh lớn nhất trong object `thumbnail`/`sources` của YouTube. */
    fun bestThumbnailUrl(thumbnail: JSONObject?): String {
        if (thumbnail == null) return ""
        val array = thumbnail.optJSONArray("thumbnails")
            ?: thumbnail.optJSONArray("sources")
            ?: return normalizeUrl(thumbnail.optString("url"))
        var bestUrl = ""
        var bestArea = -1L
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val url = item.optString("url")
            if (url.isBlank()) continue
            val area = item.optLong("width") * item.optLong("height")
            if (area >= bestArea) {
                bestUrl = url
                bestArea = area
            }
        }
        return normalizeUrl(bestUrl)
    }

    /**
     * Chuẩn hóa URL protocol-relative (`//host/path`) thành `https://host/path`.
     *
     * Feed danh mục (ví dụ Trò chơi) trả avatar/thumbnail dạng `//yt3...`;
     * Coil cần scheme đầy đủ nên nếu giữ nguyên ảnh sẽ không tải được.
     */
    fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        return if (trimmed.startsWith("//")) "https:$trimmed" else trimmed
    }

    /** Tìm mọi object nằm dưới khóa [name] ở bất kỳ độ sâu nào. */
    fun objectsNamed(root: JSONObject, name: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        fun scan(node: Any?) {
            when (node) {
                is JSONObject -> {
                    node.optJSONObject(name)?.let(out::add)
                    val keys = node.keys()
                    while (keys.hasNext()) scan(node.opt(keys.next()))
                }
                is JSONArray -> for (i in 0 until node.length()) scan(node.opt(i))
            }
        }
        scan(root)
        return out
    }

    fun firstObjectNamed(root: JSONObject, name: String): JSONObject? =
        objectsNamed(root, name).firstOrNull()

    fun range(obj: JSONObject?, key: String): Pair<Long, Long>? {
        val range = obj?.optJSONObject(key) ?: return null
        val start = range.optString("start").toLongOrNull() ?: return null
        val end = range.optString("end").toLongOrNull() ?: return null
        return start to end
    }

    fun mimeBase(mimeType: String): String = mimeType.substringBefore(';').trim()

    fun mimeCodec(mimeType: String): String {
        val quoted = Regex("codecs=\"([^\"]+)\"").find(mimeType)?.groupValues?.get(1)
        if (!quoted.isNullOrBlank()) return quoted
        return mimeType.substringAfter("codecs=", "")
            .trim()
            .trim('"')
    }

    /**
     * Đọc số kiểu YouTube tiếng Việt: `1.834.597.073`, `1,8 T`, `671 N`,
     * `2,4 Tr`, `1,5 triệu`. Trả về 0 khi không có chữ số.
     */
    fun parseCount(value: String?): Long {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return 0

        val suffixMatch = Regex(
            "([0-9][0-9.,]*)\\s*(tỷ|triệu|tr|nghìn|n|k|t)(?![\\p{L}])",
            RegexOption.IGNORE_CASE
        ).find(raw)

        if (suffixMatch == null) {
            val digits = raw.filter { it.isDigit() }
            return digits.toLongOrNull() ?: 0L
        }

        val numberText = suffixMatch.groupValues[1]
        val multiplier = when (suffixMatch.groupValues[2].lowercase(Locale.US)) {
            "tỷ", "t" -> 1_000_000_000L
            "triệu", "tr" -> 1_000_000L
            else -> 1_000L
        }
        val normalized = if (numberText.contains(',')) {
            numberText.replace(".", "").replace(',', '.')
        } else if (Regex("^\\d{1,3}(\\.\\d{3})+$").matches(numberText)) {
            numberText.replace(".", "")
        } else {
            numberText
        }
        val number = normalized.toDoubleOrNull() ?: return 0L
        return (number * multiplier).toLong()
    }

    /** `1:02:03` / `4:05` → tổng giây; 0 nếu không đọc được. */
    fun durationSeconds(text: String?): Long =
        com.luxysiv.puretube.data.model.parseDurationSecondsOrNull(text)?.toLong() ?: 0L

    fun absoluteUrl(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        else -> url
    }
}

package com.luxysiv.puretube.data.model

/**
 * Cách xử lý một loại đoạn SponsorBlock.
 *
 * - [AUTO]: tự tua qua khi vừa đi vào đầu đoạn.
 * - [MANUAL]: không tự tua, chỉ hiện nút "Bỏ qua" để người dùng quyết định.
 * - [HIGHLIGHT]: chỉ vẽ marker trên thanh tiến trình.
 * - [DISABLED]: bỏ qua hoàn toàn loại đoạn này.
 */
enum class SponsorMode(val label: String) {
    AUTO("Tự động"),
    MANUAL("Thủ công"),
    HIGHLIGHT("Chỉ đánh dấu"),
    DISABLED("Tắt")
}

/**
 * Các category chính thức của SponsorBlock.
 *
 * [colorArgb] dùng cho marker trên seek bar và danh sách đoạn. Màu được chọn
 * đủ tương phản trên nền tối của trình phát.
 */
enum class SponsorCategory(
    val apiName: String,
    val label: String,
    val colorArgb: Long,
    val defaultMode: SponsorMode
) {
    SPONSOR("sponsor", "Tài trợ", 0xFFFFC107, SponsorMode.AUTO),
    SELF_PROMO("selfpromo", "Tự quảng bá", 0xFFFFF176, SponsorMode.AUTO),
    INTERACTION("interaction", "Kêu gọi tương tác", 0xFF00E5FF, SponsorMode.AUTO),
    INTRO("intro", "Mở đầu", 0xFF2196F3, SponsorMode.AUTO),
    OUTRO("outro", "Kết thúc", 0xFF673AB7, SponsorMode.AUTO),
    PREVIEW("preview", "Xem trước", 0xFFFF9800, SponsorMode.AUTO),
    MUSIC_OFFTOPIC("music_offtopic", "Ngoài phần nhạc", 0xFF4CAF50, SponsorMode.AUTO),
    FILLER("filler", "Đoạn phụ", 0xFF9E9E9E, SponsorMode.DISABLED),
    POI_HIGHLIGHT("poi_highlight", "Điểm nhấn", 0xFFE91E63, SponsorMode.HIGHLIGHT);

    companion object {
        fun fromApiName(value: String): SponsorCategory? =
            entries.firstOrNull { it.apiName == value }

        /** Danh sách category được gửi lên API (không gửi category đã tắt hẳn). */
        fun apiNamesFor(modes: Map<String, SponsorMode>): Set<String> =
            entries.asSequence()
                .filter { modes[it.apiName] != SponsorMode.DISABLED }
                .map { it.apiName }
                .toSet()

        fun defaultModes(): Map<String, SponsorMode> =
            entries.associate { it.apiName to it.defaultMode }
    }
}

/** Một đoạn do cộng đồng SponsorBlock đánh dấu. */
data class SponsorSegment(
    val startMs: Long,
    val endMs: Long,
    val category: String,
    val uuid: String,
    /** "skip" (tự tua) hoặc "poi" (điểm nhấn, không tự tua). */
    val actionType: String = "skip"
) {
    /** Khoá ổn định kể cả khi API không trả UUID. */
    val key: String
        get() = uuid.ifBlank { "$category:$startMs:$endMs:$actionType" }

    val isHighlight: Boolean get() = actionType == "poi"

    /** Đoạn tự tua (`skip`); `poi` chỉ là điểm nhấn, không tua. */
    val isSkip: Boolean get() = actionType == "skip"

    val categoryInfo: SponsorCategory? get() = SponsorCategory.fromApiName(category)
}

/**
 * Khoá kênh dùng cho danh sách miễn trừ SponsorBlock.
 *
 * Ưu tiên `channelId` vì tên kênh có thể đổi hoặc trùng; chỉ dùng tên khi API
 * không trả id (video cũ, dữ liệu tối giản).
 */
fun sponsorChannelKey(video: VideoItem): String =
    video.channelId.ifBlank { video.channelTitle }

fun defaultSponsorCategoryModes(): Map<String, SponsorMode> = SponsorCategory.defaultModes()

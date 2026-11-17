package com.luxysiv.puretube.util

import java.util.Locale

/**
 * Định dạng chuỗi dùng chung cho UI và trình phát.
 *
 * Trước đây mỗi Activity/View tự viết lại cách hiển thị tốc độ, thời lượng hay
 * nhãn chất lượng nên rất dễ lệch nhau khi sửa. Gom về một chỗ để mọi màn hình
 * hiển thị cùng một định dạng.
 */
object Formatters {

    /** `213` giây → `3:33`; `3723` → `1:02:03`. */
    fun duration(totalSeconds: Long): String {
        if (totalSeconds <= 0L) return "--:--"
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /** Thời gian trên thanh tiến trình: luôn có 2 chữ số phút, `0` → `00:00`. */
    fun playbackTime(seconds: Long): String {
        if (seconds <= 0L) return "00:00"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remaining = seconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, remaining)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, remaining)
        }
    }

    /** `1.0` → `1`, `1.25` → `1.25`: bỏ phần thập phân thừa khi hiển thị tốc độ. */
    fun speed(value: Float): String =
        if (value == value.toInt().toFloat()) {
            value.toInt().toString()
        } else {
            value.toString()
        }

    /** `1080` → `1080p`; `0` (tự động) → nhãn [autoLabel] của màn hình gọi. */
    fun quality(height: Int, autoLabel: String): String =
        if (height > 0) "${height}p" else autoLabel

    /**
     * Thời điểm đăng video → chuỗi tương đối kiểu YouTube ("3 giờ trước").
     *
     * [nowMillis] tách khỏi thời gian thực để test được và để ViewModel có thể
     * dùng cùng một mốc cho mọi thẻ trong một lần render. Trả rỗng khi chưa biết
     * thời điểm đăng (feed cũ/không parse được ngày).
     */
    fun relativeTime(epochMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        if (epochMillis <= 0L) return ""
        val elapsed = (nowMillis - epochMillis).coerceAtLeast(0L)
        return when {
            elapsed < MINUTE_MS -> "vừa xong"
            elapsed < HOUR_MS -> "${elapsed / MINUTE_MS} phút trước"
            elapsed < DAY_MS -> "${elapsed / HOUR_MS} giờ trước"
            elapsed < WEEK_MS -> "${elapsed / DAY_MS} ngày trước"
            elapsed < MONTH_MS -> "${elapsed / WEEK_MS} tuần trước"
            elapsed < YEAR_MS -> "${elapsed / MONTH_MS} tháng trước"
            else -> "${elapsed / YEAR_MS} năm trước"
        }
    }

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS
    private const val DAY_MS = 24 * HOUR_MS
    private const val WEEK_MS = 7 * DAY_MS
    private const val MONTH_MS = 30 * DAY_MS
    private const val YEAR_MS = 365 * DAY_MS
}

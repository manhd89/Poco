package com.luxysiv.puretube.ui.components

/**
 * Tiêu đề mix kiểu YouTube: bỏ tiền tố "Mix -"/"Danh sách kết hợp" mà
 * YouTube trả về rồi chuẩn hoá thành "Danh sách kết hợp – …" như ứng dụng thật.
 */
fun mixDisplayTitle(rawTitle: String, videoTitle: String): String {
    val raw = rawTitle.trim().ifBlank { videoTitle.trim() }
    val prefixes = listOf(
        "Danh sách kết hợp – ",
        "Danh sách kết hợp - ",
        "Mix – ",
        "Mix - "
    )
    val base = prefixes
        .firstOrNull { raw.startsWith(it, ignoreCase = true) }
        ?.let { raw.substring(it.length).trim() }
        ?: raw

    return when {
        base.isBlank() -> "Danh sách kết hợp"
        base.startsWith("Danh sách kết hợp", ignoreCase = true) -> base
        else -> "Danh sách kết hợp – $base"
    }
}

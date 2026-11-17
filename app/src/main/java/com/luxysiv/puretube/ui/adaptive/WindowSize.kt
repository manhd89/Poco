package com.luxysiv.puretube.ui.adaptive

/**
 * Ba lớp kích thước theo Material 3 window size class:
 * - Compact  (< 600dp)  : điện thoại dọc.
 * - Medium   (600–839dp): điện thoại ngang, tablet nhỏ, foldable.
 * - Expanded (>= 840dp) : tablet lớn, desktop mode.
 */
enum class WindowWidthClass {
    Compact,
    Medium,
    Expanded
}

internal fun windowWidthClassForWidth(widthDp: Int): WindowWidthClass = when {
    widthDp < 600 -> WindowWidthClass.Compact
    widthDp < 840 -> WindowWidthClass.Medium
    else -> WindowWidthClass.Expanded
}

/** Số cột lưới video phù hợp cho từng bề rộng màn hình. */
internal fun videoGridColumnsForWidth(widthDp: Int): Int = when {
    widthDp < 600 -> 1
    widthDp < 900 -> 2
    widthDp < 1240 -> 3
    else -> 4
}

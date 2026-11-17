package com.luxysiv.puretube.ui.views

import android.content.Context
import android.graphics.Typeface
import android.widget.TextView

/** Đổi dp sang px. */
internal fun Context.dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

/** Giữ nguyên fontFamily tuỳ biến khi bật hoặc tắt chữ đậm. */
internal fun TextView.applyBold(bold: Boolean) {
    setTypeface(typeface, if (bold) Typeface.BOLD else Typeface.NORMAL)
}

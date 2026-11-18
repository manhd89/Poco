package com.luxysiv.puretube.ui.views

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.luxysiv.puretube.R

/** Đổi dp sang px. */
internal fun Context.dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

/** Giữ nguyên fontFamily tuỳ biến khi bật hoặc tắt chữ đậm. */
internal fun TextView.applyBold(bold: Boolean) {
    setTypeface(typeface, if (bold) Typeface.BOLD else Typeface.NORMAL)
}

/** Áp dụng nền thẻ bo góc 20dp, viền mảnh, ripple và padding chuẩn. */
internal fun View.applyCardSurface(paddingDp: Float = 10f) {
    setBackgroundResource(R.drawable.bg_video_card)
    clipToOutline = true
    val typedValue = TypedValue()
    if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)) {
        if (typedValue.resourceId != 0) {
            foreground = ContextCompat.getDrawable(context, typedValue.resourceId)
        }
    }
    val pad = context.dp(paddingDp)
    setPadding(pad, pad, pad, pad)
}

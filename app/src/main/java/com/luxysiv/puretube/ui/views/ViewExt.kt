package com.luxysiv.puretube.ui.views

import android.content.Context

/** Đổi dp sang px. */
internal fun Context.dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

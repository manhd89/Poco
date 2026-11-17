package com.luxysiv.puretube.ui.components

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Nhãn nhóm ngày cho danh sách trong Thư viện: "Hôm nay", "Hôm qua" hoặc
 * ngày cụ thể theo định dạng `dd/MM/yyyy`.
 */
fun dayLabel(timestamp: Long): String {
    if (timestamp <= 0L) return "Trước đó"

    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }

    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    if (sameYear && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) {
        return "Hôm nay"
    }

    val yesterday = (now.clone() as Calendar).apply {
        add(Calendar.DAY_OF_YEAR, -1)
    }
    if (sameYear && yesterday.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        yesterday.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    ) {
        return "Hôm qua"
    }

    return SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(then.time)
}

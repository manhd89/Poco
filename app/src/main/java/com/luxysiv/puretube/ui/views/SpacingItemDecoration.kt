package com.luxysiv.puretube.ui.views

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/** Khoảng cách đều giữa các ô trong RecyclerView (dùng cho lưới video). */
class SpacingItemDecoration(private val spacing: Int) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val half = spacing / 2
        outRect.set(half, half, half, half)
    }
}

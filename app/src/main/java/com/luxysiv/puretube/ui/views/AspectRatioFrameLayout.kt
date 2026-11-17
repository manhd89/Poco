package com.luxysiv.puretube.ui.views

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * FrameLayout luôn giữ tỉ lệ 16:9 theo chiều rộng.
 *
 * Dùng cho thumbnail video và bìa mix: chiều rộng do RecyclerView quyết định,
 * chiều cao được tính lại trong [onMeasure] để ảnh không bị méo.
 */
class AspectRatioFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var aspectRatio = 16f / 9f

    fun setAspectRatio(ratio: Float) {
        if (ratio > 0f && ratio != aspectRatio) {
            aspectRatio = ratio
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        if (widthMode != MeasureSpec.UNSPECIFIED && width > 0) {
            val height = (width / aspectRatio).toInt()
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            )
            return
        }

        val height = MeasureSpec.getSize(heightMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        if (heightMode != MeasureSpec.UNSPECIFIED && height > 0) {
            val computedWidth = (height * aspectRatio).toInt()
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(computedWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            )
            return
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

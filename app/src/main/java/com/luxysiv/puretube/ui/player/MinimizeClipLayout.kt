package com.luxysiv.puretube.ui.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * FrameLayout cắt con theo một hình chữ nhật trong toạ độ của chính nó.
 *
 * Dùng cho hiệu ứng thu nhỏ toàn trang: cả trang xem được scale/translate như
 * một khối, còn khung nhìn cắt dần về đúng khung mini player. Cắt bằng rect
 * (scissor) rẻ hơn nhiều so với clip theo outline hay alpha từng frame, nên
 * hiệu ứng không bị tụt khung hình.
 */
class MinimizeClipLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val clipRect = RectF()
    private var clipEnabled = false

    /**
     * Cắt phần được vẽ của các view con theo hình chữ nhật (toạ độ view này).
     * Dùng số thực để mép cắt trùng khít mép trang đang scale, không lộ khe
     * 1px do làm tròn.
     */
    fun setClipRect(left: Float, top: Float, right: Float, bottom: Float) {
        clipRect.set(left, top, right, bottom)
        clipEnabled = true
        invalidate()
    }

    fun clearClipRect() {
        if (!clipEnabled) return
        clipEnabled = false
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (!clipEnabled) {
            super.dispatchDraw(canvas)
            return
        }
        val save = canvas.save()
        canvas.clipRect(clipRect)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }
}

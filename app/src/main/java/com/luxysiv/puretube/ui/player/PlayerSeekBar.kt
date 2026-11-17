package com.luxysiv.puretube.ui.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatSeekBar
import com.luxysiv.puretube.data.model.SponsorSegment

/**
 * Thanh tua có vạch màu các đoạn SponsorBlock, giống thanh tiến trình YouTube.
 *
 * Vạch được vẽ đè lên rãnh mặc định; màu lấy từ category của đoạn. Chỉ vẽ khi
 * biết tổng thời lượng để quy đổi giây sang tỉ lệ.
 */
class PlayerSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.seekBarStyle
) : AppCompatSeekBar(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var segments: List<SponsorSegment> = emptyList()
    private var durationSeconds: Float = 0f

    fun setSponsorSegments(segments: List<SponsorSegment>, durationSeconds: Float) {
        this.segments = segments
        this.durationSeconds = durationSeconds
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawSponsorMarkers(canvas)
        // Vẽ lại núm tua sau cùng: marker SponsorBlock nằm trên rãnh nhưng
        // không được cắt ngang núm tua khi nó trùng vị trí đoạn. AbsSeekBar
        // vẽ thumb trong hệ toạ độ đã dịch (paddingLeft - thumbOffset,
        // paddingTop) nên phải dịch y hệt.
        val thumbDrawable = thumb
        if (thumbDrawable != null) {
            val saveCount = canvas.save()
            canvas.translate(
                (paddingLeft - thumbOffset).toFloat(),
                paddingTop.toFloat()
            )
            thumbDrawable.draw(canvas)
            canvas.restoreToCount(saveCount)
        }
    }

    private fun drawSponsorMarkers(canvas: Canvas) {
        if (segments.isEmpty() || durationSeconds <= 0f) return
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        if (right <= left) return
        val trackWidth = right - left
        val centerY = height / 2f
        val density = context.resources.displayMetrics.density
        val half = density * 1.8f
        val radius = half
        segments.forEach { segment ->
            if (segment.isHighlight) return@forEach
            val start = (segment.startMs / 1000f / durationSeconds).coerceIn(0f, 1f)
            val end = (segment.endMs / 1000f / durationSeconds).coerceIn(0f, 1f)
            if (end <= start) return@forEach
            // Đoạn cực ngắn vẫn cần một chấm nhìn thấy được.
            val startX = left + trackWidth * start
            val endX = (left + trackWidth * end).coerceAtLeast(startX + density)
            paint.color = (segment.categoryInfo?.colorArgb ?: 0xFFFFC107).toInt()
            canvas.drawRoundRect(
                startX,
                centerY - half,
                endX,
                centerY + half,
                radius,
                radius,
                paint
            )
        }
    }
}

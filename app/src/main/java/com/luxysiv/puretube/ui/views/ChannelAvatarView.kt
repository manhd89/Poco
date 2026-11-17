package com.luxysiv.puretube.ui.views

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import coil.load
import com.luxysiv.puretube.R

/**
 * Avatar kênh native: nền màu ổn định theo tên, chữ cái dự phòng và ảnh thật
 * (nếu YouTube trả về) phủ lên trên.
 */
class ChannelAvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val image: ImageView
    private val initial: TextView

    /** URL avatar đã bind lần cuối. */
    private var boundAvatarUrl: String? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.view_channel_avatar, this, true)
        image = findViewById(R.id.avatar_image)
        initial = findViewById(R.id.avatar_initial)
        clipToOutline = true
    }

    fun bind(name: String, avatarUrl: String) {
        val safeName = name.trim()
        val safeAvatarUrl = normalizeUrl(avatarUrl)
        initial.text = safeName.firstOrNull()?.uppercase()?.takeUnless { it.isBlank() } ?: "Y"
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(avatarPalette[(safeName.hashCode() and Int.MAX_VALUE) % avatarPalette.size])
        }

        if (safeAvatarUrl.isBlank()) {
            boundAvatarUrl = null
            image.load(null)
            image.setImageDrawable(null)
            image.visibility = GONE
            return
        }

        if (safeAvatarUrl == boundAvatarUrl && image.drawable != null) {
            image.visibility = VISIBLE
            return
        }

        boundAvatarUrl = safeAvatarUrl
        image.visibility = INVISIBLE
        image.load(safeAvatarUrl) {
            crossfade(true)
            listener(
                onStart = {
                    if (boundAvatarUrl == safeAvatarUrl) image.visibility = INVISIBLE
                },
                onSuccess = { _, _ ->
                    if (boundAvatarUrl == safeAvatarUrl) image.visibility = VISIBLE
                },
                onError = { _, _ ->
                    if (boundAvatarUrl == safeAvatarUrl) {
                        image.setImageDrawable(null)
                        image.visibility = GONE
                    }
                },
                onCancel = {
                    if (boundAvatarUrl == safeAvatarUrl) image.visibility = GONE
                }
            )
        }
    }

    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        return when {
            trimmed.isBlank() -> ""
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("http://") -> "https://" + trimmed.removePrefix("http://")
            else -> trimmed
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        initial.setTextSize(TypedValue.COMPLEX_UNIT_PX, h * 0.42f)
    }

    private companion object {
        val avatarPalette = intArrayOf(
            0xFFB3261E.toInt(),
            0xFF1565C0.toInt(),
            0xFF2E7D32.toInt(),
            0xFF6A1B9A.toInt(),
            0xFFEF6C00.toInt(),
            0xFF00838F.toInt(),
            0xFF4E342E.toInt(),
            0xFF37474F.toInt()
        )
    }
}

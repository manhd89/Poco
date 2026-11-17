package com.luxysiv.puretube.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import coil.load
import com.luxysiv.puretube.R
import com.luxysiv.puretube.data.model.VideoItem

/**
 * Bìa mix 2x2 giống ảnh ghép playlist của YouTube.
 *
 * Giữ tỉ lệ 16:9 và tự tính chiều cao theo chiều rộng để dùng trực tiếp trong
 * thẻ "Danh sách kết hợp".
 */
class MixCoverView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val tiles = ArrayList<ImageView>(4)
    private val emptyColor = ContextCompat.getColor(context, R.color.pure_surface_container_highest)

    /** URL đang hiển thị của từng ô, tránh nạp lại ảnh khi rebind cùng mix. */
    private val tileUrls = arrayOfNulls<String>(4)

    init {
        LayoutInflater.from(context).inflate(R.layout.view_mix_cover, this, true)
        tiles += findViewById<ImageView>(R.id.mix_tile_0)
        tiles += findViewById<ImageView>(R.id.mix_tile_1)
        tiles += findViewById<ImageView>(R.id.mix_tile_2)
        tiles += findViewById<ImageView>(R.id.mix_tile_3)
    }

    fun bind(videos: List<VideoItem>) {
        tiles.forEachIndexed { index, tile ->
            val video = videos.getOrNull(index)
            val url = video?.thumbnailUrl
            tile.setBackgroundColor(emptyColor)
            if (url.isNullOrBlank()) {
                tileUrls[index] = null
                tile.setImageDrawable(null)
            } else if (url != tileUrls[index]) {
                tileUrls[index] = url
                tile.load(url) {
                    crossfade(true)
                }
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        if (widthMode != MeasureSpec.UNSPECIFIED && width > 0) {
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((width * 9) / 16, MeasureSpec.EXACTLY)
            )
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}

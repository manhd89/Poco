package com.luxysiv.puretube.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.luxysiv.puretube.R
import com.luxysiv.puretube.data.model.HomeMix
import com.luxysiv.puretube.ui.components.mixDisplayTitle

/** Thẻ "Danh sách kết hợp" native: bìa ghép 2x2 + tiêu đề + phụ đề. */
class HomeMixCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val cover: MixCoverView
    private val titleView: TextView

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_home_mix_card, this, true)
        cover = findViewById(R.id.mix_cover)
        titleView = findViewById(R.id.mix_title)
    }

    fun bind(mix: HomeMix, onClick: () -> Unit) {
        cover.bind(mix.playlist.videos.take(4))
        titleView.text = mixDisplayTitle(mix.playlist.title, mix.seed.title)
        setOnClickListener { onClick() }
    }
}

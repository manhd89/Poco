package com.luxysiv.puretube.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import coil.load
import com.luxysiv.puretube.R
import com.luxysiv.puretube.data.model.VideoItem

/**
 * Thẻ video đầy đủ native (thumbnail 16:9 phía trên, thông tin kênh bên dưới).
 *
 * Dùng cho lưới Trang chủ, Tìm kiếm, Đăng ký và các kệ ngang. Cùng dữ liệu và
 * menu với thẻ video của bản Compose trước đây.
 */
class VideoCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val thumbnailContainer: View
    private val thumbnail: ImageView
    private val durationView: TextView
    private val progressView: ProgressBar
    private val avatar: ChannelAvatarView
    private val titleView: TextView
    private val channelView: TextView
    private val metaView: TextView
    private val menuButton: ImageButton

    private var video: VideoItem? = null
    private var isFavorite = false
    private var isWatchLater = false
    private var actions: VideoCardActions? = null

    /**
     * URL thumbnail đang hiển thị. Giữ lại để lần bind sau không nạp lại ảnh
     * nếu vẫn cùng video — Coil xoá ảnh cũ khi bắt đầu request mới, nên nạp
     * lại vô ích sẽ làm thumbnail nháy đen mỗi lần danh sách rebind.
     */
    private var thumbnailUrl: String? = null

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_video_card, this, true)
        thumbnailContainer = findViewById(R.id.card_thumbnail_container)
        thumbnail = findViewById(R.id.card_thumbnail)
        durationView = findViewById(R.id.card_duration)
        progressView = findViewById(R.id.card_progress)
        avatar = findViewById(R.id.card_avatar)
        titleView = findViewById(R.id.card_title)
        channelView = findViewById(R.id.card_channel)
        metaView = findViewById(R.id.card_meta)
        menuButton = findViewById(R.id.card_menu)

        setOnClickListener {
            video?.let { actions?.onClick?.invoke(it) }
        }
        menuButton.setOnClickListener {
            val current = video ?: return@setOnClickListener
            val currentActions = actions ?: return@setOnClickListener
            showVideoCardMenu(
                menuButton,
                context,
                current,
                isFavorite,
                isWatchLater,
                currentActions
            )
        }
    }

    fun bind(
        video: VideoItem,
        isFavorite: Boolean = false,
        isWatchLater: Boolean = false,
        progressFraction: Float = 0f,
        actions: VideoCardActions
    ) {
        this.video = video
        this.isFavorite = isFavorite
        this.isWatchLater = isWatchLater
        this.actions = actions

        titleView.text = video.title
        avatar.bind(video.channelTitle, video.channelAvatarUrl)
        channelView.text = video.channelTitle
        metaView.text = listOf(video.viewCount, video.publishedTime)
            .filter { it.isNotBlank() }
            .joinToString(" • ")

        val duration = video.duration
        if (duration.isBlank() || duration == "--:--") {
            durationView.visibility = View.GONE
        } else {
            durationView.visibility = View.VISIBLE
            durationView.text = duration
            val isLive = duration.contains("TRỰC TIẾP", ignoreCase = true) ||
                duration.contains("LIVE", ignoreCase = true)
            durationView.setBackgroundResource(
                if (isLive) R.drawable.bg_live_badge else R.drawable.bg_duration
            )
        }

        val progress = (progressFraction.coerceIn(0f, 1f) * 100).toInt()
        progressView.progress = progress
        progressView.visibility = if (progress > 0) View.VISIBLE else View.GONE

        thumbnail.contentDescription = video.title
        val url = video.thumbnailUrl
        if (url != thumbnailUrl) {
            thumbnailUrl = url
            if (url.isBlank()) {
                thumbnail.setImageDrawable(null)
            } else {
                thumbnail.load(url) {
                    crossfade(true)
                }
            }
        }
    }
}

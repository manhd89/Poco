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
 * Thẻ video dạng hàng ngang (thumbnail bên trái) cho Tìm kiếm và "Bạn".
 *
 * [showSnippet] bật dòng mô tả hai dòng như kết quả tìm kiếm của YouTube.
 */
class CompactVideoCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val thumbnailContainer: View
    private val thumbnail: ImageView
    private val durationView: TextView
    private val progressView: ProgressBar
    private val titleView: TextView
    private val channelView: TextView
    private val metaView: TextView
    private val snippetHeaderView: TextView
    private val snippetBodyView: TextView
    private val nowPlayingView: TextView
    private val menuButton: ImageButton

    private var video: VideoItem? = null
    private var isFavorite = false
    private var isWatchLater = false
    private var isCurrent = false
    private var actions: VideoCardActions? = null

    /**
     * URL thumbnail đang hiển thị. Bỏ qua nạp lại khi vẫn cùng video để danh
     * sách rebind không xoá ảnh cũ rồi hiện nền đen trong một frame.
     */
    private var thumbnailUrl: String? = null

    init {
        orientation = HORIZONTAL
        val verticalPadding = context.dp(8f)
        setPadding(0, verticalPadding, 0, verticalPadding)
        LayoutInflater.from(context).inflate(R.layout.view_video_card_compact, this, true)
        thumbnailContainer = findViewById(R.id.card_thumbnail_container)
        thumbnail = findViewById(R.id.card_thumbnail)
        durationView = findViewById(R.id.card_duration)
        progressView = findViewById(R.id.card_progress)
        titleView = findViewById(R.id.card_title)
        channelView = findViewById(R.id.card_channel)
        metaView = findViewById(R.id.card_meta)
        snippetHeaderView = findViewById(R.id.card_snippet_header)
        snippetBodyView = findViewById(R.id.card_snippet_body)
        nowPlayingView = findViewById(R.id.card_now_playing)
        menuButton = findViewById(R.id.card_menu)

        setOnClickListener {
            if (isCurrent) return@setOnClickListener
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
        showSnippet: Boolean = false,
        isCurrent: Boolean = false,
        actions: VideoCardActions
    ) {
        this.video = video
        this.isFavorite = isFavorite
        this.isWatchLater = isWatchLater
        this.isCurrent = isCurrent
        this.actions = actions

        val configuration = resources.configuration
        val thumbnailWidth = if (configuration.screenWidthDp >= 600) 144 else 128
        val params = thumbnailContainer.layoutParams
        val targetWidth = context.dp(thumbnailWidth.toFloat())
        if (params.width != targetWidth) {
            params.width = targetWidth
            thumbnailContainer.layoutParams = params
        }

        titleView.text = video.title
        nowPlayingView.visibility = if (isCurrent) View.VISIBLE else View.GONE

        if (showSnippet) {
            channelView.visibility = View.GONE
            metaView.visibility = View.GONE
            snippetHeaderView.visibility = View.VISIBLE
            snippetBodyView.visibility = if (video.description.isNotBlank()) {
                View.VISIBLE
            } else {
                View.GONE
            }
            snippetHeaderView.text = listOf(
                video.channelTitle,
                video.viewCount,
                video.publishedTime
            ).filter { it.isNotBlank() }.joinToString(" • ")
            snippetBodyView.text = video.description
        } else {
            channelView.visibility = View.VISIBLE
            metaView.visibility = View.VISIBLE
            snippetHeaderView.visibility = View.GONE
            snippetBodyView.visibility = View.GONE
            channelView.text = video.channelTitle
            metaView.text = listOf(video.viewCount, video.publishedTime)
                .filter { it.isNotBlank() }
                .joinToString(" • ")
        }

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

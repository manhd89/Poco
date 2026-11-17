package com.luxysiv.puretube.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import coil.load
import com.luxysiv.puretube.R
import com.luxysiv.puretube.data.model.PlaylistItem

/**
 * Thẻ playlist dạng hàng ngang (thumbnail bên trái) cho kết quả tìm kiếm.
 *
 * Badge số video nằm trên thumbnail giống YouTube. Playlist không có thời
 * lượng/lượt xem nên phần chữ chỉ gồm tiêu đề, kênh và nhãn "Danh sách phát".
 */
class PlaylistCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val thumbnailContainer: View
    private val thumbnail: ImageView
    private val badge: View
    private val badgeText: TextView
    private val titleView: TextView
    private val channelView: TextView
    private val metaView: TextView

    private var playlist: PlaylistItem? = null
    private var onClick: ((PlaylistItem) -> Unit)? = null
    private var thumbnailUrl: String? = null
    private var isSaved = false
    private var onToggleSave: ((PlaylistItem) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        val verticalPadding = context.dp(8f)
        setPadding(0, verticalPadding, 0, verticalPadding)
        LayoutInflater.from(context).inflate(R.layout.view_playlist_card, this, true)

        thumbnailContainer = findViewById(R.id.playlist_thumbnail_container)
        thumbnail = findViewById(R.id.playlist_thumbnail)
        badge = findViewById(R.id.playlist_badge)
        badgeText = findViewById(R.id.playlist_badge_text)
        titleView = findViewById(R.id.playlist_title)
        channelView = findViewById(R.id.playlist_channel)
        metaView = findViewById(R.id.playlist_meta)

        setOnClickListener { playlist?.let { onClick?.invoke(it) } }
    }

    fun bind(
        playlist: PlaylistItem,
        onClick: (PlaylistItem) -> Unit,
        isSaved: Boolean = false,
        onToggleSave: ((PlaylistItem) -> Unit)? = null
    ) {
        this.playlist = playlist
        this.onClick = onClick
        this.isSaved = isSaved
        this.onToggleSave = onToggleSave
        setOnLongClickListener { showSaveMenu() }

        val configuration = resources.configuration
        val thumbnailWidth = when {
            configuration.screenWidthDp >= 600 &&
                configuration.screenWidthDp > configuration.screenHeightDp -> 196
            configuration.screenWidthDp >= 600 -> 188
            else -> 128
        }
        val params = thumbnailContainer.layoutParams
        params.width = context.dp(thumbnailWidth.toFloat())
        thumbnailContainer.layoutParams = params

        titleView.text = playlist.title
        channelView.text = playlist.channelTitle
        channelView.visibility =
            if (playlist.channelTitle.isBlank()) View.GONE else View.VISIBLE
        metaView.text = buildMeta(playlist)

        if (playlist.videoCount > 0) {
            badge.visibility = View.VISIBLE
            badgeText.text = context.getString(
                R.string.playlist_video_count,
                playlist.videoCount
            )
        } else {
            badge.visibility = View.GONE
        }

        thumbnail.contentDescription = playlist.title
        val url = playlist.thumbnailUrl
        if (url != thumbnailUrl) {
            thumbnailUrl = url
            if (url.isBlank()) {
                thumbnail.setImageDrawable(null)
            } else {
                thumbnail.load(url) { crossfade(true) }
            }
        }
    }

    /**
     * Nhấn giữ card để lưu/bỏ lưu playlist vào Xem sau.
     *
     * Trả false khi màn hình không cấu hình hành động lưu, để long-press rơi về
     * hành vi mặc định thay vì "nuốt" thao tác.
     */
    private fun showSaveMenu(): Boolean {
        val item = playlist ?: return false
        val callback = onToggleSave ?: return false
        val popup = PopupMenu(context, this)
        val saveId = 1
        popup.menu.add(
            0,
            saveId,
            0,
            context.getString(if (isSaved) R.string.playlist_remove_saved else R.string.playlist_save)
        ).setIcon(if (isSaved) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border)
        popup.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == saveId) callback(item)
            true
        }
        popup.show()
        return true
    }

    private fun buildMeta(playlist: PlaylistItem): String {
        val label = context.getString(R.string.playlist_label)
        val count = if (playlist.videoCount > 0) {
            context.getString(R.string.playlist_video_count, playlist.videoCount)
        } else {
            ""
        }
        return listOf(label, count)
            .filter { it.isNotBlank() }
            .joinToString(" • ")
    }
}

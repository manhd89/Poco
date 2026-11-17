package com.luxysiv.puretube.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.luxysiv.puretube.R
import com.luxysiv.puretube.data.model.PlaylistItem
import com.luxysiv.puretube.data.model.VideoItem
import com.luxysiv.puretube.error.AppError
import com.luxysiv.puretube.util.presentation

/**
 * Màn hình chi tiết playlist native: tiêu đề, kênh, số video và danh sách video
 * tải qua InnerTube `browseId=VL...`.
 *
 * Danh sách tự tải trang tiếp khi người dùng cuộn gần cuối; Activity chỉ cần
 * đẩy trạng thái từ ViewModel vào các hàm `set...`.
 */
class PlaylistNativeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val backButton: ImageButton
    private val titleView: TextView
    private val subtitleView: TextView
    private val list: RecyclerView
    private val loading: ProgressBar
    private val moreProgress: ProgressBar
    private val errorBlock: View
    private val errorTitle: TextView
    private val errorMessage: TextView
    private val retryButton: TextView
    private val emptyView: TextView
    private val saveButton: TextView

    private val adapter = VideoAdapter()

    private var videos: List<VideoItem> = emptyList()
    private var playlist: PlaylistItem? = null
    private var isSaved = false
    private var isLoading = false
    private var isLoadingMore = false
    private var hasMore = false
    private var error: AppError? = null
    private var favoriteIds: Set<String> = emptySet()
    private var watchLaterIds: Set<String> = emptySet()
    private var watchProgress: Map<String, Float> = emptyMap()

    var onBack: (() -> Unit)? = null
    var onRetry: (() -> Unit)? = null
    var onLoadMore: (() -> Unit)? = null
    var onVideoClick: ((VideoItem) -> Unit)? = null
    var onToggleFavorite: ((VideoItem) -> Unit)? = null
    var onToggleWatchLater: ((VideoItem) -> Unit)? = null
    var onPlayNext: ((VideoItem) -> Unit)? = null
    var onAddToQueue: ((VideoItem) -> Unit)? = null
    var onToggleSave: ((PlaylistItem) -> Unit)? = null

    private val actions = videoCardActions(
        onClick = { onVideoClick?.invoke(it) },
        onToggleFavorite = { onToggleFavorite?.invoke(it) },
        onToggleWatchLater = { onToggleWatchLater?.invoke(it) },
        onPlayNext = { onPlayNext?.invoke(it) },
        onAddToQueue = { onAddToQueue?.invoke(it) }
    )

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_playlist, this, true)

        backButton = findViewById(R.id.playlist_back)
        titleView = findViewById(R.id.playlist_detail_title)
        subtitleView = findViewById(R.id.playlist_detail_subtitle)
        list = findViewById(R.id.playlist_list)
        loading = findViewById(R.id.playlist_loading)
        moreProgress = findViewById(R.id.playlist_more_progress)
        errorBlock = findViewById(R.id.playlist_error)
        errorTitle = findViewById(R.id.playlist_error_title)
        errorMessage = findViewById(R.id.playlist_error_message)
        retryButton = findViewById(R.id.playlist_error_retry)
        emptyView = findViewById(R.id.playlist_empty)
        saveButton = findViewById(R.id.playlist_save)

        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || !hasMore || isLoading || isLoadingMore) return
                val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val last = manager.findLastVisibleItemPosition()
                if (last >= adapter.itemCount - 3) onLoadMore?.invoke()
            }
        })

        backButton.setOnClickListener { onBack?.invoke() }
        retryButton.setOnClickListener { onRetry?.invoke() }
        saveButton.setOnClickListener {
            playlist?.let { onToggleSave?.invoke(it) }
        }
        updateSaveButton()
    }

    fun setPlaylist(playlist: PlaylistItem) {
        this.playlist = playlist
        titleView.text = playlist.title.ifBlank { context.getString(R.string.playlist_label) }
        subtitleView.text = listOf(
            playlist.channelTitle,
            if (playlist.videoCount > 0) {
                context.getString(R.string.playlist_video_count, playlist.videoCount)
            } else {
                ""
            }
        ).filter { it.isNotBlank() }.joinToString(" • ")
        subtitleView.visibility = if (subtitleView.text.isNullOrBlank()) View.GONE else View.VISIBLE
        updateSaveButton()
    }

    /** Cập nhật trạng thái đã lưu của playlist đang mở trong "Xem sau". */
    fun setSaved(value: Boolean) {
        isSaved = value
        updateSaveButton()
    }

    private fun updateSaveButton() {
        saveButton.setText(
            if (isSaved) R.string.playlist_saved_short else R.string.playlist_save_short
        )
        saveButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (isSaved) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border,
            0,
            0,
            0
        )
        saveButton.contentDescription = context.getString(
            if (isSaved) R.string.playlist_remove_saved else R.string.playlist_save
        )
        // Mix do YouTube tự sinh vẫn có thể lưu: người dùng không phân biệt
        // được "playlist thường" và "mix" khi chỉ nhìn tiêu đề.
        saveButton.visibility = if (playlist == null) View.GONE else View.VISIBLE
    }

    fun setVideos(values: List<VideoItem>) {
        videos = values
        adapter.submit(values)
        render()
    }

    fun setLoading(value: Boolean) {
        isLoading = value
        render()
    }

    fun setLoadingMore(value: Boolean) {
        isLoadingMore = value
        render()
    }

    fun setHasMore(value: Boolean) {
        hasMore = value
        render()
    }

    fun setError(value: AppError?) {
        error = value
        render()
    }

    fun setLibrary(
        favorites: Set<String>,
        watchLater: Set<String>,
        progress: Map<String, Float>
    ) {
        favoriteIds = favorites
        watchLaterIds = watchLater
        watchProgress = progress
        adapter.notifyDataSetChanged()
    }

    private fun render() {
        loading.visibility = if (isLoading) View.VISIBLE else View.GONE
        moreProgress.visibility = if (isLoadingMore && !isLoading) View.VISIBLE else View.GONE

        val showError = error != null && videos.isEmpty() && !isLoading
        errorBlock.visibility = if (showError) View.VISIBLE else View.GONE

        val showEmpty = !isLoading && error == null && videos.isEmpty()
        emptyView.visibility = if (showEmpty) View.VISIBLE else View.GONE

        list.visibility = if (isLoading || showError || showEmpty) View.GONE else View.VISIBLE

        val currentError = error
        if (currentError != null) {
            val presentation = currentError.presentation(context)
            errorTitle.text = presentation.title
            errorMessage.text = presentation.message
            retryButton.text = presentation.retryLabel
        }
    }

    private inner class VideoAdapter : RecyclerView.Adapter<VideoHolder>() {

        private var rows: List<VideoItem> = emptyList()

        fun submit(newRows: List<VideoItem>) {
            val oldRows = rows
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldRows.size
                override fun getNewListSize(): Int = newRows.size

                override fun areItemsTheSame(oldPosition: Int, newPosition: Int): Boolean =
                    oldRows[oldPosition].id == newRows[newPosition].id

                override fun areContentsTheSame(oldPosition: Int, newPosition: Int): Boolean =
                    oldRows[oldPosition] == newRows[newPosition]
            })
            rows = newRows
            diff.dispatchUpdatesTo(this)
        }

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoHolder {
            val card = CompactVideoCardView(parent.context)
            card.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            return VideoHolder(card)
        }

        override fun onBindViewHolder(holder: VideoHolder, position: Int) {
            val video = rows[position]
            holder.card.bind(
                video = video,
                isFavorite = favoriteIds.contains(video.id),
                isWatchLater = watchLaterIds.contains(video.id),
                progressFraction = watchProgress[video.id] ?: 0f,
                showSnippet = false,
                actions = actions
            )
        }
    }

    private class VideoHolder(val card: CompactVideoCardView) : RecyclerView.ViewHolder(card)
}

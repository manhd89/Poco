package com.luxysiv.puretube.ui.views

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.luxysiv.puretube.R
import com.luxysiv.puretube.data.model.ChannelPage
import com.luxysiv.puretube.data.model.VideoItem
import com.luxysiv.puretube.error.AppError
import com.luxysiv.puretube.ui.adaptive.videoGridColumnsForWidth
import com.luxysiv.puretube.util.presentation

class ChannelNativeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val backButton: ImageButton
    private val headerCard: View
    private val avatar: ChannelAvatarView
    private val nameView: TextView
    private val subsView: TextView
    private val list: RecyclerView
    private val loading: ProgressBar
    private val moreProgress: ProgressBar
    private val errorBlock: View
    private val errorTitle: TextView
    private val errorMessage: TextView
    private val retryButton: TextView
    private val emptyView: TextView

    private val adapter = VideoAdapter()
    private var columns = 1

    private var videos: List<VideoItem> = emptyList()
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

    private val actions = videoCardActions(
        onClick = { onVideoClick?.invoke(it) },
        onToggleFavorite = { onToggleFavorite?.invoke(it) },
        onToggleWatchLater = { onToggleWatchLater?.invoke(it) },
        onPlayNext = { onPlayNext?.invoke(it) },
        onAddToQueue = { onAddToQueue?.invoke(it) }
    )

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_channel_page, this, true)

        backButton = findViewById(R.id.channel_back)
        headerCard = findViewById(R.id.channel_page_header_card)
        avatar = findViewById(R.id.channel_page_avatar)
        nameView = findViewById(R.id.channel_page_name)
        subsView = findViewById(R.id.channel_page_subs)
        list = findViewById(R.id.channel_page_list)
        loading = findViewById(R.id.channel_page_loading)
        moreProgress = findViewById(R.id.channel_page_more)
        errorBlock = findViewById(R.id.channel_page_error)
        errorTitle = findViewById(R.id.channel_page_error_title)
        errorMessage = findViewById(R.id.channel_page_error_message)
        retryButton = findViewById(R.id.channel_page_retry)
        emptyView = findViewById(R.id.channel_page_empty)

        list.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: Rect,
                view: View,
                parent: RecyclerView,
                state: RecyclerView.State
            ) {
                if (columns > 1) {
                    val half = context.dp(8f)
                    outRect.set(half, half, half, half)
                } else {
                    val verticalHalf = context.dp(5f)
                    outRect.set(0, verticalHalf, 0, verticalHalf)
                }
            }
        })
        updateLayoutMetrics()
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
    }

    fun updateLayoutMetrics() {
        val widthDp = resources.configuration.screenWidthDp
        val compact = widthDp < 600
        val newColumns = videoGridColumnsForWidth(widthDp)
        val edge = context.dp(if (compact) 16f else 24f)

        (headerCard.layoutParams as? MarginLayoutParams)?.let { lp ->
            if (lp.marginStart != edge || lp.marginEnd != edge) {
                lp.marginStart = edge
                lp.marginEnd = edge
                headerCard.layoutParams = lp
            }
        }

        if (newColumns != columns || list.layoutManager == null) {
            columns = newColumns
            list.layoutManager = GridLayoutManager(context, columns)
            list.adapter = adapter
        }

        if (columns > 1) {
            val gridEdge = edge - context.dp(8f)
            list.setPadding(gridEdge, context.dp(4f), gridEdge, context.dp(24f))
        } else {
            list.setPadding(edge, context.dp(4f), edge, context.dp(24f))
        }
    }

    fun setChannel(page: ChannelPage?, fallbackTitle: String = "") {
        val title = page?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle
        nameView.text = title.ifBlank { context.getString(R.string.channel_page_title) }
        val avatarUrl = page?.avatarUrl.orEmpty().ifBlank {
            videos.firstOrNull { it.channelAvatarUrl.isNotBlank() }?.channelAvatarUrl.orEmpty()
        }
        avatar.bind(title.ifBlank { "?" }, avatarUrl)
        val subs = page?.subscriberCount.orEmpty()
        subsView.text = subs
        subsView.visibility = if (subs.isBlank()) View.GONE else View.VISIBLE
    }

    fun setVideos(values: List<VideoItem>) {
        videos = values
        adapter.submit(values)
        render()
    }

    fun setLoading(value: Boolean) { isLoading = value; render() }
    fun setLoadingMore(value: Boolean) { isLoadingMore = value; render() }
    fun setHasMore(value: Boolean) { hasMore = value; render() }
    fun setError(value: AppError?) { error = value; render() }

    fun setLibrary(favorites: Set<String>, watchLater: Set<String>, progress: Map<String, Float>) {
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

    private inner class VideoAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
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

        override fun getItemViewType(position: Int): Int =
            if (columns > 1) TYPE_GRID else TYPE_COMPACT

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val params = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            return if (viewType == TYPE_GRID) {
                val card = VideoCardView(parent.context).apply { layoutParams = params }
                GridVideoHolder(card)
            } else {
                val card = CompactVideoCardView(parent.context).apply { layoutParams = params }
                CompactVideoHolder(card)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val video = rows[position]
            val fav = favoriteIds.contains(video.id)
            val wl = watchLaterIds.contains(video.id)
            val progress = watchProgress[video.id] ?: 0f
            when (holder) {
                is GridVideoHolder -> holder.card.bind(
                    video = video,
                    isFavorite = fav,
                    isWatchLater = wl,
                    progressFraction = progress,
                    actions = actions
                )
                is CompactVideoHolder -> holder.card.bind(
                    video = video,
                    isFavorite = fav,
                    isWatchLater = wl,
                    progressFraction = progress,
                    showSnippet = false,
                    actions = actions
                )
            }
        }
    }

    private class GridVideoHolder(val card: VideoCardView) : RecyclerView.ViewHolder(card)
    private class CompactVideoHolder(val card: CompactVideoCardView) : RecyclerView.ViewHolder(card)

    private companion object {
        const val TYPE_COMPACT = 0
        const val TYPE_GRID = 1
    }
}

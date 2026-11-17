package com.luxysiv.puretube.ui.views

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.luxysiv.puretube.R
import com.luxysiv.puretube.data.model.SubscribedChannel
import com.luxysiv.puretube.data.model.VideoItem
import com.luxysiv.puretube.error.AppError
import com.luxysiv.puretube.ui.adaptive.videoGridColumnsForWidth

/**
 * Nội dung trang Đăng ký native.
 *
 * Dữ liệu là các kênh người dùng đăng ký cục bộ cùng feed công khai đã tải về.
 * Dải chip lọc theo kênh và lưới video thích ứng số cột giống bản Compose cũ;
 * kéo xuống để tải feed mới, nút "..." mở danh sách kênh để hủy đăng ký.
 */
class SubscriptionsNativeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val channelList: RecyclerView
    private val grid: RecyclerView
    private val refresh: SwipeRefreshLayout
    private val emptyView: View
    private val emptyTitle: TextView
    private val emptyMessage: TextView
    private val emptyAction: TextView

    private val channelAdapter = ChannelAdapter()
    private val gridAdapter = VideoGridAdapter()

    private var channels: List<SubscribedChannel> = emptyList()
    private var videos: List<VideoItem> = emptyList()
    private var favoriteIds: Set<String> = emptySet()
    private var watchLaterIds: Set<String> = emptySet()
    private var watchProgress: Map<String, Float> = emptyMap()
    private var selectedChannelId: String? = null
    private var isRefreshing = false
    private var isLoadingMore = false
    private var hasMore = false
    private var loadMoreError: AppError? = null

    private var columns = 1
    private var horizontalPadding = 16

    private var actions: VideoCardActions? = null

    private var manageDialog: BottomSheetDialog? = null
    private var manageContainer: LinearLayout? = null

    var onVideoClick: ((VideoItem) -> Unit)? = null
    var onToggleFavorite: ((VideoItem) -> Unit)? = null
    var onToggleWatchLater: ((VideoItem) -> Unit)? = null
    var onSearchClick: (() -> Unit)? = null
    var onExplore: (() -> Unit)? = null
    var onRefresh: (() -> Unit)? = null
    var onUnsubscribe: ((String) -> Unit)? = null
    /** Mở trang kênh trong ứng dụng. */
    var onOpenChannel: ((SubscribedChannel) -> Unit)? = null
    var onLoadMore: (() -> Unit)? = null
    var onRetryLoadMore: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_subscriptions, this, true)

        channelList = findViewById(R.id.subscriptions_channels)
        grid = findViewById(R.id.subscriptions_grid)
        refresh = findViewById(R.id.subscriptions_refresh)
        emptyView = findViewById(R.id.subscriptions_empty)
        emptyTitle = emptyView.findViewById(R.id.subscriptions_empty_title)
        emptyMessage = emptyView.findViewById(R.id.subscriptions_empty_message)
        emptyAction = emptyView.findViewById(R.id.subscriptions_explore)

        channelList.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        channelList.adapter = channelAdapter
        grid.adapter = gridAdapter

        // Cuộn gần cuối lưới thì nối thêm trang cũ của các kênh. Khi đang lọc
        // một kênh, ẩn footer vì phân trang hiện gộp chung mọi kênh.
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || selectedChannelId != null) return
                if (!hasMore || isRefreshing || isLoadingMore || loadMoreError != null) return
                val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val last = manager.findLastVisibleItemPosition()
                if (last >= (recyclerView.adapter?.itemCount ?: 0) - 3) {
                    onLoadMore?.invoke()
                }
            }
        })

        refresh.setColorSchemeResources(R.color.pure_primary)
        refresh.setOnRefreshListener { onRefresh?.invoke() }

        findViewById<ImageButton>(R.id.subscriptions_search).setOnClickListener {
            onSearchClick?.invoke()
        }
        findViewById<ImageButton>(R.id.subscriptions_manage).setOnClickListener {
            showManageSheet()
        }
        emptyAction.setOnClickListener {
            if (channels.isEmpty()) onExplore?.invoke() else onRefresh?.invoke()
        }

        updateLayoutMetrics()
    }

    // ------------------------------------------------------------------
    // Trạng thái từ ViewModel
    // ------------------------------------------------------------------

    fun setContent(
        channels: List<SubscribedChannel>,
        videos: List<VideoItem>,
        favoriteIds: Set<String>,
        watchLaterIds: Set<String>,
        watchProgress: Map<String, Float>,
        isRefreshing: Boolean,
        isLoadingMore: Boolean,
        hasMore: Boolean,
        loadMoreError: AppError?
    ) {
        this.channels = channels
        this.videos = videos
        this.favoriteIds = favoriteIds
        this.watchLaterIds = watchLaterIds
        this.watchProgress = watchProgress
        this.isRefreshing = isRefreshing
        this.isLoadingMore = isLoadingMore
        this.hasMore = hasMore
        this.loadMoreError = loadMoreError
        render()
        // Sheet đang mở vẫn phản ánh đúng sau khi hủy đăng ký / khôi phục.
        if (manageDialog?.isShowing == true) rebuildManageRows()
    }

    fun updateLayoutMetrics() {
        val widthDp = resources.configuration.screenWidthDp
        val newColumns = videoGridColumnsForWidth(widthDp)
        val compact = widthDp < 600
        val newPadding = context.dp(if (compact) 16f else 24f)

        val columnsChanged = newColumns != columns
        columns = newColumns
        horizontalPadding = newPadding

        if (columnsChanged || grid.layoutManager == null) {
            val manager = GridLayoutManager(context, columns)
            // Footer tải thêm chiếm toàn bộ số cột thay vì một ô video.
            manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (gridAdapter.isFullSpan(position)) columns else 1
            }
            grid.layoutManager = manager
            if (grid.itemDecorationCount == 0) {
                grid.addItemDecoration(SpacingItemDecoration(context.dp(16f)))
            }
        }
        grid.setPadding(
            newPadding - context.dp(8f),
            0,
            newPadding - context.dp(8f),
            context.dp(32f)
        )
        channelList.setPadding(newPadding, context.dp(8f), newPadding, context.dp(8f))

        render()
    }

    private fun render() {
        actions = videoCardActions(
            onClick = { onVideoClick?.invoke(it) },
            onToggleFavorite = onToggleFavorite,
            onToggleWatchLater = onToggleWatchLater
        )

        val hasChannels = channels.isNotEmpty()
        val hasVideos = videos.isNotEmpty()

        if (!hasVideos) {
            emptyView.visibility = View.VISIBLE
            channelList.visibility = View.GONE
            refresh.visibility = View.GONE
            refresh.isRefreshing = false
            if (hasChannels) {
                emptyTitle.setText(R.string.subscriptions_no_videos_title)
                emptyMessage.setText(R.string.subscriptions_no_videos_message)
                emptyAction.setText(R.string.subscriptions_refresh)
            } else {
                emptyTitle.setText(R.string.subscriptions_empty_title)
                emptyMessage.setText(R.string.subscriptions_empty_message)
                emptyAction.setText(R.string.subscriptions_explore)
            }
            channelAdapter.submit(emptyList(), null, emptyMap())
            gridAdapter.submit(
                videos = emptyList(),
                loadingMore = false,
                hasMore = false,
                error = null
            )
            return
        }

        emptyView.visibility = View.GONE
        channelList.visibility = View.VISIBLE
        refresh.visibility = View.VISIBLE
        // Chỉ hiện spinner của SwipeRefreshLayout khi thực sự có dữ liệu cũ
        // đang hiển thị; lần tải đầu tiên để trạng thái trống tự nhiên.
        refresh.isRefreshing = isRefreshing

        if (selectedChannelId != null && channels.none { it.channelId == selectedChannelId }) {
            selectedChannelId = null
        }
        val fallbackAvatars = channelAvatarsByTitle(videos)
        channelAdapter.submit(channels, selectedChannelId, fallbackAvatars)

        val filtered = selectedChannelId?.let { channelId ->
            videos.filter { it.channelId == channelId }
        } ?: videos
        val paginate = selectedChannelId == null && hasMore
        gridAdapter.submit(
            videos = filtered,
            loadingMore = isLoadingMore,
            hasMore = paginate,
            error = loadMoreError.takeIf { paginate }
        )
    }

    // ------------------------------------------------------------------
    // Adapter kênh (chip lọc)
    // ------------------------------------------------------------------

    private inner class ChannelAdapter : RecyclerView.Adapter<ChannelHolder>() {
        private var items: List<SubscribedChannel> = emptyList()
        private var selected: String? = null
        private var fallbackAvatars: Map<String, String> = emptyMap()

        fun submit(
            newItems: List<SubscribedChannel>,
            newSelected: String?,
            newFallbackAvatars: Map<String, String>
        ) {
            val oldItems = items
            val oldSelected = selected
            val oldAvatars = fallbackAvatars
            val oldLabels = listOf(null) + oldItems
            val newLabels = listOf(null) + newItems
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldLabels.size
                override fun getNewListSize(): Int = newLabels.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    oldLabels[oldItemPosition]?.channelId == newLabels[newItemPosition]?.channelId

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val label = oldLabels[oldItemPosition]
                    val newLabel = newLabels[newItemPosition]
                    val oldAvatar = label?.let { avatar(it, oldAvatars) }
                    val newAvatar = newLabel?.let { avatar(it, newFallbackAvatars) }
                    return label?.channelId == newLabel?.channelId &&
                        label?.title == newLabel?.title &&
                        (label?.channelId == oldSelected) ==
                        (newLabel?.channelId == newSelected) &&
                        oldAvatar == newAvatar
                }
            })
            items = newItems
            selected = newSelected
            fallbackAvatars = newFallbackAvatars
            diff.dispatchUpdatesTo(this)
        }

        private fun avatar(channel: SubscribedChannel, fallbacks: Map<String, String>): String =
            channel.avatarUrl.ifBlank { fallbacks[channel.title].orEmpty() }

        override fun getItemCount(): Int = items.size + 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelHolder {
            val chip = LayoutInflater.from(parent.context)
                .inflate(R.layout.view_channel_chip, parent, false) as ViewGroup
            return ChannelHolder(chip)
        }

        override fun onBindViewHolder(holder: ChannelHolder, position: Int) {
            val channel = if (position == 0) null else items[position - 1]
            val isSelected = if (position == 0) selected == null else selected == channel?.channelId
            val label = channel?.title ?: context.getString(R.string.subscriptions_all)

            val chip = holder.root
            chip.setBackgroundResource(
                if (isSelected) R.drawable.bg_chip_selected else R.drawable.bg_chip
            )
            holder.label.text = label
            holder.label.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (isSelected) R.color.pure_surface else R.color.pure_on_surface
                )
            )
            holder.label.applyBold(isSelected)
            val startPadding = context.dp(if (position == 0) 12f else 6f)
            chip.setPaddingRelative(
                startPadding,
                chip.paddingTop,
                chip.paddingEnd,
                chip.paddingBottom
            )
            if (channel == null) {
                holder.avatar.visibility = View.GONE
                (holder.label.layoutParams as ViewGroup.MarginLayoutParams).marginStart = 0
            } else {
                holder.avatar.visibility = View.VISIBLE
                holder.avatar.bind(channel.title, avatar(channel, fallbackAvatars))
                (holder.label.layoutParams as ViewGroup.MarginLayoutParams).marginStart =
                    context.dp(8f)
            }
            val params = chip.layoutParams as ViewGroup.MarginLayoutParams
            params.marginEnd = context.dp(8f)
            chip.layoutParams = params
            chip.setOnClickListener {
                selectedChannelId = when {
                    channel == null -> null
                    selectedChannelId == channel.channelId -> null
                    else -> channel.channelId
                }
                render()
            }
        }
    }

    private class ChannelHolder(val root: ViewGroup) : RecyclerView.ViewHolder(root) {
        val avatar: ChannelAvatarView = root.findViewById(R.id.channel_chip_avatar)
        val label: TextView = root.findViewById(R.id.channel_chip_label)
    }

    // ------------------------------------------------------------------
    // Sheet quản lý kênh
    // ------------------------------------------------------------------

    private fun showManageSheet() {
        val dialog = BottomSheetDialog(context)
        val scroll = ScrollView(context)
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(context.dp(24f), context.dp(16f), context.dp(24f), context.dp(24f))
        }
        scroll.addView(
            container,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        scroll.setBackgroundResource(R.drawable.bg_player_sheet)

        val title = TextView(context).apply {
            text = context.getString(R.string.subscriptions_manage_title)
            setTextColor(ContextCompat.getColor(context, R.color.pure_on_surface))
            textSize = 18f
            typeface = Typeface.create(
                ResourcesCompat.getFont(context, R.font.plus_jakarta_sans) ?: Typeface.DEFAULT,
                Typeface.BOLD
            )
        }
        container.addView(title)

        val rows = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dp(8f) }
        }
        container.addView(rows)

        manageContainer = rows
        manageDialog = dialog
        rebuildManageRows()
        dialog.setContentView(scroll)
        dialog.setOnDismissListener {
            if (manageDialog === dialog) {
                manageDialog = null
                manageContainer = null
            }
        }
        dialog.show()
    }

    /** Dựng lại danh sách kênh trong sheet; gọi cả khi dữ liệu đổi lúc đang mở. */
    private fun rebuildManageRows() {
        val container = manageContainer ?: return
        container.removeAllViews()

        if (channels.isEmpty()) {
            container.addView(TextView(context).apply {
                text = context.getString(R.string.subscriptions_empty_title)
                setTextColor(ContextCompat.getColor(context, R.color.pure_on_surface_variant))
                textSize = 14f
            })
            return
        }

        val fallbackAvatars = channelAvatarsByTitle(videos)
        for (channel in channels) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, context.dp(8f), 0, context.dp(8f))
            }

            val avatar = ChannelAvatarView(context).apply {
                layoutParams = LinearLayout.LayoutParams(context.dp(40f), context.dp(40f))
                bind(
                    channel.title,
                    channel.avatarUrl.ifBlank { fallbackAvatars[channel.title].orEmpty() }
                )
            }
            row.addView(avatar)

            val label = TextView(context).apply {
                text = channel.title
                setTextColor(ContextCompat.getColor(context, R.color.pure_on_surface))
                textSize = 16f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply { marginStart = context.dp(12f) }
            }
            row.addView(label)

            row.isClickable = true
            row.isFocusable = true
            row.setOnClickListener {
                com.luxysiv.puretube.ui.channel.ChannelActivity.start(
                    context = context,
                    channelId = channel.channelId,
                    channelTitle = channel.title
                )
            }

            val unsubscribe = TextView(context).apply {
                text = context.getString(R.string.subscriptions_unsubscribe)
                setTextColor(ContextCompat.getColor(context, R.color.pure_primary))
                textSize = 13f
                applyBold(true)
                minHeight = context.dp(48f)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(12f), 0, context.dp(8f), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener { onUnsubscribe?.invoke(channel.channelId) }
            }
            row.addView(unsubscribe)

            container.addView(row)
        }
    }

    // ------------------------------------------------------------------
    // Adapter lưới video
    // ------------------------------------------------------------------

    private inner class VideoGridAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<SubscriptionGridRow> = emptyList()

        fun submit(
            videos: List<VideoItem>,
            loadingMore: Boolean,
            hasMore: Boolean,
            error: AppError?
        ) {
            val newRows = ArrayList<SubscriptionGridRow>(videos.size + 1)
            videos.forEach { newRows += SubscriptionGridRow.Video(it) }
            if (hasMore) {
                when {
                    error != null -> newRows += SubscriptionGridRow.LoadMoreError(error)
                    loadingMore -> newRows += SubscriptionGridRow.LoadingMore
                }
            }

            val oldRows = rows
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldRows.size
                override fun getNewListSize(): Int = newRows.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val old = oldRows[oldItemPosition]
                    val new = newRows[newItemPosition]
                    return when (old) {
                        is SubscriptionGridRow.Video ->
                            new is SubscriptionGridRow.Video && old.video.id == new.video.id

                        SubscriptionGridRow.LoadingMore,
                        is SubscriptionGridRow.LoadMoreError ->
                            new is SubscriptionGridRow.LoadingMore ||
                                new is SubscriptionGridRow.LoadMoreError
                    }
                }

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    oldRows[oldItemPosition] == newRows[newItemPosition]
            })
            rows = newRows
            diff.dispatchUpdatesTo(this)
        }

        fun isFullSpan(position: Int): Boolean =
            rows.getOrNull(position) !is SubscriptionGridRow.Video

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is SubscriptionGridRow.Video -> TYPE_VIDEO
            SubscriptionGridRow.LoadingMore,
            is SubscriptionGridRow.LoadMoreError -> TYPE_LOAD_MORE
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int
        ): RecyclerView.ViewHolder = when (viewType) {
            TYPE_VIDEO -> {
                val card = VideoCardView(parent.context)
                card.layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                VideoHolder(card)
            }

            else -> {
                val footer = LoadMoreView(parent.context)
                footer.layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                LoadMoreHolder(footer)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is SubscriptionGridRow.Video -> {
                    val currentActions = actions ?: return
                    val video = row.video
                    (holder as VideoHolder).card.bind(
                        video = video,
                        isFavorite = favoriteIds.contains(video.id),
                        isWatchLater = watchLaterIds.contains(video.id),
                        progressFraction = watchProgress[video.id] ?: 0f,
                        actions = currentActions
                    )
                }

                SubscriptionGridRow.LoadingMore -> {
                    (holder as LoadMoreHolder).view.bind(null) {
                        onRetryLoadMore?.invoke()
                    }
                }

                is SubscriptionGridRow.LoadMoreError -> {
                    (holder as LoadMoreHolder).view.bind(row.error) {
                        onRetryLoadMore?.invoke()
                    }
                }
            }
        }
    }

    private class VideoHolder(val card: VideoCardView) : RecyclerView.ViewHolder(card)

    private class LoadMoreHolder(val view: LoadMoreView) : RecyclerView.ViewHolder(view)

    private companion object {
        const val TYPE_VIDEO = 0
        const val TYPE_LOAD_MORE = 1
    }
}

/** Dòng trong lưới Đăng ký: video hoặc footer tải thêm. */
private sealed interface SubscriptionGridRow {
    data class Video(val video: VideoItem) : SubscriptionGridRow
    data object LoadingMore : SubscriptionGridRow
    data class LoadMoreError(val error: AppError) : SubscriptionGridRow
}

/**
 * Avatar đầu tiên không rỗng của từng kênh trong feed.
 *
 * Kênh có thể được lưu từ một video thiếu avatar; dùng feed đã tải làm nguồn
 * dự phòng để chip/sheet vẫn hiện ảnh thật thay vì chữ cái đầu.
 */
internal fun channelAvatarsByTitle(videos: List<VideoItem>): Map<String, String> {
    val avatars = LinkedHashMap<String, String>()
    for (video in videos) {
        val title = video.channelTitle
        if (title.isBlank() || video.channelAvatarUrl.isBlank()) continue
        avatars.putIfAbsent(title, video.channelAvatarUrl)
    }
    return avatars
}

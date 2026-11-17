package com.luxysiv.puretube.ui.views

import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.luxysiv.puretube.R
import com.luxysiv.puretube.data.database.PlaylistHistoryEntity
import com.luxysiv.puretube.data.model.HomeMix
import com.luxysiv.puretube.data.model.PlaylistItem
import com.luxysiv.puretube.data.model.VideoItem
import com.luxysiv.puretube.error.AppError
import com.luxysiv.puretube.util.presentation

/** Một dòng trong lưới Trang chủ. Dữ liệu thuần để so sánh/rebuild dễ dàng. */
sealed interface HomeRow {
    data class Video(val video: VideoItem) : HomeRow
    data object Skeleton : HomeRow
    data class Offline(val error: AppError) : HomeRow
    data class Header(val title: String) : HomeRow
    data class Continue(val videos: List<VideoItem>) : HomeRow
    data class Playlists(val playlists: List<PlaylistHistoryEntity>) : HomeRow
    data class Mixes(val mixes: List<HomeMix>) : HomeRow
    data class Empty(val kind: EmptyKind, val error: AppError? = null) : HomeRow
    data object LoadingMore : HomeRow
    data class LoadMoreError(val error: AppError) : HomeRow
}

/** Các trạng thái rỗng khác nhau của Trang chủ. */
enum class EmptyKind {
    NO_HISTORY,
    NO_SUGGESTIONS,
    OFFLINE_SUGGESTIONS,
    EMPTY_FEED,
    EMPTY_FEED_OFFLINE
}

/**
 * Adapter lưới Trang chủ cho cả hai tab.
 *
 * Video và skeleton chiếm một cột; banner offline, tiêu đề mục, kệ ngang và
 * trạng thái rỗng chiếm toàn bộ số cột (xem [isFullSpan]).
 */
class HomeAdapter(
    private val cardWidthPx: Int,
    private val mixCardWidthPx: Int
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<HomeRow> = emptyList()
    private var favoriteIds: Set<String> = emptySet()
    private var watchLaterIds: Set<String> = emptySet()
    private var savedPlaylistIds: Set<String> = emptySet()
    private var watchProgress: Map<String, Float> = emptyMap()
    private var actions: VideoCardActions? = null

    private var onRetry: () -> Unit = {}
    private var onRetryLoadMore: () -> Unit = {}
    private var onExploreTrending: () -> Unit = {}
    private var onMixClick: (HomeMix) -> Unit = {}
    private var onPlaylistClick: (PlaylistHistoryEntity) -> Unit = {}
    private var onToggleSavePlaylist: ((PlaylistItem) -> Unit)? = null

    fun submit(
        rows: List<HomeRow>,
        favoriteIds: Set<String>,
        watchLaterIds: Set<String>,
        savedPlaylistIds: Set<String>,
        watchProgress: Map<String, Float>,
        actions: VideoCardActions,
        onRetry: () -> Unit,
        onRetryLoadMore: () -> Unit,
        onExploreTrending: () -> Unit,
        onMixClick: (HomeMix) -> Unit,
        onPlaylistClick: (PlaylistHistoryEntity) -> Unit,
        onToggleSavePlaylist: ((PlaylistItem) -> Unit)?
    ) {
        // Danh sách được dựng lại và submit mỗi lần một StateFlow phát lại —
        // kể cả khi trang chỉ resume sau khi đóng trình phát. Bỏ qua
        // notifyDataSetChanged() khi dữ liệu không đổi để RecyclerView không
        // rebind toàn bộ thẻ (nguồn gốc của cú nháy đen thumbnail).
        val oldRows = this.rows
        val sameRows = rows == oldRows
        val sameState = favoriteIds == this.favoriteIds &&
            watchLaterIds == this.watchLaterIds &&
            savedPlaylistIds == this.savedPlaylistIds &&
            watchProgress == this.watchProgress &&
            actions.currentVideoId == this.actions?.currentVideoId
        this.rows = rows
        this.favoriteIds = favoriteIds
        this.watchLaterIds = watchLaterIds
        this.savedPlaylistIds = savedPlaylistIds
        this.watchProgress = watchProgress
        this.actions = actions
        this.onRetry = onRetry
        this.onRetryLoadMore = onRetryLoadMore
        this.onExploreTrending = onExploreTrending
        this.onMixClick = onMixClick
        this.onPlaylistClick = onPlaylistClick
        this.onToggleSavePlaylist = onToggleSavePlaylist
        when {
            !sameRows -> {
                // DiffUtil giữ nguyên ViewHolder/thẻ không đổi: chỉ chèn/xoá/di
                // chuyển đúng phần thay đổi nên thumbnail không bị nháy.
                DiffUtil.calculateDiff(HomeRowDiff(oldRows, rows))
                    .dispatchUpdatesTo(this)
            }

            // Rows không đổi, chỉ state hiển thị (yêu thích/xem sau/tiến độ/đang
            // phát): cập nhật tại chỗ thay vì rebind toàn bộ feed.
            !sameState -> notifyItemRangeChanged(0, itemCount)
        }
    }

    /** true nếu dòng tại [position] phải chiếm toàn bộ số cột của lưới. */
    fun isFullSpan(position: Int): Boolean {
        return when (rows.getOrNull(position)) {
            is HomeRow.Video, HomeRow.Skeleton, null -> false
            HomeRow.LoadingMore, is HomeRow.LoadMoreError -> true
            else -> true
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is HomeRow.Video -> TYPE_VIDEO
        HomeRow.Skeleton -> TYPE_SKELETON
        is HomeRow.Offline -> TYPE_OFFLINE
        is HomeRow.Header -> TYPE_HEADER
        is HomeRow.Continue -> TYPE_CONTINUE
        is HomeRow.Playlists -> TYPE_PLAYLISTS
        is HomeRow.Mixes -> TYPE_MIXES
        is HomeRow.Empty -> TYPE_EMPTY
        HomeRow.LoadingMore, is HomeRow.LoadMoreError -> TYPE_LOAD_MORE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_VIDEO -> {
                val card = VideoCardView(parent.context)
                card.layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                VideoHolder(card)
            }

            TYPE_SKELETON -> SkeletonHolder(
                inflater.inflate(R.layout.view_home_skeleton, parent, false)
            )

            TYPE_OFFLINE -> OfflineHolder(
                inflater.inflate(R.layout.view_home_offline, parent, false)
            )

            TYPE_HEADER -> HeaderHolder(parent.context)

            TYPE_CONTINUE -> ContinueHolder(ContinueWatchingShelfView(parent.context))

            TYPE_PLAYLISTS -> PlaylistsHolder(PlaylistShelfView(parent.context))

            TYPE_MIXES -> MixesHolder(HomeMixShelfView(parent.context))

            TYPE_EMPTY -> EmptyHolder(
                inflater.inflate(R.layout.view_home_empty, parent, false)
            )

            else -> {
                val footer = LoadMoreView(parent.context)
                footer.layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                LoadMoreHolder(footer)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is HomeRow.Video -> {
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

            HomeRow.Skeleton -> Unit

            is HomeRow.Offline -> {
                val current = onRetry
                (holder as OfflineHolder).bind(row.error)
                (holder as OfflineHolder).retry.setOnClickListener { current() }
            }

            is HomeRow.Header -> {
                (holder as HeaderHolder).title.text = row.title
            }

            is HomeRow.Continue -> {
                val currentActions = actions ?: return
                (holder as ContinueHolder).shelf.bind(
                    videos = row.videos,
                    cardWidth = cardWidthPx,
                    actions = currentActions,
                    favoriteIds = favoriteIds,
                    watchLaterIds = watchLaterIds,
                    watchProgress = watchProgress
                )
            }

            is HomeRow.Playlists -> {
                (holder as PlaylistsHolder).shelf.bind(
                    playlists = row.playlists,
                    cardWidth = cardWidthPx,
                    savedPlaylistIds = savedPlaylistIds,
                    onClick = onPlaylistClick,
                    onToggleSave = onToggleSavePlaylist
                )
            }

            is HomeRow.Mixes -> {
                val currentMixClick = onMixClick
                (holder as MixesHolder).shelf.bind(
                    mixes = row.mixes,
                    cardWidth = mixCardWidthPx,
                    onClick = currentMixClick
                )
            }

            is HomeRow.Empty -> {
                val currentRetry = onRetry
                val currentExplore = onExploreTrending
                (holder as EmptyHolder).bind(row.kind, row.error) {
                    if (row.kind == EmptyKind.NO_HISTORY) currentExplore() else currentRetry()
                }
            }

            HomeRow.LoadingMore -> {
                val currentRetry = onRetryLoadMore
                (holder as LoadMoreHolder).view.bind(null) { currentRetry() }
            }

            is HomeRow.LoadMoreError -> {
                val currentRetry = onRetryLoadMore
                (holder as LoadMoreHolder).view.bind(row.error) { currentRetry() }
            }
        }
    }

    private class VideoHolder(val card: VideoCardView) : RecyclerView.ViewHolder(card)

    private class SkeletonHolder(view: View) : RecyclerView.ViewHolder(view)

    private class OfflineHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.home_offline_title)
        val retry: View = view.findViewById(R.id.home_offline_retry)

        fun bind(error: AppError) {
            title.text = error.presentation(itemView.context).title
        }
    }

    private class HeaderHolder(context: android.content.Context) :
        RecyclerView.ViewHolder(TextView(context)) {
        val title: TextView = itemView as TextView

        init {
            title.setTextColor(ContextCompat.getColor(context, R.color.pure_on_surface))
            title.setTextSize(22f)
            title.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            val padding = (8 * context.resources.displayMetrics.density).toInt()
            title.setPadding(0, padding, 0, padding)
            title.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private class ContinueHolder(val shelf: ContinueWatchingShelfView) :
        RecyclerView.ViewHolder(shelf)

    private class PlaylistsHolder(val shelf: PlaylistShelfView) :
        RecyclerView.ViewHolder(shelf)

    private class MixesHolder(val shelf: HomeMixShelfView) : RecyclerView.ViewHolder(shelf)

    private class LoadMoreHolder(val view: LoadMoreView) : RecyclerView.ViewHolder(view)

    private class EmptyHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.home_empty_icon)
        private val title: TextView = view.findViewById(R.id.home_empty_title)
        private val message: TextView = view.findViewById(R.id.home_empty_message)
        private val action: MaterialButton = view.findViewById(R.id.home_empty_action)

        fun bind(kind: EmptyKind, error: AppError?, onAction: () -> Unit) {
            val context = itemView.context
            val presentation = error?.presentation(context)
            val data = when (kind) {
                EmptyKind.NO_HISTORY -> Triple(
                    R.drawable.ic_history,
                    context.getString(R.string.home_no_history_title),
                    context.getString(R.string.home_no_history_message)
                )

                EmptyKind.NO_SUGGESTIONS -> Triple(
                    R.drawable.ic_explore,
                    context.getString(R.string.home_no_suggestions_title),
                    context.getString(R.string.home_no_suggestions_message)
                )

                EmptyKind.OFFLINE_SUGGESTIONS -> Triple(
                    R.drawable.ic_cloud_off,
                    presentation?.title
                        ?: context.getString(R.string.home_no_suggestions_offline_title),
                    presentation?.message
                        ?: context.getString(R.string.home_no_suggestions_offline_message)
                )

                EmptyKind.EMPTY_FEED -> Triple(
                    R.drawable.ic_cloud_off,
                    context.getString(R.string.home_empty_feed_title),
                    context.getString(R.string.home_empty_feed_message)
                )

                EmptyKind.EMPTY_FEED_OFFLINE -> Triple(
                    R.drawable.ic_cloud_off,
                    presentation?.title
                        ?: context.getString(R.string.home_empty_feed_offline_title),
                    presentation?.message
                        ?: context.getString(R.string.home_empty_feed_offline_message)
                )
            }
            icon.setImageResource(data.first)
            title.text = data.second
            message.text = data.third
            action.text = if (kind == EmptyKind.NO_HISTORY) {
                context.getString(R.string.home_explore_trending)
            } else {
                presentation?.retryLabel ?: context.getString(R.string.action_retry)
            }
            action.setOnClickListener { onAction() }
        }
    }

    private companion object {
        const val TYPE_VIDEO = 0
        const val TYPE_SKELETON = 1
        const val TYPE_OFFLINE = 2
        const val TYPE_HEADER = 3
        const val TYPE_CONTINUE = 4
        const val TYPE_MIXES = 5
        const val TYPE_EMPTY = 6
        const val TYPE_PLAYLISTS = 7
        const val TYPE_LOAD_MORE = 8
    }
}

/** So sánh hai danh sách [HomeRow] để RecyclerView chỉ cập nhật phần thay đổi. */
private class HomeRowDiff(
    private val oldRows: List<HomeRow>,
    private val newRows: List<HomeRow>
) : DiffUtil.Callback() {

    override fun getOldListSize(): Int = oldRows.size

    override fun getNewListSize(): Int = newRows.size

    override fun areItemsTheSame(oldPosition: Int, newPosition: Int): Boolean {
        val old = oldRows[oldPosition]
        val new = newRows[newPosition]
        return when (old) {
            is HomeRow.Video -> (new as? HomeRow.Video)?.video?.id == old.video.id
            is HomeRow.Header -> (new as? HomeRow.Header)?.title == old.title
            is HomeRow.Empty -> (new as? HomeRow.Empty)?.kind == old.kind
            is HomeRow.Offline -> new is HomeRow.Offline
            is HomeRow.Continue -> new is HomeRow.Continue
            is HomeRow.Playlists -> new is HomeRow.Playlists
            is HomeRow.Mixes -> new is HomeRow.Mixes
            HomeRow.Skeleton -> new is HomeRow.Skeleton
            HomeRow.LoadingMore -> new is HomeRow.LoadingMore
            is HomeRow.LoadMoreError -> new is HomeRow.LoadMoreError
        }
    }

    override fun areContentsTheSame(oldPosition: Int, newPosition: Int): Boolean =
        oldRows[oldPosition] == newRows[newPosition]
}

/** Kệ ngang "Xem tiếp" dùng lại [VideoCardView]. */
class ContinueWatchingShelfView(context: android.content.Context) :
    android.widget.FrameLayout(context) {

    private val recycler = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        clipToPadding = false
        overScrollMode = View.OVER_SCROLL_NEVER
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        addItemDecoration(SpacingItemDecoration(context.dp(12f)))
    }

    init {
        addView(recycler)
    }

    fun bind(
        videos: List<VideoItem>,
        cardWidth: Int,
        actions: VideoCardActions,
        favoriteIds: Set<String>,
        watchLaterIds: Set<String>,
        watchProgress: Map<String, Float>
    ) {
        recycler.adapter = VideoShelfAdapter(
            videos,
            cardWidth,
            actions,
            favoriteIds,
            watchLaterIds,
            watchProgress
        )
    }
}

/** Kệ ngang "Danh sách phát đã xem" dùng lại [PlaylistCardView]. */
class PlaylistShelfView(context: android.content.Context) :
    android.widget.FrameLayout(context) {

    private val recycler = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        clipToPadding = false
        overScrollMode = View.OVER_SCROLL_NEVER
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        addItemDecoration(SpacingItemDecoration(context.dp(12f)))
    }

    init {
        addView(recycler)
    }

    fun bind(
        playlists: List<PlaylistHistoryEntity>,
        cardWidth: Int,
        savedPlaylistIds: Set<String>,
        onClick: (PlaylistHistoryEntity) -> Unit,
        onToggleSave: ((PlaylistItem) -> Unit)?
    ) {
        recycler.adapter = PlaylistShelfAdapter(
            playlists = playlists,
            cardWidth = cardWidth,
            savedPlaylistIds = savedPlaylistIds,
            onClick = onClick,
            onToggleSave = onToggleSave
        )
    }
}

/** Kệ ngang "Danh sách kết hợp" dùng lại [HomeMixCardView]. */
class HomeMixShelfView(context: android.content.Context) :
    android.widget.FrameLayout(context) {

    private val recycler = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        clipToPadding = false
        overScrollMode = View.OVER_SCROLL_NEVER
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        addItemDecoration(SpacingItemDecoration(context.dp(12f)))
    }

    init {
        addView(recycler)
    }

    fun bind(mixes: List<HomeMix>, cardWidth: Int, onClick: (HomeMix) -> Unit) {
        recycler.adapter = MixShelfAdapter(mixes, cardWidth, onClick)
    }
}

private class VideoShelfAdapter(
    private val videos: List<VideoItem>,
    private val cardWidth: Int,
    private val actions: VideoCardActions,
    private val favoriteIds: Set<String>,
    private val watchLaterIds: Set<String>,
    private val watchProgress: Map<String, Float>
) : RecyclerView.Adapter<VideoShelfAdapter.Holder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val card = VideoCardView(parent.context)
        card.layoutParams = RecyclerView.LayoutParams(
            cardWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return Holder(card)
    }

    override fun getItemCount(): Int = videos.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val video = videos[position]
        holder.card.bind(
            video = video,
            isFavorite = favoriteIds.contains(video.id),
            isWatchLater = watchLaterIds.contains(video.id),
            progressFraction = watchProgress[video.id] ?: 0f,
            actions = actions
        )
    }

    class Holder(val card: VideoCardView) : RecyclerView.ViewHolder(card)
}

private class PlaylistShelfAdapter(
    private val playlists: List<PlaylistHistoryEntity>,
    private val cardWidth: Int,
    private val savedPlaylistIds: Set<String>,
    private val onClick: (PlaylistHistoryEntity) -> Unit,
    private val onToggleSave: ((PlaylistItem) -> Unit)?
) : RecyclerView.Adapter<PlaylistShelfAdapter.Holder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val card = PlaylistCardView(parent.context)
        card.layoutParams = RecyclerView.LayoutParams(
            cardWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return Holder(card)
    }

    override fun getItemCount(): Int = playlists.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = playlists[position]
        val item = entry.toPlaylistItem()
        holder.card.bind(
            playlist = item,
            onClick = { onClick(entry) },
            isSaved = savedPlaylistIds.contains(item.id),
            onToggleSave = onToggleSave
        )
    }

    class Holder(val card: PlaylistCardView) : RecyclerView.ViewHolder(card)
}

private class MixShelfAdapter(
    private val mixes: List<HomeMix>,
    private val cardWidth: Int,
    private val onClick: (HomeMix) -> Unit
) : RecyclerView.Adapter<MixShelfAdapter.Holder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val card = HomeMixCardView(parent.context)
        card.layoutParams = RecyclerView.LayoutParams(
            cardWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return Holder(card)
    }

    override fun getItemCount(): Int = mixes.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val mix = mixes[position]
        holder.card.bind(mix) { onClick(mix) }
    }

    class Holder(val card: HomeMixCardView) : RecyclerView.ViewHolder(card)
}

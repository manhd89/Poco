package com.luxysiv.puretube.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.luxysiv.puretube.R
import com.luxysiv.puretube.data.database.PlaylistHistoryEntity
import com.luxysiv.puretube.data.database.VideoEntity
import com.luxysiv.puretube.data.model.HomeMix
import com.luxysiv.puretube.data.model.PlaylistItem
import com.luxysiv.puretube.data.model.VideoCategory
import com.luxysiv.puretube.data.model.VideoItem
import com.luxysiv.puretube.data.model.parseDurationSeconds
import com.luxysiv.puretube.error.AppError
import com.luxysiv.puretube.ui.adaptive.videoGridColumnsForWidth
import com.luxysiv.puretube.ui.components.watchProgressMap
import com.luxysiv.puretube.ui.home.HomeTab
import com.luxysiv.puretube.ui.home.HomeUiState

/**
 * Nội dung Trang chủ native.
 *
 * Giữ nguyên hai tab và toàn bộ hành vi của bản Compose: chip danh mục, lưới
 * thịnh hành, Xem tiếp, Danh sách kết hợp và Đề xuất cho bạn. Activity chỉ cần
 * gọi [render] với một ảnh chụp [HomeUiState] duy nhất.
 */
class HomeNativeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val tabTrending: LinearLayout
    private val tabForYou: LinearLayout
    private val tabTrendingLabel: TextView
    private val tabForYouLabel: TextView

    private val categoryList: RecyclerView
    private val trendingRefresh: SwipeRefreshLayout
    private val forYouRefresh: SwipeRefreshLayout
    private val trendingList: RecyclerView
    private val forYouList: RecyclerView

    private val categoryAdapter = CategoryAdapter()

    private var trendingAdapter: HomeAdapter? = null
    private var forYouAdapter: HomeAdapter? = null

    // Trạng thái do Activity đẩy vào.
    private var selectedTab: HomeTab = HomeTab.TRENDING
    private var selectedCategory: VideoCategory = VideoCategory.ALL
    private var trendingVideos: List<VideoItem> = emptyList()
    private var history: List<VideoEntity> = emptyList()
    private var playlistHistory: List<PlaylistHistoryEntity> = emptyList()
    private var isLoadingFeed = false
    private var isLoadingMoreFeed = false
    private var hasMoreFeed = false
    private var feedLoadMoreError: AppError? = null
    private var isLoadingPersonalization = false
    private var isHistoryLoaded = false
    private var feedError: AppError? = null
    private var favoriteIds: Set<String> = emptySet()
    private var watchLaterIds: Set<String> = emptySet()
    private var savedPlaylistIds: Set<String> = emptySet()
    private var homeMixes: List<HomeMix> = emptyList()
    private var forYouVideos: List<VideoItem> = emptyList()
    private var isLoadingMoreForYou = false
    private var hasMoreForYou = false
    private var forYouLoadMoreError: AppError? = null
    private var currentVideoId: String? = null

    private var columns = 1
    private var horizontalPadding = 16
    private var continueCardWidth = 236
    private var mixCardWidth = 264

    var onSearchClick: (() -> Unit)? = null
    var onYouClick: (() -> Unit)? = null
    var onSelectTab: ((HomeTab) -> Unit)? = null
    var onSelectCategory: ((VideoCategory) -> Unit)? = null
    var onVideoClick: ((VideoItem) -> Unit)? = null
    var onPlaylistClick: ((PlaylistHistoryEntity) -> Unit)? = null
    var onHomeMixClick: ((HomeMix) -> Unit)? = null
    var onToggleFavorite: ((VideoItem) -> Unit)? = null
    var onToggleWatchLater: ((VideoItem) -> Unit)? = null
    var onToggleSavePlaylist: ((PlaylistItem) -> Unit)? = null
    var onPlayNext: ((VideoItem) -> Unit)? = null
    var onAddToQueue: ((VideoItem) -> Unit)? = null
    var onRefresh: (() -> Unit)? = null
    var onLoadMore: (() -> Unit)? = null
    var onRetryLoadMore: (() -> Unit)? = null
    var onLoadMoreForYou: (() -> Unit)? = null
    var onRetryLoadMoreForYou: (() -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.view_home, this, true)

        tabTrending = findViewById(R.id.home_tab_trending)
        tabForYou = findViewById(R.id.home_tab_for_you)
        tabTrendingLabel = findViewById(R.id.home_tab_trending_label)
        tabForYouLabel = findViewById(R.id.home_tab_for_you_label)

        categoryList = findViewById(R.id.home_category_list)
        trendingRefresh = findViewById(R.id.home_trending_refresh)
        forYouRefresh = findViewById(R.id.home_for_you_refresh)
        trendingList = findViewById(R.id.home_trending_list)
        forYouList = findViewById(R.id.home_for_you_list)

        categoryList.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        categoryList.addItemDecoration(SpacingItemDecoration(context.dp(8f)))
        categoryList.adapter = categoryAdapter

        trendingRefresh.setColorSchemeColors(
            ContextCompat.getColor(context, R.color.pure_primary)
        )
        forYouRefresh.setColorSchemeColors(
            ContextCompat.getColor(context, R.color.pure_primary)
        )
        trendingRefresh.setOnRefreshListener { onRefresh?.invoke() }
        forYouRefresh.setOnRefreshListener { onRefresh?.invoke() }

        // Cuộn gần cuối tab Thịnh hành thì tải trang kế tiếp. Chỉ chạy khi
        // còn trang, không đang tải và footer lỗi chưa được người dùng thử lại.
        trendingList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || selectedTab != HomeTab.TRENDING) return
                if (!hasMoreFeed || isLoadingFeed || isLoadingMoreFeed) return
                if (feedLoadMoreError != null) return
                val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val last = manager.findLastVisibleItemPosition()
                if (last >= (recyclerView.adapter?.itemCount ?: 0) - 3) {
                    onLoadMore?.invoke()
                }
            }
        })

        // Tab Dành cho bạn: nối tiếp gợi ý/mix theo cùng cơ chế với Thịnh hành.
        // Dừng khi footer đang lỗi để người dùng chủ động bấm thử lại.
        forYouList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || selectedTab != HomeTab.FOR_YOU) return
                if (!hasMoreForYou || isLoadingPersonalization || isLoadingMoreForYou) return
                if (forYouLoadMoreError != null) return
                val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val last = manager.findLastVisibleItemPosition()
                if (last >= (recyclerView.adapter?.itemCount ?: 0) - 3) {
                    onLoadMoreForYou?.invoke()
                }
            }
        })

        tabTrending.setOnClickListener { onSelectTab?.invoke(HomeTab.TRENDING) }
        tabForYou.setOnClickListener { onSelectTab?.invoke(HomeTab.FOR_YOU) }

        findViewById<View>(R.id.home_search).setOnClickListener {
            onSearchClick?.invoke()
        }
        findViewById<TextView>(R.id.home_avatar).setOnClickListener {
            onYouClick?.invoke()
        }

        updateLayoutMetrics()
        applyTabUi()
    }

    // ------------------------------------------------------------------
    // Trạng thái từ ViewModel
    // ------------------------------------------------------------------

    /**
     * Render toàn bộ Trang chủ từ một ảnh chụp [HomeUiState].
     *
     * Một entry point duy nhất thay cho 6 hàm `set...` trước đây: mọi thay đổi
     * (tab, danh mục, feed, cá nhân hoá, thư viện, video đang phát) đều đi qua
     * đây nên không còn thứ tự render phụ thuộc vào thứ tự collector.
     */
    fun render(state: HomeUiState) {
        val previousTab = selectedTab
        val previousCategory = selectedCategory

        selectedTab = state.selectedTab
        selectedCategory = state.selectedCategory
        trendingVideos = state.trendingVideos
        history = state.history
        playlistHistory = state.playlistHistory
        isLoadingFeed = state.isLoadingFeed
        isLoadingMoreFeed = state.isLoadingMoreFeed
        hasMoreFeed = state.hasMoreFeed
        feedLoadMoreError = state.feedLoadMoreError
        isLoadingPersonalization = state.isLoadingPersonalization
        isHistoryLoaded = state.historyLoaded
        feedError = state.feedError
        favoriteIds = state.favoriteIds
        watchLaterIds = state.watchLaterIds
        savedPlaylistIds = state.savedPlaylistIds
        homeMixes = state.homeMixes
        forYouVideos = state.forYouVideos
        isLoadingMoreForYou = state.isLoadingMoreForYou
        hasMoreForYou = state.hasMoreForYou
        forYouLoadMoreError = state.forYouLoadMoreError
        currentVideoId = state.currentVideoId

        if (previousTab != selectedTab) applyTabUi()
        if (previousCategory != selectedCategory) {
            val oldIndex = VideoCategory.entries.indexOf(previousCategory)
            val newIndex = VideoCategory.entries.indexOf(selectedCategory)
            if (oldIndex >= 0) categoryAdapter.notifyItemChanged(oldIndex)
            if (newIndex >= 0) categoryAdapter.notifyItemChanged(newIndex)
        }
        renderRows()
    }

    // ------------------------------------------------------------------
    // Dựng dữ liệu lưới
    // ------------------------------------------------------------------

    private fun renderRows() {
        val actions = buildActions()
        val progress = watchProgressMap(history)

        trendingAdapter?.submit(
            rows = buildTrendingRows(),
            favoriteIds = favoriteIds,
            watchLaterIds = watchLaterIds,
            savedPlaylistIds = savedPlaylistIds,
            watchProgress = progress,
            actions = actions,
            onRetry = { onRefresh?.invoke() },
            onRetryLoadMore = { onRetryLoadMore?.invoke() },
            onExploreTrending = { onSelectTab?.invoke(HomeTab.TRENDING) },
            onMixClick = { onHomeMixClick?.invoke(it) },
            onPlaylistClick = { onPlaylistClick?.invoke(it) },
            onToggleSavePlaylist = onToggleSavePlaylist
        )
        forYouAdapter?.submit(
            rows = buildForYouRows(),
            favoriteIds = favoriteIds,
            watchLaterIds = watchLaterIds,
            savedPlaylistIds = savedPlaylistIds,
            watchProgress = progress,
            actions = actions,
            onRetry = { onRefresh?.invoke() },
            onRetryLoadMore = { onRetryLoadMoreForYou?.invoke() },
            onExploreTrending = { onSelectTab?.invoke(HomeTab.TRENDING) },
            onMixClick = { onHomeMixClick?.invoke(it) },
            onPlaylistClick = { onPlaylistClick?.invoke(it) },
            onToggleSavePlaylist = onToggleSavePlaylist
        )

        trendingRefresh.isRefreshing = isLoadingFeed && trendingVideos.isNotEmpty()
        forYouRefresh.isRefreshing = isLoadingPersonalization && history.isNotEmpty()
        maybeContinueForYou()
    }

    /**
     * Nếu tab Dành cho bạn đang mở mà danh sách chưa đủ lấp màn hình (tablet
     * nhiều cột), chủ động nối thêm trang thay vì chờ sự kiện cuộn — vốn không
     * xảy ra khi RecyclerView không cuộn được.
     */
    private fun maybeContinueForYou() {
        forYouList.post {
            if (selectedTab != HomeTab.FOR_YOU) return@post
            if (!hasMoreForYou || isLoadingPersonalization || isLoadingMoreForYou) return@post
            if (forYouLoadMoreError != null) return@post
            val adapter = forYouList.adapter ?: return@post
            val manager = forYouList.layoutManager as? LinearLayoutManager ?: return@post
            val last = manager.findLastVisibleItemPosition()
            if (last == RecyclerView.NO_POSITION || last >= adapter.itemCount - 3) {
                onLoadMoreForYou?.invoke()
            }
        }
    }

    private fun buildActions(): VideoCardActions = videoCardActions(
        onClick = { onVideoClick?.invoke(it) },
        onToggleFavorite = onToggleFavorite,
        onToggleWatchLater = onToggleWatchLater,
        currentVideoId = currentVideoId,
        onPlayNext = onPlayNext,
        onAddToQueue = onAddToQueue
    )

    private fun buildTrendingRows(): List<HomeRow> {
        val rows = ArrayList<HomeRow>()
        val error = feedError
        if (error != null && !isLoadingFeed) rows += HomeRow.Offline(error)

        when {
            isLoadingFeed && trendingVideos.isEmpty() -> {
                repeat(columns * 3) { rows += HomeRow.Skeleton }
            }

            trendingVideos.isEmpty() -> {
                rows += HomeRow.Empty(
                    kind = if (error == null) EmptyKind.EMPTY_FEED else EmptyKind.EMPTY_FEED_OFFLINE,
                    error = error
                )
            }

            else -> {
                trendingVideos.forEach { rows += HomeRow.Video(it) }
                val loadMoreError = feedLoadMoreError
                when {
                    loadMoreError != null -> rows += HomeRow.LoadMoreError(loadMoreError)
                    isLoadingMoreFeed -> rows += HomeRow.LoadingMore
                }
            }
        }
        return rows
    }

    private fun buildForYouRows(): List<HomeRow> {
        val rows = ArrayList<HomeRow>()
        val error = feedError
        if (error != null && !isLoadingPersonalization) rows += HomeRow.Offline(error)

        val continueWatching = continueWatching()
        val watchedPlaylists = playlistHistory.take(10)
        val hasHistory = history.isNotEmpty() || watchedPlaylists.isNotEmpty()

        if (!hasHistory) {
            if (!isHistoryLoaded || isLoadingPersonalization) {
                repeat(columns * 3) { rows += HomeRow.Skeleton }
            } else {
                rows += HomeRow.Empty(EmptyKind.NO_HISTORY)
            }
            return rows
        }

        if (continueWatching.isNotEmpty()) {
            rows += HomeRow.Header(context.getString(R.string.home_continue_watching))
            rows += HomeRow.Continue(continueWatching)
        }
        if (watchedPlaylists.isNotEmpty()) {
            rows += HomeRow.Header(context.getString(R.string.home_playlist_history))
            rows += HomeRow.Playlists(watchedPlaylists)
        }
        if (homeMixes.isNotEmpty()) {
            rows += HomeRow.Header(context.getString(R.string.home_mixes))
            rows += HomeRow.Mixes(homeMixes)
        }
        if (forYouVideos.isNotEmpty()) {
            rows += HomeRow.Header(context.getString(R.string.home_suggestions))
            forYouVideos.forEach { rows += HomeRow.Video(it) }
            val loadMoreError = forYouLoadMoreError
            when {
                loadMoreError != null -> rows += HomeRow.LoadMoreError(loadMoreError)
                isLoadingMoreForYou -> rows += HomeRow.LoadingMore
            }
        } else if (isLoadingPersonalization) {
            rows += HomeRow.Header(context.getString(R.string.home_suggestions))
            repeat(columns * 2) { rows += HomeRow.Skeleton }
        } else if (continueWatching.isEmpty() &&
            watchedPlaylists.isEmpty() &&
            homeMixes.isEmpty()
        ) {
            rows += HomeRow.Empty(
                kind = if (error == null) EmptyKind.NO_SUGGESTIONS else EmptyKind.OFFLINE_SUGGESTIONS,
                error = error
            )
        }
        return rows
    }

    private fun continueWatching(): List<VideoItem> = history
        .filter { entity ->
            if (entity.watchProgressSeconds <= 0L) return@filter false
            val total = parseDurationSeconds(entity.duration)
            total <= 0 || entity.watchProgressSeconds < total - 5L
        }
        .take(10)
        .map { it.toVideoItem() }

    // ------------------------------------------------------------------
    // Bố cục thích ứng
    // ------------------------------------------------------------------

    fun updateLayoutMetrics() {
        val widthDp = resources.configuration.screenWidthDp
        val compact = widthDp < 600
        val newColumns = videoGridColumnsForWidth(widthDp)
        val newPadding = context.dp(if (compact) 16f else 24f)
        val newContinueWidth = context.dp(if (compact) 236f else 300f)
        val newMixWidth = context.dp(if (compact) 264f else 320f)

        val metricsChanged = newColumns != columns || newPadding != horizontalPadding
        columns = newColumns
        horizontalPadding = newPadding
        continueCardWidth = newContinueWidth
        mixCardWidth = newMixWidth

        categoryList.setPadding(newPadding, context.dp(8f), newPadding, context.dp(8f))

        if (metricsChanged || trendingAdapter == null) {
            trendingAdapter = homeAdapter(compact = compact)
            forYouAdapter = homeAdapter(compact = compact)

            trendingList.layoutManager = gridLayoutManager(trendingAdapter!!)
            forYouList.layoutManager = gridLayoutManager(forYouAdapter!!)
            trendingList.adapter = trendingAdapter
            forYouList.adapter = forYouAdapter
        }

        if (trendingList.itemDecorationCount == 0) {
            trendingList.addItemDecoration(SpacingItemDecoration(context.dp(16f)))
        }
        if (forYouList.itemDecorationCount == 0) {
            forYouList.addItemDecoration(SpacingItemDecoration(context.dp(16f)))
        }

        val gridEdgePadding = newPadding - context.dp(8f)
        trendingList.setPadding(gridEdgePadding, 0, gridEdgePadding, context.dp(32f))
        forYouList.setPadding(
            gridEdgePadding,
            context.dp(4f),
            gridEdgePadding,
            context.dp(32f)
        )

        renderRows()
    }

    private fun homeAdapter(compact: Boolean): HomeAdapter = HomeAdapter(
        cardWidthPx = context.dp(if (compact) 236f else 300f),
        mixCardWidthPx = context.dp(if (compact) 264f else 320f)
    )

    private fun gridLayoutManager(adapter: HomeAdapter): GridLayoutManager {
        val manager = GridLayoutManager(context, columns)
        manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (adapter.isFullSpan(position)) columns else 1
            }
        }
        return manager
    }

    private fun applyTabUi() {
        val trending = selectedTab == HomeTab.TRENDING
        trendingRefresh.visibility = if (trending) View.VISIBLE else View.GONE
        forYouRefresh.visibility = if (trending) View.GONE else View.VISIBLE
        categoryList.visibility = if (trending) View.VISIBLE else View.GONE

        styleTab(tabTrending, tabTrendingLabel, trending)
        styleTab(tabForYou, tabForYouLabel, !trending)
    }

    private fun styleTab(tab: LinearLayout, label: TextView, selected: Boolean) {
        if (selected) {
            tab.setBackgroundResource(R.drawable.bg_segmented_tab_selected)
        } else {
            tab.background = null
        }
        label.setTextColor(
            ContextCompat.getColor(
                context,
                if (selected) R.color.pure_on_surface else R.color.pure_on_surface_variant
            )
        )
        label.applyBold(selected)
    }

    // ------------------------------------------------------------------
    // Chip danh mục
    // ------------------------------------------------------------------

    private inner class CategoryAdapter : RecyclerView.Adapter<CategoryHolder>() {

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): CategoryHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.view_category_chip, parent, false) as TextView
            return CategoryHolder(view)
        }

        override fun getItemCount(): Int = VideoCategory.entries.size

        override fun onBindViewHolder(holder: CategoryHolder, position: Int) {
            val category = VideoCategory.entries[position]
            val selected = category == selectedCategory
            val chip = holder.chip
            chip.text = category.displayName
            chip.setBackgroundResource(
                if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip
            )
            chip.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (selected) R.color.pure_surface else R.color.pure_on_surface
                )
            )
            chip.applyBold(selected)
            chip.setOnClickListener {
                val oldCategory = selectedCategory
                if (oldCategory == category) return@setOnClickListener
                val oldIndex = VideoCategory.entries.indexOf(oldCategory)
                selectedCategory = category
                if (oldIndex >= 0) notifyItemChanged(oldIndex)
                notifyItemChanged(position)
                onSelectCategory?.invoke(category)
            }
        }
    }

    private class CategoryHolder(val chip: TextView) : RecyclerView.ViewHolder(chip)
}

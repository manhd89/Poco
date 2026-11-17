package com.luxysiv.puretube.ui.subscriptions

import android.content.res.Configuration
import android.view.View
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.luxysiv.puretube.data.model.SubscribedChannel
import com.luxysiv.puretube.data.model.VideoItem
import com.luxysiv.puretube.error.AppError
import com.luxysiv.puretube.ui.base.PageActivity
import com.luxysiv.puretube.ui.components.watchProgressMap
import com.luxysiv.puretube.ui.navigation.AppTab
import com.luxysiv.puretube.ui.navigation.PageNavigator
import com.luxysiv.puretube.ui.views.SubscriptionsNativeView
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Trang Đăng ký - Activity riêng, dùng [SubscriptionsViewModel] và giao diện XML native. */
class SubscriptionsActivity : PageActivity() {

    override val viewModel: SubscriptionsViewModel by viewModels()

    override val currentTab: AppTab = AppTab.SUBSCRIPTIONS

    private lateinit var subscriptionsView: SubscriptionsNativeView

    override fun createPageView(): View {
        val view = SubscriptionsNativeView(this)
        subscriptionsView = view
        view.onVideoClick = { video -> playAndOpenPlayer(video) }
        view.onToggleFavorite = viewModel::toggleFavorite
        view.onToggleWatchLater = viewModel::toggleWatchLater
        view.onSearchClick = { PageNavigator.open(this, AppTab.SEARCH) }
        view.onExplore = { PageNavigator.open(this, AppTab.HOME) }
        view.onRefresh = { viewModel.refresh(force = true) }
        view.onUnsubscribe = viewModel::unsubscribe
        view.onLoadMore = viewModel::loadMore
        view.onRetryLoadMore = viewModel::retryLoadMore
        return view
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        observeSubscriptionsState()
    }

    private fun observeSubscriptionsState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Ghép sẵn lịch sử + trạng thái làm mới/tải thêm để `combine`
                // bên dưới chỉ còn 5 nguồn (giới hạn overload có kiểu).
                val feedExtras = combine(
                    viewModel.history,
                    viewModel.isRefreshing,
                    viewModel.isLoadingMore,
                    viewModel.hasMore,
                    viewModel.loadMoreError
                ) { history, refreshing, loadingMore, hasMore, loadMoreError ->
                    FeedExtras(
                        progress = watchProgressMap(history),
                        isRefreshing = refreshing,
                        isLoadingMore = loadingMore,
                        hasMore = hasMore,
                        loadMoreError = loadMoreError
                    )
                }

                combine(
                    viewModel.channels,
                    viewModel.videos,
                    viewModel.favoriteIds,
                    viewModel.watchLaterIds,
                    feedExtras
                ) { channels, videos, favorites, watchLater, extras ->
                    SubscriptionsState(
                        channels = channels,
                        videos = videos,
                        favorites = favorites,
                        watchLater = watchLater,
                        progress = extras.progress,
                        isRefreshing = extras.isRefreshing,
                        isLoadingMore = extras.isLoadingMore,
                        hasMore = extras.hasMore,
                        loadMoreError = extras.loadMoreError
                    )
                }.collect { state ->
                    subscriptionsView.setContent(
                        channels = state.channels,
                        videos = state.videos,
                        favoriteIds = state.favorites,
                        watchLaterIds = state.watchLater,
                        watchProgress = state.progress,
                        isRefreshing = state.isRefreshing,
                        isLoadingMore = state.isLoadingMore,
                        hasMore = state.hasMore,
                        loadMoreError = state.loadMoreError
                    )
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::subscriptionsView.isInitialized) subscriptionsView.updateLayoutMetrics()
    }

    private data class FeedExtras(
        val progress: Map<String, Float>,
        val isRefreshing: Boolean,
        val isLoadingMore: Boolean,
        val hasMore: Boolean,
        val loadMoreError: AppError?
    )

    private data class SubscriptionsState(
        val channels: List<SubscribedChannel>,
        val videos: List<VideoItem>,
        val favorites: Set<String>,
        val watchLater: Set<String>,
        val progress: Map<String, Float>,
        val isRefreshing: Boolean,
        val isLoadingMore: Boolean,
        val hasMore: Boolean,
        val loadMoreError: AppError?
    )
}

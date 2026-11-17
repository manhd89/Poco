package com.luxysiv.puretube.ui.home

import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.luxysiv.puretube.data.model.VideoCategory
import com.luxysiv.puretube.ui.base.PageActivity
import com.luxysiv.puretube.ui.navigation.AppTab
import com.luxysiv.puretube.ui.navigation.PageNavigator
import com.luxysiv.puretube.ui.playlist.PlaylistActivity
import com.luxysiv.puretube.ui.views.HomeNativeView
import kotlinx.coroutines.launch

/** Trang chủ - Activity riêng, dùng [HomeViewModel] và giao diện XML native. */
class HomeActivity : PageActivity() {

    override val viewModel: HomeViewModel by viewModels()

    override val currentTab: AppTab = AppTab.HOME

    private lateinit var homeView: HomeNativeView

    override fun createPageView(): View {
        val view = HomeNativeView(this)
        homeView = view
        view.onSearchClick = { PageNavigator.open(this, AppTab.SEARCH) }
        view.onYouClick = { PageNavigator.open(this, AppTab.LIBRARY) }
        view.onSelectTab = viewModel::selectTab
        view.onSelectCategory = viewModel::selectCategory
        view.onVideoClick = { video -> playAndOpenPlayer(video) }
        view.onPlaylistClick = { entry ->
            PlaylistActivity.start(
                context = this,
                playlist = entry.toPlaylistItem(),
                resumeVideoId = entry.lastVideoId,
                backTab = AppTab.HOME
            )
        }
        view.onHomeMixClick = { mix ->
            viewModel.playHomeMix(mix)
            openPlayer()
        }
        view.onToggleFavorite = viewModel::toggleFavorite
        view.onToggleWatchLater = viewModel::toggleWatchLater
        view.onToggleSavePlaylist = viewModel::toggleSavedPlaylist
        view.onPlayNext = viewModel::playNextInQueue
        view.onAddToQueue = viewModel::addToQueue
        view.onRefresh = viewModel::refreshFeed
        view.onLoadMore = viewModel::loadMoreFeed
        view.onRetryLoadMore = viewModel::retryLoadMore
        view.onLoadMoreForYou = viewModel::loadMoreForYou
        view.onRetryLoadMoreForYou = viewModel::retryLoadMoreForYou
        return view
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreState(savedInstanceState)
        observeHomeState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val state = viewModel.uiState.value
        outState.putString(KEY_SELECTED_TAB, state.selectedTab.name)
        outState.putString(KEY_SELECTED_CATEGORY, state.selectedCategory.name)
    }

    /**
     * Khôi phục tab/danh mục sau khi tiến trình bị hệ thống thu hồi.
     * ViewModel tự giữ state khi chỉ xoay màn hình, nên ở đây chỉ cần chạy khi
     * có bundle; [HomeViewModel] sẽ tự bỏ qua tab mặc định nếu người dùng đã
     * chọn tab (tabPinnedByUser).
     */
    private fun restoreState(bundle: Bundle?) {
        if (bundle == null) return
        val current = viewModel.uiState.value
        bundle.getString(KEY_SELECTED_TAB)
            ?.let { name -> runCatching { HomeTab.valueOf(name) }.getOrNull() }
            ?.takeIf { it != current.selectedTab }
            ?.let(viewModel::selectTab)
        bundle.getString(KEY_SELECTED_CATEGORY)
            ?.let { name -> runCatching { VideoCategory.valueOf(name) }.getOrNull() }
            ?.takeIf { it != current.selectedCategory }
            ?.let(viewModel::selectCategory)
    }

    private fun observeHomeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> homeView.render(state) }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::homeView.isInitialized) homeView.updateLayoutMetrics()
    }

    private companion object {
        const val KEY_SELECTED_TAB = "home_selected_tab"
        const val KEY_SELECTED_CATEGORY = "home_selected_category"
    }
}

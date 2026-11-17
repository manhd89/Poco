package com.luxysiv.puretube.ui.channel

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.luxysiv.puretube.ui.base.PageActivity
import com.luxysiv.puretube.ui.components.watchProgressMap
import com.luxysiv.puretube.ui.navigation.AppTab
import com.luxysiv.puretube.ui.views.ChannelNativeView
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ChannelActivity : PageActivity() {

    override val viewModel: ChannelViewModel by viewModels()

    override val currentTab: AppTab get() = backParentTab

    override val backParentTab: AppTab
        get() = intent.getStringExtra(EXTRA_BACK_TAB)
            ?.let { name -> AppTab.values().firstOrNull { it.name == name } }
            ?: AppTab.HOME

    private lateinit var channelView: ChannelNativeView

    override fun createPageView(): View {
        val view = ChannelNativeView(this)
        channelView = view
        view.onBack = { finish() }
        view.onRetry = viewModel::retry
        view.onLoadMore = viewModel::loadMore
        view.onVideoClick = { video ->
            viewModel.playVideo(video)
            openPlayer()
        }
        view.onToggleFavorite = viewModel::toggleFavorite
        view.onToggleWatchLater = viewModel::toggleWatchLater
        view.onPlayNext = viewModel::playNextInQueue
        view.onAddToQueue = viewModel::addToQueue
        return view
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID).orEmpty()
        val channelTitle = intent.getStringExtra(EXTRA_CHANNEL_TITLE).orEmpty()
        channelView.setChannel(null, channelTitle)
        if (channelId.isNotBlank()) {
            viewModel.start(channelId, channelTitle)
        }
        observeState(channelTitle)
    }

    private fun observeState(fallbackTitle: String) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.page.collect { page ->
                        channelView.setChannel(page, fallbackTitle)
                    }
                }
                launch { viewModel.videos.collect { channelView.setVideos(it) } }
                launch { viewModel.isLoading.collect { channelView.setLoading(it) } }
                launch { viewModel.isLoadingMore.collect { channelView.setLoadingMore(it) } }
                launch { viewModel.hasMore.collect { channelView.setHasMore(it) } }
                launch { viewModel.error.collect { channelView.setError(it) } }
                launch {
                    combine(viewModel.favoriteIds, viewModel.watchLaterIds, viewModel.history) { f, w, h ->
                        Triple(f, w, watchProgressMap(h))
                    }.collect { (f, w, p) ->
                        channelView.setLibrary(f, w, p)
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_CHANNEL_ID = "channel_id"
        private const val EXTRA_CHANNEL_TITLE = "channel_title"
        private const val EXTRA_BACK_TAB = "channel_back_tab"

        fun start(
            context: Context,
            channelId: String,
            channelTitle: String = "",
            backTab: AppTab = AppTab.HOME
        ) {
            if (channelId.isBlank()) return
            context.startActivity(
                Intent(context, ChannelActivity::class.java).apply {
                    putExtra(EXTRA_CHANNEL_ID, channelId)
                    putExtra(EXTRA_CHANNEL_TITLE, channelTitle)
                    putExtra(EXTRA_BACK_TAB, backTab.name)
                }
            )
        }
    }
}

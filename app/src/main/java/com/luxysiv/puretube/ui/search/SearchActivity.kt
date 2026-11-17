package com.luxysiv.puretube.ui.search

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.luxysiv.puretube.ui.base.PageActivity
import com.luxysiv.puretube.ui.components.watchProgressMap
import com.luxysiv.puretube.ui.navigation.AppTab
import com.luxysiv.puretube.ui.navigation.PageNavigator
import com.luxysiv.puretube.ui.playlist.PlaylistActivity
import com.luxysiv.puretube.ui.views.SearchNativeView
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Trang tìm kiếm - Activity riêng, dùng [SearchViewModel] và giao diện XML native. */
class SearchActivity : PageActivity() {

    override val viewModel: SearchViewModel by viewModels()

    override val currentTab: AppTab = AppTab.SEARCH

    private lateinit var searchView: SearchNativeView

    private val voiceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
            if (!text.isNullOrEmpty()) {
                searchView.submitText(text)
            }
        }
    }

    override fun createPageView(): View {
        val view = SearchNativeView(this)
        searchView = view
        view.onBackClick = {
            PageNavigator.open(this, AppTab.HOME)
            finish()
        }
        view.onQueryChange = viewModel::onSearchQueryChange
        view.onSubmit = viewModel::performSearch
        view.onClearRecentSearches = viewModel::clearSearchHistory
        view.onRemoveHistory = viewModel::removeSearchHistory
        view.onMicClick = { launchVoiceSearch() }
        view.onVideoClick = { video -> playAndOpenPlayer(video) }
        view.onPlaylistClick = { playlist -> PlaylistActivity.start(this, playlist) }
        view.onToggleFavorite = viewModel::toggleFavorite
        view.onToggleWatchLater = viewModel::toggleWatchLater
        view.onToggleSavePlaylist = viewModel::toggleSavedPlaylist
        view.postDelayed({ if (!view.hasActiveSearch()) view.focusSearch() }, 150)
        return view
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        observeSearchState()
        onBackPressedDispatcher.addCallback(this, clearSearchCallback)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::searchView.isInitialized) {
            outState.putString(KEY_QUERY, searchView.currentQuery())
        }
    }

    /**
     * Khôi phục từ khoá sau khi tiến trình bị thu hồi.
     *
     * Khi chỉ xoay màn hình, ViewModel vẫn giữ query và view đã có nội dung nên
     * bỏ qua; chỉ chạy lại tìm kiếm khi view thực sự trống.
     */
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val query = savedInstanceState.getString(KEY_QUERY).orEmpty()
        if (query.isNotBlank() && ::searchView.isInitialized && !searchView.hasActiveSearch()) {
            searchView.submitText(query)
        }
    }

    private fun launchVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                getString(com.luxysiv.puretube.R.string.search_voice_prompt)
            )
        }
        runCatching { voiceLauncher.launch(intent) }
    }

    private val clearSearchCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (::searchView.isInitialized && searchView.hasActiveSearch()) {
                viewModel.onSearchQueryChange("")
                searchView.clearFocusAndHideKeyboard()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }
    }

    private fun observeSearchState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.searchQuery.collect { searchView.setQuery(it) } }
                launch { viewModel.searchResults.collect { searchView.setResults(it) } }
                launch { viewModel.isSearching.collect { searchView.setSearching(it) } }
                launch {
                    viewModel.isShowingResults.collect { searchView.setShowingResults(it) }
                }
                launch { viewModel.searchError.collect { searchView.setSearchError(it) } }
                launch {
                    viewModel.searchSuggestions.collect { searchView.setSuggestions(it) }
                }
                launch {
                    viewModel.recentSearches.collect { searchView.setRecentSearches(it) }
                }
                launch {
                    viewModel.popularSearches.collect { searchView.setPopularSearches(it) }
                }
                launch {
                    viewModel.savedPlaylistIds.collect {
                        searchView.setSavedPlaylistIds(it)
                    }
                }
                launch {
                    combine(
                        viewModel.favoriteIds,
                        viewModel.watchLaterIds,
                        viewModel.watchedIds,
                        viewModel.history
                    ) { favorites, watchLater, watched, history ->
                        LibraryState(favorites, watchLater, watched, watchProgressMap(history))
                    }.collect { state ->
                        searchView.setLibrary(
                            favorites = state.favorites,
                            watchLater = state.watchLater,
                            watched = state.watched,
                            progress = state.progress
                        )
                    }
                }
            }
        }
    }

    private data class LibraryState(
        val favorites: Set<String>,
        val watchLater: Set<String>,
        val watched: Set<String>,
        val progress: Map<String, Float>
    )

    private companion object {
        const val KEY_QUERY = "search_query"
    }
}

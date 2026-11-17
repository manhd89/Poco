package com.luxysiv.puretube.ui.library

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.luxysiv.puretube.R
import com.luxysiv.puretube.data.database.PlaylistHistoryEntity
import com.luxysiv.puretube.data.database.SavedPlaylistEntity
import com.luxysiv.puretube.data.database.VideoEntity
import com.luxysiv.puretube.ui.base.PageActivity
import com.luxysiv.puretube.ui.navigation.AppTab
import com.luxysiv.puretube.ui.navigation.PageNavigator
import com.luxysiv.puretube.ui.playlist.PlaylistActivity
import com.luxysiv.puretube.ui.views.LibraryNativeView
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Trang "Bạn" - Activity riêng, dùng [LibraryViewModel] và giao diện XML native. */
class LibraryActivity : PageActivity() {

    override val viewModel: LibraryViewModel by viewModels()

    override val currentTab: AppTab = AppTab.LIBRARY

    private lateinit var libraryView: LibraryNativeView

    override fun createPageView(): View {
        val view = LibraryNativeView(this)
        libraryView = view
        view.onVideoClick = { video -> playAndOpenPlayer(video) }
        view.onPlaylistClick = { entry ->
            // Mở lại playlist và resume đúng video đang xem dở (nếu còn).
            PlaylistActivity.start(
                context = this,
                playlist = entry.toPlaylistItem(),
                resumeVideoId = entry.lastVideoId,
                backTab = AppTab.LIBRARY
            )
        }
        view.onToggleFavorite = viewModel::toggleFavorite
        view.onToggleWatchLater = viewModel::toggleWatchLater
        view.onSavedPlaylistClick = { playlist ->
            PlaylistActivity.start(
                context = this,
                playlist = playlist,
                backTab = AppTab.LIBRARY
            )
        }
        view.onToggleSavePlaylist = viewModel::toggleSavedPlaylist
        view.onClearHistory = { showClearHistoryDialog() }
        view.onRemoveHistory = viewModel::removeFromHistory
        view.onOpenSettings = { PageNavigator.open(this, AppTab.SETTINGS) }
        view.onSearchClick = { PageNavigator.open(this, AppTab.SEARCH) }
        return view
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        observeLibraryState()
    }

    private fun observeLibraryState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val savedState = combine(
                    viewModel.savedPlaylists,
                    viewModel.savedPlaylistIds
                ) { playlists, ids -> playlists to ids }
                combine(
                    viewModel.favorites,
                    viewModel.history,
                    viewModel.playlistHistory,
                    savedState,
                    viewModel.watchLater
                ) { favorites, history, playlists, saved, watchLater ->
                    LibraryContent(
                        favorites = favorites,
                        history = history,
                        playlists = playlists,
                        savedPlaylists = saved.first,
                        savedPlaylistIds = saved.second,
                        watchLater = watchLater
                    )
                }.collect { content ->
                    libraryView.setContent(
                        favorites = content.favorites,
                        history = content.history,
                        playlists = content.playlists,
                        savedPlaylists = content.savedPlaylists,
                        savedPlaylistIds = content.savedPlaylistIds,
                        watchLater = content.watchLater
                    )
                }
            }
        }
    }

    private data class LibraryContent(
        val favorites: List<VideoEntity>,
        val history: List<VideoEntity>,
        val playlists: List<PlaylistHistoryEntity>,
        val savedPlaylists: List<SavedPlaylistEntity>,
        val savedPlaylistIds: Set<String>,
        val watchLater: List<VideoEntity>
    )

    private fun showClearHistoryDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.library_clear_dialog_title)
            .setMessage(R.string.library_clear_dialog_message)
            .setPositiveButton(R.string.library_clear_dialog_confirm) { _, _ ->
                viewModel.clearWatchHistory()
            }
            .setNegativeButton(R.string.library_clear_dialog_dismiss, null)
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::libraryView.isInitialized) {
            outState.putInt(KEY_SELECTED_TAB, libraryView.selectedTab())
        }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        if (::libraryView.isInitialized) {
            libraryView.selectTab(savedInstanceState.getInt(KEY_SELECTED_TAB, 0))
        }
    }

    private companion object {
        const val KEY_SELECTED_TAB = "library_selected_tab"
    }
}

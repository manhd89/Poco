package com.luxysiv.puretube.ui.playlist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.luxysiv.puretube.data.model.PlaylistItem
import com.luxysiv.puretube.data.model.VideoItem
import com.luxysiv.puretube.ui.base.PageActivity
import com.luxysiv.puretube.ui.components.watchProgressMap
import com.luxysiv.puretube.ui.navigation.AppTab
import com.luxysiv.puretube.ui.views.PlaylistNativeView
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Màn hình chi tiết playlist (`PL...`/`RD...`), mở từ card playlist trong Tìm
 * kiếm.
 *
 * Bấm một video sẽ phát nó trong ngữ cảnh cả playlist: hàng đợi giữ đúng thứ
 * tự đang thấy, Next/Previous và auto-next đi theo playlist, và tự tải thêm
 * trang bằng continuation khi chạm cuối danh sách.
 */
class PlaylistActivity : PageActivity() {

    override val viewModel: PlaylistViewModel by viewModels()

    /** Tab đang mở màn hình này: Tìm kiếm hoặc Thư viện (khi resume từ Lịch sử). */
    override val currentTab: AppTab get() = backParentTab

    /** Back hệ thống quay về tab đã mở màn hình này. */
    override val backParentTab: AppTab
        get() = intent.getStringExtra(EXTRA_BACK_TAB)
            ?.let { name -> AppTab.values().firstOrNull { it.name == name } }
            ?: AppTab.SEARCH

    private lateinit var playlistView: PlaylistNativeView

    /** Đã xử lý yêu cầu resume từ Lịch sử chưa (tránh phát lại khi xoay máy). */
    private var resumeRequested = false

    override fun createPageView(): View {
        val view = PlaylistNativeView(this)
        playlistView = view
        view.onBack = { finish() }
        view.onRetry = viewModel::retry
        view.onLoadMore = viewModel::loadMore
        view.onVideoClick = ::playFromPlaylist
        view.onToggleFavorite = viewModel::toggleFavorite
        view.onToggleWatchLater = viewModel::toggleWatchLater
        view.onPlayNext = viewModel::playNextInQueue
        view.onAddToQueue = viewModel::addToQueue
        view.onToggleSave = viewModel::toggleSavedPlaylist

        playlistFromIntent()?.let { playlist ->
            view.setPlaylist(playlist)
            viewModel.start(playlist)
        }
        return view
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        observePlaylistState()
        resumeFromHistoryIfNeeded()
    }

    private fun observePlaylistState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.playlist.collect { playlist ->
                        playlist?.let(playlistView::setPlaylist)
                    }
                }
                launch { viewModel.videos.collect { playlistView.setVideos(it) } }
                launch { viewModel.isLoading.collect { playlistView.setLoading(it) } }
                launch {
                    viewModel.isLoadingMore.collect { playlistView.setLoadingMore(it) }
                }
                launch { viewModel.hasMore.collect { playlistView.setHasMore(it) } }
                launch { viewModel.error.collect { playlistView.setError(it) } }
                launch {
                    combine(
                        viewModel.favoriteIds,
                        viewModel.watchLaterIds,
                        viewModel.history
                    ) { favorites, watchLater, history ->
                        LibraryState(favorites, watchLater, watchProgressMap(history))
                    }.collect { state ->
                        playlistView.setLibrary(
                            favorites = state.favorites,
                            watchLater = state.watchLater,
                            progress = state.progress
                        )
                    }
                }
                launch {
                    combine(
                        viewModel.playlist,
                        viewModel.savedPlaylistIds
                    ) { playlist, ids ->
                        playlist?.id?.let(ids::contains) == true
                    }.collect { saved -> playlistView.setSaved(saved) }
                }
            }
        }
    }

    /**
     * Phát video vừa bấm cùng toàn bộ playlist đang tải.
     *
     * Danh sách hiện tại (có thể mới là trang đầu) và continuation được chuyển
     * cho hàng đợi; coordinator tự tải thêm khi hết danh sách nên không cần
     * người dùng quay lại màn hình này.
     */
    private fun playFromPlaylist(video: VideoItem) {
        val playlist = viewModel.playlist.value ?: playlistFromIntent() ?: return
        viewModel.playPlaylistVideo(
            video = video,
            playlist = playlist,
            videos = viewModel.videos.value,
            nextToken = viewModel.nextPageToken
        )
        openPlayer()
    }

    /**
     * Mở lại playlist từ Lịch sử: tải tới đúng video đang xem dở rồi phát nó
     * trong ngữ cảnh cả playlist. Nếu video đã bị xoá khỏi playlist, chỉ mở
     * danh sách để người dùng chọn lại.
     */
    private fun resumeFromHistoryIfNeeded() {
        if (resumeRequested) return
        val videoId = intent.getStringExtra(EXTRA_RESUME_VIDEO).orEmpty()
        if (videoId.isBlank()) return
        resumeRequested = true
        // Đã tiêu thụ: xoay máy không phát lại từ đầu.
        intent.removeExtra(EXTRA_RESUME_VIDEO)
        lifecycleScope.launch {
            val video = viewModel.ensureVideoLoaded(videoId)
            if (video != null) playFromPlaylist(video)
        }
    }

    private fun playlistFromIntent(): PlaylistItem? {
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        if (id.isBlank()) return null
        return PlaylistItem(
            id = id,
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            channelTitle = intent.getStringExtra(EXTRA_CHANNEL).orEmpty(),
            channelId = intent.getStringExtra(EXTRA_CHANNEL_ID).orEmpty(),
            videoCount = intent.getIntExtra(EXTRA_VIDEO_COUNT, 0),
            thumbnailUrl = intent.getStringExtra(EXTRA_THUMBNAIL).orEmpty(),
            firstVideoId = intent.getStringExtra(EXTRA_FIRST_VIDEO).orEmpty()
        )
    }

    private data class LibraryState(
        val favorites: Set<String>,
        val watchLater: Set<String>,
        val progress: Map<String, Float>
    )

    companion object {
        private const val EXTRA_ID = "playlist_id"
        private const val EXTRA_TITLE = "playlist_title"
        private const val EXTRA_CHANNEL = "playlist_channel"
        private const val EXTRA_CHANNEL_ID = "playlist_channel_id"
        private const val EXTRA_VIDEO_COUNT = "playlist_video_count"
        private const val EXTRA_THUMBNAIL = "playlist_thumbnail"
        private const val EXTRA_FIRST_VIDEO = "playlist_first_video"
        private const val EXTRA_RESUME_VIDEO = "playlist_resume_video"
        private const val EXTRA_BACK_TAB = "playlist_back_tab"

        /**
         * Mở màn hình chi tiết playlist từ card tìm kiếm.
         *
         * [resumeVideoId] dùng khi mở lại từ Lịch sử: Activity tự tải tới video
         * đó rồi phát tiếp trong ngữ cảnh playlist. [backTab] quyết định nút
         * Back quay về Tìm kiếm hay Thư viện.
         */
        fun start(
            context: Context,
            playlist: PlaylistItem,
            resumeVideoId: String? = null,
            backTab: AppTab = AppTab.SEARCH
        ) {
            context.startActivity(
                Intent(context, PlaylistActivity::class.java).apply {
                    putExtra(EXTRA_ID, playlist.id)
                    putExtra(EXTRA_TITLE, playlist.title)
                    putExtra(EXTRA_CHANNEL, playlist.channelTitle)
                    putExtra(EXTRA_CHANNEL_ID, playlist.channelId)
                    putExtra(EXTRA_VIDEO_COUNT, playlist.videoCount)
                    putExtra(EXTRA_THUMBNAIL, playlist.thumbnailUrl)
                    putExtra(EXTRA_FIRST_VIDEO, playlist.firstVideoId)
                    putExtra(EXTRA_BACK_TAB, backTab.name)
                    if (!resumeVideoId.isNullOrBlank()) {
                        putExtra(EXTRA_RESUME_VIDEO, resumeVideoId)
                    }
                }
            )
        }
    }
}

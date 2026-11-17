package com.luxysiv.puretube.ui.player

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.luxysiv.puretube.data.model.CommentItem
import com.luxysiv.puretube.data.model.CommentSort
import com.luxysiv.puretube.data.repository.YouTubeDataRepository
import com.luxysiv.puretube.error.AppError
import com.luxysiv.puretube.ui.base.PureTubeViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Trạng thái bình luận gộp thành một khối.
 *
 * Trước đây mỗi trường là một `StateFlow` riêng, khiến Activity phải mở 10
 * collector và dựng lại sheet bình luận 10 lần cho mỗi thay đổi nhỏ. Một state
 * duy nhất cho phép so sánh/diff và chỉ render một lần.
 */
data class CommentsUiState(
    val comments: List<CommentItem> = emptyList(),
    val loading: Boolean = false,
    val hasMore: Boolean = false,
    val total: Int = 0,
    val disabled: Boolean = false,
    val error: AppError? = null,
    /** Thứ tự người dùng đang chọn (chip trong sheet). */
    val sort: CommentSort = CommentSort.TOP,
    /**
     * Thứ tự của [comments] đang hiển thị. Khác [sort] khi thứ tự mới còn đang
     * tải; nhờ vậy sheet giữ nguyên nội dung cũ thay vì co lại rồi bung ra.
     */
    val commentsSort: CommentSort? = null,
    val canSortNewest: Boolean = false,
    val replies: Map<String, List<CommentItem>> = emptyMap(),
    val replyLoading: Set<String> = emptySet(),
    val replyHasMore: Set<String> = emptySet(),
    val replyError: Map<String, AppError> = emptyMap()
)

/**
 * ViewModel riêng cho trang xem video.
 *
 * Trạng thái phát/kế hoạch/hàng đợi dùng chung nằm ở [PureTubeViewModel]; lớp
 * này bổ sung phần bình luận và phản hồi gắn với video đang phát.
 */
class PlayerViewModel(application: Application) : PureTubeViewModel(application) {

    // Bình luận thật của video đang xem (phân trang qua CommentsExtractor)
    private val _commentsState = MutableStateFlow(CommentsUiState())
    val commentsState: StateFlow<CommentsUiState> = _commentsState.asStateFlow()

    // Job tải bình luận + video id đang sở hữu kết quả (chống ghi đè khi đổi video nhanh)
    private var commentsJob: Job? = null
    private var commentsVideoId: String? = null
    private val replyJobs = mutableMapOf<String, Job>()

    init {
        viewModelScope.launch {
            playbackController.playback.collect { playback ->
                val videoId = playback.video?.id
                if (videoId != null && videoId != commentsVideoId) {
                    startComments(videoId)
                } else if (videoId == null && commentsVideoId != null) {
                    resetComments()
                }
            }
        }
    }

    /** Tải trang bình luận kế tiếp (nút "Xem thêm bình luận" hoặc cuộn tới cuối). */
    fun loadMoreComments() {
        val state = _commentsState.value
        if (state.loading || !state.hasMore) return
        // Đang đổi thứ tự: không nối trang của thứ tự cũ vào danh sách mới.
        if (state.commentsSort != null && state.commentsSort != state.sort) return
        val videoId = commentsVideoId ?: return
        _commentsState.update { it.copy(loading = true, error = null) }
        commentsJob = viewModelScope.launch {
            val result = YouTubeDataRepository.getComments(videoId, loadMore = true)
            if (commentsVideoId != videoId) return@launch
            result.fold(
                onSuccess = { page ->
                    _commentsState.update { current ->
                        val existingIds = current.comments.mapTo(mutableSetOf()) { it.id }
                        current.copy(
                            comments = current.comments +
                                page.comments.filter { it.id !in existingIds },
                            hasMore = page.hasMore,
                            total = if (page.totalCount > 0) page.totalCount else current.total,
                            sort = page.sort,
                            commentsSort = page.sort,
                            canSortNewest = current.canSortNewest || page.canSortNewest,
                            loading = false
                        )
                    }
                },
                onFailure = { error ->
                    _commentsState.update { it.copy(error = error, loading = false) }
                }
            )
        }
    }

    fun retryComments() {
        val videoId = commentsVideoId ?: playerState.value.currentVideo?.id ?: return
        val state = _commentsState.value
        if (state.loading) return
        if (state.comments.isEmpty() || state.commentsSort != state.sort) {
            loadFirstPage(videoId, sort = state.sort)
        } else {
            loadMoreComments()
        }
    }

    /**
     * Đổi thứ tự bình luận (Nổi bật ↔ Mới nhất) rồi tải lại từ trang đầu.
     *
     * YouTube cấp token riêng cho từng thứ tự; [canSortNewest] chỉ bật khi đã
     * đọc được menu sắp xếp nên nút "Mới nhất" không bao giờ gọi mạng vô ích.
     */
    fun setCommentSort(sort: CommentSort) {
        val state = _commentsState.value
        // Bấm lại đúng thứ tự đang tải/đang hiển thị thì không làm gì; nếu lần
        // tải trước lỗi thì vẫn cho bấm lại để thử.
        if (state.sort == sort && (state.loading ||
                (state.comments.isNotEmpty() && state.commentsSort == sort))
        ) {
            return
        }
        if (sort == CommentSort.NEWEST && !state.canSortNewest) return
        val videoId = commentsVideoId ?: return
        commentsJob?.cancel()
        // Giữ nguyên bình luận và phản hồi đang hiển thị để sheet không co lại
        // rồi bung ra khi chờ trang mới; chip đổi ngay để có phản hồi tức thì.
        _commentsState.update {
            it.copy(
                loading = true,
                hasMore = false,
                error = null,
                sort = sort
            )
        }
        loadFirstPage(videoId, sort = sort)
    }

    /**
     * Mở rộng "Xem N câu trả lời" (hoặc tải thêm phản hồi) bằng dữ liệu thật.
     * Gọi lại khi lỗi sẽ thử lại đúng trang đang dở.
     */
    fun loadReplies(commentId: String) {
        val videoId = commentsVideoId ?: return
        val state = _commentsState.value
        if (commentId in state.replyLoading) return
        val alreadyLoaded = state.replies.containsKey(commentId)
        if (alreadyLoaded && commentId !in state.replyHasMore) return

        _commentsState.update {
            it.copy(
                replyError = it.replyError - commentId,
                replyLoading = it.replyLoading + commentId
            )
        }
        val job = viewModelScope.launch {
            val result = YouTubeDataRepository.getCommentReplies(
                videoId = videoId,
                commentId = commentId,
                loadMore = alreadyLoaded
            )
            if (commentsVideoId != videoId) return@launch
            result.fold(
                onSuccess = { page ->
                    _commentsState.update { current ->
                        val previous = current.replies[commentId].orEmpty()
                        val merged = if (alreadyLoaded) {
                            val existingIds = previous.mapTo(mutableSetOf()) { it.id }
                            previous + page.comments.filter { it.id !in existingIds }
                        } else {
                            page.comments
                        }
                        current.copy(
                            replies = current.replies + (commentId to merged),
                            replyHasMore = if (page.hasMore) {
                                current.replyHasMore + commentId
                            } else {
                                current.replyHasMore - commentId
                            },
                            replyLoading = current.replyLoading - commentId
                        )
                    }
                },
                onFailure = { error ->
                    _commentsState.update {
                        it.copy(
                            replyError = it.replyError + (commentId to error),
                            replyLoading = it.replyLoading - commentId
                        )
                    }
                }
            )
            replyJobs.remove(commentId)
        }
        replyJobs[commentId] = job
    }

    fun retryReplies(commentId: String) {
        loadReplies(commentId)
    }

    override fun setHideComments(enabled: Boolean) {
        super.setHideComments(enabled)
        if (enabled) {
            // Không tải bình luận khi người dùng đã ẩn để tiết kiệm dữ liệu.
            commentsJob?.cancel()
            _commentsState.update { it.copy(loading = false) }
        } else if (_commentsState.value.comments.isEmpty()) {
            // Bật lại sau khi ẩn: nạp bình luận cho video đang xem.
            playerState.value.currentVideo?.let { startComments(it.id) }
        }
    }

    /** Bắt đầu tải trang bình luận đầu tiên cho [videoId]. */
    private fun startComments(videoId: String) {
        commentsJob?.cancel()
        replyJobs.values.forEach { it.cancel() }
        replyJobs.clear()
        commentsVideoId = videoId
        _commentsState.value = CommentsUiState()
        if (settings.hideComments) {
            // Người dùng đã ẩn bình luận: không gọi mạng.
            return
        }
        // sort = null: tạo phiên mới, mặc định "Nổi bật" như YouTube.
        loadFirstPage(videoId, sort = null)
    }

    /**
     * Tải trang đầu của [videoId]. [sort] = null nghĩa là phiên mới/mặc định;
     * khác null nghĩa là đổi thứ tự trên phiên hiện có.
     */
    private fun loadFirstPage(videoId: String, sort: CommentSort?) {
        _commentsState.update { it.copy(loading = true, error = null) }
        commentsJob = viewModelScope.launch {
            val result = YouTubeDataRepository.getComments(
                videoId = videoId,
                loadMore = false,
                sort = sort
            )
            if (commentsVideoId != videoId) return@launch
            result.fold(
                onSuccess = { page ->
                    _commentsState.update {
                        it.copy(
                            comments = page.comments,
                            hasMore = page.hasMore,
                            total = if (page.totalCount > 0) page.totalCount else it.total,
                            disabled = page.isDisabled,
                            sort = page.sort,
                            commentsSort = page.sort,
                            canSortNewest = page.canSortNewest,
                            loading = false
                        )
                    }
                },
                onFailure = { error ->
                    _commentsState.update { it.copy(error = error, loading = false) }
                }
            )
        }
    }

    private fun resetComments() {
        commentsJob?.cancel()
        replyJobs.values.forEach { it.cancel() }
        replyJobs.clear()
        commentsVideoId = null
        _commentsState.value = CommentsUiState()
    }

    override fun onCleared() {
        commentsJob?.cancel()
        replyJobs.values.forEach { it.cancel() }
        replyJobs.clear()
        super.onCleared()
    }
}

package com.luxysiv.puretube.ui.subscriptions

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.luxysiv.puretube.R
import com.luxysiv.puretube.data.model.SubscribedChannel
import com.luxysiv.puretube.data.model.VideoItem
import com.luxysiv.puretube.error.AppError
import com.luxysiv.puretube.error.runAppCatching
import com.luxysiv.puretube.ui.base.PureTubeViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel riêng cho trang Đăng ký.
 *
 * Đăng ký là cục bộ (không có tài khoản YouTube): [com.luxysiv.puretube.data.repository.SubscriptionRepository]
 * lưu danh sách kênh trong Room và tải feed công khai của từng kênh. ViewModel
 * chỉ ghép hai nguồn đó thành dữ liệu hiển thị và quản lý trạng thái làm mới.
 */
class SubscriptionsViewModel(application: Application) : PureTubeViewModel(application) {

    /** Kênh người dùng đã đăng ký, sắp theo tên. */
    val channels: StateFlow<List<SubscribedChannel>> = subscriptionRepository.subscriptions
        .map { entities ->
            entities.map { entity ->
                SubscribedChannel(
                    channelId = entity.channelId,
                    title = entity.channelTitle,
                    avatarUrl = entity.avatarUrl,
                    subscriberCount = entity.subscriberCount
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    /**
     * Feed gộp của mọi kênh, mới nhất trước.
     *
     * Tên/avatar kênh được ghép tại đây thay vì lưu trùng trong bảng video, nên
     * khi thông tin kênh thay đổi thì toàn bộ feed cập nhật theo.
     */
    val videos: StateFlow<List<VideoItem>> = combine(
        subscriptionRepository.subscriptions,
        subscriptionRepository.videos
    ) { channelEntities, videoEntities ->
        val channelsById = channelEntities.associateBy { it.channelId }
        videoEntities.mapNotNull { video ->
            channelsById[video.channelId]?.let { channel -> video.toVideoItem(channel) }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _loadMoreError = MutableStateFlow<AppError?>(null)
    val loadMoreError: StateFlow<AppError?> = _loadMoreError.asStateFlow()

    /** Còn kênh nào có trang cũ hơn để cuộn tới hay không. */
    val hasMore: StateFlow<Boolean> = subscriptionRepository.hasMore.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false
    )

    /** Chỉ chạy một lần làm mới tại một thời điểm (kéo tay + tự làm mới). */
    private var refreshJob: Job? = null
    private var loadMoreJob: Job? = null

    init {
        // Tự làm mới khi mở tab: repository chỉ gọi mạng cho kênh quá hạn nên
        // mở lại tab liên tục không tạo thêm request.
        refresh(force = false)
    }

    /**
     * Tải feed của các kênh quá hạn. [force] = true bỏ qua bộ đệm 15 phút
     * (dùng cho thao tác kéo xuống làm mới).
     */
    fun refresh(force: Boolean = false) {
        if (refreshJob?.isActive == true) return
        // Làm mới sẽ thay toàn bộ feed bằng trang đầu, nên huỷ lần tải thêm đang
        // chạy và xoá footer lỗi cũ để trạng thái không bị lẫn.
        loadMoreJob?.cancel()
        _isLoadingMore.value = false
        _loadMoreError.value = null
        refreshJob = viewModelScope.launch {
            _isRefreshing.value = true
            try {
                // Lần tự làm mới giữ kín lỗi mạng để dữ liệu cũ vẫn hiển thị;
                // khi người dùng chủ động kéo xuống, báo lại nếu có kênh lỗi.
                val result = runCatching { subscriptionRepository.refresh(force) }.getOrNull()
                if (force && result != null && result.failed > 0) {
                    postMessage(
                        getApplication<Application>()
                            .getString(R.string.subscriptions_refresh_failed)
                    )
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /**
     * Nối thêm một trang cũ cho các kênh còn token.
     *
     * Lỗi chỉ hiện footer thử lại; danh sách đã tải vẫn giữ nguyên. Khi thành
     * công, `hasMore` tự cập nhật theo token mới trong Room.
     */
    fun loadMore() {
        if (refreshJob?.isActive == true || loadMoreJob?.isActive == true) return
        if (!hasMore.value) return
        loadMoreJob = viewModelScope.launch {
            _isLoadingMore.value = true
            _loadMoreError.value = null
            try {
                // runAppCatching ném lại CancellationException thay vì nuốt như
                // runCatching, nên làm mới/huỷ màn hình vẫn dừng đúng cách.
                val result = runAppCatching { subscriptionRepository.loadMore() }
                val stats = result.getOrNull()
                // Không có kênh nào tải được mới là lỗi; một phần lỗi vẫn cho
                // người dùng cuộn tiếp và các kênh lỗi sẽ được thử lại sau.
                if (stats == null || (stats.updated == 0 && stats.failed > 0)) {
                    _loadMoreError.value = result.errorOrNull() ?: AppError.Network()
                }
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    /** Người dùng bấm "Thử lại" ở footer sau khi tải thêm thất bại. */
    fun retryLoadMore() {
        _loadMoreError.value = null
        loadMore()
    }

    /** Hủy đăng ký một kênh và xoá feed đã đệm của kênh đó. */
    fun unsubscribe(channelId: String) {
        val application = getApplication<Application>()
        viewModelScope.launch {
            runCatching { subscriptionRepository.unsubscribe(channelId) }
                .onSuccess {
                    postMessage(application.getString(R.string.subscriptions_removed))
                }
                .onFailure {
                    postMessage(application.getString(R.string.subscriptions_action_failed))
                }
        }
    }
}

@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.luxysiv.puretube.ui.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.ImageViewCompat
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.media3.common.Player
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.luxysiv.puretube.R
import com.luxysiv.puretube.data.model.CommentItem
import com.luxysiv.puretube.data.model.CommentSort
import com.luxysiv.puretube.data.model.PlaybackPlan
import com.luxysiv.puretube.data.model.VideoDetails
import com.luxysiv.puretube.data.model.VideoItem
import com.luxysiv.puretube.data.model.formatCompactCount
import com.luxysiv.puretube.error.AppError
import com.luxysiv.puretube.player.AutoPlayMode
import com.luxysiv.puretube.player.PlaybackFailure
import com.luxysiv.puretube.player.PlaybackPosition
import com.luxysiv.puretube.player.PurePlayerState
import com.luxysiv.puretube.player.QueueUiState
import com.luxysiv.puretube.player.RepeatMode
import com.luxysiv.puretube.ui.components.mixDisplayTitle
import com.luxysiv.puretube.ui.views.ChannelAvatarView
import com.luxysiv.puretube.ui.views.CompactVideoCardView
import com.luxysiv.puretube.ui.views.MixCoverView
import com.luxysiv.puretube.ui.views.videoCardActions
import com.luxysiv.puretube.ui.views.applyBold
import com.luxysiv.puretube.ui.views.dp
import com.luxysiv.puretube.ui.views.shareVideo
import com.luxysiv.puretube.util.Formatters
import com.luxysiv.puretube.util.userMessage
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Toàn bộ trang xem native: vùng video + HUD + thông tin/bình luận/video liên quan.
 *
 * Dữ liệu vẫn do [PlayerViewModel] cung cấp. View giữ lại bản sao trạng thái gần
 * nhất để có thể dựng lại nội dung khi bất kỳ luồng StateFlow nào thay đổi.
 */
class PlayerNativeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : CoordinatorLayout(context, attrs, defStyleAttr) {

    val playerHud: PlayerHudView

    private val videoContainer: FrameLayout
    /** Sheet duy nhất chứa thông tin, bình luận và video liên quan. */
    private val detailsSheet: FrameLayout
    private val detailsHeader: View
    private val detailsScroll: ScrollView
    private val detailsContainer: LinearLayout

    private val infoView: View
    private val infoTitle: TextView
    private val infoMeta: TextView
    private val infoAvatar: ChannelAvatarView
    private val infoChannel: TextView
    private val infoSubscribers: TextView
    private val infoSubscribe: MaterialButton
    private val infoLikePill: View
    private val infoLikeIcon: ImageView
    private val infoLikeText: TextView
    private val infoDislike: View
    private val infoShare: View
    private val infoSavePill: View
    private val infoSaveIcon: ImageView
    private val infoSaveText: TextView
    private val infoBackgroundPill: View
    private val infoBackgroundIcon: ImageView
    private val infoTimerPill: View
    private val infoTimerIcon: ImageView
    private val infoTimerText: TextView

    private val commentsCard: View
    private val commentsTitle: TextView
    private val commentsViewAll: TextView
    private val commentsStatus: TextView
    private val commentsContainer: LinearLayout

    private val relatedHeader: View
    private val relatedContainer: LinearLayout

    var onTogglePlayPause: () -> Unit = {}
    var onSeekTo: (Float) -> Unit = {}
    var onForward10: () -> Unit = {}
    var onRewind10: () -> Unit = {}
    var onToggleFullscreen: () -> Unit = {}
    /** Vuốt xuống tay cầm của sheet chính: luôn vào fullscreen (không toggle). */
    var onEnterFullscreen: () -> Unit = {}
    var onSkipSponsor: () -> Unit = {}
    var onUnskipSponsor: () -> Unit = {}
    var onClose: () -> Unit = {}
    var onEnterPiP: () -> Unit = {}
    var onPrevious: () -> Unit = {}
    var onNext: () -> Unit = {}
    var onRetry: () -> Unit = {}
    var onSetAutoPlayMode: (AutoPlayMode) -> Unit = {}

    var onToggleFavorite: (VideoItem) -> Unit = {}
    var onToggleWatchLater: (VideoItem) -> Unit = {}
    /** Đăng ký/hủy đăng ký kênh cục bộ (không mở YouTube). */
    var onToggleSubscription: (VideoItem) -> Unit = {}
    var onPlayNext: (VideoItem) -> Unit = {}
    var onAddToQueue: (VideoItem) -> Unit = {}
    var onSelectVideo: (VideoItem) -> Unit = {}
    var onToggleBackgroundPlay: () -> Unit = {}
    var onShowSleepTimer: () -> Unit = {}
    var onSetSleepTimer: (Int) -> Unit = {}
    var onLoadMoreComments: () -> Unit = {}
    var onRetryComments: () -> Unit = {}
    var onSelectCommentSort: (CommentSort) -> Unit = {}
    var onLoadReplies: (String) -> Unit = {}
    var onRetryReplies: (String) -> Unit = {}
    /** Tỉ lệ video đổi: Activity cần cập nhật lại tham số PiP. */
    var onPipParamsInvalidated: () -> Unit = {}

    /**
     * Vị trí khung video mini player trên màn hình. Trả về null để dùng vị trí
     * ước lượng (mở trình phát từ deep link, trang nền chưa có mini player...).
     */
    var minimizeTargetProvider: (() -> Rect?)? = null

    var onSetPlaybackSpeed: (Float) -> Unit = {}
    var onSetPlaybackPitch: (Float) -> Unit = {}
    var onToggleSkipSilence: () -> Unit = {}
    var onResetPlaybackParameters: () -> Unit = {}
    var onSetQuality: (Int?) -> Unit = {}
    var onToggleCaptions: () -> Unit = {}
    var onSelectCaptionLanguage: (String) -> Unit = {}

    private var state = PurePlayerState()
    private var position = PlaybackPosition()
    private var queue = QueueUiState()
    private var plan: PlaybackPlan? = null
    private var details: VideoDetails? = null
    private var playbackFailure: PlaybackFailure? = null

    private var comments: List<CommentItem> = emptyList()
    private var commentsLoading = false
    private var commentsHasMore = false
    private var commentsTotal = 0
    private var commentsDisabled = false
    private var commentsError: AppError? = null
    private var commentsSort = CommentSort.TOP
    private var commentsCanSortNewest = false
    private var commentReplies: Map<String, List<CommentItem>> = emptyMap()
    /** Chặn gọi load-more nhiều lần trước khi StateFlow kịp báo `loading`. */
    private var commentsLoadMoreRequested = false
    private var replyLoading: Set<String> = emptySet()
    private var replyHasMore: Set<String> = emptySet()
    private var replyError: Map<String, AppError> = emptyMap()

    private var favoriteIds: Set<String> = emptySet()
    private var watchLaterIds: Set<String> = emptySet()
    private var watchProgress: Map<String, Float> = emptyMap()
    private var subscribedChannelIds: Set<String> = emptySet()

    private var fullscreen = false
    private var inPip = false
    private var twoColumn = false

    /** Tiến độ hiệu ứng thu nhỏ 0..1 và bản ghi vị trí gốc của khung video. */
    private var minimizeProgress = 0f
    private var minimizeSpring: SpringAnimation? = null

    /**
     * Tiến độ kéo tay cầm để mở rộng video tới full màn (0 = khung nhúng,
     * 1 = tràn màn hình). Dùng chung cơ chế spring với hiệu ứng thu nhỏ nhưng
     * chạy ngược lại: ngón tay kéo tới đâu khung hình giãn tới đó.
     */
    private var fullscreenDragProgress = 0f
    private var fullscreenDragSpring: SpringAnimation? = null
    private var fullscreenDragActive = false
    // Phải dùng toạ độ thô: sheet (và tay cầm) bị dời theo đúng ngón tay trong
    // lúc kéo, nên event.x/y của chính tay cầm bị triệt tiêu chuyển động.
    private var fullscreenDragStartRawY = 0f
    private var fullscreenDragStartRawX = 0f
    private var fullscreenDragBaseTop = 0
    private var fullscreenDragBaseBottom = 0
    private var fullscreenDragTargetBottom = 0
    private var fullscreenDragVelocity: VelocityTracker? = null
    // ACTION_MOVE có thể tới nhiều lần trong một frame. Gom các cập nhật lại
    // bằng postOnAnimation và sửa trực tiếp LayoutParams hiện có thay vì cấp
    // phát đối tượng mới: đây là nguồn chính gây giật lúc kéo fullscreen.
    private var fullscreenDragPendingProgress = 0f
    private var fullscreenDragUpdatePosted = false
    private val fullscreenDragUpdateRunnable = Runnable {
        fullscreenDragUpdatePosted = false
        if (fullscreenDragActive) setFullscreenDragProgress(fullscreenDragPendingProgress)
    }

    /**
     * Trạng thái duy nhất của hiệu ứng thu nhỏ/mở rộng. Mọi lối vào (chevron,
     * Back, kéo xuống, mở từ mini player) đều đi qua cùng một máy trạng thái
     * này, nên không thể có hai animation chồng nhau hoặc hai nhánh xử lý khác
     * nhau.
     */
    private var minimizeState = MinimizeState.IDLE

    private enum class MinimizeState {
        /** Trang đang hiển thị đầy đủ, không có hiệu ứng nào chạy. */
        IDLE,
        /** Ngón tay đang kéo trang xuống. */
        DRAGGING,
        /** Spring đang chạy về mini player. */
        MINIMIZING,
        /** Spring đang chạy từ mini player về trang đầy đủ. */
        EXPANDING
    }

    /**
     * Thuộc tính tiến trình cho [SpringAnimation]: giúp animation nối tiếp
     * vận tốc ngón tay và giảm tốc tự nhiên thay vì đường cong cố định.
     */
    private val minimizeProgressProperty =
        object : FloatPropertyCompat<PlayerNativeView>("minimizeProgress") {
            override fun getValue(view: PlayerNativeView): Float = view.minimizeProgress

            override fun setValue(view: PlayerNativeView, value: Float) {
                view.setMinimizeProgress(value)
            }
        }

    /** Thuộc tính spring cho cú kéo mở rộng video (ngược hiệu ứng thu nhỏ). */
    private val fullscreenDragProgressProperty =
        object : FloatPropertyCompat<PlayerNativeView>("fullscreenDragProgress") {
            override fun getValue(view: PlayerNativeView): Float = view.fullscreenDragProgress

            override fun setValue(view: PlayerNativeView, value: Float) {
                view.setFullscreenDragProgress(value)
            }
        }
    private var minimizeBaseCaptured = false
    private var minimizeBaseLeft = 0
    private var minimizeBaseTop = 0
    private var minimizeBaseWidth = 0
    private var minimizeBaseHeight = 0
    private var minimizeScreenWidth = 0
    private var minimizeScreenHeight = 0
    private var minimizeTargetCache: RectF? = null
    private var minimizeCornerRadiusLocal = 0f
    private val minimizeOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            if (minimizeCornerRadiusLocal <= 0f) {
                outline.setRect(0, 0, view.width, view.height)
            } else {
                outline.setRoundRect(
                    0,
                    0,
                    view.width,
                    view.height,
                    minimizeCornerRadiusLocal
                )
            }
        }
    }
    /**
     * Sheet chính dưới video: bo góc cả nền lẫn nội dung con để không còn cạnh
     * vuông lộ ra ở hai đầu mép trên (nền shape tự bo nhưng child thì không).
     */
    private val detailsSheetCornerRadiusPx = context.dp(24f).toFloat()
    private val detailsSheetOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(
                0,
                0,
                view.width.coerceAtLeast(1),
                view.height.coerceAtLeast(1),
                detailsSheetCornerRadiusPx
            )
        }
    }

    private val minimizeCornerRadiusPx = context.dp(MINIMIZE_CORNER_DP).toFloat()
    /** Ngưỡng lệch bán kính (px) mới dựng lại outline, tránh làm mỗi frame. */
    private val minimizeCornerStepPx = context.dp(1.5f).toFloat()
    /** Giá trị đích đã chốt, tránh tính lại mỗi frame. */
    private var minimizeTargetScale = 1f
    private var minimizeTargetX = 0f
    private var minimizeTargetY = 0f
    private var minimizeTargetReady = false

    /**
     * Trang xem (video + thông tin) được scale/translate như một khối, còn
     * [clipHost] cắt khung nhìn thu dần về đúng khung mini player.
     */
    private lateinit var pageRoot: FrameLayout
    private lateinit var clipHost: MinimizeClipLayout
    private lateinit var activityRoot: ViewGroup
    private var activityRootBackground: Drawable? = null

    /** Chữ ký nội dung dưới trình phát, tránh dựng lại khi dữ liệu không đổi. */
    private var detailsSignature: DetailsSignature? = null
    private var settingsSignature: String? = null
    private var boundAvatarKey: String? = null
    private var windowInsets: WindowInsetsCompat? = null
    /** Inset trên của thanh trạng thái; dùng để đẩy vùng video xuống dưới nó. */
    private var topInset = 0

    private data class DetailsSignature(
        val videoId: String,
        val description: String,
        val hideDescription: Boolean,
        val hideComments: Boolean,
        val hideRelated: Boolean,
        val relatedIds: List<String>,
        val favoriteIds: Set<String>,
        val watchLaterIds: Set<String>,
        val relatedProgress: List<Float>
    )

    private var commentsDialog: BottomSheetDialog? = null
    private var commentsAdapter: CommentSheetAdapter? = null
    private var settingsDialog: BottomSheetDialog? = null
    private var settingsSheetContainer: LinearLayout? = null
    private var mixDialog: BottomSheetDialog? = null
    private var mixSheetContainer: LinearLayout? = null
    private var mixSheetVideoId: String? = null
    private var descriptionDialog: BottomSheetDialog? = null
    private var descriptionSheetContent: View? = null
    private var commentsSheetCount: TextView? = null
    private var commentsSheetSortRow: LinearLayout? = null
    private var commentsSheetTopChip: TextView? = null
    private var commentsSheetNewestChip: TextView? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.activity_player, this, true)
        pageRoot = findViewById(R.id.player_page_root)
        clipHost = findViewById(R.id.player_clip_host)
        activityRoot = findViewById(R.id.player_activity_root)
        activityRootBackground = activityRoot.background
        videoContainer = findViewById(R.id.player_video_container)
        detailsSheet = findViewById(R.id.player_details_sheet)
        detailsSheet.outlineProvider = detailsSheetOutlineProvider
        detailsSheet.clipToOutline = true
        detailsHeader = findViewById(R.id.player_details_header)
        detailsScroll = findViewById(R.id.player_details_scroll)
        detailsContainer = findViewById(R.id.player_details_container)

        playerHud = PlayerHudView(context)
        videoContainer.addView(
            playerHud,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val info = LayoutInflater.from(context)
            .inflate(R.layout.view_player_info, detailsContainer, false)
        infoView = info
        infoTitle = info.findViewById(R.id.player_info_title)
        infoMeta = info.findViewById(R.id.player_info_meta)
        infoAvatar = info.findViewById(R.id.player_info_avatar)
        infoChannel = info.findViewById(R.id.player_info_channel)
        infoSubscribers = info.findViewById(R.id.player_info_subscribers)
        infoSubscribe = info.findViewById(R.id.player_info_subscribe)
        infoLikePill = info.findViewById(R.id.player_info_like)
        infoLikeIcon = info.findViewById(R.id.player_info_like_icon)
        infoLikeText = info.findViewById(R.id.player_info_like_text)
        infoDislike = info.findViewById(R.id.player_info_dislike)
        infoShare = info.findViewById(R.id.player_info_share)
        infoSavePill = info.findViewById(R.id.player_info_save)
        infoSaveIcon = info.findViewById(R.id.player_info_save_icon)
        infoSaveText = info.findViewById(R.id.player_info_save_text)
        infoBackgroundPill = info.findViewById(R.id.player_info_background)
        infoBackgroundIcon = info.findViewById(R.id.player_info_background_icon)
        infoTimerPill = info.findViewById(R.id.player_info_timer)
        infoTimerIcon = info.findViewById(R.id.player_info_timer_icon)
        infoTimerText = info.findViewById(R.id.player_info_timer_text)

        commentsCard = LayoutInflater.from(context)
            .inflate(R.layout.view_player_comments_preview, detailsContainer, false)
        commentsTitle = commentsCard.findViewById(R.id.player_comments_title)
        commentsViewAll = commentsCard.findViewById(R.id.player_comments_view_all)
        commentsStatus = commentsCard.findViewById(R.id.player_comments_status)
        commentsContainer = commentsCard.findViewById(R.id.player_comments_container)

        relatedHeader = LayoutInflater.from(context)
            .inflate(R.layout.view_player_related_header, detailsContainer, false)
        relatedContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        infoSubscribe.setOnClickListener { toggleSubscription() }
        infoDislike.setOnClickListener {
            val video = state.currentVideo ?: return@setOnClickListener
            if (state.isFavorite) onToggleFavorite(video)
        }
        infoShare.setOnClickListener {
            val video = state.currentVideo ?: return@setOnClickListener
            runCatching { shareVideo(context, video) }
        }
        infoLikePill.setOnClickListener {
            state.currentVideo?.let { onToggleFavorite(it) }
        }
        infoSavePill.setOnClickListener {
            state.currentVideo?.let { onToggleWatchLater(it) }
        }
        infoBackgroundPill.setOnClickListener { onToggleBackgroundPlay() }
        infoTimerPill.setOnClickListener { onShowSleepTimer() }
        infoAvatar.setOnClickListener { openCurrentChannel() }

        // Bỏ card giới thiệu trên trang chính: chạm vào tiêu đề video là mở sheet.
        infoTitle.setOnClickListener { showDescriptionSheet() }
        // Toàn bộ card bình luận (và nút "Xem tất cả") đều mở sheet bình luận.
        commentsCard.setOnClickListener { showCommentsSheet() }
        commentsViewAll.setOnClickListener { showCommentsSheet() }

        // Tay cầm của sheet chính và tiêu đề "Video liên quan": vuốt xuống vào fullscreen.
        detailsHeader.setOnClickListener { enterFullscreenFromSwipe() }
        attachSwipeDownToFullscreen(detailsHeader)
        attachSwipeDownToFullscreen(relatedHeader)

        playerHud.onTogglePlayPause = { onTogglePlayPause() }
        playerHud.onSeekTo = { onSeekTo(it) }
        playerHud.onForward10 = { onForward10() }
        playerHud.onRewind10 = { onRewind10() }
        playerHud.onToggleFullscreen = { onToggleFullscreen() }
        playerHud.onSkipSponsor = { onSkipSponsor() }
        playerHud.onUnskipSponsor = { onUnskipSponsor() }
        playerHud.onClose = { onClose() }
        playerHud.onMinimize = { minimize() }
        playerHud.onMinimizeBegin = { beginMinimizeDrag() }
        playerHud.onMinimizeProgress = { updateMinimizeDrag(it) }
        playerHud.onMinimizeEnd = { commit, velocity ->
            endMinimizeDrag(commit, velocity)
        }
        playerHud.onEnterPiP = { onEnterPiP() }
        playerHud.onPrevious = { onPrevious() }
        playerHud.onNext = { onNext() }
        playerHud.onRetry = { onRetry() }
        playerHud.onSetAutoPlayMode = { onSetAutoPlayMode(it) }
        playerHud.onToggleCaptions = { onToggleCaptions() }

        playerHud.onTracksChanged = { refreshSettingsSheet() }
        playerHud.onVideoAspectRatioChanged = { onPipParamsInvalidated() }
        playerHud.onOpenSettings = { showPlayerSettings() }
        playerHud.onOpenMix = { showMixSheet() }

        updateLayoutMetrics()
    }

    // ------------------------------------------------------------------
    // Dữ liệu từ ViewModel
    // ------------------------------------------------------------------

    fun bindPlayer(player: Player) = playerHud.bindPlayer(player)

    fun updatePlayerState(
        state: PurePlayerState,
        position: PlaybackPosition,
        plan: PlaybackPlan?,
        failure: PlaybackFailure?
    ) {
        this.state = state
        this.position = position
        this.plan = plan
        this.playbackFailure = failure
        renderPlayer()
        renderDetails()
        // Video tự chuyển khi sheet mix đang mở: cập nhật dòng "Đang phát".
        refreshMixSheet()
    }

    fun updatePosition(position: PlaybackPosition) {
        this.position = position
        playerHud.renderPosition(position)
    }

    fun updateQueue(
        queue: QueueUiState,
        favoriteIds: Set<String>,
        watchLaterIds: Set<String>,
        watchProgress: Map<String, Float>
    ) {
        this.queue = queue
        this.favoriteIds = favoriteIds
        this.watchLaterIds = watchLaterIds
        this.watchProgress = watchProgress
        renderPlayer()
        renderDetails()
        refreshDescriptionSheet()
    }

    fun updateDetails(details: VideoDetails?) {
        this.details = details
        renderDetails()
        refreshDescriptionSheet()
    }

    /** Đồng bộ trạng thái đăng ký cục bộ của kênh đang phát. */
    fun updateSubscriptions(channelIds: Set<String>) {
        this.subscribedChannelIds = channelIds
        // Chỉ đổi nút Đăng ký, không dựng lại toàn bộ phần thông tin.
        state.currentVideo?.let { bindInfo(it) }
        refreshDescriptionSheet()
    }

    fun updateComments(state: CommentsUiState) {
        this.comments = state.comments
        this.commentsLoading = state.loading
        this.commentsHasMore = state.hasMore
        this.commentsTotal = state.total
        this.commentsDisabled = state.disabled
        this.commentsError = state.error
        this.commentsSort = state.sort
        this.commentsCanSortNewest = state.canSortNewest
        this.commentReplies = state.replies
        this.replyLoading = state.replyLoading
        this.replyHasMore = state.replyHasMore
        this.replyError = state.replyError
        if (!state.loading) commentsLoadMoreRequested = false
        renderCommentsPreview()
        commentsAdapter?.submit(buildCommentSheetItems())
        bindCommentsSheetHeader()
    }

    /** Tỉ lệ khung hình thật của video, dùng cho PiP; null khi chưa biết. */
    val videoAspectRatio: Float?
        get() = playerHud.videoAspectRatio

    /** Đóng mọi sheet trước khi vào PiP để không còn dialog treo trên màn hình. */
    fun dismissSheets(restoreLayout: Boolean = true) {
        cancelFullscreenDrag(restoreLayout = restoreLayout)
        commentsDialog?.dismiss()
        settingsDialog?.dismiss()
        mixDialog?.dismiss()
        descriptionDialog?.dismiss()
    }

    fun onResume() = playerHud.onResume()

    fun release() {
        minimizeSpring?.cancel()
        minimizeSpring = null
        minimizeState = MinimizeState.IDLE
        cancelFullscreenDrag(restoreLayout = false)
        detailsSheet.alpha = 1f
        detailsSheet.translationY = 0f
        clipHost.clearClipRect()
        playerHud.release()
        dismissSheets(restoreLayout = false)
    }

    fun setPipMode(inPip: Boolean) {
        this.inPip = inPip
        if (inPip) cancelFullscreenDrag(restoreLayout = false)
        playerHud.setPipMode(inPip)
        // applyWindowInsets() đã tự tính lại bố cục, không gọi updateLayoutMetrics()
        // thêm lần nữa nếu không muốn requestLayout hai liên tiếp.
        windowInsets?.let { applyWindowInsets(it) } ?: updateLayoutMetrics()
    }

    // ------------------------------------------------------------------
    // Thu nhỏ / mở rộng trang xem (một cơ chế duy nhất)
    // ------------------------------------------------------------------

    /**
     * Thu nhỏ trang xem về đúng khung mini player.
     *
     * Đây là lối vào duy nhất cho nút chevron và phím/vuốt Back. Thao tác kéo
     * xuống dùng [beginMinimizeDrag]/[updateMinimizeDrag]/[endMinimizeDrag]
     * nhưng chạy trên đúng cùng phép biến đổi, nên cả ba lối vào chỉ khác nhau
     * ở chỗ tiến trình do ngón tay hay do spring cung cấp.
     */
    fun minimize() {
        when (minimizeState) {
            // Đang chạy về mini player: bỏ qua để không chồng animation.
            MinimizeState.MINIMIZING -> return
            // Đang mở rộng dở: huỷ rồi thu nhỏ tiếp từ đúng vị trí hiện tại.
            MinimizeState.EXPANDING, MinimizeState.DRAGGING -> cancelMinimizeAnimation()
            MinimizeState.IDLE -> Unit
        }
        beginMinimize()
        if (!minimizeBaseCaptured) {
            // Chưa đo được khung hình (Activity quá nhỏ/đang bị huỷ): đóng
            // luôn thay vì để trang kẹt ở trạng thái nửa vời.
            onClose()
            return
        }
        minimizeState = MinimizeState.MINIMIZING
        animateMinimizeTo(1f, 0f) { onClose() }
    }

    /** Người dùng bắt đầu kéo trang xuống. */
    fun beginMinimizeDrag() {
        if (minimizeState == MinimizeState.MINIMIZING) return
        cancelMinimizeAnimation()
        beginMinimize()
        if (!minimizeBaseCaptured) return
        minimizeState = MinimizeState.DRAGGING
        // Giữ nguyên tiến trình hiện tại nếu vừa huỷ một animation mở rộng;
        // frame kế tiếp của ngón tay sẽ cập nhật lại ngay.
    }

    /** Cập nhật khung hình theo ngón tay (0..1). */
    fun updateMinimizeDrag(progress: Float) {
        if (minimizeState != MinimizeState.DRAGGING) return
        setMinimizeProgress(progress)
    }

    /** Thả tay: chốt thu nhỏ hoặc trả trang về trạng thái đầy đủ. */
    fun endMinimizeDrag(commit: Boolean, velocity: Float) {
        if (minimizeState != MinimizeState.DRAGGING) return
        if (commit) {
            minimizeState = MinimizeState.MINIMIZING
            animateMinimizeTo(1f, velocity) { onClose() }
        } else {
            minimizeState = MinimizeState.EXPANDING
            animateMinimizeTo(0f, velocity, ::resetMinimizeTransform)
        }
    }

    /**
     * Mở trang từ mini player: chạy đúng phép biến đổi của [minimize] theo
     * chiều ngược lại, bằng cùng spring và cùng đích. Không có animation riêng
     * cho thao tác mở.
     *
     * Chỉ gọi khi trang được mở từ mini player (chạm vào mini player).
     */
    fun expandFromMiniPlayer() {
        if (minimizeState != MinimizeState.IDLE || minimizeProgress > 0f) return
        // View vừa được chuyển từ GONE sang VISIBLE nên có thể chưa được đo lại
        // (nhất là sau khi xoay máy lúc trình phát đang đóng). Luôn chờ preDraw:
        // lúc đó hình học đã mới, và vì preDraw chạy trước draw nên frame đầu
        // tiên đã ở đúng khung mini player, không lóe trang đầy đủ.
        viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    viewTreeObserver.removeOnPreDrawListener(this)
                    if (minimizeState == MinimizeState.IDLE &&
                        width > 0 && height > 0 && videoContainer.width > 0
                    ) {
                        startExpand()
                    }
                    return true
                }
            }
        )
    }

    private fun startExpand() {
        beginMinimize()
        if (!minimizeBaseCaptured) return
        // Vẽ đúng một frame ở khung mini player rồi mới chạy spring về trang
        // đầy đủ, tránh nháy trang đầy đủ trước khi thu về đích.
        minimizeState = MinimizeState.EXPANDING
        setMinimizeProgress(1f)
        postOnAnimation {
            if (minimizeState == MinimizeState.EXPANDING) {
                animateMinimizeTo(0f, 0f, ::resetMinimizeTransform)
            }
        }
    }

    /**
     * Bắt đầu hiệu ứng thu nhỏ: ẩn HUD, chốt hình học gốc và đích mini player.
     *
     * Vì trình phát là overlay trong cùng cửa sổ với trang nội dung, nền của
     * overlay được đặt trong suốt để chính các View thật của trang bên dưới lộ
     * ra khi khung video thu nhỏ — không cần ảnh chụp tĩnh nào.
     */
    private fun beginMinimize() {
        cancelMinimizeAnimation()
        // Áp dụng cho mọi lối vào: hiệu ứng chỉ còn khung hình.
        playerHud.hideControlsForMinimize()
        if (!minimizeBaseCaptured) captureMinimizeBase()
        if (!minimizeBaseCaptured) return
        minimizeTargetCache = resolveMinimizeTarget()
        cacheMinimizeTarget()
        // Cho HUD biết quãng đường thật của khung hình tới mini player để cử
        // chỉ kéo đi đúng 1:1 với ngón tay, không lao nhanh gấp nhiều lần.
        val target = minimizeTargetCache
        if (target != null) {
            playerHud.minimizeDragDistancePx =
                (target.top - minimizeBaseTop).coerceAtLeast(context.dp(160f).toFloat())
        }
        // Bo góc giống mini player trong suốt hiệu ứng.
        setMinimizeCorners(true)
        // Nền trong suốt: phần quanh khung video đang thu nhỏ chính là trang
        // nội dung thật bên dưới, nên không cần ảnh chụp và không bao giờ lệch.
        activityRoot.setBackgroundColor(Color.TRANSPARENT)
        detailsScroll.alpha = 1f
    }

    private fun cancelMinimizeAnimation() {
        minimizeSpring?.cancel()
        minimizeSpring = null
        minimizeState = MinimizeState.IDLE
    }

    /** Chốt sẵn các giá trị đích để mỗi frame chỉ còn phép nội suy. */
    private fun cacheMinimizeTarget() {
        minimizeTargetReady = false
        val target = minimizeTargetCache ?: return
        if (!minimizeBaseCaptured) return
        minimizeTargetScale = (target.width() / minimizeBaseWidth).coerceIn(0.02f, 1f)
        // Dịch cả trang sao cho khung video rơi đúng vào khung mini player.
        minimizeTargetX = target.left - minimizeBaseLeft * minimizeTargetScale
        minimizeTargetY = target.top - minimizeBaseTop * minimizeTargetScale
        minimizeTargetReady = true
    }

    private fun captureMinimizeBase() {
        pageRoot.translationX = 0f
        pageRoot.translationY = 0f
        pageRoot.scaleX = 1f
        pageRoot.scaleY = 1f
        pageRoot.pivotX = 0f
        pageRoot.pivotY = 0f
        clipHost.clearClipRect()
        // Khung video nằm trực tiếp trong pageRoot nên left/top đã là toạ độ
        // tương đối của cả trang.
        minimizeBaseLeft = videoContainer.left
        minimizeBaseTop = videoContainer.top
        minimizeBaseWidth = videoContainer.width
        minimizeBaseHeight = videoContainer.height
        minimizeScreenWidth = pageRoot.width
        minimizeScreenHeight = pageRoot.height
        minimizeBaseCaptured = minimizeBaseWidth > 0 && minimizeBaseHeight > 0 &&
            minimizeScreenWidth > 0 && minimizeScreenHeight > 0
    }

    private fun setMinimizeProgress(progress: Float) {
        if (!minimizeBaseCaptured) captureMinimizeBase()
        if (!minimizeBaseCaptured) return
        val fraction = progress.coerceIn(0f, 1f)
        minimizeProgress = fraction
        if (!minimizeTargetReady) {
            if (minimizeTargetCache == null) minimizeTargetCache = resolveMinimizeTarget()
            cacheMinimizeTarget()
        }
        // Cả trang xem di chuyển + thu nhỏ như một khối duy nhất.
        val translationX = minimizeTargetX * fraction
        val translationY = minimizeTargetY * fraction
        val scale = 1f + (minimizeTargetScale - 1f) * fraction
        pageRoot.translationX = translationX
        pageRoot.translationY = translationY
        pageRoot.scaleX = scale
        pageRoot.scaleY = scale
        // Bo góc to dần theo tiến trình: bán kính nhìn thấy là
        // minimizeCornerRadiusPx * fraction, nên frame cuối trùng đúng bán
        // kính 8dp của mini player và không bị "bật" góc khi bàn giao.
        // Chỉ gọi invalidateOutline khi bán kính lệch đủ nhiều — dựng lại clip
        // mỗi frame chính là thứ làm hiệu ứng bị giật trước đây.
        val cornerLocal = minimizeCornerRadiusPx * fraction / scale
        if (abs(cornerLocal - minimizeCornerRadiusLocal) > minimizeCornerStepPx) {
            minimizeCornerRadiusLocal = cornerLocal
            videoContainer.invalidateOutline()
        }
        // Khung nhìn thu dần về đúng khung mini player: phần thông tin bên
        // dưới video bị cắt mất dần, lộ ra trang phía sau. Cắt bằng rect nên
        // không phải fade cả ScrollView hay blend một bitmap toàn màn hình.
        val target = minimizeTargetCache
        if (fraction <= 0f || target == null) {
            clipHost.clearClipRect()
        } else {
            // Khung nhìn là giao của "cửa sổ đang thu về mini player" với biên
            // thật của trang đã scale. Kẹp cả bốn mép để cửa sổ không bao giờ
            // vượt ra ngoài trang, trong khi vẫn cắt dần phần thông tin bên
            // dưới video.
            val pageLeft = translationX
            val pageTop = translationY
            val pageRight = minimizeScreenWidth * scale + translationX
            val pageBottom = minimizeScreenHeight * scale + translationY
            val windowLeft = target.left * fraction
            val windowTop = target.top * fraction
            val windowRight =
                minimizeScreenWidth + (target.right - minimizeScreenWidth) * fraction
            val windowBottom =
                minimizeScreenHeight + (target.bottom - minimizeScreenHeight) * fraction
            clipHost.setClipRect(
                windowLeft.coerceAtLeast(pageLeft),
                windowTop.coerceAtLeast(pageTop),
                windowRight.coerceAtMost(pageRight),
                windowBottom.coerceAtMost(pageBottom)
            )
        }
        // Không tắt nền/bo góc khi fraction == 0 ở đây. SpringAnimation luôn
        // phát lại đúng giá trị bắt đầu ở frame đầu tiên (xem
        // DynamicAnimation.doAnimationFrame), nên lần thu nhỏ bằng Back/chevron
        // sẽ gọi setMinimizeProgress(0f) một lần trước khi tiến trình tăng.
        // Nếu tắt ở đây, khung hình mất bo góc trong frame đầu. Việc dọn dẹp
        // chỉ thuộc về resetMinimizeTransform() (khi huỷ/trả về trang đầy đủ).
    }

    /**
     * Chạy nốt phần còn lại bằng spring animation của AndroidX DynamicAnimation.
     *
     * [startVelocity] là vận tốc tiến trình (1/s) lúc thả tay. Nhờ nối tiếp
     * đúng vận tốc này, khung hình không bị khựng lại rồi mới chạy tiếp như
     * kiểu ValueAnimator với đường cong cố định trước đây.
     */
    private fun animateMinimizeTo(
        target: Float,
        startVelocity: Float,
        onEnd: () -> Unit
    ) {
        minimizeSpring?.cancel()
        val distance = target - minimizeProgress
        // Đã nằm đúng đích (hoặc đã bị kẹp ở biên) mà vận tốc còn đẩy ra
        // ngoài dải 0..1 thì kết thúc luôn. Nếu vẫn chạy spring, giá trị sẽ
        // vượt biên, bị kẹp hình nên khung hình đứng yên một lúc rồi mới đóng
        // — đúng kiểu "khựng" ở cuối thao tác.
        val atTarget = abs(distance) < 0.001f
        val pushedOut = (target <= 0f && startVelocity <= 0f) ||
            (target >= 1f && startVelocity >= 0f)
        if (atTarget && (pushedOut || abs(startVelocity) < 0.001f)) {
            setMinimizeProgress(target)
            onEnd()
            return
        }
        // Giới hạn vận tốc mở màn theo quãng đường còn lại để spring không
        // lao quá đích (bị kẹp hình) rồi phải chờ nó quay lại.
        val omega = sqrt(MINIMIZE_SPRING_STIFFNESS)
        val velocity = startVelocity.coerceIn(
            -omega * abs(distance),
            omega * abs(distance)
        )
        val animation = SpringAnimation(this, minimizeProgressProperty, target)
        // Dùng lời gọi hàm tường minh: các setter của SpringForce trả về
        // chính nó (fluent) nên không phải lúc nào cũng dùng được cú pháp
        // thuộc tính của Kotlin.
        animation.setSpring(
            SpringForce(target)
                .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY)
                .setStiffness(MINIMIZE_SPRING_STIFFNESS)
        )
        // Tiến trình chỉ có 0..1 nên ngưỡng "đủ nhỏ để dừng" phải rất bé;
        // mặc định của DynamicAnimation là 1px sẽ khiến spring dừng ngay.
        animation.setMinimumVisibleChange(0.001f)
        animation.setStartValue(minimizeProgress)
        animation.setStartVelocity(velocity)
        animation.addEndListener { ended, canceled, _, _ ->
            if (ended === minimizeSpring) minimizeSpring = null
            // Huỷ (do thao tác mới) không kích hoạt onEnd, tránh đóng
            // trang xem ngoài ý muốn.
            if (!canceled) {
                // Chốt đúng đích để khung hình không lệch vài phần nghìn.
                setMinimizeProgress(target)
                // Vẽ xong frame cuối (khung hình đã nằm đúng chỗ mini
                // player) rồi mới đóng trang, tránh cửa sổ biến mất trước
                // khi kịp vẽ.
                postOnAnimation {
                    if (minimizeSpring == null) onEnd()
                }
            }
        }
        minimizeSpring = animation
        animation.start()
    }

    /**
     * Trả trang về trạng thái đầy đủ (huỷ hiệu ứng hoặc dọn dẹp sau khi thu
     * nhỏ xong). Công khai để chủ sở hữu overlay gọi khi đóng trình phát, vì
     * view không còn bị huỷ theo Activity như trước.
     */
    fun resetMinimizeTransform() {
        minimizeProgress = 0f
        minimizeTargetCache = null
        minimizeTargetReady = false
        minimizeState = MinimizeState.IDLE
        // Huỷ giữa đường (thả tay chưa đủ xa hoặc huỷ cử chỉ Back) thì trả
        // điều khiển về như trước khi bắt đầu thu nhỏ.
        playerHud.showControlsAfterMinimizeCancel()
        pageRoot.translationX = 0f
        pageRoot.translationY = 0f
        pageRoot.scaleX = 1f
        pageRoot.scaleY = 1f
        clipHost.clearClipRect()
        detailsScroll.alpha = 1f
        setMinimizeCorners(false)
        activityRoot.background = activityRootBackground
        minimizeBaseCaptured = false
    }

    /** Bo góc khung hình trong lúc thu nhỏ để khớp thẻ mini player. */
    private fun setMinimizeCorners(enabled: Boolean) {
        if (enabled) {
            // Bắt đầu từ góc vuông; bán kính tăng dần theo tiến trình kéo.
            minimizeCornerRadiusLocal = 0f
            videoContainer.outlineProvider = minimizeOutlineProvider
            videoContainer.clipToOutline = true
            videoContainer.invalidateOutline()
        } else {
            minimizeCornerRadiusLocal = 0f
            videoContainer.clipToOutline = false
            videoContainer.outlineProvider = ViewOutlineProvider.BACKGROUND
            videoContainer.invalidateOutline()
        }
    }

    /** Đích thu nhỏ: khung video mini player, hoặc vị trí ước lượng nếu chưa có. */
    private fun resolveMinimizeTarget(): RectF {
        // Toạ độ của overlay trong cửa sổ; mọi phép biến đổi chạy trong hệ toạ
        // độ của overlay nên phải quy đổi đích từ toạ độ màn hình về đây.
        val location = IntArray(2)
        getLocationOnScreen(location)
        minimizeTargetProvider?.invoke()?.let { rect ->
            if (rect.width() > 0 && rect.height() > 0) {
                return RectF(
                    (rect.left - location[0]).toFloat(),
                    (rect.top - location[1]).toFloat(),
                    (rect.right - location[0]).toFloat(),
                    (rect.bottom - location[1]).toFloat()
                )
            }
        }
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        fun dp(value: Float) = value * density
        val metrics = resources.displayMetrics
        val widthDp = metrics.widthPixels / density
        val compact = widthDp < 600f
        val bars = windowInsets?.getInsets(
            WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout()
        )
        val bottomInset = bars?.bottom ?: 0
        // Mini player nằm trên thanh điều hướng ở bố cục compact, còn bố cục
        // rộng đặt nó sát đáy sau rail/drawer bên trái.
        val bottomMargin = if (compact) {
            dp(64f) + dp(1f) + dp(6f) + dp(3f) + dp(5f)
        } else {
            dp(6f) + dp(3f) + dp(5f)
        }
        val sideNavWidth = when {
            compact -> 0f
            widthDp < 840f -> dp(84f)
            else -> dp(248f)
        }
        val width = dp(86f)
        val height = dp(48f)
        val left = (bars?.left ?: 0) + sideNavWidth + dp(18f) - location[0]
        val bottom = metrics.heightPixels - bottomInset - bottomMargin - location[1]
        return RectF(left, bottom - height, left + width, bottom)
    }

    /**
     * Áp inset hệ thống (thanh trạng thái/điều hướng + tai thỏ) cho HUD và nội
     * dung cuộn. Ở chế độ thường, vùng video được đẩy xuống dưới thanh trạng thái
     * để không bị che; fullscreen/PiP mới tràn viền.
     */
    fun applyWindowInsets(insets: WindowInsetsCompat) {
        windowInsets = insets
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        // Cập nhật lại kích thước trước: vùng video được đẩy xuống dưới thanh
        // trạng thái (xem updateLayoutMetrics) nên cần biết inset mới.
        topInset = bars.top
        updateLayoutMetrics()
        // Dùng kích thước cửa sổ từ resources thay vì width/height của view:
        // trong lúc xoay, view vẫn còn kích thước cũ nên tính inset bằng số đo
        // cũ sẽ làm nội dung và video chồng lên nhau một nhịp.
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val twoColumn = isTwoColumnLayout(screenWidth)
        // Ở bố cục 2 cột, video đã nằm bên trái nên nội dung bên phải không cần
        // chừa inset trái; nhưng cột phải bắt đầu từ đỉnh màn hình nên phải
        // chừa thanh trạng thái, nếu không tiêu đề/thông tin sẽ nằm dưới nó.
        // Inset trên đã được tính vào topMargin của sheet ở updateLayoutMetrics;
        // phần nội dung chỉ cần chừa lề hai bên và đáy.
        detailsScroll.setPadding(
            if (twoColumn) 0 else bars.left,
            0,
            bars.right,
            bars.bottom
        )
        // Đáy HUD chỉ trùng đáy màn hình khi fullscreen/PiP hoặc khi vùng video
        // cao hơn màn hình (landscape không fullscreen). Lúc đó mới cần chừa
        // thanh điều hướng; ở portrait HUD kết thúc ngay dưới video.
        val videoWidth = if (twoColumn) {
            (screenWidth * 0.6f).toInt()
        } else {
            screenWidth
        }
        val hudAtScreenBottom = state.isFullscreen || inPip ||
            (videoWidth * 9 / 16) >= screenHeight
        val hudBottom = if (hudAtScreenBottom) bars.bottom else 0
        // Video đã được đẩy xuống dưới thanh trạng thái ở chế độ thường nên HUD
        // không cần dịch thêm lần nữa; chỉ fullscreen/PiP mới dùng inset trực tiếp.
        val hudTop = if (state.isFullscreen || inPip) bars.top else 0
        val hudRight = if (twoColumn) 0 else bars.right
        playerHud.applyWindowInsets(bars.left, hudTop, hudRight, hudBottom)
    }

    private fun isTwoColumnLayout(widthPx: Int): Boolean {
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        return !state.isFullscreen && !inPip && widthPx / density >= 600f
    }

    /**
     * Gọi khi cửa sổ đổi cấu hình (xoay màn hình, đổi kích thước cửa sổ).
     * Xoay màn hình không tạo lại Activity nên phải tính lại bố cục và áp lại
     * inset; nếu không, các phần tử giữ kích thước của hướng cũ và đè lên nhau.
     */
    fun onWindowConfigurationChanged() {
        // Xoay/đổi cỡ cửa sổ làm sai toàn bộ hình học đã chốt của cú kéo.
        cancelFullscreenDrag(restoreLayout = false)
        val insets = ViewCompat.getRootWindowInsets(this) ?: windowInsets
        if (insets != null) {
            // applyWindowInsets() đã tự tính lại bố cục theo inset hiện tại.
            applyWindowInsets(insets)
        } else {
            updateLayoutMetrics()
        }
        ViewCompat.requestApplyInsets(this)
    }

    fun updateLayoutMetrics() {
        fullscreen = state.isFullscreen
        // Fullscreen/PiP có thể đến từ HUD hoặc hệ thống giữa lúc đang kéo:
        // huỷ cử chỉ trước khi ghi lại LayoutParams cho trạng thái mới.
        if ((fullscreen || inPip) && fullscreenDragActive) {
            cancelFullscreenDrag(restoreLayout = false)
        }
        // Khi vào fullscreen bằng cú kéo, sheet bị làm mờ dần theo ngón tay.
        // Phải trả lại alpha/độ dời ở MỌI lần tính lại bố cục, nếu không lúc
        // thoát fullscreen sheet hiện ra nhưng vẫn trong suốt (card trắng).
        detailsSheet.alpha = 1f
        detailsSheet.translationY = 0f
        if (!fullscreenDragActive) fullscreenDragProgress = 0f
        // Kích thước lấy từ resources (cửa sổ hiện tại) thay vì width/height của
        // view: khi xoay, onConfigurationChanged chạy trước khi view được đo lại.
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        twoColumn = isTwoColumnLayout(screenWidth)
        // Trình phát nhúng có khung thấp: báo HUD dùng bố cục gọn để các cụm
        // điều khiển không chồng lên nhau.
        playerHud.setCompactMode(!fullscreen && !inPip)

        detailsHeader.visibility = if (!fullscreen && !inPip && twoColumn) View.GONE else View.VISIBLE
        if (!fullscreen && !inPip) {
            if (twoColumn) {
                detailsContainer.setPadding(
                    context.dp(20f),
                    context.dp(16f),
                    context.dp(20f),
                    context.dp(32f)
                )
            } else {
                detailsContainer.setPadding(
                    context.dp(16f),
                    context.dp(4f),
                    context.dp(16f),
                    context.dp(32f)
                )
            }
        }
        if (fullscreen || inPip) {
            videoContainer.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            detailsSheet.visibility = View.GONE
            val lp = detailsSheet.layoutParams as FrameLayout.LayoutParams
            lp.leftMargin = 0
            lp.topMargin = 0
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            detailsSheet.layoutParams = lp
        } else if (twoColumn) {
            // Tablet/landscape: video bên trái, sheet thông tin + bình luận +
            // liên quan cuộn độc lập bên phải thay vì đẩy xuống dưới video.
            val contentTop = topInset
            val videoWidth = (screenWidth * 0.6f).toInt().coerceAtLeast(1)
            val videoHeight = (videoWidth * 9 / 16)
                .coerceAtMost((screenHeight - contentTop).coerceAtLeast(1))
            videoContainer.layoutParams = FrameLayout.LayoutParams(videoWidth, videoHeight)
                .apply { topMargin = contentTop }
            detailsSheet.visibility = View.VISIBLE
            val lp = detailsSheet.layoutParams as FrameLayout.LayoutParams
            lp.leftMargin = videoWidth
            lp.topMargin = contentTop
            lp.width = (screenWidth - videoWidth).coerceAtLeast(1)
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            detailsSheet.layoutParams = lp
        } else {
            // Điện thoại: sheet bắt đầu ngay dưới đáy video, bo góc hai bên,
            // không để lại khoảng trống giữa khung hình và nội dung.
            val contentTop = topInset
            val videoHeight = (screenWidth * 9 / 16)
                .coerceAtMost((screenHeight - contentTop).coerceAtLeast(1))
            videoContainer.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                videoHeight
            ).apply { topMargin = contentTop }
            detailsSheet.visibility = View.VISIBLE
            val lp = detailsSheet.layoutParams as FrameLayout.LayoutParams
            lp.leftMargin = 0
            lp.topMargin = contentTop + videoHeight
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            detailsSheet.layoutParams = lp
        }
        updateOpenSheetsLayout()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Chiều cao cũng phải tính lại: thanh trạng thái/điều hướng hoặc bàn
        // phím có thể chỉ đổi chiều cao mà không đổi chiều rộng.
        if (w != oldw || h != oldh) updateLayoutMetrics()
    }

    private fun renderPlayer() {
        val autoPlayMode = when (queue.repeatMode) {
            RepeatMode.ONE -> AutoPlayMode.ONE
            RepeatMode.ALL -> AutoPlayMode.ALL
            RepeatMode.OFF ->
                if (state.isAutoNextEnabled) AutoPlayMode.NEXT else AutoPlayMode.OFF
        }
        // Nút danh sách dùng chung cho mix và playlist: cả hai đều là "phát
        // tiếp theo cái gì", chỉ khác nguồn dữ liệu.
        val hasQueueList = queue.mix?.videos?.isNotEmpty() == true ||
            queue.playlist?.videos?.isNotEmpty() == true
        playerHud.render(
            state = state,
            autoPlayMode = autoPlayMode,
            hasMix = hasQueueList,
            isLive = plan?.isLive == true,
            failure = playbackFailure
        )
        playerHud.renderPosition(position)
        if (fullscreen != state.isFullscreen) {
            // applyWindowInsets() đã gọi updateLayoutMetrics(); chỉ gọi riêng
            // updateLayoutMetrics() khi chưa có inset để tránh requestLayout đôi.
            // Inset đáy của HUD cũng phụ thuộc trạng thái toàn màn hình.
            windowInsets?.let { applyWindowInsets(it) } ?: updateLayoutMetrics()
        }
    }

    // ------------------------------------------------------------------
    // Nội dung dưới trình phát
    // ------------------------------------------------------------------

    private fun renderDetails() {
        val video = state.currentVideo ?: run {
            detailsContainer.removeAllViews()
            detailsSignature = null
            return
        }

        // Phần thông tin (tiêu đề, lượt xem, pill) luôn cập nhật tại chỗ.
        bindInfo(video)

        // Chỉ dựng lại cấu trúc khi nội dung thực sự thay đổi. Nhờ vậy các tick
        // lưu lịch sử mỗi 5 giây không còn xoá/khôi phục toàn bộ trang xem.
        val signature = computeDetailsSignature(video)
        if (signature != detailsSignature) {
            detailsSignature = signature
            buildDetails(video)
        }
        refreshSettingsSheet()
    }

    private fun computeDetailsSignature(video: VideoItem): DetailsSignature {
        val related = relatedVideos(video)
        return DetailsSignature(
            videoId = video.id,
            description = details?.description?.takeIf { it.isNotBlank() }
                ?: video.description.takeIf { it.isNotBlank() }.orEmpty(),
            hideDescription = state.hideDescription,
            hideComments = state.hideComments,
            hideRelated = state.hideRelated,
            relatedIds = related.map { it.id },
            favoriteIds = favoriteIds,
            watchLaterIds = watchLaterIds,
            relatedProgress = related.map { watchProgress[it.id] ?: 0f }
        )
    }

    private fun bindInfo(video: VideoItem) {
        infoTitle.text = video.title
        val views = details?.viewCount
            ?.takeIf { it > 0 }
            ?.let { getString(R.string.player_views_count, formatCompactCount(it)) }
            ?: video.viewCount
        val published = details?.publishedTime?.takeIf { it.isNotBlank() }
            ?: video.publishedTime
        val meta = listOf(views, published)
            .filter { it.isNotBlank() }
            .joinToString(" • ")
        infoMeta.text = meta
        infoMeta.visibility = if (meta.isBlank()) View.GONE else View.VISIBLE

        // Metadata `next` có thể thiếu avatar (hoặc chưa tải xong) dù thẻ video
        // đã có URL từ feed, nên dùng URL của video làm dự phòng.
        val avatarUrl = details?.channelAvatarUrl?.takeIf { it.isNotBlank() }
            ?: video.channelAvatarUrl
        val avatarKey = "${video.channelTitle}|$avatarUrl"
        if (avatarKey != boundAvatarKey) {
            boundAvatarKey = avatarKey
            infoAvatar.bind(video.channelTitle, avatarUrl)
        }
        infoChannel.text = video.channelTitle
        val subscriberCount = details?.subscriberCount ?: 0
        if (subscriberCount > 0) {
            infoSubscribers.visibility = View.VISIBLE
            infoSubscribers.text = getString(
                R.string.player_subscribers_count,
                formatCompactCount(subscriberCount)
            )
        } else {
            infoSubscribers.visibility = View.GONE
        }

        // Đăng ký là cục bộ: nút đổi nhãn/màu ngay, không mở trang kênh YouTube.
        bindSubscribeButton(infoSubscribe, video)

        val likeLabel = formatCompactCount(details?.likeCount ?: 0)
            .ifBlank { getString(R.string.player_like) }
        infoLikeText.text = likeLabel
        infoLikeIcon.setImageResource(R.drawable.ic_thumb_up)
        updatePill(infoLikePill, state.isFavorite)
        // "Bỏ thích" chỉ có ý nghĩa khi video đã được thích; không mở YouTube.
        infoDislike.visibility = if (state.isFavorite) View.VISIBLE else View.GONE

        infoSaveIcon.setImageResource(
            if (state.isWatchLater) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border
        )
        infoSaveText.text = getString(
            if (state.isWatchLater) R.string.player_saved else R.string.player_save
        )
        updatePill(infoSavePill, state.isWatchLater)

        updatePill(infoBackgroundPill, state.isBackgroundPlayEnabled)

        when {
            state.sleepTimerRemainingSeconds > 0 -> {
                infoTimerText.text = getString(
                    R.string.player_sleep_remaining,
                    PlaybackPosition.formatTime(state.sleepTimerRemainingSeconds.toLong())
                )
            }

            state.sleepTimerMinutes > 0 -> {
                infoTimerText.text = getString(
                    R.string.player_sleep_minutes,
                    state.sleepTimerMinutes
                )
            }

            else -> infoTimerText.setText(R.string.settings_sleep_title)
        }
        updatePill(
            infoTimerPill,
            state.sleepTimerMinutes > 0 || state.sleepTimerRemainingSeconds > 0
        )
    }

    private fun bindSubscribeButton(button: MaterialButton, video: VideoItem) {
        // Thẻ video có thể thiếu channelId; dùng channelId của metadata trang xem.
        val channelId = video.channelId.ifBlank { details?.channelId.orEmpty() }
        val subscribed = channelId.isNotBlank() && subscribedChannelIds.contains(channelId)
        button.setText(
            if (subscribed) R.string.player_subscribed else R.string.player_subscribe
        )
        button.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                context,
                if (subscribed) R.color.pure_surface_variant else R.color.pure_on_surface
            )
        )
        button.setTextColor(
            ContextCompat.getColor(
                context,
                if (subscribed) R.color.pure_on_surface else R.color.pure_surface
            )
        )
    }

    private fun updatePill(pill: View, active: Boolean) {
        pill.setBackgroundResource(
            if (active) R.drawable.bg_player_pill_active else R.drawable.bg_player_pill
        )
        val color = ContextCompat.getColor(
            context,
            if (active) R.color.pure_surface else R.color.pure_on_surface
        )
        val tint = ColorStateList.valueOf(color)
        fun apply(view: View) {
            when (view) {
                is ImageView -> ImageViewCompat.setImageTintList(view, tint)
                is TextView -> {
                    view.setTextColor(color)
                    view.compoundDrawableTintList = tint
                }
            }
            // Đi sâu hết cây để pill hoạt động đúng cả khi cấu trúc được lồng thêm.
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) apply(view.getChildAt(index))
            }
        }
        apply(pill)
    }

    private fun buildDetails(video: VideoItem) {
        detailsContainer.removeAllViews()
        detailsContainer.addView(infoView, matchWrap(top = 0))

        // Trang chính chỉ còn thông tin, bình luận và video liên quan.
        // Mô tả được mở riêng bằng sheet khi chạm vào tiêu đề.
        if (!state.hideComments) {
            renderCommentsPreview()
            detailsContainer.addView(commentsCard, matchWrap(top = 12))
        }

        val related = relatedVideos(video)
        if (!state.hideRelated && related.isNotEmpty()) {
            detailsContainer.addView(relatedHeader, matchWrap(top = 8))
            relatedContainer.removeAllViews()
            related.forEach { relatedVideo ->
                val card = CompactVideoCardView(context)
                card.bind(
                    video = relatedVideo,
                    isFavorite = favoriteIds.contains(relatedVideo.id),
                    isWatchLater = watchLaterIds.contains(relatedVideo.id),
                    progressFraction = watchProgress[relatedVideo.id] ?: 0f,
                    actions = videoCardActions(
                        onClick = { onSelectVideo(it) },
                        onToggleFavorite = onToggleFavorite,
                        onToggleWatchLater = onToggleWatchLater,
                        onShare = { shareVideo(context, it) },
                        currentVideoId = video.id,
                        onPlayNext = onPlayNext,
                        onAddToQueue = onAddToQueue
                    )
                )
                relatedContainer.addView(card, matchWrap(top = 4))
            }
            detailsContainer.addView(relatedContainer, matchWrap(top = 0))
        }
    }

    private fun relatedVideos(video: VideoItem): List<VideoItem> {
        val mixIds = queue.mix?.videos.orEmpty().mapTo(mutableSetOf()) { it.id }
        return queue.related.filter { it.id != video.id && it.id !in mixIds }
    }

    private fun renderCommentsPreview() {
        val count = if (commentsTotal > 0) commentsTotal else comments.size
        val newest = commentsSort == CommentSort.NEWEST
        commentsTitle.text = when {
            count > 0 && newest -> getString(
                R.string.player_comments_newest_count,
                formatCompactCount(count.toLong())
            )

            count > 0 -> getString(
                R.string.player_comments_count,
                formatCompactCount(count.toLong())
            )

            newest -> getString(R.string.player_comments_newest)
            else -> getString(R.string.player_comments)
        }

        commentsContainer.removeAllViews()
        when {
            commentsDisabled -> {
                commentsStatus.visibility = View.VISIBLE
                commentsStatus.setText(R.string.player_comments_disabled)
            }

            commentsError != null && comments.isEmpty() -> {
                commentsStatus.visibility = View.VISIBLE
                commentsStatus.text = commentsError?.userMessage(context)
            }

            commentsLoading && comments.isEmpty() -> {
                commentsStatus.visibility = View.VISIBLE
                commentsStatus.setText(R.string.player_comments_loading)
            }

            comments.isEmpty() -> {
                commentsStatus.visibility = View.VISIBLE
                commentsStatus.setText(R.string.player_comments_empty)
            }

            else -> {
                commentsStatus.visibility = View.GONE
                comments.take(3).forEach { comment ->
                    commentsContainer.addView(createCommentRow(comment))
                }
            }
        }
    }

    private fun createCommentRow(comment: CommentItem): View {
        val row = LayoutInflater.from(context)
            .inflate(R.layout.view_player_comment_row, commentsContainer, false)
        // Preview chỉ để đọc nhanh; phản hồi được mở trong sheet.
        bindCommentRow(row, comment, compact = true)
        return row
    }

    // ------------------------------------------------------------------
    // Sheet bình luận
    // ------------------------------------------------------------------

    private fun showCommentsSheet() {
        if (commentsDialog?.isShowing == true) return
        val sheet = createSheet()
        val content = LayoutInflater.from(context)
            .inflate(R.layout.view_player_comments_sheet, null, false)
        val recycler = content.findViewById<RecyclerView>(R.id.comment_sheet_list).apply {
            layoutManager = LinearLayoutManager(context)
            itemAnimator = null
        }
        content.findViewById<View>(R.id.comment_sheet_close)
            .setOnClickListener { sheet.dismiss() }

        // Header và hàng sắp xếp nằm ngoài RecyclerView nên đứng yên khi cuộn,
        // đồng thời không bị dựng lại mỗi lần đổi thứ tự bình luận.
        val sortRow = content.findViewById<LinearLayout>(R.id.comment_sheet_sort)
        commentsSheetCount = content.findViewById(R.id.comment_sheet_count)
        commentsSheetSortRow = sortRow
        val topChip = createSortChip(
            sortRow,
            R.string.player_comments_top,
            CommentSort.TOP
        )
        val newestChip = createSortChip(
            sortRow,
            R.string.player_comments_newest_sort,
            CommentSort.NEWEST
        )
        commentsSheetTopChip = topChip
        commentsSheetNewestChip = newestChip
        sortRow.addView(topChip)
        sortRow.addView(newestChip)

        val adapter = CommentSheetAdapter(onAction = ::handleCommentAction)
        recycler.adapter = adapter
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val layoutManager = rv.layoutManager as? LinearLayoutManager ?: return
                val last = layoutManager.findLastVisibleItemPosition()
                if (last >= rv.adapter?.itemCount?.minus(3) ?: 0) {
                    requestMoreComments()
                }
            }
        })
        sheet.setContentView(content)
        applySheetCorners(content)
        configureSheet(sheet)
        commentsDialog = sheet
        commentsAdapter = adapter
        commentsLoadMoreRequested = false
        sheet.setOnDismissListener {
            commentsDialog = null
            commentsAdapter = null
            commentsSheetCount = null
            commentsSheetSortRow = null
            commentsSheetTopChip = null
            commentsSheetNewestChip = null
            commentsLoadMoreRequested = false
        }
        bindCommentsSheetHeader()
        adapter.submit(buildCommentSheetItems())
        sheet.show()
    }

    /** Nền trong suốt để lộ bo góc của nội dung sheet (không bị nền dialog che). */
    private fun applySheetCorners(content: View) {
        (content.parent as? View)?.background = ColorDrawable(Color.TRANSPARENT)
    }

    /**
     * Chiều cao của cửa sổ chứa sheet. Ở bố cục 2 cột, cửa sổ được dịch xuống
     * dưới thanh trạng thái nên không còn chứa vùng đó nữa.
     */
    private fun sheetWindowHeightPx(): Int =
        if (twoColumn) {
            (resources.displayMetrics.heightPixels - topInset).coerceAtLeast(1)
        } else {
            resources.displayMetrics.heightPixels
        }

    /** Đỉnh sheet trong toạ độ của cửa sổ sheet (đã dịch ở bố cục 2 cột). */
    private fun sheetTopOffsetPx(): Int =
        if (twoColumn) 0 else topInset + embeddedVideoHeightPx()

    /** Tạo sheet có cấu hình cửa sổ trước lúc hiện để tránh giật vị trí ở chế độ 2 cột. */
    private fun createSheet(): BottomSheetDialog =
        AnchoredBottomSheetDialog(context) { dialog ->
            configureSheetWindow(dialog)
        }

    /**
     * Bố cục 2 cột: video nằm bên trái, nên sheet chỉ chiếm cột nội dung bên
     * phải thay vì phủ toàn màn và che khung hình. Áp dụng ngay trong
     * [AnchoredBottomSheetDialog.onCreate] và [AnchoredBottomSheetDialog.onStart]
     * trước khi WindowManager vẽ frame đầu tiên để tránh hiệu ứng sheet nhảy từ
     * trái qua phải.
     */
    private fun configureSheetWindow(sheet: BottomSheetDialog) {
        val window = sheet.window ?: return
        // Theme app đặt windowBackground đặc; phải thay bằng trong suốt thì bo
        // góc của nội dung mới lộ ra (nền cửa sổ vuông sẽ che các góc lại).
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val lp = window.attributes
        if (!twoColumn) {
            lp.gravity = Gravity.FILL
            lp.x = 0
            lp.y = 0
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            window.attributes = lp
            return
        }
        val metrics = resources.displayMetrics
        val videoWidth = (metrics.widthPixels * 0.6f).toInt().coerceAtLeast(1)
        val contentWidth = (metrics.widthPixels - videoWidth).coerceAtLeast(1)
        val windowHeight = sheetWindowHeightPx()
        val windowLoc = IntArray(2)
        if (isAttachedToWindow) {
            getLocationOnScreen(windowLoc)
        }
        lp.gravity = Gravity.TOP or Gravity.LEFT
        lp.width = contentWidth
        lp.height = windowHeight
        lp.x = windowLoc[0] + videoWidth
        lp.y = windowLoc[1] + topInset
        window.attributes = lp
    }

    /**
     * Mọi sheet trong player dùng chung một chiều cao: từ mép dưới khung video
     * tới đáy màn hình (ở bố cục 2 cột là toàn bộ cột nội dung). Ở
     * [STATE_EXPANDED] với `expandedOffset = mép dưới video`, kéo lên bị kẹp tại
     * chính vị trí đó, còn kéo xuống quá ngưỡng thì ẩn hẳn.
     */
    private fun configureSheet(sheet: BottomSheetDialog) {
        applySheetBehaviorMetrics(sheet)
        sheet.setOnShowListener {
            configureSheetWindow(sheet)
            sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun applySheetBehaviorMetrics(sheet: BottomSheetDialog) {
        val anchor = sheetTopOffsetPx()
        sheet.behavior.apply {
            isHideable = true
            skipCollapsed = true
            isFitToContents = false
            // Ngưỡng hide nhỏ: kéo xuống đủ xa thì ẩn, chạm nhẹ thì không.
            peekHeight = anchor.coerceAtMost(context.dp(120f))
                .coerceAtLeast(context.dp(80f))
            expandedOffset = anchor
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun updateOpenSheetsLayout() {
        listOfNotNull(commentsDialog, descriptionDialog, settingsDialog, mixDialog)
            .filter { it.isShowing }
            .forEach { dialog ->
                configureSheetWindow(dialog)
                applySheetBehaviorMetrics(dialog)
            }
    }

    private class AnchoredBottomSheetDialog(
        context: Context,
        private val onConfigureWindow: (BottomSheetDialog) -> Unit
    ) : BottomSheetDialog(context) {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            onConfigureWindow(this)
        }

        override fun onStart() {
            super.onStart()
            onConfigureWindow(this)
        }
    }

    private fun createSortChip(
        parent: LinearLayout,
        labelRes: Int,
        sort: CommentSort
    ): TextView {
        val chip = LayoutInflater.from(context)
            .inflate(R.layout.view_category_chip, parent, false) as TextView
        chip.text = getString(labelRes)
        chip.setOnClickListener { onSelectCommentSort(sort) }
        val params = chip.layoutParams as LinearLayout.LayoutParams
        params.marginEnd = context.dp(8f)
        chip.layoutParams = params
        return chip
    }

    private fun bindSortChip(chip: TextView, selected: Boolean) {
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
    }

    private fun bindCommentsSheetHeader() {
        val count = if (commentsTotal > 0) commentsTotal else comments.size
        commentsSheetCount?.apply {
            text = if (count > 0) formatCompactCount(count.toLong()) else ""
            visibility = if (count > 0) View.VISIBLE else View.GONE
        }
        val topChip = commentsSheetTopChip ?: return
        val newestChip = commentsSheetNewestChip ?: return
        commentsSheetSortRow?.visibility =
            if (commentsDisabled) View.GONE else View.VISIBLE
        newestChip.visibility = if (commentsCanSortNewest) View.VISIBLE else View.GONE
        bindSortChip(topChip, commentsSort == CommentSort.TOP)
        bindSortChip(newestChip, commentsSort == CommentSort.NEWEST)
    }

    /** Gọi tải thêm bình luận, chặn các lần lặp trong cùng một frame. */
    private fun requestMoreComments() {
        if (!commentsHasMore || commentsLoading || commentsLoadMoreRequested) return
        commentsLoadMoreRequested = true
        onLoadMoreComments()
    }

    private fun handleCommentAction(action: CommentAction, commentId: String) {
        when (action) {
            CommentAction.NONE -> Unit
            CommentAction.LOAD_MORE -> requestMoreComments()
            CommentAction.RETRY_COMMENTS -> onRetryComments()
            CommentAction.LOAD_REPLIES -> onLoadReplies(commentId)
            CommentAction.RETRY_REPLIES -> onRetryReplies(commentId)
            CommentAction.LOAD_MORE_REPLIES -> onLoadReplies(commentId)
        }
    }

    /**
     * Chuyển state bình luận thành danh sách dòng phẳng cho [CommentSheetAdapter].
     *
     * Header và hàng sắp xếp do sheet giữ riêng nên danh sách chỉ còn nội dung
     * bình luận; các dòng phản hồi được đánh dấu [CommentSheetItem.indent] để
     * thụt lề theo bình luận mẹ.
     */
    private fun buildCommentSheetItems(): List<CommentSheetItem> {
        val items = mutableListOf<CommentSheetItem>()

        // Các trạng thái này loại trừ nhau: không bao giờ hiện đồng thời
        // "Đang tải..." + "Đã tắt" + "Lỗi" do state cũ chưa kịp reset.
        if (commentsDisabled) {
            items += statusItem("disabled", getString(R.string.player_comments_disabled))
        } else {
            when {
                commentsError != null && comments.isEmpty() -> {
                    items += statusItem(
                        "error",
                        commentsError?.userMessage(context).orEmpty()
                    )
                    items += actionItem(
                        key = "retry-comments",
                        label = getString(R.string.player_retry_comments),
                        action = CommentAction.RETRY_COMMENTS
                    )
                }
                commentsLoading && comments.isEmpty() -> {
                    items += statusItem("loading", getString(R.string.player_comments_loading))
                }
                comments.isEmpty() -> {
                    items += statusItem("empty", getString(R.string.player_comments_empty))
                }
            }
            // Lỗi khi đang tải thêm vẫn giữ danh sách cũ và hiện hàng thử lại.
            if (commentsError != null && comments.isNotEmpty()) {
                items += statusItem("error", commentsError?.userMessage(context).orEmpty())
                items += actionItem(
                    key = "retry-comments",
                    label = getString(R.string.player_retry_comments),
                    action = CommentAction.RETRY_COMMENTS
                )
            }
        }

        comments.forEach { comment ->
            items += CommentSheetItem(
                key = "comment-${comment.id}",
                kind = CommentSheetItem.Kind.COMMENT,
                comment = comment
            )
            commentReplies[comment.id].orEmpty().forEach { reply ->
                items += CommentSheetItem(
                    key = "reply-${comment.id}-${reply.id}",
                    kind = CommentSheetItem.Kind.COMMENT,
                    comment = reply,
                    indent = true
                )
            }
            val hasReplies = comment.hasReplies || comment.replyCount > 0
            when {
                comment.id in replyLoading -> items += statusItem(
                    "replies-loading-${comment.id}",
                    getString(R.string.player_replies_loading),
                    indent = true
                )

                comment.id in replyError -> {
                    items += statusItem(
                        "replies-error-${comment.id}",
                        replyError.getValue(comment.id).userMessage(context),
                        indent = true
                    )
                    items += actionItem(
                        key = "replies-retry-${comment.id}",
                        label = getString(R.string.player_retry_comments),
                        action = CommentAction.RETRY_REPLIES,
                        commentId = comment.id,
                        indent = true
                    )
                }

                comment.id in replyHasMore -> items += actionItem(
                    key = "replies-more-${comment.id}",
                    label = getString(R.string.player_replies_more),
                    action = CommentAction.LOAD_MORE_REPLIES,
                    commentId = comment.id,
                    indent = true
                )

                hasReplies && commentReplies[comment.id].isNullOrEmpty() -> items += actionItem(
                    key = "replies-show-${comment.id}",
                    label = if (comment.replyCount > 0) {
                        getString(R.string.player_reply_count, comment.replyCount)
                    } else {
                        getString(R.string.player_reply_show)
                    },
                    action = CommentAction.LOAD_REPLIES,
                    commentId = comment.id,
                    indent = true
                )
            }
        }

        if (commentsHasMore && !commentsLoading) {
            items += actionItem(
                key = "load-more",
                label = getString(R.string.player_load_more),
                action = CommentAction.LOAD_MORE
            )
        }
        if (commentsLoading && comments.isNotEmpty()) {
            items += statusItem("loading-more", getString(R.string.player_comments_loading))
        }
        return items
    }

    private fun statusItem(key: String, label: String, indent: Boolean = false) = CommentSheetItem(
        key = key,
        kind = CommentSheetItem.Kind.STATUS,
        label = label,
        indent = indent
    )

    private fun actionItem(
        key: String,
        label: String,
        action: CommentAction,
        commentId: String = "",
        indent: Boolean = false
    ) = CommentSheetItem(
        key = key,
        kind = CommentSheetItem.Kind.ACTION,
        label = label,
        action = action,
        actionCommentId = commentId,
        indent = indent
    )

    // ------------------------------------------------------------------
    // Sheet mô tả
    // ------------------------------------------------------------------

    private fun showDescriptionSheet() {
        if (state.currentVideo == null) return
        descriptionDialog?.dismiss()
        val sheet = createSheet()
        val content = LayoutInflater.from(context)
            .inflate(R.layout.view_player_description_sheet, null, false)

        content.findViewById<View>(R.id.description_sheet_close)
            .setOnClickListener { sheet.dismiss() }
        content.findViewById<View>(R.id.description_sheet_channel_row)
            .setOnClickListener { openCurrentChannel() }
        content.findViewById<View>(R.id.description_sheet_subscribe)
            .setOnClickListener { toggleSubscription() }
        content.findViewById<View>(R.id.description_sheet_like)
            .setOnClickListener {
                state.currentVideo?.let { onToggleFavorite(it) }
            }
        content.findViewById<View>(R.id.description_sheet_dislike)
            .setOnClickListener {
                val video = state.currentVideo ?: return@setOnClickListener
                if (state.isFavorite) onToggleFavorite(video)
            }
        content.findViewById<View>(R.id.description_sheet_share)
            .setOnClickListener {
                state.currentVideo?.let { video ->
                    runCatching { shareVideo(context, video) }
                }
            }
        content.findViewById<View>(R.id.description_sheet_save)
            .setOnClickListener {
                state.currentVideo?.let { onToggleWatchLater(it) }
            }

        sheet.setContentView(content)
        applySheetCorners(content)
        configureSheet(sheet)
        descriptionDialog = sheet
        descriptionSheetContent = content
        sheet.setOnDismissListener {
            if (descriptionDialog === sheet) {
                descriptionDialog = null
                descriptionSheetContent = null
            }
        }
        bindDescriptionSheet()
        sheet.show()
    }

    /** Cập nhật sheet mô tả tại chỗ khi lượt thích, lưu hoặc đăng ký đổi. */
    private fun refreshDescriptionSheet() {
        if (descriptionDialog?.isShowing == true) bindDescriptionSheet()
    }

    private fun bindDescriptionSheet() {
        val content = descriptionSheetContent ?: return
        val video = state.currentVideo ?: return

        content.findViewById<TextView>(R.id.description_sheet_title).text = video.title
        val views = details?.viewCount
            ?.takeIf { it > 0 }
            ?.let { getString(R.string.player_views_count, formatCompactCount(it)) }
            ?: video.viewCount
        val meta = listOfNotNull(
            views.takeIf { it.isNotBlank() },
            (details?.publishedTime?.takeIf { it.isNotBlank() } ?: video.publishedTime)
                .takeIf { it.isNotBlank() },
            details?.likeCount
                ?.takeIf { it > 0 }
                ?.let { getString(R.string.player_likes_count, formatCompactCount(it)) }
        ).joinToString(" • ")
        content.findViewById<TextView>(R.id.description_sheet_meta).apply {
            text = meta
            visibility = if (meta.isBlank()) View.GONE else View.VISIBLE
        }

        val avatarUrl = details?.channelAvatarUrl?.takeIf { it.isNotBlank() }
            ?: video.channelAvatarUrl
        content.findViewById<ChannelAvatarView>(R.id.description_sheet_avatar)
            .bind(video.channelTitle, avatarUrl)
        content.findViewById<TextView>(R.id.description_sheet_channel).text =
            video.channelTitle
        val subscriberCount = details?.subscriberCount ?: 0
        content.findViewById<TextView>(R.id.description_sheet_subscribers).apply {
            visibility = if (subscriberCount > 0) View.VISIBLE else View.GONE
            text = if (subscriberCount > 0) {
                getString(
                    R.string.player_subscribers_count,
                    formatCompactCount(subscriberCount)
                )
            } else {
                ""
            }
        }
        bindSubscribeButton(
            content.findViewById(R.id.description_sheet_subscribe),
            video
        )

        val likeLabel = formatCompactCount(details?.likeCount ?: 0)
            .ifBlank { getString(R.string.player_like) }
        content.findViewById<TextView>(R.id.description_sheet_like_text).text = likeLabel
        content.findViewById<ImageView>(R.id.description_sheet_like_icon)
            .setImageResource(R.drawable.ic_thumb_up)
        updatePill(content.findViewById(R.id.description_sheet_like), state.isFavorite)
        content.findViewById<View>(R.id.description_sheet_dislike).visibility =
            if (state.isFavorite) View.VISIBLE else View.GONE

        content.findViewById<ImageView>(R.id.description_sheet_save_icon).setImageResource(
            if (state.isWatchLater) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border
        )
        content.findViewById<TextView>(R.id.description_sheet_save_text).text = getString(
            if (state.isWatchLater) R.string.player_saved else R.string.player_save
        )
        updatePill(content.findViewById(R.id.description_sheet_save), state.isWatchLater)

        val description = details?.description
            ?.takeIf { it.isNotBlank() }
            ?: video.description.takeIf { it.isNotBlank() }
            ?: getString(R.string.player_no_description)
        content.findViewById<TextView>(R.id.description_sheet_body).text = description
    }

    private fun toggleSubscription() {
        val video = state.currentVideo ?: return
        // Thẻ video có thể thiếu channelId (bảng xếp hạng, lockup); metadata
        // trang xem luôn có. ViewModel vẫn tự kiểm tra lại nếu cả hai trống.
        val channelId = video.channelId.ifBlank { details?.channelId.orEmpty() }
        val channelTitle = video.channelTitle
            .takeIf { it.isNotBlank() && it != "YouTube" }
            ?: details?.channelTitle.orEmpty()
        onToggleSubscription(
            video.copy(channelId = channelId, channelTitle = channelTitle)
        )
    }

    private fun openCurrentChannel() {
        val video = state.currentVideo ?: return
        val channelId = video.channelId.ifBlank { details?.channelId.orEmpty() }
        val channelTitle = video.channelTitle
            .takeIf { it.isNotBlank() && it != "YouTube" }
            ?: details?.channelTitle.orEmpty()
        if (channelId.isNotBlank()) {
            com.luxysiv.puretube.ui.channel.ChannelActivity.start(
                context = context,
                channelId = channelId,
                channelTitle = channelTitle
            )
        } else if (channelTitle.isNotBlank()) {
            val url = "https://www.youtube.com/results?search_query=${Uri.encode(channelTitle)}"
            openUrl(url)
        }
    }

    // ------------------------------------------------------------------
    // Sheet mix
    // ------------------------------------------------------------------

    /**
     * Mở sheet danh sách đang phát: ưu tiên playlist người dùng vừa mở, sau đó
     * mới tới mix của YouTube.
     */
    private fun showMixSheet() {
        val sheetContent = queueSheetContent() ?: return
        mixDialog?.dismiss()
        val sheet = createSheet()
        val scroll = ScrollView(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(16f), context.dp(8f), context.dp(16f), context.dp(24f))
        }
        scroll.addView(container)
        sheet.setContentView(scroll)
        scroll.setBackgroundResource(R.drawable.bg_player_sheet)
        applySheetCorners(scroll)
        configureSheet(sheet)
        sheet.setOnDismissListener {
            mixDialog = null
            mixSheetContainer = null
            mixSheetVideoId = null
        }
        mixDialog = sheet
        mixSheetContainer = container
        mixSheetVideoId = state.currentVideo?.id
        buildQueueSheetContent(container, sheetContent, sheet)
        sheet.show()
    }

    /** Dữ liệu cho sheet danh sách: playlist đang phát hoặc mix dự phòng. */
    private data class QueueSheetContent(
        val videos: List<VideoItem>,
        val title: String,
        val countText: String
    )

    private fun queueSheetContent(): QueueSheetContent? {
        val playlist = queue.playlist
        if (playlist != null && playlist.videos.isNotEmpty()) {
            return QueueSheetContent(
                videos = playlist.videos,
                title = playlist.title.ifBlank { getString(R.string.playlist_label) },
                countText = getString(R.string.playlist_video_count, playlist.videos.size)
            )
        }
        val mix = queue.mix
        if (mix != null && mix.videos.isNotEmpty()) {
            return QueueSheetContent(
                videos = mix.videos,
                title = mixDisplayTitle(mix.title, state.currentVideo?.title.orEmpty()),
                countText = getString(R.string.player_mix_count, mix.videos.size)
            )
        }
        return null
    }

    /** Dựng lại toàn bộ nội dung sheet danh sách (dùng cả khi video đang phát đổi). */
    private fun buildQueueSheetContent(
        container: LinearLayout,
        content: QueueSheetContent,
        sheet: BottomSheetDialog
    ) {
        container.removeAllViews()
        val video = state.currentVideo

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cover = MixCoverView(context)
        header.addView(
            cover,
            LinearLayout.LayoutParams(context.dp(96f), context.dp(54f))
        )
        cover.bind(content.videos.take(4))

        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(context).apply {
            text = content.title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.pure_on_surface))
        })
        titles.addView(TextView(context).apply {
            text = content.countText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ContextCompat.getColor(context, R.color.pure_on_surface_variant))
        })
        header.addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            marginStart = context.dp(12f)
        })
        header.addView(closeButton { sheet.dismiss() })
        container.addView(header)
        container.addView(divider(), matchWrap(top = 8))

        content.videos.forEach { listVideo ->
            val card = CompactVideoCardView(context)
            card.bind(
                video = listVideo,
                isFavorite = favoriteIds.contains(listVideo.id),
                isWatchLater = watchLaterIds.contains(listVideo.id),
                progressFraction = watchProgress[listVideo.id] ?: 0f,
                isCurrent = listVideo.id == video?.id,
                actions = videoCardActions(
                    onClick = {
                        sheet.dismiss()
                        onSelectVideo(it)
                    },
                    onToggleFavorite = onToggleFavorite,
                    onToggleWatchLater = onToggleWatchLater,
                    onShare = { shareVideo(context, it) },
                    currentVideoId = video?.id,
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue
                )
            )
            container.addView(card)
        }
    }

    /** Video đang phát đổi khi sheet danh sách mở: dựng lại để đúng nhãn "Đang phát". */
    private fun refreshMixSheet() {
        val container = mixSheetContainer ?: return
        if (mixDialog?.isShowing != true) return
        val videoId = state.currentVideo?.id
        if (videoId == mixSheetVideoId) return
        mixSheetVideoId = videoId
        val content = queueSheetContent() ?: return
        buildQueueSheetContent(container, content, mixDialog ?: return)
    }

    // ------------------------------------------------------------------
    // Sheet cài đặt trình phát
    // ------------------------------------------------------------------

    private fun showPlayerSettings() {
        if (settingsDialog?.isShowing == true) return
        playerHud.setSettingsOpen(true)
        val sheet = createSheet()
        val scroll = ScrollView(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(20f), context.dp(8f), context.dp(20f), context.dp(24f))
        }
        scroll.addView(container)
        sheet.setContentView(scroll)
        scroll.setBackgroundResource(R.drawable.bg_player_sheet)
        applySheetCorners(scroll)
        configureSheet(sheet)
        sheet.setOnDismissListener {
            settingsDialog = null
            settingsSheetContainer = null
            playerHud.setSettingsOpen(false)
        }
        settingsDialog = sheet
        settingsSheetContainer = container
        rebuildSettingsSheet()
        settingsSignature = computeSettingsSignature()
        sheet.show()
    }

    private fun refreshSettingsSheet() {
        if (settingsDialog?.isShowing != true) return
        val signature = computeSettingsSignature()
        if (signature == settingsSignature) return
        settingsSignature = signature
        rebuildSettingsSheet()
    }

    private fun computeSettingsSignature(): String = listOf(
        state.selectedQuality,
        state.playbackSpeed,
        state.playbackPitch,
        state.captionsEnabled,
        state.isSkipSilenceEnabled,
        playerHud.currentVideoHeight?.toString().orEmpty(),
        playerHud.currentCaptionLanguage,
        playerHud.captionOptions.joinToString { it.languageTag }
    ).joinToString("|")

    private fun rebuildSettingsSheet() {
        val container = settingsSheetContainer ?: return
        container.removeAllViews()

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = getString(R.string.player_settings)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.pure_on_surface))
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(closeButton { settingsDialog?.dismiss() })
        container.addView(header)

        val autoLabel = getString(R.string.player_auto)
        val qualityLabel = if (state.qualityPreferenceHeight <= 0) {
            if (state.selectedQuality != autoLabel) {
                state.selectedQuality
            } else {
                val height = playerHud.currentVideoHeight
                if (height != null) "$autoLabel · ${height}p" else autoLabel
            }
        } else {
            state.selectedQuality
        }
        container.addView(settingRow(getString(R.string.player_quality), qualityLabel) {
            showQualityDialog()
        })
        container.addView(settingRow(getString(R.string.player_speed), "${Formatters.speed(state.playbackSpeed)}x") {
            showSpeedDialog()
        })
        container.addView(settingRow(getString(R.string.player_pitch), "${Formatters.speed(state.playbackPitch)}x") {
            showPitchDialog()
        })
        container.addView(settingRow(getString(R.string.player_captions), captionValue()) {
            showCaptionDialog()
        })

        val skipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(4f), context.dp(4f), context.dp(4f), context.dp(4f))
            minimumHeight = context.dp(52f)
        }
        skipRow.addView(TextView(context).apply {
            text = getString(R.string.player_skip_silence)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(ContextCompat.getColor(context, R.color.pure_on_surface))
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val skipSwitch = SwitchCompat(context).apply {
            isChecked = state.isSkipSilenceEnabled
            setOnCheckedChangeListener { _, _ -> onToggleSkipSilence() }
        }
        skipRow.addView(skipSwitch)
        container.addView(skipRow)

        container.addView(settingRow(
            getString(R.string.player_reset_params),
            null
        ) {
            onResetPlaybackParameters()
        })
    }

    private fun captionValue(): String {
        val tracks = playerHud.captionOptions
        return when {
            tracks.isEmpty() -> getString(R.string.player_no_captions)
            !state.captionsEnabled -> getString(R.string.player_off)
            else -> tracks.firstOrNull {
                it.languageTag == playerHud.currentCaptionLanguage
            }?.label ?: getString(R.string.player_off)
        }
    }

    private fun showQualityDialog() {
        // Chỉ hiện mức có thật trong video hiện tại, cộng thêm mức người dùng đã
        // chọn (có thể cao hơn video này) để hộp thoại vẫn đánh dấu đúng lựa chọn.
        val heights = (state.availableQualities + state.qualityPreferenceHeight)
            .filter { it > 0 }
            .distinct()
            .sortedDescending()
        val autoLabel = getString(R.string.player_auto)
        val selectedAuto = state.qualityPreferenceHeight <= 0
        val autoDisplay = if (selectedAuto && state.selectedQuality != autoLabel) {
            state.selectedQuality
        } else {
            autoLabel
        }
        val options = buildList {
            add(autoDisplay to (null as Int?))
            heights.forEach { add("${it}p" to it) }
        }
        val selected = if (state.qualityPreferenceHeight <= 0) {
            0
        } else {
            options.indexOfFirst { it.second == state.qualityPreferenceHeight }
                .let { if (it >= 0) it else 0 }
        }
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.player_quality)
            .setSingleChoiceItems(
                options.map { it.first }.toTypedArray(),
                selected
            ) { dialog, which ->
                onSetQuality(options[which].second)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.player_close, null)
        if (state.isDataSaverEnabled && state.isMeteredNetwork &&
            state.qualityPreferenceHeight <= 0
        ) {
            builder.setMessage(R.string.player_quality_data_saver_note)
        }
        builder.show()
    }

    private fun showSpeedDialog() {
        val speeds = listOf(
            0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f,
            2.0f, 2.25f, 2.5f, 2.75f, 3.0f
        )
        showFloatChoiceDialog(
            title = getString(R.string.player_speed),
            values = speeds,
            selected = state.playbackSpeed,
            onSelect = onSetPlaybackSpeed
        )
    }

    private fun showPitchDialog() {
        val pitches = listOf(0.85f, 0.9f, 0.95f, 1.0f, 1.05f, 1.1f, 1.15f)
        showFloatChoiceDialog(
            title = getString(R.string.player_pitch),
            values = pitches,
            selected = state.playbackPitch,
            onSelect = onSetPlaybackPitch
        )
    }

    private fun showFloatChoiceDialog(
        title: String,
        values: List<Float>,
        selected: Float,
        onSelect: (Float) -> Unit
    ) {
        val labels = values.map { "${Formatters.speed(it)}x" }
        val selectedIndex = values.indexOfFirst { it == selected }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), selectedIndex) { dialog, which ->
                onSelect(values[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.player_close, null)
            .show()
    }

    private fun showCaptionDialog() {
        val tracks = playerHud.captionOptions
        if (tracks.isEmpty()) {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.player_captions)
                .setMessage(R.string.player_no_captions)
                .setPositiveButton(R.string.player_close, null)
                .show()
            return
        }
        val options = buildList {
            add(getString(R.string.player_off) to (null as String?))
            tracks.forEach { add(it.label to it.languageTag) }
        }
        val activeLanguage = playerHud.currentCaptionLanguage
            .ifBlank { state.captionLanguageTag }
        val selected = if (!state.captionsEnabled) {
            0
        } else {
            options.indexOfFirst { it.second == activeLanguage }.coerceAtLeast(0)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.player_captions)
            .setSingleChoiceItems(
                options.map { it.first }.toTypedArray(),
                selected
            ) { dialog, which ->
                val tag = options[which].second
                if (tag == null) {
                    if (state.captionsEnabled) onToggleCaptions()
                } else {
                    onSelectCaptionLanguage(tag)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.player_close, null)
            .show()
    }

    // ------------------------------------------------------------------
    // Tiện ích dựng view
    // ------------------------------------------------------------------

    private fun settingRow(title: String, value: String?, onClick: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(4f), context.dp(4f), context.dp(4f), context.dp(4f))
            minimumHeight = context.dp(52f)
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(TextView(context).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(ContextCompat.getColor(context, R.color.pure_on_surface))
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (!value.isNullOrBlank()) {
            row.addView(TextView(context).apply {
                text = value
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(ContextCompat.getColor(context, R.color.pure_on_surface_variant))
            })
        }
        row.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron_right)
            ImageViewCompat.setImageTintList(
                this,
                ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.pure_on_surface_variant)
                )
            )
        }, LinearLayout.LayoutParams(context.dp(20f), context.dp(20f)).apply {
            marginStart = context.dp(8f)
        })
        return row
    }

    private fun closeButton(onClick: (() -> Unit)? = null): View {
        return ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            ImageViewCompat.setImageTintList(
                this,
                ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.pure_on_surface_variant)
                )
            )
            setPadding(context.dp(10f), context.dp(10f), context.dp(10f), context.dp(10f))
            isClickable = true
            setOnClickListener { onClick?.invoke() }
        }
    }

    private fun divider(): View {
        return View(context).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.pure_outline_variant))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.dp(1f)
            )
        }
    }

    /**
     * Kéo tay cầm sheet xuống: khung video giãn dần theo ngón tay (đáy đi xuống
     * đúng quãng tay đi, đỉnh nhích dần lên thanh trạng thái). Thả tay thì
     * spring chốt về fullscreen hoặc quay lại khung nhúng — cùng cảm giác với
     * hiệu ứng thu nhỏ nhưng chạy ngược lại.
     */
    private fun attachSwipeDownToFullscreen(view: View) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fullscreenDragStartRawX = event.rawX
                    fullscreenDragStartRawY = event.rawY
                    fullscreenDragVelocity?.recycle()
                    fullscreenDragVelocity = VelocityTracker.obtain()
                    addRawMovement(event)
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    if (fullscreenDragVelocity == null) {
                        fullscreenDragVelocity = VelocityTracker.obtain()
                    }
                    addRawMovement(event)
                    val dy = event.rawY - fullscreenDragStartRawY
                    val dx = abs(event.rawX - fullscreenDragStartRawX)
                    if (!fullscreenDragActive &&
                        dy > touchSlop &&
                        dy > dx * FULLSCREEN_SWIPE_AXIS_RATIO &&
                        canStartFullscreenDrag()
                    ) {
                        fullscreenDragSpring?.cancel()
                        captureFullscreenDragBase()
                        fullscreenDragActive = true
                    }
                    if (fullscreenDragActive) {
                        val distance =
                            (fullscreenDragTargetBottom - fullscreenDragBaseBottom)
                                .coerceAtLeast(1)
                        fullscreenDragPendingProgress = (dy / distance).coerceIn(0f, 1f)
                        if (!fullscreenDragUpdatePosted) {
                            fullscreenDragUpdatePosted = true
                            postOnAnimation(fullscreenDragUpdateRunnable)
                        }
                        true
                    } else {
                        false
                    }
                }

                MotionEvent.ACTION_UP -> {
                    addRawMovement(event)
                    val tracker = fullscreenDragVelocity
                    tracker?.computeCurrentVelocity(1000)
                    val velocityY = tracker?.yVelocity ?: 0f
                    tracker?.recycle()
                    fullscreenDragVelocity = null
                    if (fullscreenDragActive) {
                        removeCallbacks(fullscreenDragUpdateRunnable)
                        fullscreenDragUpdatePosted = false
                        // Áp đúng vị trí cuối cùng của ngón tay trước khi chốt
                        // spring, nếu frame gom còn chưa kịp chạy.
                        setFullscreenDragProgress(fullscreenDragPendingProgress)
                        finishFullscreenDrag(velocityY)
                        true
                    } else {
                        false
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    fullscreenDragVelocity?.recycle()
                    fullscreenDragVelocity = null
                    removeCallbacks(fullscreenDragUpdateRunnable)
                    fullscreenDragUpdatePosted = false
                    if (fullscreenDragActive) {
                        animateFullscreenDragTo(0f, 0f, commit = false)
                        true
                    } else {
                        false
                    }
                }

                else -> false
            }
        }
    }

    /**
     * VelocityTracker chỉ đọc x/y cục bộ của MotionEvent. Dịch bản sao về toạ
     * độ thô để vận tốc fling không bị méo khi tay cầm đi theo ngón tay.
     */
    private fun addRawMovement(event: MotionEvent) {
        val tracker = fullscreenDragVelocity ?: return
        val copy = MotionEvent.obtain(event)
        copy.offsetLocation(event.rawX - event.x, event.rawY - event.y)
        tracker.addMovement(copy)
        copy.recycle()
    }

    /**
     * Huỷ cử chỉ kéo fullscreen khi rời ngữ cảnh (PiP, xoay màn hình, release,
     * đóng sheet) hoặc khi fullscreen được bật từ nơi khác. Dọn toàn bộ trạng
     * thái lò xo/vận tốc; [restoreLayout] đúng thì tính lại bố cục ngay, sai vì
     * bố cục mới sẽ được tính ở bước tiếp theo.
     */
    private fun cancelFullscreenDrag(restoreLayout: Boolean = true) {
        fullscreenDragSpring?.cancel()
        fullscreenDragSpring = null
        fullscreenDragVelocity?.recycle()
        fullscreenDragVelocity = null
        removeCallbacks(fullscreenDragUpdateRunnable)
        fullscreenDragUpdatePosted = false
        val wasActive = fullscreenDragActive
        fullscreenDragActive = false
        fullscreenDragProgress = 0f
        fullscreenDragPendingProgress = 0f
        if (wasActive && restoreLayout) updateLayoutMetrics()
    }

    private fun canStartFullscreenDrag(): Boolean =
        !state.isFullscreen && !inPip && !twoColumn

    private fun captureFullscreenDragBase() {
        val metrics = resources.displayMetrics
        fullscreenDragBaseTop = topInset
        fullscreenDragBaseBottom = topInset + embeddedVideoHeightPx()
        fullscreenDragTargetBottom = metrics.heightPixels
        fullscreenDragProgress = 0f
        fullscreenDragPendingProgress = 0f
    }

    private fun embeddedVideoHeightPx(): Int {
        val metrics = resources.displayMetrics
        return (metrics.widthPixels * 9 / 16)
            .coerceAtMost((metrics.heightPixels - topInset).coerceAtLeast(1))
    }

    private fun setFullscreenDragProgress(value: Float) {
        val progress = value.coerceIn(0f, 1f)
        fullscreenDragProgress = progress
        if (!fullscreenDragActive) return
        val top = (fullscreenDragBaseTop * (1f - progress)).toInt()
        val bottom = (
            fullscreenDragBaseBottom +
                (fullscreenDragTargetBottom - fullscreenDragBaseBottom) * progress
            ).toInt()
        val height = (bottom - top).coerceAtLeast(1)
        // Sửa ngay đối tượng LayoutParams đang dùng, chỉ requestLayout() khi
        // số đo thực sự đổi. Gán lại layoutParams ở mỗi ACTION_MOVE vừa cấp
        // phát đối tượng mới vừa kích hoạt lại cả chu trình measure/layout.
        (videoContainer.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            if (lp.height != height || lp.topMargin != top) {
                lp.height = height
                lp.topMargin = top
                videoContainer.requestLayout()
            }
        }
        (detailsSheet.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            if (lp.topMargin != bottom) {
                lp.topMargin = bottom
                detailsSheet.requestLayout()
            }
        }
        detailsSheet.alpha = 1f - progress
        detailsSheet.translationY = progress * context.dp(24f)
    }

    private fun finishFullscreenDrag(velocityY: Float) {
        val commit = fullscreenDragProgress >= FULLSCREEN_DRAG_COMMIT_FRACTION ||
            velocityY >= context.dp(FULLSCREEN_DRAG_FLING_DP)
        animateFullscreenDragTo(if (commit) 1f else 0f, velocityY, commit)
    }

    private fun animateFullscreenDragTo(target: Float, startVelocity: Float, commit: Boolean) {
        val distance = target - fullscreenDragProgress
        if (abs(distance) < 0.001f && abs(startVelocity) < 0.001f) {
            settleFullscreenDrag(target, commit)
            return
        }
        val omega = sqrt(FULLSCREEN_SPRING_STIFFNESS)
        val velocity = startVelocity.coerceIn(
            -omega * abs(distance),
            omega * abs(distance)
        )
        val animation = SpringAnimation(this, fullscreenDragProgressProperty, target)
        animation.setSpring(
            SpringForce(target)
                .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY)
                .setStiffness(FULLSCREEN_SPRING_STIFFNESS)
        )
        animation.setMinimumVisibleChange(0.001f)
        animation.setStartValue(fullscreenDragProgress)
        animation.setStartVelocity(velocity)
        animation.addEndListener { ended, canceled, _, _ ->
            if (ended === fullscreenDragSpring) fullscreenDragSpring = null
            if (!canceled) settleFullscreenDrag(target, commit)
        }
        fullscreenDragSpring = animation
        animation.start()
    }

    private fun settleFullscreenDrag(target: Float, commit: Boolean) {
        setFullscreenDragProgress(target)
        fullscreenDragActive = false
        if (commit && !inPip) {
            // Khung hình đã tràn màn hình; Activity sẽ đổi state + xoay ngay sau đó.
            onEnterFullscreen()
        } else {
            // Đang PiP thì không được đổi state fullscreen: chỉ khôi phục bố cục.
            detailsSheet.alpha = 1f
            detailsSheet.translationY = 0f
            updateLayoutMetrics()
        }
    }

    private fun enterFullscreenFromSwipe() {
        if (!state.isFullscreen) onEnterFullscreen()
    }

    private fun matchWrap(top: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = context.dp(top.toFloat())
        }

    private fun getString(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    private companion object {
        const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT

        /** Bo góc khung hình khi thu nhỏ, khớp khung thumbnail mini player. */
        const val MINIMIZE_CORNER_DP = 8f

        /**
         * Độ cứng lò xo của hiệu ứng thu nhỏ/mở rộng.
         *
         * STIFFNESS_MEDIUM (1500) làm chuyển cảnh xong trong khoảng 150 ms —
         * nhanh tới mức người dùng chỉ thấy một cú giật. Hạ xuống 350 cho
         * thời gian rơi vào khoảng 300 ms, cùng nhịp với hiệu ứng thu nhỏ của
         * YouTube, mà vẫn giữ được cảm giác nối tiếp vận tốc khi vuốt.
         */
        const val MINIMIZE_SPRING_STIFFNESS = 350f

        /** Tỉ lệ đường kéo (so với quãng đáy video tới đáy màn) để chốt fullscreen. */
        const val FULLSCREEN_DRAG_COMMIT_FRACTION = 0.35f
        const val FULLSCREEN_DRAG_FLING_DP = 500f
        const val FULLSCREEN_SPRING_STIFFNESS = 350f
        const val FULLSCREEN_SWIPE_AXIS_RATIO = 1.2f
    }
}

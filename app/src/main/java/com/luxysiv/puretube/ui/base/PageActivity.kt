package com.luxysiv.puretube.ui.base

import android.Manifest
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.luxysiv.puretube.R
import com.luxysiv.puretube.data.model.PlaybackPlan
import com.luxysiv.puretube.data.model.VideoItem
import com.luxysiv.puretube.player.PlaybackFailure
import com.luxysiv.puretube.player.PlaybackPosition
import com.luxysiv.puretube.player.PlayerPipActionReceiver
import com.luxysiv.puretube.player.PurePlayerState
import com.luxysiv.puretube.player.QueueUiState
import com.luxysiv.puretube.ui.adaptive.WindowWidthClass
import com.luxysiv.puretube.ui.adaptive.windowWidthClassForWidth
import com.luxysiv.puretube.ui.components.watchProgressMap
import com.luxysiv.puretube.ui.navigation.AppTab
import com.luxysiv.puretube.ui.navigation.PageNavigator
import com.luxysiv.puretube.ui.player.PlayerNativeView
import com.luxysiv.puretube.ui.player.PlayerViewModel
import com.luxysiv.puretube.ui.scaffold.MiniPlayerView
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Khung chung cho mọi trang có thanh điều hướng và mini player.
 *
 * Mỗi trang vẫn là một Activity riêng với ViewModel riêng; lớp này chỉ chứa
 * phần giao diện lặp lại (snackbar, mini player, bottom bar/rail/drawer).
 *
 * Giai đoạn 6 của quá trình chuyển sang XML View: toàn bộ giao diện đã là View
 * native, khung trang chỉ còn thanh điều hướng, snackbar và mini player dùng
 * chung; mỗi trang tự cung cấp nội dung qua [createPageView].
 */
abstract class PageActivity : AppCompatActivity() {

    protected abstract val viewModel: PureTubeViewModel

    protected abstract val currentTab: AppTab

    /**
     * Tab mà nút Back hệ thống quay về khi trang hiện tại không phải tab chính.
     * Màn hình con (ví dụ chi tiết playlist mở từ Tìm kiếm) ghi đè giá trị này.
     */
    protected open val backParentTab: AppTab
        get() = if (currentTab == AppTab.SETTINGS) AppTab.LIBRARY else AppTab.HOME

    /** Trả về nội dung native của trang (View/XML). */
    protected abstract fun createPageView(): View

    private lateinit var pageRoot: View
    private lateinit var pageMain: View
    private lateinit var contentContainer: android.view.ViewGroup
    private lateinit var sideNav: View
    private lateinit var rail: View
    private lateinit var drawer: View
    private lateinit var bottomNav: View
    private lateinit var miniPlayer: MiniPlayerView
    private lateinit var playerOverlay: PlayerNativeView

    /** ViewModel riêng cho trình phát overlay (dùng chung engine cấp process). */
    private val playerViewModel: PlayerViewModel by viewModels()

    /** Trình phát overlay đang hiển thị hay không. */
    private var playerOpen = false

    /**
     * Đã từng thấy video trong phiên mở overlay hiện tại chưa. Trạng thái phát
     * có thể chưa kịp đồng bộ ngay sau khi mở (nhất là khi mở từ notification),
     * nên chỉ đóng overlay khi video *biến mất* sau khi đã từng xuất hiện.
     */
    private var playerVideoSeen = false

    private var playerState = PurePlayerState()
    private var playerPosition = PlaybackPosition()
    private var playerPlan: PlaybackPlan? = null
    private var playerQueue = QueueUiState()
    private var playerFailure: PlaybackFailure? = null
    private var playerFavoriteIds: Set<String> = emptySet()
    private var playerWatchLaterIds: Set<String> = emptySet()
    private var playerWatchProgress: Map<String, Float> = emptyMap()
    private var playerSubscribedChannelIds: Set<String> = emptySet()

    private var lastFullscreen = false

    /**
     * PiP được yêu cầu từ nút bấm (khác với tự vào PiP khi người dùng rời app).
     * Chỉ trường hợp này mới cần chủ động đưa task ra nền.
     */
    private var pipRequestedByUser = false

    private var widthClass = WindowWidthClass.Compact
    private var snackbar: Snackbar? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Từ chối vẫn phát được, chỉ thiếu notification điều khiển */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_page)

        bindShellViews()
        bindNavigation()
        applyResponsiveLayout()
        applyWindowInsets()
        setUpPageContent()
        setUpMiniPlayer()
        setUpPlayerOverlay()
        collectUiState()
        observePlayer()

        requestNotificationPermissionOnce()
        registerBackNavigation()

        // Mở từ notification "đang phát": hiện luôn trình phát overlay. Trạng
        // thái phát có thể chưa kịp đồng bộ nên cứ mở; observePlayer() sẽ tự
        // đóng nếu thực sự không còn video nào.
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) {
            intent.removeExtra(EXTRA_OPEN_PLAYER)
            openPlayer(fromMiniPlayer = false)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Notification "đang phát" mở lại Activity đã tồn tại (SINGLE_TOP +
        // REORDER_TO_FRONT): extra chỉ tới được đây, không qua onCreate.
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) {
            intent.removeExtra(EXTRA_OPEN_PLAYER)
            openPlayer(fromMiniPlayer = false)
        }
    }

    private fun bindShellViews() {
        pageRoot = findViewById(R.id.page_root)
        pageMain = findViewById(R.id.page_main)
        contentContainer = findViewById(R.id.page_content_container)
        sideNav = findViewById(R.id.page_side_nav)
        rail = findViewById(R.id.page_rail)
        drawer = findViewById(R.id.page_drawer)
        bottomNav = findViewById(R.id.page_bottom_nav)
        miniPlayer = findViewById(R.id.page_mini_player)
        playerOverlay = findViewById(R.id.page_player_overlay)
    }

    private fun setUpPageContent() {
        val nativeView = createPageView()
        contentContainer.addView(
            nativeView,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private fun setUpMiniPlayer() {
        miniPlayer.attachToLifecycle(this)
        miniPlayer.bindPlayer(viewModel.player)
        miniPlayer.onExpand = {
            openPlayer(fromMiniPlayer = true)
        }
        miniPlayer.onTogglePlayPause = viewModel::togglePlayPause
        miniPlayer.onNext = { viewModel.playNextVideo(force = true) }
        miniPlayer.onClose = viewModel::closePlayer
    }

    // ------------------------------------------------------------------
    // Trình phát overlay (cùng cửa sổ với trang nội dung)
    // ------------------------------------------------------------------

    /** Phát video và mở trình phát (dùng chung cho các trang nội dung). */
    protected fun playAndOpenPlayer(video: VideoItem) {
        viewModel.playVideo(video)
        openPlayer()
    }

    /**
     * Mở trình phát overlay, dùng chung cho mọi luồng phát (kể cả mix ở Trang
     * chủ). Trình phát là một View cùng cửa sổ với trang nội dung, nên trang
     * bên dưới là View thật: thu nhỏ chỉ animate overlay, trang không hề đổi.
     */
    protected fun openPlayer(fromMiniPlayer: Boolean = false) {
        if (playerOpen) return
        playerOpen = true
        playerVideoSeen = playerState.currentVideo != null
        // Chặn mini player mượn surface trong lúc overlay đang giữ nó.
        miniPlayer.setSurfaceSuppressed(true)
        playerOverlay.visibility = View.VISIBLE
        playerOverlay.bindPlayer(playerViewModel.player)
        playerOverlay.onResume()
        ViewCompat.requestApplyInsets(playerOverlay)
        // Trình phát dùng lại cùng một view (không tạo lại theo Activity như
        // trước), nên không có emission trạng thái nào tự vẽ lại. Đẩy ngay
        // trạng thái đã cache vào để mở lại từ mini player là thấy đúng nội dung.
        renderPlayer()
        syncPlayerLibraryState()
        syncPlayerSubscriptionState()
        applyKeepScreenOn()
        playerOverlay.updateDetails(playerViewModel.videoDetails.value)
        playerOverlay.updateComments(playerViewModel.commentsState.value)
        if (fromMiniPlayer) {
            // Đăng ký ngay (không post): PlayerNativeView sẽ chờ đúng preDraw
            // của frame kế tiếp, khi overlay đã được đo lại, rồi mới chạy hiệu
            // ứng mở rộng. Nhờ vậy frame đầu tiên đã ở khung mini player.
            playerOverlay.expandFromMiniPlayer()
        }
    }

    /**
     * Đóng trình phát overlay sau khi hiệu ứng thu nhỏ chạy xong: trả surface
     * cho mini player và dọn phép biến đổi để lần mở sau bắt đầu từ trạng thái
     * sạch (view không còn bị huỷ theo Activity như trước).
     */
    private fun closePlayerOverlay() {
        if (!playerOpen) return
        playerOpen = false
        playerOverlay.release()
        playerOverlay.resetMinimizeTransform()
        playerOverlay.visibility = View.GONE
        miniPlayer.setSurfaceSuppressed(false)
        // Đặt lại cờ toàn màn hình để lần mở sau bắt đầu ở chế độ thường.
        playerViewModel.setFullscreen(false)
        applyFullscreenUi(false)
        applyKeepScreenOn()
        // Tắt auto-enter PiP vì không còn video nào đang mở.
        updatePipParams()
    }

    /**
     * Vị trí khung video của mini player trên màn hình, dùng làm đích cho hiệu
     * ứng thu nhỏ trình phát. Trả về null khi mini player chưa hiển thị hoặc
     * chưa được đo (PlayerNativeView sẽ tự ước lượng vị trí dự phòng).
     */
    fun miniPlayerVideoBoundsOnScreen(): Rect? {
        if (miniPlayer.visibility != View.VISIBLE) return null
        val target = miniPlayer.findViewById<View>(R.id.mini_player_video_container)
            ?: return null
        if (target.width <= 0 || target.height <= 0) return null
        val location = IntArray(2)
        target.getLocationOnScreen(location)
        return Rect(
            location[0],
            location[1],
            location[0] + target.width,
            location[1] + target.height
        )
    }

    private fun setUpPlayerOverlay() {
        playerOverlay.minimizeTargetProvider = { miniPlayerVideoBoundsOnScreen() }
        playerOverlay.onClose = { closePlayerOverlay() }
        bindPlayerCallbacks()
    }

    private fun bindPlayerCallbacks() {
        playerOverlay.onTogglePlayPause = playerViewModel::togglePlayPause
        playerOverlay.onSeekTo = playerViewModel::seekTo
        playerOverlay.onForward10 = playerViewModel::forward10Seconds
        playerOverlay.onRewind10 = playerViewModel::rewind10Seconds
        playerOverlay.onToggleFullscreen = playerViewModel::toggleFullscreen
        // Vuốt xuống tay cầm sheet chính chỉ có một hướng: vào fullscreen.
        playerOverlay.onEnterFullscreen = { playerViewModel.setFullscreen(true) }
        playerOverlay.onSkipSponsor = playerViewModel::skipCurrentSponsorSegment
        playerOverlay.onUnskipSponsor = playerViewModel::unskipCurrentSponsorSegment
        playerOverlay.onEnterPiP = { enterPiPMode() }
        playerOverlay.onPrevious = playerViewModel::playPreviousVideo
        playerOverlay.onNext = { playerViewModel.playNextVideo(force = true) }
        playerOverlay.onRetry = playerViewModel::retryPlayback
        playerOverlay.onSetAutoPlayMode = playerViewModel::setAutoPlayMode

        playerOverlay.onToggleFavorite = playerViewModel::toggleFavorite
        playerOverlay.onToggleWatchLater = playerViewModel::toggleWatchLater
        playerOverlay.onToggleSubscription = playerViewModel::toggleSubscription
        playerOverlay.onPlayNext = playerViewModel::playNextInQueue
        playerOverlay.onAddToQueue = playerViewModel::addToQueue
        playerOverlay.onToggleBackgroundPlay = playerViewModel::toggleBackgroundPlay
        // Tỉ lệ video chỉ biết sau khi Media3 giải mã khung hình đầu; cập nhật
        // lại tham số PiP để cửa sổ không bị kẹt ở 16:9 với video dọc.
        playerOverlay.onPipParamsInvalidated = { updatePipParams() }
        playerOverlay.onShowSleepTimer = { showSleepTimerDialog() }
        playerOverlay.onSetSleepTimer = playerViewModel::setSleepTimer
        playerOverlay.onSelectVideo = { video -> playerViewModel.playVideo(video) }

        playerOverlay.onLoadMoreComments = playerViewModel::loadMoreComments
        playerOverlay.onRetryComments = playerViewModel::retryComments
        playerOverlay.onSelectCommentSort = playerViewModel::setCommentSort
        playerOverlay.onLoadReplies = playerViewModel::loadReplies
        playerOverlay.onRetryReplies = playerViewModel::retryReplies

        playerOverlay.onSetPlaybackSpeed = playerViewModel::setPlaybackSpeed
        playerOverlay.onSetPlaybackPitch = playerViewModel::setPlaybackPitch
        playerOverlay.onToggleSkipSilence = playerViewModel::toggleSkipSilence
        playerOverlay.onResetPlaybackParameters = playerViewModel::resetPlaybackParameters
        playerOverlay.onSetQuality = playerViewModel::setQuality
        playerOverlay.onToggleCaptions = playerViewModel::toggleCaptions
        playerOverlay.onSelectCaptionLanguage = playerViewModel::setCaptionLanguage
    }

    /** Ảnh chụp các nguồn cần cho một lần [renderPlayer]. */
    private data class PlayerRenderInputs(
        val state: PurePlayerState,
        val plan: PlaybackPlan?,
        val failure: PlaybackFailure?
    )

    private fun observePlayer() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    // Gộp 3 nguồn ảnh hưởng trực tiếp tới khung hình thành một
                    // collector để mỗi thay đổi chỉ render đúng một lần.
                    combine(
                        playerViewModel.playerState,
                        playerViewModel.playbackPlan,
                        playerViewModel.playbackFailure
                    ) { state, plan, failure ->
                        PlayerRenderInputs(state, plan, failure)
                    }.collect { input ->
                        playerState = input.state
                        playerPlan = input.plan
                        playerFailure = input.failure
                        if (!playerOpen) return@collect
                        // Player có thể được tạo lại khi engine khởi động.
                        playerOverlay.bindPlayer(playerViewModel.player)
                        if (input.state.currentVideo == null) {
                            // Video bị đóng (hết hàng đợi, người dùng bấm đóng):
                            // ẩn overlay để lộ lại trang nội dung. Chỉ đóng khi
                            // video đã từng xuất hiện, tránh đóng nhầm lúc trạng
                            // thái còn đang đồng bộ ngay sau khi mở.
                            if (playerVideoSeen) closePlayerOverlay()
                            return@collect
                        }
                        playerVideoSeen = true
                        renderPlayer()
                        applyKeepScreenOn()
                        if (applyFullscreenUi(input.state.isFullscreen)) {
                            // Thanh hệ thống vừa ẩn/hiện: áp lại inset sau khi cờ
                            // toàn màn hình đổi để nội dung không dùng inset cũ.
                            playerOverlay.onWindowConfigurationChanged()
                        }
                    }
                }
                launch {
                    playerViewModel.position.collect { position ->
                        playerPosition = position
                        if (playerOpen) playerOverlay.updatePosition(position)
                    }
                }
                launch {
                    playerViewModel.queueState.collect { queue ->
                        playerQueue = queue
                        if (playerOpen) syncPlayerLibraryState()
                    }
                }
                launch {
                    playerViewModel.videoDetails.collect { details ->
                        if (playerOpen) playerOverlay.updateDetails(details)
                    }
                }
                launch {
                    // Trang xem dùng ViewModel riêng nên snackbar của trang
                    // không tự nhận thông báo Đăng ký/Lưu; thu riêng luồng này.
                    playerViewModel.message.collect { text ->
                        if (text != null) {
                            showSnackbar(text)
                            playerViewModel.consumeMessage()
                        }
                    }
                }
                launch {
                    playerViewModel.favoriteIds.collect { ids ->
                        playerFavoriteIds = ids
                        if (playerOpen) syncPlayerLibraryState()
                    }
                }
                launch {
                    playerViewModel.watchLaterIds.collect { ids ->
                        playerWatchLaterIds = ids
                        if (playerOpen) syncPlayerLibraryState()
                    }
                }
                launch {
                    playerViewModel.subscribedChannelIds.collect { ids ->
                        playerSubscribedChannelIds = ids
                        if (playerOpen) syncPlayerSubscriptionState()
                    }
                }
                launch {
                    playerViewModel.history.collect { history ->
                        playerWatchProgress = watchProgressMap(history)
                        if (playerOpen) syncPlayerLibraryState()
                    }
                }
                launch {
                    playerViewModel.commentsState.collect { state ->
                        if (playerOpen) playerOverlay.updateComments(state)
                    }
                }
            }
        }
    }

    private fun renderPlayer() {
        playerOverlay.updatePlayerState(
            state = playerState,
            position = playerPosition,
            plan = playerPlan,
            failure = playerFailure
        )
        updatePipParams()
    }

    private fun syncPlayerLibraryState() {
        playerOverlay.updateQueue(
            queue = playerQueue,
            favoriteIds = playerFavoriteIds,
            watchLaterIds = playerWatchLaterIds,
            watchProgress = playerWatchProgress
        )
    }

    /** Đẩy trạng thái đăng ký cục bộ sang nút Đăng ký trên trang xem. */
    private fun syncPlayerSubscriptionState() {
        playerOverlay.updateSubscriptions(playerSubscribedChannelIds)
    }

    private fun showSleepTimerDialog() {
        val options = listOf(
            0 to getString(R.string.settings_off),
            15 to getString(R.string.player_minutes, 15),
            30 to getString(R.string.player_minutes, 30),
            45 to getString(R.string.player_minutes, 45),
            60 to getString(R.string.player_minutes, 60)
        )
        val selected = options.indexOfFirst { it.first == playerState.sleepTimerMinutes }
            .coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_sleep_title)
            .setSingleChoiceItems(options.map { it.second }.toTypedArray(), selected) { dialog, which ->
                playerViewModel.setSleepTimer(options[which].first)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.settings_close, null)
            .show()
    }

    // ------------------------------------------------------------------
    // Toàn màn hình / PiP / giữ màn hình sáng
    // ------------------------------------------------------------------

    /** @return true nếu trạng thái toàn màn hình vừa thay đổi. */
    private fun applyFullscreenUi(fullscreen: Boolean): Boolean {
        // Toàn màn hình chỉ có ý nghĩa khi trình phát đang mở.
        val effective = fullscreen && playerOpen
        // Fullscreen luôn giữ dọc: cả nút trên HUD lẫn cú kéo tay cầm đều
        // không tự động xoay máy. Khoá PORTRAIT thay vì để UNSPECIFIED để người
        // dùng bật tự xoay lúc đang toàn màn cũng không đẩy video sang ngang.
        val desiredOrientation = if (effective) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (requestedOrientation != desiredOrientation) {
            requestedOrientation = desiredOrientation
        }
        if (effective == lastFullscreen) return false
        lastFullscreen = effective
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (effective) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            // Thanh hệ thống hiện tạm khi vuốt nằm trên video tối, nên icon phải
            // sáng bất kể theme sáng/tối của ứng dụng.
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            val lightIcons = !isNightMode()
            controller.isAppearanceLightStatusBars = lightIcons
            controller.isAppearanceLightNavigationBars = lightIcons
        }
        return true
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun applyKeepScreenOn() {
        if (playerOpen && playerState.isPlaying) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun enterPiPMode(byUserAction: Boolean = true) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || isInPictureInPictureMode) return
        playerOverlay.dismissSheets()
        // Đánh dấu trước khi gọi: callback vào PiP có thể chạy ngay sau đó.
        // Chỉ khi người dùng bấm nút PiP mới cần chủ động mở Home; tự vào PiP
        // lúc rời app thì hệ thống đã hiển thị đúng ứng dụng người dùng chọn.
        pipRequestedByUser = byUserAction
        val entered = runCatching {
            enterPictureInPictureMode(buildPipParams(autoEnter = false))
        }.getOrDefault(false)
        if (!entered) pipRequestedByUser = false
    }

    /**
     * Đưa ứng dụng ra nền để cửa sổ PiP nổi trên Home (hoặc ứng dụng trước
     * đó), đúng nghĩa "thoát khỏi app".
     *
     * Không dùng [moveTaskToBack]: sau khi trình phát được hợp nhất vào
     * PageActivity, Activity đang ghim PiP chính là Activity gọi hàm này, nên
     * đẩy task của nó xuống nền sẽ khiến hệ thống đóng luôn cửa sổ PiP. Mở Home
     * bằng intent tương đương người dùng bấm nút Home: PiP vẫn nổi phía trên,
     * đồng thời các trang còn lại trong task không lộ ra sau cửa sổ.
     */
    private fun sendAppToBackgroundForPip() {
        if (isFinishing || isDestroyed) return
        runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Cập nhật tham số PiP (tỉ lệ thật, nút điều khiển, tự động vào PiP). */
    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // Luôn đẩy tham số mới, kể cả khi trình phát đã đóng: nếu bỏ qua, Activity
        // sẽ giữ nguyên tham số cũ với auto-enter bật và tự vào PiP khi người
        // dùng rời app dù không còn video nào. Trong PiP vẫn cập nhật để icon
        // play/pause khớp trạng thái sau khi bấm nút.
        val autoEnter = playerOpen && !isInPictureInPictureMode &&
            playerState.isPlaying && playerViewModel.shouldAutoEnterPiP()
        runCatching {
            setPictureInPictureParams(buildPipParams(autoEnter = autoEnter))
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(autoEnter: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(pipAspectRatio())
            .setActions(pipActions())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter)
        }
        return builder.build()
    }

    private fun pipAspectRatio(): Rational {
        val ratio = playerOverlay.videoAspectRatio
            ?.takeIf { it.isFinite() && it > 0f }
            ?: (16f / 9f)
        // PiP chỉ chấp nhận tỉ lệ trong khoảng 0.42–2.39.
        val milli = (ratio * 1000f).roundToInt().coerceIn(420, 2390)
        return Rational(milli, 1000)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun pipActions(): List<RemoteAction> {
        val toggleIcon = if (playerState.isPlaying) {
            R.drawable.ic_pause
        } else {
            R.drawable.ic_play_arrow
        }
        val toggleLabel = if (playerState.isPlaying) {
            R.string.player_pause
        } else {
            R.string.player_play
        }
        return listOf(
            pipAction(toggleIcon, toggleLabel, PlayerPipActionReceiver.ACTION_TOGGLE_PLAY, 1),
            pipAction(
                R.drawable.ic_skip_previous,
                R.string.player_previous,
                PlayerPipActionReceiver.ACTION_PREVIOUS,
                2
            ),
            pipAction(
                R.drawable.ic_skip_next,
                R.string.player_next,
                PlayerPipActionReceiver.ACTION_NEXT,
                3
            )
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun pipAction(iconRes: Int, labelRes: Int, action: String, requestCode: Int): RemoteAction {
        val label = getString(labelRes)
        val intent = Intent(this, PlayerPipActionReceiver::class.java).setAction(action)
        val pending = PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // PiP hiển thị icon trên nền tối: tô trắng để không bị chìm.
        val icon = Icon.createWithResource(this, iconRes).apply { setTint(Color.WHITE) }
        return RemoteAction(icon, label, label, pending)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode
        playerOverlay.setPipMode(isInPictureInPictureMode)
        if (isInPictureInPictureMode) {
            if (pipRequestedByUser) {
                pipRequestedByUser = false
                // Chờ cửa sổ PiP tách xong rồi mới mở Home; gọi quá sớm có
                // thể rơi vào lúc quá trình chuyển chưa hoàn tất.
                playerOverlay.post { sendAppToBackgroundForPip() }
            }
        } else {
            pipRequestedByUser = false
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()

        // API 31+ tự vào PiP qua setAutoEnterEnabled; chỉ cần đường thủ công
        // cho các bản Android cũ hơn.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        if (!playerOpen) return

        val playing = playerState.isPlaying && playerState.currentVideo != null

        if (playing && playerViewModel.shouldAutoEnterPiP() && !isInPictureInPictureMode) {
            enterPiPMode(byUserAction = false)
        }
    }

    private fun collectUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.playerState.collect { state ->
                        // Player có thể được tạo lại khi engine khởi động; gắn
                        // lại nếu cần trước khi render trạng thái mới.
                        miniPlayer.bindPlayer(viewModel.player)
                        miniPlayer.visibility =
                            if (state.currentVideo != null) View.VISIBLE else View.GONE
                        miniPlayer.render(state)
                    }
                }
                launch {
                    viewModel.position.collect { position ->
                        miniPlayer.setProgress(position.progressFraction)
                    }
                }
                launch {
                    viewModel.message.collect { text ->
                        if (text != null) {
                            showSnackbar(text)
                            viewModel.consumeMessage()
                        }
                    }
                }
            }
        }
    }

    private fun showSnackbar(text: String) {
        snackbar?.dismiss()
        val anchor = if (widthClass == WindowWidthClass.Compact) {
            bottomNav
        } else if (miniPlayer.visibility == View.VISIBLE) {
            miniPlayer
        } else {
            pageMain
        }
        snackbar = Snackbar.make(pageRoot, text, Snackbar.LENGTH_SHORT)
            .setAnchorView(anchor)
        snackbar?.show()
    }

    // ------------------------------------------------------------------
    // Điều hướng
    // ------------------------------------------------------------------

    private data class NavItem(
        val tab: AppTab,
        val rowId: Int,
        val iconId: Int,
        val labelId: Int,
        val iconRes: Int
    )

    private fun bottomNavItems(): List<NavItem> = listOf(
        NavItem(
            AppTab.HOME,
            R.id.bottom_nav_home,
            R.id.bottom_nav_home_icon,
            R.id.bottom_nav_home_label,
            R.drawable.ic_home
        ),
        NavItem(
            AppTab.SUBSCRIPTIONS,
            R.id.bottom_nav_subscriptions,
            R.id.bottom_nav_subscriptions_icon,
            R.id.bottom_nav_subscriptions_label,
            R.drawable.ic_subscriptions
        ),
        NavItem(
            AppTab.LIBRARY,
            R.id.bottom_nav_library,
            R.id.bottom_nav_library_icon,
            R.id.bottom_nav_library_label,
            R.drawable.ic_account_circle
        )
    )

    private fun railNavItems(): List<NavItem> = listOf(
        NavItem(
            AppTab.HOME,
            R.id.rail_nav_home,
            R.id.rail_nav_home_icon,
            R.id.rail_nav_home_label,
            R.drawable.ic_home
        ),
        NavItem(
            AppTab.SUBSCRIPTIONS,
            R.id.rail_nav_subscriptions,
            R.id.rail_nav_subscriptions_icon,
            R.id.rail_nav_subscriptions_label,
            R.drawable.ic_subscriptions
        ),
        NavItem(
            AppTab.LIBRARY,
            R.id.rail_nav_library,
            R.id.rail_nav_library_icon,
            R.id.rail_nav_library_label,
            R.drawable.ic_account_circle
        ),
        NavItem(
            AppTab.SETTINGS,
            R.id.rail_settings,
            R.id.rail_settings_icon,
            R.id.rail_settings_label,
            R.drawable.ic_settings
        )
    )

    private fun drawerNavItems(): List<NavItem> = listOf(
        NavItem(
            AppTab.HOME,
            R.id.drawer_nav_home,
            R.id.drawer_nav_home_icon,
            R.id.drawer_nav_home_label,
            R.drawable.ic_home
        ),
        NavItem(
            AppTab.SUBSCRIPTIONS,
            R.id.drawer_nav_subscriptions,
            R.id.drawer_nav_subscriptions_icon,
            R.id.drawer_nav_subscriptions_label,
            R.drawable.ic_subscriptions
        ),
        NavItem(
            AppTab.LIBRARY,
            R.id.drawer_nav_library,
            R.id.drawer_nav_library_icon,
            R.id.drawer_nav_library_label,
            R.drawable.ic_account_circle
        ),
        NavItem(
            AppTab.SETTINGS,
            R.id.drawer_settings,
            R.id.drawer_settings_icon,
            R.id.drawer_settings_label,
            R.drawable.ic_settings
        )
    )

    private fun bindNavigation() {
        bottomNavItems().forEach { item ->
            findViewById<View>(item.rowId).setOnClickListener { openTab(item.tab) }
        }
        railNavItems().forEach { item ->
            findViewById<View>(item.rowId).setOnClickListener { openTab(item.tab) }
        }
        drawerNavItems().forEach { item ->
            findViewById<View>(item.rowId).setOnClickListener { openTab(item.tab) }
        }
        findViewById<View>(R.id.rail_brand).setOnClickListener { openTab(AppTab.HOME) }
        findViewById<View>(R.id.drawer_brand).setOnClickListener { openTab(AppTab.HOME) }
    }

    private fun openTab(tab: AppTab) {
        if (tab != currentTab) PageNavigator.open(this, tab)
    }

    private fun updateNavigationSelection() {
        bottomNavItems().forEach { applyNavVisual(it, it.tab == currentTab, drawerRow = false) }
        railNavItems().forEach { applyNavVisual(it, it.tab == currentTab, drawerRow = false) }
        drawerNavItems().forEach { applyNavVisual(it, it.tab == currentTab, drawerRow = true) }
    }

    private fun applyNavVisual(item: NavItem, selected: Boolean, drawerRow: Boolean) {
        val color = ContextCompat.getColor(
            this,
            if (selected) R.color.pure_nav_selected else R.color.pure_nav_unselected
        )
        val icon = findViewById<ImageView>(item.iconId)
        icon.setImageResource(item.iconRes)
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(color))
        findViewById<TextView>(item.labelId).setTextColor(color)
        if (drawerRow) {
            findViewById<View>(item.rowId).setBackgroundResource(
                if (selected) R.drawable.bg_nav_item_selected else R.drawable.bg_nav_item
            )
        }
    }

    // ------------------------------------------------------------------
    // Bố cục thích ứng + system bar
    // ------------------------------------------------------------------

    private fun applyResponsiveLayout() {
        widthClass = windowWidthClassForWidth(resources.configuration.screenWidthDp)
        val wide = widthClass != WindowWidthClass.Compact

        sideNav.visibility = if (wide) View.VISIBLE else View.GONE
        bottomNav.visibility = if (wide) View.GONE else View.VISIBLE
        rail.visibility = if (widthClass == WindowWidthClass.Medium) View.VISIBLE else View.GONE
        drawer.visibility = if (widthClass == WindowWidthClass.Expanded) View.VISIBLE else View.GONE

        if (wide) {
            val widthRes = if (widthClass == WindowWidthClass.Expanded) {
                R.dimen.page_drawer_width
            } else {
                R.dimen.page_rail_width
            }
            val params = sideNav.layoutParams
            params.width = resources.getDimensionPixelSize(widthRes)
            sideNav.layoutParams = params
        }

        updateNavigationSelection()
        applyBarAppearance()
    }

    private fun applyBarAppearance() {
        val nightMode = (resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, pageRoot).apply {
            isAppearanceLightStatusBars = !nightMode
            isAppearanceLightNavigationBars = !nightMode
        }
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(pageRoot) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val railBase = dp(4)
            val drawerBase = dp(8)

            pageRoot.setPadding(bars.left, 0, bars.right, 0)
            contentContainer.updatePadding(top = bars.top)

            if (widthClass == WindowWidthClass.Compact) {
                pageMain.updatePadding(bottom = 0)
                bottomNav.updatePadding(bottom = bars.bottom)
                rail.updatePadding(top = railBase, bottom = railBase)
                drawer.updatePadding(top = drawerBase, bottom = drawerBase)
            } else {
                pageMain.updatePadding(bottom = bars.bottom)
                bottomNav.updatePadding(bottom = 0)
                rail.updatePadding(top = bars.top + railBase, bottom = bars.bottom + railBase)
                drawer.updatePadding(
                    top = bars.top + drawerBase,
                    bottom = bars.bottom + drawerBase
                )
            }
            insets
        }
        // Overlay là sibling của pageRoot nên không nhận inset từ listener trên;
        // cần listener riêng để video/HUD chừa thanh hệ thống đúng.
        ViewCompat.setOnApplyWindowInsetsListener(playerOverlay) { _, insets ->
            playerOverlay.applyWindowInsets(insets)
            insets
        }
        ViewCompat.requestApplyInsets(pageRoot)
        ViewCompat.requestApplyInsets(playerOverlay)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyResponsiveLayout()
        ViewCompat.requestApplyInsets(pageRoot)
        ViewCompat.requestApplyInsets(playerOverlay)
        // Fullscreen đã khoá dọc nên không còn trường hợp xoay ngang → dọc cần
        // tự thoát. Mọi thay đổi cấu hình chỉ cần tính lại bố cục/inset.
        if (playerOpen) playerOverlay.onWindowConfigurationChanged()
    }

    override fun onStart() {
        super.onStart()
        if (playerOpen) {
            applyKeepScreenOn()
            playerOverlay.onResume()
        }
    }

    override fun onResume() {
        super.onResume()
        // Mở rộng cửa sổ PiP chỉ gọi onResume (không qua onStart); gắn lại
        // surface cho HUD để chắc chắn video không đen sau khi đổi cửa sổ.
        if (playerOpen) playerOverlay.onResume()
    }

    override fun onStop() {
        super.onStop()
        // App không còn hiển thị: nhả cờ giữ sáng (sẽ bật lại ở onStart).
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        // Đóng PiP bằng nút X có thể không đi qua onPictureInPictureModeChanged;
        // xoá cờ để lần chuyển nền sau không bị coi là vẫn đang trong PiP.
        isInPipMode = false
        if (playerOpen) {
            playerOpen = false
            playerOverlay.release()
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Quyền thông báo + Back
    // ------------------------------------------------------------------

    /**
     * Giữ nguyên cách thoát của bản một-Activity cũ:
     * - Trang chủ: Back thoát hẳn ứng dụng (`finishAffinity` cũng dọn các trang
     *   còn nằm dưới trong task do chuyển tab bằng reorder-to-front).
     * - Cài đặt: quay về "Bạn"; các trang khác quay về Trang chủ.
     *
     * BackHandler trong từng màn (ví dụ xoá kết quả tìm kiếm) được ưu tiên vì
     * được đăng ký sau callback này.
     */
    private fun registerBackNavigation() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // Trình phát overlay đang mở: Back dùng chung một cơ chế với
                    // nút chevron và thao tác kéo xuống (thu nhỏ về mini player).
                    if (playerOpen) {
                        if (playerState.isFullscreen) {
                            playerViewModel.setFullscreen(false)
                        } else {
                            playerOverlay.minimize()
                        }
                        return
                    }
                    if (currentTab == AppTab.HOME) {
                        finishAffinity()
                        return
                    }
                    PageNavigator.open(this@PageActivity, backParentTab)
                    finish()
                }
            }
        )
    }

    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        synchronized(notificationPermissionRequested) {
            if (notificationPermissionRequested.get()) return
            notificationPermissionRequested.set(true)
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        /** Chỉ hỏi quyền thông báo một lần trong toàn bộ phiên app. */
        private val notificationPermissionRequested = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * Cờ để [com.luxysiv.puretube.PureTubeApplication] không coi việc vào
         * PiP là app chuyển xuống nền (tránh dừng phát khi "Phát trong nền" tắt).
         */
        @Volatile
        var isInPipMode: Boolean = false
            private set

        /**
         * Mở trình phát overlay ngay khi Activity được mở từ notification "đang
         * phát" (xem [com.luxysiv.puretube.player.PlaybackService]).
         */
        const val EXTRA_OPEN_PLAYER = "com.luxysiv.puretube.extra.OPEN_PLAYER"
    }
}

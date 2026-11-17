@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.luxysiv.puretube.ui.player

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.provider.Settings
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.luxysiv.puretube.R
import com.luxysiv.puretube.player.AutoPlayMode
import com.luxysiv.puretube.player.PlaybackFailure
import com.luxysiv.puretube.player.PlaybackPosition
import com.luxysiv.puretube.player.PurePlayerState
import com.luxysiv.puretube.ui.views.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Trình phát native: surface Media3 dùng chung + lớp HUD điều khiển.
 *
 * Surface (PlayerView/TextureView) do [PlayerSurfaceHost] sở hữu và di chuyển
 * vào HUD này khi trang xem hiển thị; mini player/PiP dùng lại đúng surface đó.
 * HUD chỉ vẽ lớp điều khiển phía trên nên chuyển màn hình không tạo texture mới.
 *
 * HUD gồm ba vùng: thanh trên (thu nhỏ, tiêu đề, tự động phát, PiP), cụm giữa
 * (trước/play-pause/sau) và thanh dưới (thời gian, thanh tua có marker
 * SponsorBlock, hàng nút). Toàn bộ logic phát nằm ở player module; view chỉ
 * hiển thị trạng thái và chuyển thao tác người dùng thành callback.
 *
 * Hai chế độ kích thước:
 * - **compact** khi trình phát nhúng trong trang xem (khung 16:9 thấp): thu
 *   nhỏ thanh trên, nút play, ẩn nút chuyển bài và nhãn thời gian còn lại.
 * - **full** khi toàn màn hình/PiP: hiện đủ nút và khoảng đệm rộng hơn.
 */
class PlayerHudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Surface dùng chung cho player/fullscreen/mini player/PiP. */
    private val playerView: PlayerView

    /** Container tạm của HUD; surface được host di chuyển vào/ra khỏi đây. */
    private val surfaceContainer: FrameLayout

    private val topScrim: View
    private val bottomScrim: View
    private val topBar: View
    private val closeButton: ImageButton
    private val titleView: TextView
    private val channelView: TextView
    private val autoPlayButton: ImageButton
    private val pipButton: ImageButton

    private val centerControls: View
    private val previousButton: ImageButton
    private val playPauseButton: ImageButton
    private val nextButton: ImageButton
    private val bufferingView: ProgressBar

    private val bottomControls: View
    private val currentTimeView: TextView
    private val totalTimeView: TextView
    private val remainingTimeView: TextView
    private val liveBadge: TextView
    private val seekContainer: View
    private val seekBar: PlayerSeekBar
    private val seekPreview: TextView
    private val buttonRow: View
    private val mixButton: ImageButton
    private val captionsButton: ImageButton
    private val settingsButton: ImageButton
    private val fullscreenButton: ImageButton

    private val sponsorSkipButton: TextView
    private val lockButton: ImageButton
    private val unlockButton: ImageButton

    private val errorOverlay: View
    private val errorTitle: TextView
    private val errorDetail: TextView
    private val errorRetry: View

    private val flashOverlay: View
    private val flashPill: View
    private val flashIcon: ImageView
    private val flashText: TextView

    var onTogglePlayPause: () -> Unit = {}
    var onSeekTo: (Float) -> Unit = {}
    var onForward10: () -> Unit = {}
    var onRewind10: () -> Unit = {}
    var onToggleFullscreen: () -> Unit = {}
    var onSkipSponsor: () -> Unit = {}
    var onUnskipSponsor: () -> Unit = {}
    var onClose: () -> Unit = {}

    /**
     * Hiệu ứng thu nhỏ kiểu YouTube. View cha ([PlayerNativeView]) sở hữu
     * khung video nên nó dựng chuyển động; HUD chỉ báo tiến độ kéo và thời
     * điểm thả tay.
     */
    var onMinimizeBegin: (() -> Unit)? = null
    var onMinimizeProgress: ((Float) -> Unit)? = null
    /**
     * Quãng đường thật (px) từ vị trí khung hình tới mini player, do
     * [PlayerNativeView] chốt khi bắt đầu kéo. Nhờ vậy khung hình đi xuống
     * đúng 1:1 với ngón tay thay vì lao nhanh gấp nhiều lần.
     */
    var minimizeDragDistancePx = 0f
    /**
     * Kết thúc thao tác thu nhỏ. Tham số thứ hai là vận tốc tiến trình
     * (0..1 mỗi giây, dương = đang thu nhỏ) để animation nối tiếp chuyển động
     * của ngón tay thay vì dừng lại rồi chạy lại từ đầu.
     */
    var onMinimizeEnd: ((Boolean, Float) -> Unit)? = null

    /** Bấm nút chevron xuống: chạy đúng hiệu ứng của thao tác vuốt xuống. */
    var onMinimize: (() -> Unit)? = null
    var onEnterPiP: () -> Unit = {}
    var onPrevious: () -> Unit = {}
    var onNext: () -> Unit = {}
    var onSetAutoPlayMode: (AutoPlayMode) -> Unit = {}
    var onOpenSettings: () -> Unit = {}
    var onOpenMix: () -> Unit = {}
    var onRetry: () -> Unit = {}
    var onToggleCaptions: () -> Unit = {}
    var onTracksChanged: (() -> Unit)? = null
    var onVideoAspectRatioChanged: (() -> Unit)? = null

    private var player: Player? = null
    private var state: PurePlayerState? = null
    private var position = PlaybackPosition()
    private var isLive = false
    private var autoPlayMode = AutoPlayMode.NEXT
    private var hasMix = false
    private var playbackFailed = false
    private var inPip = false

    private var controlsVisible = true
    private var isLocked = false
    private var settingsOpen = false
    private var isSeeking = false
    private var seekFraction = 0f
    private var compact = false

    private var captionTracks: List<PlayerCaptionTrack> = emptyList()
    private var selectedCaptionLanguage: String = ""
    private var activeVideoHeight: Int? = null
    private var activeAspectRatio: Float? = null

    /** Padding gốc của các thanh, dùng để cộng thêm window insets. */
    private val topBarBasePadding = IntArray(4)
    private val bottomControlsBasePadding = IntArray(4)

    private var subtitleWidth = 0
    private var subtitleHeight = 0
    private var appliedSubtitleFraction = -1f

    /** Ngôn ngữ phụ đề thật của Media3, dùng cho sheet cài đặt. */
    val captionOptions: List<PlayerCaptionTrack> get() = captionTracks
    val currentCaptionLanguage: String get() = selectedCaptionLanguage
    val currentVideoHeight: Int? get() = activeVideoHeight

    /** Tỉ lệ rộng/cao thật của video, dùng cho cửa sổ PiP; null khi chưa có. */
    val videoAspectRatio: Float? get() = activeAspectRatio
    val hasCaptions: Boolean get() = captionTracks.isNotEmpty()

    private val hideRunnable = Runnable {
        if (controlsVisible && !isLocked && !settingsOpen && !isSeeking &&
            state?.isPlaying == true
        ) {
            controlsVisible = false
            applyControlsVisibility()
        }
    }

    private val flashRunnable = Runnable {
        flashPill.animate()
            .alpha(0f)
            .setDuration(160)
            .withEndAction {
                if (flashPill.alpha == 0f) flashOverlay.visibility = View.GONE
            }
            .start()
    }

    private val gestureDetector: GestureDetector
    private val scaleDetector: ScaleGestureDetector
    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var zoomBase = 1f

    /**
     * Mức âm lượng dạng thực trong lúc vuốt. Cần tích luỹ vì mỗi sự kiện chỉ
     * dịch vài phần trăm, nếu làm tròn ngay sẽ gần như không đổi được âm lượng.
     */
    private var volumeLevel: Float? = null

    /** Nửa màn hình đã chọn khi bắt đầu vuốt dọc: phải = âm lượng, trái = sáng. */
    private var levelAdjustsVolume = false

    init {
        // HUD được inflate trước; surface dùng chung được host chèn xuống dưới
        // cùng để lớp điều khiển luôn nằm trên hình video.
        LayoutInflater.from(context).inflate(R.layout.view_player_hud, this, true)
        playerView = PlayerSurfaceHost.obtain(context)
        surfaceContainer = FrameLayout(context).apply {
            isClickable = false
            isFocusable = false
            // Giữ nền đen khi surface tạm thời được mini player mượn.
            setBackgroundColor(Color.BLACK)
        }
        addView(
            surfaceContainer,
            0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        topScrim = findViewById(R.id.hud_top_gradient)
        bottomScrim = findViewById(R.id.hud_bottom_gradient)
        topBar = findViewById(R.id.hud_top_bar)
        closeButton = findViewById(R.id.hud_close)
        titleView = findViewById(R.id.hud_title)
        channelView = findViewById(R.id.hud_channel)
        autoPlayButton = findViewById(R.id.hud_autoplay)
        pipButton = findViewById(R.id.hud_pip)

        centerControls = findViewById(R.id.hud_center_controls)
        previousButton = findViewById(R.id.hud_previous)
        playPauseButton = findViewById(R.id.hud_play_pause)
        nextButton = findViewById(R.id.hud_next)
        bufferingView = findViewById(R.id.hud_buffering)

        bottomControls = findViewById(R.id.hud_bottom_controls)
        currentTimeView = findViewById(R.id.hud_time_current)
        totalTimeView = findViewById(R.id.hud_time_total)
        remainingTimeView = findViewById(R.id.hud_time_remaining)
        liveBadge = findViewById(R.id.hud_live_badge)
        seekContainer = findViewById(R.id.hud_seek_container)
        seekBar = findViewById(R.id.hud_seek)
        seekPreview = findViewById(R.id.hud_seek_preview)
        buttonRow = findViewById(R.id.hud_button_row)
        mixButton = findViewById(R.id.hud_mix)
        captionsButton = findViewById(R.id.hud_captions)
        settingsButton = findViewById(R.id.hud_settings)
        fullscreenButton = findViewById(R.id.hud_fullscreen)

        sponsorSkipButton = findViewById(R.id.hud_sponsor_skip)
        lockButton = findViewById(R.id.hud_lock)
        unlockButton = findViewById(R.id.hud_unlock)

        errorOverlay = findViewById(R.id.hud_error)
        errorTitle = findViewById(R.id.hud_error_title)
        errorDetail = findViewById(R.id.hud_error_detail)
        errorRetry = findViewById(R.id.hud_error_retry)

        flashOverlay = findViewById(R.id.hud_flash)
        flashPill = findViewById(R.id.hud_flash_pill)
        flashIcon = findViewById(R.id.hud_flash_icon)
        flashText = findViewById(R.id.hud_flash_text)

        topBarBasePadding[0] = topBar.paddingLeft
        topBarBasePadding[1] = topBar.paddingTop
        topBarBasePadding[2] = topBar.paddingRight
        topBarBasePadding[3] = topBar.paddingBottom
        bottomControlsBasePadding[0] = bottomControls.paddingLeft
        bottomControlsBasePadding[1] = bottomControls.paddingTop
        bottomControlsBasePadding[2] = bottomControls.paddingRight
        bottomControlsBasePadding[3] = bottomControls.paddingBottom

        playerView.apply {
            useController = false
            setBackgroundColor(Color.BLACK)
            @Suppress("DEPRECATION")
            subtitleView?.apply {
                setApplyEmbeddedStyles(false)
                setApplyEmbeddedFontSizes(false)
                setStyle(captionStyle())
                val side = context.dp(16f)
                setPadding(side, 0, side, 0)
            }
        }
        // Pinch-to-zoom phóng texture vượt khung 16:9; tắt clip để không bị cắt.
        (playerView.videoSurfaceView?.parent as? ViewGroup)?.clipChildren = false

        closeButton.setOnClickListener {
            // Toàn màn hình: chevron chỉ thoát toàn màn hình, vì khung mini
            // player nằm ở bố cục dọc nên không thể ánh xạ đúng vào cửa sổ
            // ngang. Khi đã ở bố cục dọc, chevron thu nhỏ trang bằng đúng cơ
            // chế của Back và kéo xuống.
            if (state?.isFullscreen == true) {
                showControls()
                onToggleFullscreen()
            } else if (onMinimize != null) {
                onMinimize?.invoke()
            } else {
                showControls()
                onClose()
            }
        }
        playPauseButton.setOnClickListener {
            showControls()
            onTogglePlayPause()
        }
        previousButton.setOnClickListener {
            // Luôn cho phép: coordinator tự tua về đầu video khi đang phát
            // quá 3 giây, chỉ chuyển bài khi thực sự có video trước.
            showControls()
            onPrevious()
        }
        nextButton.setOnClickListener {
            showControls()
            onNext()
        }
        pipButton.setOnClickListener {
            showControls()
            onEnterPiP()
        }
        autoPlayButton.setOnClickListener {
            showControls()
            onSetAutoPlayMode(autoPlayMode.next())
        }
        mixButton.setOnClickListener {
            showControls()
            onOpenMix()
        }
        captionsButton.setOnClickListener {
            showControls()
            onToggleCaptions()
        }
        settingsButton.setOnClickListener {
            showControls()
            onOpenSettings()
        }
        fullscreenButton.setOnClickListener {
            showControls()
            onToggleFullscreen()
        }
        sponsorSkipButton.setOnClickListener {
            val data = state ?: return@setOnClickListener
            if (data.sponsorCanUnskip) onUnskipSponsor() else onSkipSponsor()
        }
        lockButton.setOnClickListener {
            isLocked = true
            controlsVisible = false
            applyControlsVisibility()
        }
        unlockButton.setOnClickListener {
            isLocked = false
            showControls()
        }
        errorRetry.setOnClickListener { onRetry() }

        seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: android.widget.SeekBar?) {
                isSeeking = true
                seekFraction = position.progressFraction
                showControls()
                showSeekPreview(seekFraction, seekFraction * position.durationSeconds)
            }

            override fun onProgressChanged(
                bar: android.widget.SeekBar?,
                progress: Int,
                fromUser: Boolean
            ) {
                if (!fromUser) return
                seekFraction = progress.toFloat() / (bar?.max ?: 1).coerceAtLeast(1)
                val seconds = seekFraction * position.durationSeconds
                updateSeekTimeLabels(seconds)
                showSeekPreview(seekFraction, seconds)
            }

            override fun onStopTrackingTouch(bar: android.widget.SeekBar?) {
                hideSeekPreview()
                if (!isLive && position.durationSeconds > 0f) {
                    onSeekTo(seekFraction * position.durationSeconds)
                }
                isSeeking = false
                updatePosition(position)
                scheduleAutoHide()
            }
        })

        // Pinch-to-zoom giống Just Player: phóng to khung hình đang phát mà
        // không đổi kích thước PlayerView, nhờ vậy HUD vẫn đứng yên.
        scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    if (isLocked || playbackFailed) return false
                    // Huỷ thao tác tua đang dở để pinch không kích hoạt seek.
                    dragMode = DragMode.NONE
                    isSeeking = false
                    hideSeekPreview()
                    zoomBase = playerView.videoSurfaceView?.scaleX ?: 1f
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    // scaleFactor là tỉ lệ so với sự kiện trước, nên phải nhân
                    // dồn vào zoomBase thay vì luôn nhân với mức ban đầu.
                    zoomBase = (zoomBase * detector.scaleFactor)
                        .coerceIn(1f, MAX_ZOOM)
                    applyZoom(zoomBase)
                    showLevelIndicator(
                        R.drawable.ic_fullscreen,
                        "${(zoomBase * 100f).roundToInt()}%"
                    )
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    if (zoomBase <= 1.02f) resetZoom()
                }
            }
        )

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                dragMode = DragMode.NONE
                dragTotalX = 0f
                dragTotalY = 0f
                dragStartSeconds = position.currentSeconds
                dragFraction = position.progressFraction
                volumeLevel = null
                minimizeFling = false
                minimizeVelocityY = 0f
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!isLocked && !playbackFailed) {
                    if (controlsVisible) {
                        controlsVisible = false
                        applyControlsVisibility()
                    } else {
                        showControls()
                    }
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isLocked || playbackFailed) return false
                val third = width / 3f
                when {
                    e.x < third && !isLive -> {
                        onRewind10()
                        showFlash(R.drawable.ic_replay_10, -0.24f)
                        showControls()
                    }

                    e.x > third * 2 && !isLive -> {
                        onForward10()
                        showFlash(R.drawable.ic_forward_10, 0.24f)
                        showControls()
                    }

                    else -> {
                        val wasPlaying = state?.isPlaying == true
                        onTogglePlayPause()
                        // `state` chỉ cập nhật bất đồng bộ sau toggle, nên suy
                        // trạng thái kết quả để icon flash luôn khớp video.
                        showFlash(
                            if (wasPlaying) R.drawable.ic_play_arrow else R.drawable.ic_pause,
                            0f
                        )
                        showControls()
                    }
                }
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (dragMode != DragMode.SWIPE_DOWN) return false
                // Lưu vận tốc thật (kể cả khi chưa đủ nhanh để commit) để
                // spring animation nối tiếp đúng chuyển động của ngón tay.
                minimizeVelocityY =
                    if (abs(velocityY) > abs(velocityX)) velocityY else 0f
                // Vuốt xuống nhanh cũng thu nhỏ dù chưa đi đủ quãng đường.
                if (onMinimizeEnd != null &&
                    velocityY > minimizeFlingVelocityPx.toFloat() &&
                    abs(velocityY) > abs(velocityX)
                ) {
                    // Không nhảy thẳng tới đích: để endMinimizeDrag chạy nốt
                    // phần còn lại cho mượt như khi kéo đủ quãng.
                    minimizeFling = true
                    return true
                }
                return false
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (isLocked || playbackFailed) return false
                // Đang pinch-to-zoom thì không tua/chỉnh âm lượng bằng ngón thứ nhất.
                if (scaleDetector.isInProgress) return false
                dragTotalX += distanceX
                dragTotalY += distanceY
                if (dragMode == DragMode.NONE) {
                    // Live không có timeline để tua nên chỉ nhận cử chỉ dọc.
                    if (!isLive && abs(dragTotalX) > touchSlop &&
                        abs(dragTotalX) > abs(dragTotalY)
                    ) {
                        dragMode = DragMode.SEEK
                    } else if (abs(dragTotalY) > touchSlop) {
                        // Fullscreen: vuốt dọc trái = độ sáng, phải = âm lượng
                        // (tham khảo Just Player). Trình phát nhúng chỉ nhận
                        // vuốt xuống để thu nhỏ; vuốt lên không làm gì.
                        dragMode = if (state?.isFullscreen == true) {
                            // Chốt nửa màn hình ngay khi bắt đầu để vuốt không
                            // nhảy qua lại giữa độ sáng và âm lượng.
                            levelAdjustsVolume = e2.x >= width / 2f
                            DragMode.LEVEL
                        } else if (dragTotalY < 0f) {
                            // Bắt đầu thu nhỏ. PlayerNativeView tự ẩn HUD trong
                            // beginMinimizeDrag(), nên mọi lối vào dùng chung
                            // đúng một lần ẩn và một phép biến đổi.
                            if (onMinimizeBegin != null) {
                                onMinimizeBegin?.invoke()
                            }
                            DragMode.SWIPE_DOWN
                        } else {
                            DragMode.NONE
                        }
                    }
                }
                when (dragMode) {
                    DragMode.SEEK -> {
                        val duration = position.durationSeconds
                        if (duration > 0f && !isLive) {
                            val deltaSeconds = -dragTotalX / width.coerceAtLeast(1) * duration
                            val seconds = (dragStartSeconds + deltaSeconds)
                                .coerceIn(0f, duration)
                            dragFraction = seconds / duration
                            isSeeking = true
                            seekFraction = dragFraction
                            seekBar.progress = (dragFraction * seekBar.max).roundToInt()
                            updateSeekTimeLabels(seconds)
                            showSeekPreview(dragFraction, seconds)
                            showControls()
                        }
                    }

                    DragMode.LEVEL -> {
                        val delta = distanceY / height.coerceAtLeast(1)
                        if (delta != 0f) {
                            if (levelAdjustsVolume) {
                                adjustVolume(delta)
                            } else {
                                adjustBrightness(delta)
                            }
                        }
                    }

                    DragMode.SWIPE_DOWN -> {
                        // Tính theo toạ độ thô (toạ độ cửa sổ) chứ không dùng
                        // delta cục bộ: cả trang đang scale/translate nên delta
                        // cục bộ bị chia cho hệ số scale, khiến cùng một quãng
                        // vuốt chạy nhanh dần và hiệu ứng bị "vọt".
                        minimizeDragPx = (e2.rawY - minimizeStartRawY)
                            .coerceAtLeast(0f)
                        val progress =
                            (minimizeDragPx / minimizeDistanceForDrag())
                                .coerceIn(0f, 1f)
                        onMinimizeProgress?.invoke(progress)
                    }

                    DragMode.NONE -> Unit
                }
                return dragMode != DragMode.NONE
            }

            override fun onLongPress(e: MotionEvent) {
                // Chạm giữ để khoá/mở khoá điều khiển khi đang xem toàn màn
                // hình — cùng thao tác với Just Player, không cần mở HUD.
                if (playbackFailed || state?.isFullscreen != true) return
                isLocked = !isLocked
                if (isLocked) {
                    controlsVisible = false
                    applyControlsVisibility()
                } else {
                    showControls()
                }
                showLevelIndicator(
                    if (isLocked) R.drawable.ic_lock else R.drawable.ic_lock_open,
                    getString(
                        if (isLocked) {
                            R.string.player_lock_controls
                        } else {
                            R.string.player_unlock_controls
                        }
                    )
                )
            }
        })

        // Gắn gesture vào HUD, KHÔNG gắn vào PlayerView dùng chung: surface có
        // thể đang được mini player mượn, listener ở đây sẽ nuốt chạm của mini
        // player nếu gắn trực tiếp lên view.
        setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Toạ độ thô làm mốc cho thao tác thu nhỏ: HUD nằm trong
                    // khối đang scale nên toạ độ cục bộ bị khuếch đại dần.
                    minimizeStartRawY = event.rawY
                    minimizeDragPx = 0f
                }
                MotionEvent.ACTION_UP -> finishDrag(cancelled = false)
                MotionEvent.ACTION_CANCEL -> finishDrag(cancelled = true)
            }
            true
        }

        seekPreview.translationY = -context.dp(28f).toFloat()
        applyControlsVisibility()
    }

    private var dragMode = DragMode.NONE
    private var dragTotalX = 0f
    private var dragTotalY = 0f
    private var dragStartSeconds = 0f
    private var dragFraction = 0f
    private var minimizeFling = false
    /** Vận tốc dọc lúc thả tay (px/s, dương = đi xuống). */
    private var minimizeVelocityY = 0f
    /** Toạ độ Y thô (toạ độ cửa sổ) lúc bắt đầu chạm. */
    private var minimizeStartRawY = 0f
    /** Quãng đã vuốt xuống theo toạ độ cửa sổ (px). */
    private var minimizeDragPx = 0f
    private val touchSlop: Int get() = ViewConfiguration.get(context).scaledTouchSlop

    /** Quãng vuốt xuống (px) để thu nhỏ hoàn toàn, giống ngưỡng cũ 120dp. */
    private val minimizeDistancePx: Int get() = context.dp(120f)

    /**
     * Quãng kéo thực tế để khung hình đi hết đường tới mini player. Trước khi
     * [PlayerNativeView] chốt được vị trí đích thì tạm dùng ngưỡng cũ.
     */
    private fun minimizeDistanceForDrag(): Float =
        minimizeDragDistancePx.takeIf { it > 0f } ?: minimizeDistancePx.toFloat()

    /** Vận tốc vuốt xuống tối thiểu (px/s) để thu nhỏ ngay cả khi quãng ngắn. */
    private val minimizeFlingVelocityPx: Int get() = context.dp(900f)

    private enum class DragMode { NONE, SEEK, LEVEL, SWIPE_DOWN }

    // ------------------------------------------------------------------
    // Vòng đời player
    // ------------------------------------------------------------------

    /** Gắn player dùng chung và lắng nghe track phụ đề. */
    fun bindPlayer(player: Player) {
        // Nguồn phát duy nhất: host chỉ giữ Player, không tạo player mới.
        PlayerSurfaceHost.bind(player)
        if (this.player !== player) {
            this.player?.removeListener(playerListener)
            this.player = player
            player.addListener(playerListener)
            updateTracks(player.currentTracks)
            player.videoSize.let { size ->
                if (size.width > 0 && size.height > 0) {
                    activeAspectRatio =
                        size.width * size.pixelWidthHeightRatio / size.height
                }
            }
            if (player.isCommandAvailable(Player.COMMAND_GET_TEXT)) {
                @Suppress("DEPRECATION")
                playerView.subtitleView?.setCues(player.currentCues.cues.centerCues())
            }
        }
        attachSurface()
    }

    /** Gọi ở onResume để surface dùng chung luôn quay về HUD của trang xem. */
    fun onResume() {
        if (player == null) return
        attachSurface()
    }

    /** Gọi ở onDestroy khi Activity kết thúc để nhả surface cho mini player. */
    fun release() {
        player?.removeListener(playerListener)
        PlayerSurfaceHost.detach(surfaceContainer)
        resetZoom()
        player = null
        removeCallbacks(hideRunnable)
        removeCallbacks(flashRunnable)
        flashPill.animate().cancel()
    }

    /**
     * Đưa surface dùng chung vào HUD. Hàm này chạy cả khi HUD chưa attach vào
     * window; lúc đó view nằm trong container và sẽ hiện ngay khi HUD được gắn.
     */
    @Suppress("DEPRECATION")
    private fun attachSurface() {
        if (player == null) return
        PlayerSurfaceHost.attach(surfaceContainer)
        playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        playerView.subtitleView?.visibility = View.VISIBLE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (player != null) attachSurface()
    }

    override fun onDetachedFromWindow() {
        // Chỉ gỡ nếu HUD còn sở hữu surface; nếu mini player vừa nhận thì
        // detach() tự bỏ qua.
        PlayerSurfaceHost.detach(surfaceContainer)
        resetZoom()
        removeCallbacks(hideRunnable)
        removeCallbacks(flashRunnable)
        flashPill.animate().cancel()
        super.onDetachedFromWindow()
    }

    private fun getString(id: Int, vararg args: Any): String = context.getString(id, *args)

    // ------------------------------------------------------------------
    // Render trạng thái
    // ------------------------------------------------------------------

    /** Cập nhật toàn bộ trạng thái cấu trúc của HUD. */
    fun render(
        state: PurePlayerState,
        autoPlayMode: AutoPlayMode,
        hasMix: Boolean,
        isLive: Boolean,
        failure: PlaybackFailure?
    ) {
        val previousPlaying = this.state?.isPlaying
        val previousVideoId = this.state?.currentVideo?.id
        this.state = state
        this.autoPlayMode = autoPlayMode
        this.hasMix = hasMix
        this.isLive = isLive
        this.playbackFailed = failure != null

        titleView.text = state.currentVideo?.title ?: getString(R.string.player_now_playing)
        channelView.text = state.currentVideo?.channelTitle.orEmpty()
        channelView.visibility =
            if (channelView.text.isNullOrBlank() || compact) View.GONE else View.VISIBLE

        updatePlayPauseButtons(state.isPlaying)
        updateAutoPlayButton(autoPlayMode)
        updateFullscreenButton(state.isFullscreen)

        // Luôn bật nút "Video trước": coordinator tự tua về đầu video hiện tại
        // nếu đang phát quá 3 giây, kể cả khi không có video trước trong hàng đợi.
        // Live không có timeline tua/previous nên ẩn các nút liên quan.
        previousButton.alpha = 1f
        previousButton.isEnabled = true
        previousButton.visibility = if (isLive || compact) View.GONE else View.VISIBLE
        nextButton.visibility = if (isLive || compact) View.GONE else View.VISIBLE
        mixButton.visibility = if (hasMix && !isLive) View.VISIBLE else View.GONE

        captionsButton.visibility = if (hasCaptions) View.VISIBLE else View.GONE
        ImageViewCompat.setImageTintList(
            captionsButton,
            ColorStateList.valueOf(
                ContextCompat.getColor(
                    context,
                    if (state.captionsEnabled) R.color.pure_primary else R.color.player_on_scrim
                )
            )
        )

        if (!state.isFullscreen) isLocked = false
        lockButton.visibility =
            if (state.isFullscreen && controlsVisible && !isLocked && !playbackFailed) {
                View.VISIBLE
            } else {
                View.GONE
            }
        unlockButton.visibility = if (isLocked) View.VISIBLE else View.GONE

        // Khi đang tải, spinner thay chỗ nút play lớn để không chồng hai biểu tượng.
        val showBuffering = state.isBuffering && !playbackFailed && !inPip
        playPauseButton.visibility = if (showBuffering) View.INVISIBLE else View.VISIBLE
        bufferingView.visibility = if (showBuffering) View.VISIBLE else View.GONE

        errorOverlay.visibility = if (failure != null) View.VISIBLE else View.GONE
        if (failure != null) {
            errorTitle.text = failure.message
            if (failure.detail.isNullOrBlank()) {
                errorDetail.visibility = View.GONE
            } else {
                errorDetail.visibility = View.VISIBLE
                errorDetail.text = failure.detail
            }
        }

        updateSponsorButton(state)
        applyControlsVisibility()
        updateSeekBarMode()
        // Chỉ đặt lại hẹn giờ ẩn khi trạng thái phát hoặc video đổi. Nếu đặt
        // lại ở mọi lần render, bộ đếm ngược hẹn giờ ngủ (cập nhật mỗi giây)
        // sẽ khiến thanh điều khiển không bao giờ tự ẩn.
        if (state.isPlaying != previousPlaying || state.currentVideo?.id != previousVideoId) {
            if (state.currentVideo?.id != previousVideoId) resetZoom()
            if (state.isPlaying) scheduleAutoHide() else showControls()
        }
    }

    /** Cập nhật vị trí phát (được gọi 2 lần/giây, không dựng lại nội dung). */
    fun renderPosition(position: PlaybackPosition) {
        this.position = position
        if (!isSeeking) updatePosition(position)
        else updateBufferedProgress(position)
    }

    fun setSettingsOpen(open: Boolean) {
        settingsOpen = open
        if (open) {
            controlsVisible = true
            removeCallbacks(hideRunnable)
            applyControlsVisibility()
        } else {
            scheduleAutoHide()
        }
    }

    fun setPipMode(inPip: Boolean) {
        this.inPip = inPip
        if (inPip) {
            controlsVisible = false
            isLocked = false
            resetZoom()
            applyControlsVisibility()
            // PiP tách Activity sang task riêng và có thể dựng lại cửa sổ. Gắn
            // lại surface sau khi bố cục ổn định để chắc chắn video không đen.
            post { attachSurface() }
        } else {
            showControls()
        }
        bufferingView.visibility =
            if (state?.isBuffering == true && !playbackFailed && !inPip) {
                View.VISIBLE
            } else {
                View.GONE
            }
        playPauseButton.visibility =
            if (state?.isBuffering == true && !playbackFailed && !inPip) {
                View.INVISIBLE
            } else {
                View.VISIBLE
            }
    }

    // ------------------------------------------------------------------
    // Chế độ gọn / inset
    // ------------------------------------------------------------------

    /**
     * Bật/tắt chế độ gọn cho trình phát nằm trong trang xem.
     *
     * Khung video nhúng chỉ cao `width * 9 / 16` — trên máy 360dp rộng thì chưa
     * tới 203dp. Ở chế độ gọn: thanh trên thấp hơn, nút play nhỏ hơn, bỏ nút
     * chuyển bài, thu nhỏ thanh tua và hàng nút. Cụm giữa luôn giữ đúng tâm
     * video như YouTube.
     */
    fun setCompactMode(compact: Boolean) {
        if (this.compact == compact) return
        this.compact = compact
        applyCompactMetrics()
        channelView.visibility =
            if (compact || channelView.text.isNullOrBlank()) View.GONE else View.VISIBLE
        previousButton.visibility = if (isLive || compact) View.GONE else View.VISIBLE
        nextButton.visibility = if (isLive || compact) View.GONE else View.VISIBLE
        mixButton.visibility = if (hasMix && !isLive) View.VISIBLE else View.GONE
        remainingTimeView.visibility = if (compact || isLive) View.GONE else View.VISIBLE
        applyControlsVisibility()
        updateSubtitleLayout(subtitleWidth, subtitleHeight)
    }

    private fun applyCompactMetrics() {
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        fun dp(value: Float) = (value * density).toInt()

        setHeight(topBar, dp(if (compact) 40f else 56f))
        setHeight(topScrim, dp(if (compact) 76f else 136f))
        setHeight(bottomScrim, dp(if (compact) 132f else 204f))
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 13f else 15f)

        val topIconSize = dp(if (compact) 36f else 44f)
        val topIconPadding = dp(if (compact) 8f else 10f)
        listOf(closeButton, autoPlayButton, pipButton).forEach { button ->
            setSize(button, topIconSize)
            button.setPadding(topIconPadding, topIconPadding, topIconPadding, topIconPadding)
        }

        val playSize = dp(if (compact) 48f else 72f)
        val playPadding = dp(if (compact) 12f else 18f)
        setSize(playPauseButton, playSize)
        playPauseButton.setPadding(playPadding, playPadding, playPadding, playPadding)

        setHeight(seekBar, dp(if (compact) 24f else 30f))
        setHeight(seekContainer, dp(if (compact) 28f else 34f))
        setHeight(buttonRow, dp(if (compact) 40f else 48f))
        seekPreview.translationY = -dp(if (compact) 24f else 28f).toFloat()

        val actionSize = dp(if (compact) 36f else 42f)
        val actionPadding = dp(if (compact) 8f else 9f)
        listOf(
            mixButton,
            captionsButton,
            settingsButton,
            fullscreenButton
        ).forEach { button ->
            setSize(button, actionSize)
            button.setPadding(actionPadding, actionPadding, actionPadding, actionPadding)
        }

        // Thanh dưới ở chế độ gọn cao ~90dp (thời gian + thanh tua + hàng nút),
        // nên nút nổi phải nằm trên mốc đó để không đè lên hàng nút.
        setBottomMargin(sponsorSkipButton, dp(if (compact) 92f else 104f))
        setBottomMargin(lockButton, dp(if (compact) 92f else 104f))
        setBottomMargin(unlockButton, dp(if (compact) 16f else 20f))
        positionCenterControls()
    }

    private fun setHeight(view: View, height: Int) {
        val lp = view.layoutParams ?: return
        lp.height = height
        view.layoutParams = lp
    }

    private fun setSize(view: View, size: Int) {
        val lp = view.layoutParams ?: return
        lp.width = size
        lp.height = size
        view.layoutParams = lp
    }

    private fun setBottomMargin(view: View, margin: Int) {
        val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.bottomMargin = margin
        view.layoutParams = lp
    }

    /**
     * Cụm giữa kiểu YouTube: luôn nằm đúng tâm video, kể cả ở chế độ gọn nhúng
     * (thanh dưới mỏng, chữ thời gian nằm hai mép nên cụm phát không đè chữ).
     */
    private fun positionCenterControls() {
        centerControls.translationY = 0f
        // Spinner buffer nằm ngoài cụm giữa, luôn khớp tâm với nút play.
        bufferingView.translationY = 0f
    }

    /**
     * Áp inset của hệ thống cho các lớp điều khiển. Video vẫn tràn viền; chỉ
     * thanh trên/dưới và các nút nổi được đẩy vào vùng an toàn.
     */
    fun applyWindowInsets(left: Int, top: Int, right: Int, bottom: Int) {
        // Dùng padding ngang nhưng dịch dọc thay vì padding dọc: topBar có chiều
        // cao cố định nên padding dọc sẽ ép/clip các nút bên trong.
        topBar.setPadding(
            topBarBasePadding[0] + left,
            topBarBasePadding[1],
            topBarBasePadding[2] + right,
            topBarBasePadding[3]
        )
        topBar.translationY = top.toFloat()
        bottomControls.setPadding(
            bottomControlsBasePadding[0] + left,
            bottomControlsBasePadding[1],
            bottomControlsBasePadding[2] + right,
            bottomControlsBasePadding[3]
        )
        bottomControls.translationY = -bottom.toFloat()
        sponsorSkipButton.translationX = -right.toFloat()
        sponsorSkipButton.translationY = -bottom.toFloat()
        lockButton.translationX = left.toFloat()
        lockButton.translationY = -bottom.toFloat()
        unlockButton.translationX = left.toFloat()
        unlockButton.translationY = -bottom.toFloat()
    }

    // ------------------------------------------------------------------
    // Thanh tua / thời gian
    // ------------------------------------------------------------------

    private fun updatePosition(position: PlaybackPosition) {
        if (isLive) return
        val fraction = position.progressFraction
        seekBar.progress = (fraction * seekBar.max).roundToInt()
        val sponsorSegments = if (state?.isSponsorBlockEnabled == true) {
            state?.sponsorSegments.orEmpty()
        } else {
            emptyList()
        }
        seekBar.setSponsorSegments(sponsorSegments, position.durationSeconds)
        updateBufferedProgress(position)
        updateSeekTimeLabels(position.currentSeconds)
    }

    private fun updateBufferedProgress(position: PlaybackPosition) {
        if (isLive) return
        seekBar.secondaryProgress =
            (position.bufferedFraction.coerceIn(0f, 1f) * seekBar.max).roundToInt()
    }

    private fun updateSeekTimeLabels(currentSeconds: Float) {
        currentTimeView.text = PlaybackPosition.formatTime(currentSeconds.toLong())
        totalTimeView.text = " / ${position.formattedDuration}"
        val remaining = (position.durationSeconds - currentSeconds).coerceAtLeast(0f)
        remainingTimeView.text = "-${PlaybackPosition.formatTime(remaining.toLong())}"
    }

    private fun updateSeekBarMode() {
        if (isLive) {
            currentTimeView.visibility = View.GONE
            totalTimeView.visibility = View.GONE
            remainingTimeView.visibility = View.GONE
            liveBadge.visibility = View.VISIBLE
            seekContainer.visibility = View.GONE
            seekBar.visibility = View.GONE
        } else {
            currentTimeView.visibility = View.VISIBLE
            currentTimeView.setTextColor(Color.WHITE)
            totalTimeView.visibility = View.VISIBLE
            remainingTimeView.visibility = if (compact) View.GONE else View.VISIBLE
            liveBadge.visibility = View.GONE
            seekContainer.visibility = View.VISIBLE
            seekBar.visibility = View.VISIBLE
            // Khi người dùng đang kéo thì không ghi đè vị trí đang xem trước.
            if (!isSeeking) updatePosition(position) else updateBufferedProgress(position)
        }
    }

    private fun showSeekPreview(fraction: Float, seconds: Float) {
        if (isLive) return
        seekPreview.text = PlaybackPosition.formatTime(seconds.toLong())
        seekPreview.visibility = View.VISIBLE
        if (seekPreview.width > 0 && seekContainer.width > 0) {
            positionSeekPreview(fraction)
        } else {
            seekPreview.post { positionSeekPreview(fraction) }
        }
    }

    private fun hideSeekPreview() {
        seekPreview.visibility = View.GONE
    }

    private fun positionSeekPreview(fraction: Float) {
        val trackLeft = seekBar.left + seekBar.paddingLeft
        val trackWidth =
            (seekBar.width - seekBar.paddingLeft - seekBar.paddingRight).coerceAtLeast(1)
        val centerX = trackLeft + trackWidth * fraction.coerceIn(0f, 1f)
        val maxX = (seekContainer.width - seekPreview.width).coerceAtLeast(0)
        seekPreview.translationX = (centerX - seekPreview.width / 2f).coerceIn(0f, maxX.toFloat())
    }

    // ------------------------------------------------------------------
    // Nút và điều khiển
    // ------------------------------------------------------------------

    private fun updatePlayPauseButtons(isPlaying: Boolean) {
        playPauseButton.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        )
        val label = getString(if (isPlaying) R.string.player_pause else R.string.player_play)
        playPauseButton.contentDescription = label
    }

    private fun updateAutoPlayButton(mode: AutoPlayMode) {
        autoPlayButton.setImageResource(
            when (mode) {
                AutoPlayMode.ONE -> R.drawable.ic_repeat_one
                AutoPlayMode.ALL -> R.drawable.ic_repeat
                AutoPlayMode.OFF, AutoPlayMode.NEXT -> R.drawable.ic_autorenew
            }
        )
        autoPlayButton.alpha = if (mode == AutoPlayMode.OFF) 0.55f else 1f
        ImageViewCompat.setImageTintList(
            autoPlayButton,
            ColorStateList.valueOf(
                ContextCompat.getColor(
                    context,
                    if (mode == AutoPlayMode.OFF) {
                        R.color.player_on_scrim
                    } else {
                        R.color.pure_primary
                    }
                )
            )
        )
        autoPlayButton.contentDescription = getString(
            when (mode) {
                AutoPlayMode.OFF -> R.string.player_autoplay_off
                AutoPlayMode.NEXT -> R.string.player_autoplay_next
                AutoPlayMode.ALL -> R.string.player_autoplay_all
                AutoPlayMode.ONE -> R.string.player_autoplay_one
            }
        )
    }

    private fun updateFullscreenButton(fullscreen: Boolean) {
        fullscreenButton.setImageResource(
            if (fullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen
        )
        fullscreenButton.contentDescription = getString(
            if (fullscreen) R.string.player_exit_fullscreen else R.string.player_fullscreen
        )
    }

    private fun updateSponsorButton(state: PurePlayerState) {
        val action = when {
            isLocked || playbackFailed -> null
            !state.isSponsorBlockEnabled -> null
            !state.sponsorShowSkipButton -> null
            state.sponsorCanUnskip -> getString(R.string.player_undo) to onUnskipSponsor
            state.sponsorManualSegment != null -> {
                val label = state.sponsorManualSegment?.categoryInfo?.label
                (if (label.isNullOrBlank()) {
                    getString(R.string.player_skip_segment)
                } else {
                    getString(R.string.player_skip_segment_label, label)
                }) to onSkipSponsor
            }

            else -> null
        }
        if (action == null) {
            sponsorSkipButton.visibility = View.GONE
        } else {
            sponsorSkipButton.visibility = View.VISIBLE
            sponsorSkipButton.text = action.first
        }
    }

    private fun applyControlsVisibility() {
        val visible = controlsVisible && !isLocked && !playbackFailed
        val visibility = if (visible) View.VISIBLE else View.GONE
        topScrim.visibility = visibility
        bottomScrim.visibility = visibility
        topBar.visibility = visibility
        centerControls.visibility = visibility
        bottomControls.visibility = visibility
        lockButton.visibility =
            if (state?.isFullscreen == true && visible) View.VISIBLE else View.GONE
        unlockButton.visibility = if (isLocked) View.VISIBLE else View.GONE
        // Không return sớm: bố cục phụ đề bên dưới vẫn phải cập nhật kể cả khi
        // HUD chưa có trạng thái (lần render đầu tiên).
        state?.let { updateSponsorButton(it) }
        if (isLocked) sponsorSkipButton.visibility = View.GONE
        // Phụ đề phải nhường chỗ cho thanh điều khiển khi chúng đang hiện.
        if (subtitleWidth > 0 && subtitleHeight > 0) {
            updateSubtitleLayout(subtitleWidth, subtitleHeight)
        }
    }

    /** Ẩn HUD để hiệu ứng thu nhỏ chỉ còn khung hình (nút chevron/vuốt xuống). */
    fun hideControlsForMinimize() {
        controlsVisible = false
        applyControlsVisibility()
    }

    /** Hiện lại HUD khi cử chỉ thu nhỏ (kéo xuống hoặc vuốt Back) bị huỷ. */
    fun showControlsAfterMinimizeCancel() {
        showControls()
    }

    private fun showControls() {
        controlsVisible = true
        removeCallbacks(hideRunnable)
        applyControlsVisibility()
        scheduleAutoHide()
    }

    private fun scheduleAutoHide() {
        removeCallbacks(hideRunnable)
        if (state?.isPlaying == true && controlsVisible && !isLocked &&
            !settingsOpen && !isSeeking
        ) {
            postDelayed(hideRunnable, CONTROLS_TIMEOUT_MS)
        }
    }

    private fun showFlash(iconRes: Int, horizontalOffsetFraction: Float) {
        flashPill.animate().cancel()
        removeCallbacks(flashRunnable)
        flashIcon.setImageResource(iconRes)
        // Double-tap chỉ hiện icon hướng tua/play-pause, không nền tròn và
        // không kèm nhãn "10 giây" để không che hình.
        flashPill.setBackgroundResource(0)
        flashText.visibility = View.GONE
        flashPill.translationX = width * horizontalOffsetFraction
        flashPill.translationY = 0f
        flashPill.alpha = 0f
        flashPill.scaleX = 0.82f
        flashPill.scaleY = 0.82f
        flashOverlay.visibility = View.VISIBLE
        flashPill.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(120)
            .start()
        postDelayed(flashRunnable, FLASH_TIMEOUT_MS)
    }

    /**
     * Chỉ báo mức sáng/âm lượng/zoom. Khác [showFlash] ở chỗ không chạy lại
     * animation mỗi lần vuốt (tránh nhấp nháy khi cập nhật liên tục).
     */
    private fun showLevelIndicator(iconRes: Int, text: String) {
        flashPill.animate().cancel()
        removeCallbacks(flashRunnable)
        flashIcon.setImageResource(iconRes)
        flashPill.setBackgroundResource(R.drawable.bg_player_flash)
        flashText.visibility = View.VISIBLE
        flashText.text = text
        flashPill.translationX = 0f
        flashPill.translationY = 0f
        flashPill.alpha = 1f
        flashPill.scaleX = 1f
        flashPill.scaleY = 1f
        flashOverlay.visibility = View.VISIBLE
        postDelayed(flashRunnable, LEVEL_TIMEOUT_MS)
    }

    /** Vuốt dọc nửa trái: đổi độ sáng cửa sổ (không cần quyền hệ thống). */
    private fun adjustBrightness(delta: Float) {
        val activity = context as? Activity ?: return
        val attributes = activity.window.attributes
        val current = if (attributes.screenBrightness >= 0f) {
            attributes.screenBrightness
        } else {
            systemBrightness()
        }
        val next = (current + delta).coerceIn(0.02f, 1f)
        attributes.screenBrightness = next
        activity.window.attributes = attributes
        showLevelIndicator(
            R.drawable.ic_brightness_medium,
            "${(next * 100f).roundToInt()}%"
        )
    }

    private fun systemBrightness(): Float {
        val value = Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            128
        )
        return (value / 255f).coerceIn(0.02f, 1f)
    }

    /** Vuốt dọc nửa phải: đổi âm lượng luồng media. */
    private fun adjustVolume(delta: Float) {
        val manager = audioManager ?: return
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val current = volumeLevel
            ?: manager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
        val next = (current + delta * max).coerceIn(0f, max.toFloat())
        volumeLevel = next
        val applied = next.roundToInt().coerceIn(0, max)
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, applied, 0)
        showLevelIndicator(
            if (applied == 0) R.drawable.ic_volume_off else R.drawable.ic_volume_up,
            "${applied * 100 / max}%"
        )
    }

    private fun applyZoom(scale: Float) {
        playerView.videoSurfaceView?.let { surface ->
            surface.scaleX = scale
            surface.scaleY = scale
        }
    }

    private fun resetZoom() {
        zoomBase = 1f
        applyZoom(1f)
    }

    // ------------------------------------------------------------------
    // Track phụ đề / khung hình
    // ------------------------------------------------------------------

    private fun updateTracks(tracks: Tracks) {
        captionTracks = tracks.toCaptionTracks(getString(R.string.player_captions))
        selectedCaptionLanguage = tracks.selectedCaptionLanguage().orEmpty()
        activeVideoHeight = tracks.selectedVideoTrackHeight()
        captionsButton.visibility = if (captionTracks.isNotEmpty()) View.VISIBLE else View.GONE
        onTracksChanged?.invoke()
    }

    private val playerListener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            updateTracks(tracks)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            activeAspectRatio = if (videoSize.width > 0 && videoSize.height > 0) {
                videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            } else {
                null
            }
            onVideoAspectRatioChanged?.invoke()
        }

        override fun onCues(cueGroup: CueGroup) {
            @Suppress("DEPRECATION")
            val subtitleView = playerView.subtitleView ?: return
            subtitleView.post {
                subtitleView.setCues(cueGroup.cues.centerCues())
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateSubtitleLayout(w, h)
        positionCenterControls()
    }

    private fun updateSubtitleLayout(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        subtitleWidth = width
        subtitleHeight = height
        val density = resources.displayMetrics.density
        val heightDp = height / density
        val textSize = (heightDp * 0.08f).coerceIn(16f, 22f)
        // Khi thanh điều khiển đang hiện, chừa đủ chỗ cho thanh tua + hàng nút;
        // lúc ẩn chỉ cần một khoảng đệm nhỏ để phụ đề không sát mép.
        val controlsOnScreen = controlsVisible && !isLocked && !playbackFailed
        val reservedDp = if (controlsOnScreen) {
            if (compact) 82f else 104f
        } else {
            22f
        }
        val bottomFraction = (reservedDp / heightDp).coerceIn(0.04f, 0.45f)
        @Suppress("DEPRECATION")
        playerView.subtitleView?.apply {
            setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
            if (bottomFraction != appliedSubtitleFraction) {
                setBottomPaddingFraction(bottomFraction)
                appliedSubtitleFraction = bottomFraction
            }
        }
    }

    private fun captionStyle() = CaptionStyleCompat(
        Color.WHITE,
        Color.TRANSPARENT,
        Color.argb(0xB3, 0, 0, 0),
        CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
        Color.BLACK,
        Typeface.create("sans-serif-medium", Typeface.NORMAL)
    )

    private fun finishDrag(cancelled: Boolean = false) {
        when (dragMode) {
            DragMode.SEEK -> {
                if (!isLive && position.durationSeconds > 0f) {
                    onSeekTo(dragFraction * position.durationSeconds)
                }
            }

            DragMode.SWIPE_DOWN -> {
                // Khung hình đi 1:1 với ngón tay nên chỉ cần kéo quá 40%
                // đường tới mini player là chốt thu nhỏ; vuốt nhanh thì dùng
                // vận tốc để chốt sớm hơn.
                val distance = minimizeDistanceForDrag()
                val commit = !cancelled &&
                    (minimizeFling || minimizeDragPx >= distance * MINIMIZE_COMMIT_FRACTION)
                val settle = onMinimizeEnd
                if (settle != null) {
                    // Đổi px/s sang tiến trình/s để spring dùng đúng đơn vị.
                    val velocity = (minimizeVelocityY / distance.coerceAtLeast(1f))
                        .coerceIn(-20f, 20f)
                    settle(commit, velocity)
                } else if (commit && state?.isFullscreen != true) {
                    // Dự phòng khi HUD dùng độc lập (không có hiệu ứng).
                    onClose()
                }
                if (!commit) showControls()
            }

            DragMode.LEVEL, DragMode.NONE -> Unit
        }
        dragMode = DragMode.NONE
        isSeeking = false
        volumeLevel = null
        minimizeFling = false
        minimizeVelocityY = 0f
        minimizeDragPx = 0f
        hideSeekPreview()
        updatePosition(position)
        scheduleAutoHide()
    }

    private companion object {
        const val CONTROLS_TIMEOUT_MS = 3500L
        const val FLASH_TIMEOUT_MS = 620L
        const val LEVEL_TIMEOUT_MS = 700L

        /** Kéo quá tỉ lệ này đường tới mini player là chốt thu nhỏ khi thả tay. */
        const val MINIMIZE_COMMIT_FRACTION = 0.4f

        /** Giới hạn phóng to bằng pinch, giống Just Player. */
        const val MAX_ZOOM = 3f
    }
}

/** Một ngôn ngữ phụ đề lấy từ track text thật của Media3. */
data class PlayerCaptionTrack(
    val languageTag: String,
    val label: String
)

internal fun Tracks.toCaptionTracks(fallbackLabel: String): List<PlayerCaptionTrack> {
    val options = mutableListOf<PlayerCaptionTrack>()
    groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }.forEach { group ->
        val index = (0 until group.length).firstOrNull { group.isTrackSupported(it) }
            ?: return@forEach
        val format = group.getTrackFormat(index)
        val tag = format.captionKey()
        if (tag.isBlank() || options.any { it.languageTag == tag }) return@forEach
        val label = format.label?.toString()?.takeIf { it.isNotBlank() }
            ?: format.language?.takeIf { it.isNotBlank() }?.uppercase()
            ?: fallbackLabel
        options += PlayerCaptionTrack(tag, label)
    }
    return options
}

internal fun Tracks.selectedCaptionLanguage(): String? {
    val group = groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
        ?: return null
    val index = (0 until group.length).firstOrNull { group.isTrackSelected(it) }
        ?: return null
    return group.getTrackFormat(index).captionKey().takeIf { it.isNotBlank() }
}

internal fun Tracks.selectedVideoTrackHeight(): Int? {
    val group = groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
        ?: return null
    val index = (0 until group.length).firstOrNull { group.isTrackSelected(it) }
        ?: return null
    return group.getTrackFormat(index).height.takeIf { it > 0 }
}

internal fun List<Cue>.centerCues(): List<Cue> = map { cue ->
    if (cue.bitmap != null || cue.verticalType != Cue.TYPE_UNSET) {
        cue
    } else {
        cue.buildUpon()
            .setPosition(Cue.DIMEN_UNSET)
            .setPositionAnchor(Cue.TYPE_UNSET)
            .setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET)
            .setLineAnchor(Cue.TYPE_UNSET)
            .setSize(Cue.DIMEN_UNSET)
            .setTextAlignment(null)
            .build()
    }
}

private fun Format.captionKey(): String =
    language?.takeIf { it.isNotBlank() }
        ?: id?.takeIf { it.isNotBlank() }
        ?: label?.toString().orEmpty()

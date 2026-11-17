@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.luxysiv.puretube.ui.scaffold

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import com.luxysiv.puretube.R
import com.luxysiv.puretube.player.PurePlayerState
import com.luxysiv.puretube.ui.player.PlayerSurfaceHost
import kotlin.math.roundToInt

/**
 * Mini player native thay cho bản Compose.
 *
 * Không còn tự tạo `PlayerView` riêng: video dùng chung surface với trang xem
 * qua [PlayerSurfaceHost]. Mini player chỉ *mượn* surface khi Activity của nó
 * đang foreground rồi trả lại khi pause/stop, nhờ vậy trình phát nhúng,
 * fullscreen, PiP và mini player không bao giờ tranh nhau một texture.
 */
class MiniPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val videoContainer: FrameLayout
    private val titleView: TextView
    private val channelView: TextView
    private val playPauseButton: ImageButton
    private val bufferingView: ProgressBar
    private val progressView: View

    private var mediaPlayer: Player? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var lifecycleObserver: LifecycleEventObserver? = null
    private var resumed = false
    private var hasVideo = false
    private var progressFraction = 0f

    /**
     * Tạm ngừng mượn surface khi trình phát overlay trong cùng cửa sổ đang giữ
     * nó. Không có cờ này, mini player (vẫn RESUMED vì cùng Activity) sẽ giành
     * lại surface mỗi khi trạng thái đổi, làm video nháy giữa hai nơi.
     */
    private var surfaceSuppressed = false

    var onExpand: (() -> Unit)? = null
    var onTogglePlayPause: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.view_mini_player_bar, this, true)
        videoContainer = findViewById(R.id.mini_player_video_container)
        titleView = findViewById(R.id.mini_player_title)
        channelView = findViewById(R.id.mini_player_channel)
        playPauseButton = findViewById(R.id.mini_player_play_pause)
        bufferingView = findViewById(R.id.mini_player_buffering)
        progressView = findViewById(R.id.mini_player_progress)

        findViewById<View>(R.id.mini_player_row).setOnClickListener {
            onExpand?.invoke()
        }
        playPauseButton.setOnClickListener { onTogglePlayPause?.invoke() }
        findViewById<View>(R.id.mini_player_next).setOnClickListener {
            onNext?.invoke()
        }
        findViewById<View>(R.id.mini_player_close).setOnClickListener {
            onClose?.invoke()
        }
    }

    /** Gắn lifecycle của Activity để chỉ giữ video surface khi đang foreground. */
    fun attachToLifecycle(owner: LifecycleOwner) {
        lifecycleOwner = owner
        resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        if (isAttachedToWindow) ensureObserver()
        if (resumed && hasVideo && isShown) attachPlayer()
    }

    /** Player dùng chung cấp process. */
    fun bindPlayer(player: Player) {
        if (mediaPlayer === player) return
        detachPlayer()
        mediaPlayer = player
        if (resumed && hasVideo && isShown) attachPlayer()
    }

    /**
     * Bật/tắt việc mini player mượn surface. Trình phát overlay gọi
     * `setSurfaceSuppressed(true)` khi mở và `false` khi đóng, để chỉ một nơi
     * giữ surface tại mỗi thời điểm.
     */
    fun setSurfaceSuppressed(suppressed: Boolean) {
        if (surfaceSuppressed == suppressed) return
        surfaceSuppressed = suppressed
        if (suppressed) {
            detachPlayer()
        } else if (resumed && hasVideo && isShown) {
            attachPlayer()
        }
    }

    /** Cập nhật tiêu đề, trạng thái phát và buffer. */
    fun render(state: PurePlayerState) {
        val video = state.currentVideo
        hasVideo = video != null
        if (video == null) {
            detachPlayer()
            return
        }

        titleView.text = video.title
        channelView.text = video.channelTitle
        playPauseButton.setImageResource(
            if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        )
        playPauseButton.contentDescription = context.getString(
            if (state.isPlaying) R.string.mini_player_pause else R.string.mini_player_play
        )
        bufferingView.visibility = if (state.isBuffering) View.VISIBLE else View.GONE

        if (resumed && isShown) attachPlayer()
    }

    /** Cập nhật vạch tiến độ đỏ ở đáy thẻ. */
    fun setProgress(fraction: Float) {
        progressFraction = fraction.coerceIn(0f, 1f)
        progressView.post { updateProgressWidth() }
    }

    private fun updateProgressWidth() {
        val trackWidth = (progressView.parent as? View)?.width ?: 0
        val width = (trackWidth * progressFraction).roundToInt().coerceAtLeast(0)
        val params = progressView.layoutParams
        if (params.width != width) {
            params.width = width
            progressView.layoutParams = params
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        progressView.post { updateProgressWidth() }
    }

    /**
     * Mượn surface dùng chung. Host tự gỡ khỏi chủ cũ (trang xem/PiP) trước
     * khi gắn vào đây, nên không cần gọi `setVideoTextureView` thủ công.
     */
    @Suppress("DEPRECATION")
    private fun attachPlayer() {
        if (surfaceSuppressed) return
        val player = mediaPlayer ?: return
        if (!isShown) return
        PlayerSurfaceHost.bind(player)
        PlayerSurfaceHost.obtain(context).apply {
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            subtitleView?.visibility = View.GONE
            // Mini player không zoom; trả khung hình về 1:1 nếu HUD vừa
            // pinch-to-zoom trước khi chuyển sang đây.
            videoSurfaceView?.scaleX = 1f
            videoSurfaceView?.scaleY = 1f
        }
        PlayerSurfaceHost.attach(videoContainer)
    }

    private fun detachPlayer() {
        // Chỉ trả surface nếu mini player đang thực sự giữ nó; nếu trang xem
        // vừa nhận thì host bỏ qua lệnh này.
        PlayerSurfaceHost.detach(videoContainer)
    }

    private fun ensureObserver() {
        if (lifecycleObserver != null) return
        val owner = lifecycleOwner ?: return
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    if (hasVideo && isShown) attachPlayer()
                }

                Lifecycle.Event.ON_PAUSE -> {
                    // Chỉ đánh dấu pause; chưa trả surface để trang xem vừa mở
                    // có thể nhận surface mà không có khoảng đen.
                    resumed = false
                }

                Lifecycle.Event.ON_STOP -> {
                    resumed = false
                    detachPlayer()
                }

                else -> Unit
            }
        }
        lifecycleObserver = observer
        owner.lifecycle.addObserver(observer)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resumed = lifecycleOwner?.lifecycle?.currentState
            ?.isAtLeast(Lifecycle.State.RESUMED) == true
        ensureObserver()
        if (resumed && hasVideo && isShown) attachPlayer()
    }

    override fun onDetachedFromWindow() {
        detachPlayer()
        lifecycleObserver?.let { lifecycleOwner?.lifecycle?.removeObserver(it) }
        lifecycleObserver = null
        super.onDetachedFromWindow()
    }
}

@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.luxysiv.puretube.ui.player

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.luxysiv.puretube.R

/**
 * Chủ sở hữu **duy nhất** một [PlayerView] cho toàn bộ tiến trình.
 *
 * Trước đây mỗi nơi (trình phát nhúng/fullscreen, mini player) tự inflate một
 * `PlayerView` riêng. Vì ExoPlayer chỉ có một video output, các view này giành
 * nhau `TextureView` mỗi khi chuyển trang, xoay màn hình hoặc thu nhỏ, gây nháy
 * đen và phải gọi `setVideoTextureView` thủ công ở nhiều chỗ.
 *
 * Từ bản này, player/fullscreen/mini player/PiP cùng dùng chung đúng một
 * `PlayerView`: view được **di chuyển** (re-parent) giữa các container thay vì
 * tạo mới. Nhờ vậy:
 *
 * - chỉ có một surface Media3, không còn tranh chấp giữa các Activity;
 * - subtitle, tỉ lệ khung hình và chế độ resize luôn đi theo cùng một view;
 * - `PlaybackEngine` vẫn là nguồn [Player] duy nhất, còn host này là nguồn
 *   hiển thị duy nhất.
 *
 * Host không giữ Context của Activity: view được tạo bằng application context
 * nên không làm rò rỉ Activity khi phát nền.
 */
object PlayerSurfaceHost {

    private var surface: PlayerView? = null
    private var boundPlayer: Player? = null
    private var owner: ViewGroup? = null

    /** View dùng chung; tạo mới ở lần gọi đầu tiên (phải ở main thread). */
    fun obtain(context: Context): PlayerView {
        surface?.let { return it }
        val created = LayoutInflater.from(context.applicationContext)
            .inflate(R.layout.view_player, null, false) as PlayerView
        created.apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setBackgroundColor(Color.BLACK)
            // Video là nội dung chính; gesture được xử lý ở HUD phía trên.
            isClickable = false
            isFocusable = false
        }
        surface = created
        created.player = boundPlayer
        return created
    }

    /** Gắn [Player] dùng chung (nguồn phát duy nhất của app). */
    fun bind(player: Player) {
        boundPlayer = player
        surface?.player = player
    }

    /**
     * Đưa surface vào [container]. Nếu surface đang nằm ở container khác
     * (mini player ↔ trang xem ↔ fullscreen), nó được gỡ ra trước rồi mới gắn
     * lại, nên không bao giờ có hai nơi cùng giữ surface.
     */
    fun attach(container: ViewGroup) {
        val view = obtain(container.context)
        if (owner === container && view.parent === container) {
            if (view.player !== boundPlayer) view.player = boundPlayer
            return
        }
        // Gỡ khỏi chủ cũ nhưng KHÔNG xoá player: giữ nguyên output để surface
        // không bị release rồi tạo lại khi chuyển mini player ↔ trang xem.
        (view.parent as? ViewGroup)?.removeView(view)
        owner = container
        container.addView(
            view,
            0,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        if (view.player !== boundPlayer) view.player = boundPlayer
    }

    /**
     * Gỡ surface khỏi [container]. Truyền container để tránh trường hợp một
     * Activity đang dừng lại gỡ mất surface mà Activity mới vừa nhận.
     */
    fun detach(container: ViewGroup? = null) {
        if (container != null && owner != null && owner !== container) return
        val view = surface ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        view.player = null
        owner = null
    }

    /** Container hiện đang giữ surface, dùng cho kiểm tra/assert. */
    fun currentOwner(): ViewGroup? = owner

    fun isAttachedTo(container: ViewGroup): Boolean =
        owner === container && surface?.parent === container
}

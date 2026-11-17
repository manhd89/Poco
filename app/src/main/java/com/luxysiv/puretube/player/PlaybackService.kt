@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.luxysiv.puretube.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.luxysiv.puretube.ui.base.PageActivity
import com.luxysiv.puretube.ui.home.HomeActivity

/**
 * Foreground service chỉ đóng vai trò **host** của [MediaSession].
 *
 * Không còn custom `SessionCommand`, `CommandButton` hay xử lý `onMediaButtonEvent`:
 * ExoPlayer giờ sở hữu playlist thật nên Media3 tự quảng bá
 * `COMMAND_SEEK_TO_NEXT/PREVIOUS` và `DefaultMediaNotificationProvider` tự thêm
 * đủ nút. Nhờ vậy notification, tai nghe Bluetooth, màn hình khoá và Android Auto
 * đều đi qua lệnh native.
 *
 * `MediaSession` bọc **raw `engine.player`** (không `ForwardingPlayer`) để metadata
 * title/artist/artwork mà AVRCP đọc được giữ nguyên như bản 1.5.0.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        // Khởi tạo coordinator sớm: nếu process bị kill và service được tạo lại,
        // coordinator khôi phục now-playing/queue nhưng KHÔNG tự phát.
        PlaybackCoordinatorProvider.get(this)

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, HomeActivity::class.java)
                .putExtra(PageActivity.EXTRA_OPEN_PLAYER, true)
                .addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val engine = PlaybackEngineProvider.get(this)
        val session = MediaSession.Builder(this, engine.player)
            .setSessionActivity(sessionActivity)
            .build()
        mediaSession = session

        // Bắt buộc để Media3 tạo "media notification controller" và hiện
        // notification hệ thống (đây là hành vi đã sửa ở commit cc72f27).
        addSession(session)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Vuốt app khỏi đa nhiệm = thoát hẳn. close() đã dừng player, xoá
        // now-playing/queue và dừng service nên không cleanup tay lần nữa.
        PlaybackCoordinatorProvider.peek()?.close()
        stopSelf()
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        /**
         * Dùng startService (không phải startForegroundService) vì service được
         * khởi động từ foreground; Media3 sẽ tự gọi startForeground khi phát.
         */
        fun start(context: Context) {
            runCatching {
                context.startService(Intent(context, PlaybackService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, PlaybackService::class.java))
            }
        }
    }
}

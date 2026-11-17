package com.luxysiv.puretube.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Nhận lệnh từ các nút điều khiển trong cửa sổ PiP.
 *
 * PiP dùng [android.app.RemoteAction] kèm broadcast tường minh thay vì mở
 * Activity, nên thao tác chạy được cả khi Activity đang ở chế độ PiP.
 */
class PlayerPipActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val controller = PlaybackControllerProvider.peek() ?: return
        when (intent.action) {
            ACTION_TOGGLE_PLAY -> controller.togglePlayPause()
            ACTION_PREVIOUS -> controller.previous()
            ACTION_NEXT -> controller.next(force = true)
        }
    }

    companion object {
        const val ACTION_TOGGLE_PLAY = "com.luxysiv.puretube.action.PIP_TOGGLE_PLAY"
        const val ACTION_PREVIOUS = "com.luxysiv.puretube.action.PIP_PREVIOUS"
        const val ACTION_NEXT = "com.luxysiv.puretube.action.PIP_NEXT"
    }
}

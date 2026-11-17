package com.luxysiv.puretube

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.luxysiv.puretube.player.PlaybackControllerProvider
import com.luxysiv.puretube.ui.base.PageActivity
import com.luxysiv.puretube.ui.home.HomeActivity

/**
 * Ứng dụng theo dõi vòng đời Activity để quyết định khi nào **toàn bộ app**
 * thực sự xuống nền.
 *
 * Vì mỗi trang là một Activity riêng, không thể dựa vào `onStop` của một
 * Activity đơn lẻ: chuyển trang nội bộ cũng làm Activity cũ dừng. Bộ đếm ở đây
 * chỉ báo nền khi không còn Activity nào hoạt động (có trễ ngắn để bỏ qua
 * trường hợp xoay màn hình/đổi cấu hình).
 */
class PureTubeApplication : Application(), Application.ActivityLifecycleCallbacks {

    private var startedActivities = 0
    private val handler = Handler(Looper.getMainLooper())

    private val handleBackground = Runnable {
        if (startedActivities > 0) return@Runnable
        // PiP vẫn đang hiển thị video: không coi là xuống nền.
        if (PageActivity.isInPipMode) return@Runnable
        PlaybackControllerProvider.get(this).onAppBackgrounded()
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivities++
        handler.removeCallbacks(handleBackground)
        // App vừa quay lại foreground: bảo đảm ticker vị trí còn sống để
        // thanh tiến trình tiếp tục chạy sau khi mở khoá màn hình.
        if (startedActivities == 1) {
            PlaybackControllerProvider.peek()?.onAppForegrounded()
        }
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0) {
            handler.removeCallbacks(handleBackground)
            handler.postDelayed(handleBackground, APP_BACKGROUND_DEBOUNCE_MS)
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        // Thoát hẳn app từ trang gốc: dừng phát và xoá now-playing.
        if (activity is HomeActivity &&
            activity.isFinishing &&
            !activity.isChangingConfigurations &&
            startedActivities == 0
        ) {
            PlaybackControllerProvider.peek()?.close()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private companion object {
        const val APP_BACKGROUND_DEBOUNCE_MS = 700L
    }
}

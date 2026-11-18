package com.luxysiv.puretube.ui.player

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.luxysiv.puretube.R
import com.luxysiv.puretube.data.model.VideoItem
import com.luxysiv.puretube.player.PlaybackPosition
import com.luxysiv.puretube.player.PurePlayerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class PlayerNativeViewFullscreenTransitionTest {

    private fun createMeasuredPlayerView(
        widthPx: Int,
        heightPx: Int,
        topInsetPx: Int
    ): PlayerNativeView {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val context = ContextThemeWrapper(baseContext, R.style.Theme_MyApplication)
        val playerView = PlayerNativeView(context)
        val insets = WindowInsetsCompat.Builder()
            .setInsets(
                WindowInsetsCompat.Type.systemBars(),
                Insets.of(0, topInsetPx, 0, 0)
            )
            .setInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars(),
                Insets.of(0, topInsetPx, 0, 0)
            )
            .build()
        playerView.applyWindowInsets(insets)
        playerView.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        )
        playerView.layout(0, 0, widthPx, heightPx)
        return playerView
    }

    private fun testVideo() = VideoItem(
        id = "dQw4w9WgXcQ",
        title = "Video kiểm thử",
        channelTitle = "PureTube"
    )

    @Test
    @Config(sdk = [36], qualifiers = "w412dp-h915dp-port")
    fun `portrait exit fullscreen animates in reverse of pull down back to embedded bounds`() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val metrics = baseContext.resources.displayMetrics
        val topInset = 48
        val playerView = createMeasuredPlayerView(metrics.widthPixels, metrics.heightPixels, topInset)
        val videoContainer = playerView.findViewById<FrameLayout>(R.id.player_video_container)
        val detailsSheet = playerView.findViewById<FrameLayout>(R.id.player_details_sheet)
        val video = testVideo()

        // 1. Vào fullscreen bằng code (nút fullscreen)
        playerView.updatePlayerState(
            state = PurePlayerState(currentVideo = video, isFullscreen = true),
            position = PlaybackPosition(),
            plan = null,
            failure = null
        )
        // Thanh hệ thống ẩn đi khi vào fullscreen (bars.top = 0)
        val hiddenBarsInsets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
            .setInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars(),
                Insets.of(0, topInset, 0, 0)
            )
            .build()
        playerView.applyWindowInsets(hiddenBarsInsets)
        shadowOf(playerView.handler?.looper ?: baseContext.mainLooper).idleFor(Duration.ofSeconds(1))

        assertEquals(View.GONE, detailsSheet.visibility)
        assertEquals(
            FrameLayout.LayoutParams.MATCH_PARENT,
            (videoContainer.layoutParams as FrameLayout.LayoutParams).height
        )

        // 2. Thoát fullscreen ở màn hình dọc: sheet bật VISIBLE ở đáy (alpha = 0) và video giữ fullHeight rồi co ngược lại
        playerView.updatePlayerState(
            state = PurePlayerState(currentVideo = video, isFullscreen = false),
            position = PlaybackPosition(),
            plan = null,
            failure = null
        )
        assertEquals(View.VISIBLE, detailsSheet.visibility)
        assertEquals(0f, detailsSheet.alpha, 0.01f)
        assertEquals(
            metrics.heightPixels,
            (videoContainer.layoutParams as FrameLayout.LayoutParams).height
        )
        assertEquals(
            metrics.heightPixels,
            (detailsSheet.layoutParams as FrameLayout.LayoutParams).topMargin
        )

        // Hệ thống phát lại inset giữa lúc hiệu ứng đang chạy: không được ngắt ngang transition
        playerView.onSystemBarsVisibilityChanged()
        playerView.applyWindowInsets(
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, topInset, 0, 0))
                .setInsetsIgnoringVisibility(
                    WindowInsetsCompat.Type.systemBars(),
                    Insets.of(0, topInset, 0, 0)
                )
                .build()
        )

        // Cho spring chạy tới trạng thái nhúng hoàn chỉnh
        shadowOf(baseContext.mainLooper).idleFor(Duration.ofSeconds(1))

        val expectedEmbeddedHeight = (metrics.widthPixels * 9 / 16)
            .coerceAtMost((metrics.heightPixels - topInset).coerceAtLeast(1))
        val finalVideoLp = videoContainer.layoutParams as FrameLayout.LayoutParams
        val finalSheetLp = detailsSheet.layoutParams as FrameLayout.LayoutParams
        assertEquals(topInset, finalVideoLp.topMargin)
        assertEquals(expectedEmbeddedHeight, finalVideoLp.height)
        assertEquals(topInset + expectedEmbeddedHeight, finalSheetLp.topMargin)
        assertEquals(1f, detailsSheet.alpha, 0.01f)
        assertEquals(0f, detailsSheet.translationY, 0.5f)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w840dp-h412dp-land")
    fun `landscape and tablet enter and exit fullscreen animate smoothly between two-column and fullscreen`() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val metrics = baseContext.resources.displayMetrics
        val topInset = 24
        val playerView = createMeasuredPlayerView(metrics.widthPixels, metrics.heightPixels, topInset)
        val videoContainer = playerView.findViewById<FrameLayout>(R.id.player_video_container)
        val detailsSheet = playerView.findViewById<FrameLayout>(R.id.player_details_sheet)
        val video = testVideo()

        val embeddedVideoWidth = (metrics.widthPixels * 0.6f).toInt().coerceAtLeast(1)
        val embeddedVideoHeight = (embeddedVideoWidth * 9 / 16)
            .coerceAtMost((metrics.heightPixels - topInset).coerceAtLeast(1))

        // 1. Trạng thái nhúng 2 cột ban đầu
        playerView.updatePlayerState(
            state = PurePlayerState(currentVideo = video, isFullscreen = false),
            position = PlaybackPosition(),
            plan = null,
            failure = null
        )
        assertEquals(embeddedVideoWidth, (videoContainer.layoutParams as FrameLayout.LayoutParams).width)
        assertEquals(embeddedVideoHeight, (videoContainer.layoutParams as FrameLayout.LayoutParams).height)
        assertEquals(View.VISIBLE, detailsSheet.visibility)

        // 2. Bật fullscreen ở màn hình ngang / tablet: khung hình bắt đầu từ kích thước 2 cột rồi mở rộng ra toàn màn
        playerView.updatePlayerState(
            state = PurePlayerState(currentVideo = video, isFullscreen = true),
            position = PlaybackPosition(),
            plan = null,
            failure = null
        )
        val startEnterLp = videoContainer.layoutParams as FrameLayout.LayoutParams
        assertEquals(embeddedVideoWidth, startEnterLp.width)
        assertEquals(embeddedVideoHeight, startEnterLp.height)
        assertEquals(View.VISIBLE, detailsSheet.visibility)
        assertEquals(1f, detailsSheet.alpha, 0.01f)

        // Cho spring kết thúc vào fullscreen
        shadowOf(baseContext.mainLooper).idleFor(Duration.ofSeconds(1))
        assertEquals(View.GONE, detailsSheet.visibility)
        assertEquals(
            FrameLayout.LayoutParams.MATCH_PARENT,
            (videoContainer.layoutParams as FrameLayout.LayoutParams).width
        )

        // 3. Thoát fullscreen ở màn hình ngang / tablet: sheet hiện ở mép phải (alpha = 0, translationX > 0) và thu gọn về 2 cột
        playerView.updatePlayerState(
            state = PurePlayerState(currentVideo = video, isFullscreen = false),
            position = PlaybackPosition(),
            plan = null,
            failure = null
        )
        val startExitLp = videoContainer.layoutParams as FrameLayout.LayoutParams
        assertEquals(metrics.widthPixels, startExitLp.width)
        assertEquals(metrics.heightPixels, startExitLp.height)
        assertEquals(View.VISIBLE, detailsSheet.visibility)
        assertEquals(0f, detailsSheet.alpha, 0.01f)
        assertTrue(detailsSheet.translationX > 0f)

        // Cho spring kết thúc về lại bố cục 2 cột
        shadowOf(baseContext.mainLooper).idleFor(Duration.ofSeconds(1))
        val endExitLp = videoContainer.layoutParams as FrameLayout.LayoutParams
        assertEquals(embeddedVideoWidth, endExitLp.width)
        assertEquals(embeddedVideoHeight, endExitLp.height)
        assertEquals(topInset, endExitLp.topMargin)
        assertEquals(1f, detailsSheet.alpha, 0.01f)
        assertEquals(0f, detailsSheet.translationX, 0.5f)
        assertEquals(View.VISIBLE, detailsSheet.visibility)
    }
}

package com.luxysiv.puretube.ui.player

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.luxysiv.puretube.R
import com.luxysiv.puretube.data.model.SponsorSegment
import com.luxysiv.puretube.data.model.VideoItem
import com.luxysiv.puretube.error.AppError
import com.luxysiv.puretube.player.AutoPlayMode
import com.luxysiv.puretube.player.PlaybackFailure
import com.luxysiv.puretube.player.PurePlayerState
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class PlayerHudOverlapTest {

    private fun createHud(widthPx: Int, heightPx: Int, compact: Boolean): PlayerHudView {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val context = ContextThemeWrapper(baseContext, R.style.Theme_MyApplication)
        val hud = PlayerHudView(context)
        hud.setCompactMode(compact)
        hud.render(
            state = PurePlayerState(
                currentVideo = VideoItem(
                    id = "dQw4w9WgXcQ",
                    title = "Video thử nghiệm giao diện trình phát hiện đại",
                    channelTitle = "PureTube Studio"
                ),
                isFullscreen = !compact,
                isSponsorBlockEnabled = true,
                sponsorShowSkipButton = true,
                sponsorManualSegment = SponsorSegment(
                    startMs = 5_000L,
                    endMs = 20_000L,
                    category = "sponsor",
                    uuid = "seg-1"
                )
            ),
            autoPlayMode = AutoPlayMode.NEXT,
            hasMix = true,
            isLive = false,
            failure = null
        )
        hud.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        )
        hud.layout(0, 0, widthPx, heightPx)
        return hud
    }

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h780dp-port")
    fun `compact portrait 16by9 hud controls never overlap vertically or clip error card`() {
        val metrics = ApplicationProvider.getApplicationContext<Context>().resources.displayMetrics
        val widthPx = metrics.widthPixels
        val heightPx = widthPx * 9 / 16
        val hud = createHud(widthPx, heightPx, compact = true)

        val topBar = hud.findViewById<View>(R.id.hud_top_bar)
        val centerControls = hud.findViewById<View>(R.id.hud_center_controls)
        val playPause = hud.findViewById<View>(R.id.hud_play_pause)
        val bottomControls = hud.findViewById<View>(R.id.hud_bottom_controls)
        val sponsorSkip = hud.findViewById<View>(R.id.hud_sponsor_skip)

        val centerTop = centerControls.top + centerControls.translationY
        val centerBottom = centerControls.bottom + centerControls.translationY
        assertTrue(
            "Top bar (${topBar.bottom}) must stay above center controls ($centerTop)",
            topBar.bottom <= centerTop
        )
        assertTrue(
            "Center controls ($centerBottom) must stay above bottom controls (${bottomControls.top})",
            centerBottom <= bottomControls.top
        )
        assertTrue(
            "Sponsor skip pill (${sponsorSkip.bottom}) must sit cleanly above bottom controls (${bottomControls.top})",
            sponsorSkip.bottom <= bottomControls.top
        )
        val playRight = centerControls.left + playPause.right
        assertTrue(
            "Sponsor skip pill (${sponsorSkip.left}) must not overlap play/pause ($playRight)",
            sponsorSkip.left >= playRight
        )

        hud.render(
            state = PurePlayerState(
                currentVideo = VideoItem(id = "dQw4w9WgXcQ", title = "Err", channelTitle = "PureTube"),
                isFullscreen = false
            ),
            autoPlayMode = AutoPlayMode.OFF,
            hasMix = false,
            isLive = false,
            failure = PlaybackFailure(
                error = AppError.Playback(),
                message = "Không phát được video",
                detail = "Lỗi thử nghiệm"
            )
        )
        hud.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        )
        hud.layout(0, 0, widthPx, heightPx)
        val errorCard = hud.findViewById<View>(R.id.hud_error_card)
        assertTrue(
            "Error card (${errorCard.height}px) must fit inside compact 16:9 height (${heightPx}px)",
            errorCard.top >= 0 && errorCard.bottom <= heightPx
        )
    }

    @Test
    @Config(sdk = [36], qualifiers = "w840dp-h412dp-land")
    fun `fullscreen landscape hud places lock in bottom row and keeps generous clearances`() {
        val metrics = ApplicationProvider.getApplicationContext<Context>().resources.displayMetrics
        val hud = createHud(metrics.widthPixels, metrics.heightPixels, compact = false)
        hud.applyWindowInsets(24, 24, 24, 24)
        hud.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY)
        )
        hud.layout(0, 0, metrics.widthPixels, metrics.heightPixels)

        val topBar = hud.findViewById<View>(R.id.hud_top_bar)
        val centerControls = hud.findViewById<View>(R.id.hud_center_controls)
        val bottomControls = hud.findViewById<View>(R.id.hud_bottom_controls)
        val sponsorSkip = hud.findViewById<View>(R.id.hud_sponsor_skip)
        val lockButton = hud.findViewById<View>(R.id.hud_lock)

        assertTrue(lockButton.visibility == View.VISIBLE)
        assertTrue(topBar.bottom <= centerControls.top + centerControls.translationY)
        assertTrue(centerControls.bottom + centerControls.translationY <= bottomControls.top)
        assertTrue(sponsorSkip.bottom <= bottomControls.top)
    }
}

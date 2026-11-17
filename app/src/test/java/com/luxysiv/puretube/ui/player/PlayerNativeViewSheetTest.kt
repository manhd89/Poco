package com.luxysiv.puretube.ui.player

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.luxysiv.puretube.R
import com.luxysiv.puretube.data.model.VideoItem
import com.luxysiv.puretube.player.PlaybackPosition
import com.luxysiv.puretube.player.PurePlayerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w840dp-h412dp-land")
class PlayerNativeViewSheetTest {

    @Test
    fun `description and comments sheets anchor to right column before first show message in landscape`() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val context = ContextThemeWrapper(baseContext, R.style.Theme_MyApplication)
        val playerView = PlayerNativeView(context)
        val video = VideoItem(
            id = "dQw4w9WgXcQ",
            title = "Video kiểm thử",
            channelTitle = "PureTube"
        )
        playerView.updatePlayerState(
            state = PurePlayerState(currentVideo = video),
            position = PlaybackPosition(),
            plan = null,
            failure = null
        )

        val metrics = context.resources.displayMetrics
        val expectedVideoWidth = (metrics.widthPixels * 0.6f).toInt().coerceAtLeast(1)
        val expectedContentWidth = (metrics.widthPixels - expectedVideoWidth).coerceAtLeast(1)

        // Mở sheet giới thiệu từ tiêu đề video (trước khi ShadowLooper chạy OnShowListener).
        playerView.findViewById<View>(R.id.player_info_title).performClick()
        val descriptionDialog = ShadowDialog.getLatestDialog() as? BottomSheetDialog
        assertNotNull(descriptionDialog)
        val descAttrs = descriptionDialog!!.window!!.attributes
        assertEquals(Gravity.TOP or Gravity.LEFT, descAttrs.gravity)
        assertEquals(expectedVideoWidth, descAttrs.x)
        assertEquals(expectedContentWidth, descAttrs.width)
        descriptionDialog.dismiss()

        // Mở sheet bình luận từ thẻ bình luận (trước khi ShadowLooper chạy OnShowListener).
        playerView.findViewById<View>(R.id.player_comments_view_all).performClick()
        val commentsDialog = ShadowDialog.getLatestDialog() as? BottomSheetDialog
        assertNotNull(commentsDialog)
        val commentsAttrs = commentsDialog!!.window!!.attributes
        assertEquals(Gravity.TOP or Gravity.LEFT, commentsAttrs.gravity)
        assertEquals(expectedVideoWidth, commentsAttrs.x)
        assertEquals(expectedContentWidth, commentsAttrs.width)
        commentsDialog.dismiss()
    }
}

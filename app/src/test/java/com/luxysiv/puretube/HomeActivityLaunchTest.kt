package com.luxysiv.puretube

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.luxysiv.puretube.ui.home.HomeActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Kiểm thử hồi quy: mở trang chủ phải không crash, bảo vệ luồng khởi động của
 * ứng dụng sau khi chuyển toàn bộ giao diện sang XML View.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HomeActivityLaunchTest {

  @Test
  fun `home activity starts without crashing`() {
    ApplicationProvider.getApplicationContext<android.content.Context>()
    val controller = Robolectric.buildActivity(HomeActivity::class.java)
    controller.setup()
    shadowOf(controller.get().mainLooper).idle()
    assertFalse(controller.get().isFinishing)
  }

  @Test
  fun `activity does not lock portrait orientation on configuration change`() {
    val controller = Robolectric.buildActivity(HomeActivity::class.java)
    controller.setup()
    val activity = controller.get()
    assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
    val landscape = Configuration(activity.resources.configuration).apply {
      orientation = Configuration.ORIENTATION_LANDSCAPE
      screenWidthDp = 840
      screenHeightDp = 412
    }
    activity.onConfigurationChanged(landscape)
    assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
  }
}

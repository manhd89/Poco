package com.luxysiv.puretube

import androidx.test.core.app.ApplicationProvider
import com.luxysiv.puretube.ui.home.HomeActivity
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
}

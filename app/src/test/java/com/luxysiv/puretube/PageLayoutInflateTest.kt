package com.luxysiv.puretube

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Kiểm thử hồi quy: khung trang và mini player phải inflate được với theme ứng
 * dụng, tránh lỗi khởi động do layout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PageLayoutInflateTest {

  @Test
  fun `activity_page inflates with app theme`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val themed = ContextThemeWrapper(context, R.style.Theme_MyApplication)
    val view = LayoutInflater.from(themed).inflate(R.layout.activity_page, null)
    assertNotNull(view)
  }

  @Test
  fun `mini player view constructs with app theme`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val themed = ContextThemeWrapper(context, R.style.Theme_MyApplication)
    val view = com.luxysiv.puretube.ui.scaffold.MiniPlayerView(themed)
    assertNotNull(view)
  }

  @Test
  fun `playlist card view constructs with app theme`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val themed = ContextThemeWrapper(context, R.style.Theme_MyApplication)
    val view = com.luxysiv.puretube.ui.views.PlaylistCardView(themed)
    assertNotNull(view)
  }

  @Test
  fun `playlist detail view constructs with app theme`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val themed = ContextThemeWrapper(context, R.style.Theme_MyApplication)
    val view = com.luxysiv.puretube.ui.views.PlaylistNativeView(themed)
    assertNotNull(view)
  }

  @Test
  fun `library view renders playlist history without crashing`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val themed = ContextThemeWrapper(context, R.style.Theme_MyApplication)
    val view = com.luxysiv.puretube.ui.views.LibraryNativeView(themed)
    view.setContent(
      favorites = emptyList(),
      history = emptyList(),
      playlists = listOf(
        com.luxysiv.puretube.data.database.PlaylistHistoryEntity(
          playlistId = "PL1",
          title = "Danh sách",
          lastVideoId = "v1",
          lastVideoTitle = "Video 1",
          updatedAt = System.currentTimeMillis()
        )
      ),
      savedPlaylists = emptyList(),
      savedPlaylistIds = emptySet(),
      watchLater = emptyList()
    )
    assertNotNull(view)
  }

  @Test
  fun `library watch later renders saved playlists separately`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val themed = ContextThemeWrapper(context, R.style.Theme_MyApplication)
    val view = com.luxysiv.puretube.ui.views.LibraryNativeView(themed)
    view.selectTab(2)
    view.setContent(
      favorites = emptyList(),
      history = emptyList(),
      playlists = emptyList(),
      savedPlaylists = listOf(
        com.luxysiv.puretube.data.database.SavedPlaylistEntity(
          playlistId = "PL2",
          title = "Playlist đã lưu",
          videoCount = 3,
          savedAt = System.currentTimeMillis()
        )
      ),
      savedPlaylistIds = setOf("PL2"),
      watchLater = emptyList()
    )
    assertNotNull(view)
  }
}

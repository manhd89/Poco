package com.luxysiv.puretube.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.luxysiv.puretube.data.model.SponsorMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Kiểm tra lưu bền tuỳ chọn và lịch sử tìm kiếm trên bộ nhớ thiết bị. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsAndHistoryStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearPreferences() {
        // Robolectric có thể tái sử dụng vùng nhớ giữa các test; dọn sạch để
        // từng test độc lập với thứ tự chạy.
        context.getSharedPreferences("puretube_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences("puretube_search", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `AppSettings survives save and load`() {
        val store = SettingsStore(context)
        val settings = AppSettings(
            backgroundPlay = false,
            autoNext = false,
            autoPiP = true,
            playbackSpeed = 1.5f,
            playbackPitch = 0.9f,
            skipSilence = true,
            qualityHeight = 720,
            qualityMobileHeight = 480,
            dataSaver = true,
            resumePlayback = false,
            skipSponsors = false,
            captionsEnabled = true,
            captionLanguageTag = "vi"
        )

        store.save(settings)

        assertEquals(settings, store.load())
    }

    @Test
    fun `settings default to safe values`() {
        val loaded = SettingsStore(context).load()

        assertTrue(loaded.backgroundPlay)
        assertTrue(loaded.autoNext)
        assertFalse(loaded.autoPiP)
        assertEquals(1.0f, loaded.playbackSpeed)
        assertEquals(1.0f, loaded.playbackPitch)
        assertFalse(loaded.skipSilence)
        assertEquals(0, loaded.qualityHeight)
        assertEquals(0, loaded.qualityMobileHeight)
        assertFalse(loaded.dataSaver)
        assertTrue(loaded.resumePlayback)
        assertTrue(loaded.skipSponsors)
        assertFalse(loaded.sponsorNotify)
        assertFalse(loaded.sponsorShowSkipButton)
        assertEquals(SponsorMode.AUTO, loaded.sponsorCategoryModes["intro"])
        assertEquals(SponsorMode.AUTO, loaded.sponsorCategoryModes["outro"])
        assertFalse(loaded.captionsEnabled)
        assertEquals("", loaded.captionLanguageTag)
    }

    @Test
    fun `legacy sponsor defaults migrate once without overriding later choices`() {
        context.getSharedPreferences("puretube_settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("sponsor_notify", true)
            .putBoolean("sponsor_show_skip_button", true)
            .putStringSet(
                "sponsor_category_modes",
                setOf("intro=DISABLED", "outro=DISABLED", "sponsor=AUTO")
            )
            .commit()

        val migrated = SettingsStore(context).load()

        assertFalse(migrated.sponsorNotify)
        assertFalse(migrated.sponsorShowSkipButton)
        assertEquals(SponsorMode.AUTO, migrated.sponsorCategoryModes["intro"])
        assertEquals(SponsorMode.AUTO, migrated.sponsorCategoryModes["outro"])

        // Sau khi migrate, lựa chọn người dùng phải được giữ nguyên khi lưu lại.
        SettingsStore(context).save(
            migrated.copy(
                sponsorNotify = true,
                sponsorShowSkipButton = true,
                sponsorCategoryModes = migrated.sponsorCategoryModes +
                    ("intro" to SponsorMode.DISABLED)
            )
        )
        val reloaded = SettingsStore(context).load()

        assertTrue(reloaded.sponsorNotify)
        assertTrue(reloaded.sponsorShowSkipButton)
        assertEquals(SponsorMode.DISABLED, reloaded.sponsorCategoryModes["intro"])
        assertEquals(SponsorMode.AUTO, reloaded.sponsorCategoryModes["outro"])
    }

    @Test
    fun `search history deduplicates is case insensitive and removes one item`() {
        val store = SearchHistoryStore(context)

        store.add("lofi")
        store.add("Gaming")
        store.add("LOFI")

        assertEquals(listOf("LOFI", "Gaming"), store.load())

        val afterRemove = store.remove("lofi")

        assertEquals(listOf("Gaming"), afterRemove)
        assertEquals(listOf("Gaming"), store.load())
    }

    @Test
    fun `search history keeps at most the limit`() {
        val store = SearchHistoryStore(context)

        repeat(15) { index -> store.add("từ khóa $index", limit = 10) }

        val history = store.load()
        assertEquals(10, history.size)
        assertEquals("từ khóa 14", history.first())
    }
}

package com.luxysiv.puretube.data.local

import android.content.Context
import com.luxysiv.puretube.data.model.SponsorCategory
import com.luxysiv.puretube.data.model.SponsorMode
import com.luxysiv.puretube.data.model.defaultSponsorCategoryModes
import com.luxysiv.puretube.data.repository.SponsorBlockDataSource

/** Toàn bộ tuỳ chọn người dùng, lưu bền trên thiết bị. */
data class AppSettings(
    val backgroundPlay: Boolean = true,
    val autoNext: Boolean = true,
    val autoPiP: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    /** Cao độ (pitch) khi phát; 1.0 = giữ nguyên giọng. */
    val playbackPitch: Float = 1.0f,
    /** Tự bỏ qua khoảng lặng trong video (Media3 skip silence). */
    val skipSilence: Boolean = false,
    /** Giới hạn chất lượng khi dùng Wi-Fi; 0 = tự động. */
    val qualityHeight: Int = 0,
    /** Giới hạn chất lượng khi dùng dữ liệu di động; 0 = tự động. */
    val qualityMobileHeight: Int = 0,
    /** Trên mạng di động, hạ chất lượng "Tự động" xuống 480p để tiết kiệm dữ liệu. */
    val dataSaver: Boolean = false,
    val resumePlayback: Boolean = true,
    val skipSponsors: Boolean = true,
    /** Hiện thông báo ngắn khi tự tua qua một đoạn. */
    val sponsorNotify: Boolean = false,
    /** Hiện nút Bỏ qua / Hoàn tác trên trình phát. */
    val sponsorShowSkipButton: Boolean = false,
    /** Tua lại vào đoạn đã bỏ qua thì tạm ngừng tự tua cho hết đoạn đó. */
    val sponsorGracefulRewind: Boolean = true,
    /** API SponsorBlock; cho phép self-host. */
    val sponsorApiUrl: String = SponsorBlockDataSource.DEFAULT_API_URL,
    /** Chế độ xử lý riêng cho từng category. */
    val sponsorCategoryModes: Map<String, SponsorMode> = defaultSponsorCategoryModes(),
    /** Kênh được miễn trừ SponsorBlock. */
    val sponsorWhitelist: Set<String> = emptySet(),
    /** Nhớ trạng thái phụ đề giữa các video/phiên, giống nút CC của YouTube. */
    val captionsEnabled: Boolean = false,
    /** Ngôn ngữ phụ đề đã chọn; rỗng = theo ngôn ngữ thiết bị/track mặc định. */
    val captionLanguageTag: String = "",
    /** Ẩn phần bình luận trên trang xem (giống chế độ gọn nhẹ). */
    val hideComments: Boolean = false,
    /** Ẩn danh sách video liên quan/mix trên trang xem. */
    val hideRelated: Boolean = false,
    /** Ẩn mô tả video trên trang xem. */
    val hideDescription: Boolean = false
)

/**
 * Lưu tuỳ chọn bằng SharedPreferences (đồng bộ với [SearchHistoryStore]).
 *
 * Trước đây các toggle chỉ nằm trong `PurePlayerState` nên bị reset mỗi lần
 * kill app; lớp này giữ chúng qua các phiên.
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    fun load(): AppSettings = synchronized(lock) {
        val version = prefs.getInt(KEY_SETTINGS_VERSION, SETTINGS_VERSION_LEGACY)
        val migrateLegacySponsorDefaults = version < SETTINGS_VERSION
        val sponsorModes = SettingsCodec.parseSponsorModesStringSet(
            prefs.getStringSet(KEY_SPONSOR_MODES, null)
        ).let { modes ->
            if (migrateLegacySponsorDefaults) modes.withAutomaticIntroOutro() else modes
        }

        val settings = AppSettings(
            backgroundPlay = prefs.getBoolean(KEY_BACKGROUND_PLAY, true),
            autoNext = prefs.getBoolean(KEY_AUTO_NEXT, true),
            autoPiP = prefs.getBoolean(KEY_AUTO_PIP, false),
            playbackSpeed = prefs.getFloat(KEY_SPEED, 1.0f),
            playbackPitch = prefs.getFloat(KEY_PITCH, 1.0f),
            skipSilence = prefs.getBoolean(KEY_SKIP_SILENCE, false),
            qualityHeight = prefs.getInt(KEY_QUALITY, 0),
            qualityMobileHeight = prefs.getInt(KEY_QUALITY_MOBILE, 0),
            dataSaver = prefs.getBoolean(KEY_DATA_SAVER, false),
            resumePlayback = prefs.getBoolean(KEY_RESUME, true),
            skipSponsors = prefs.getBoolean(KEY_SKIP_SPONSORS, true),
            sponsorNotify = if (migrateLegacySponsorDefaults) {
                false
            } else {
                prefs.getBoolean(KEY_SPONSOR_NOTIFY, false)
            },
            sponsorShowSkipButton = if (migrateLegacySponsorDefaults) {
                false
            } else {
                prefs.getBoolean(KEY_SPONSOR_SHOW_BUTTON, false)
            },
            sponsorGracefulRewind = prefs.getBoolean(KEY_SPONSOR_GRACEFUL_REWIND, true),
            sponsorApiUrl = prefs.getString(KEY_SPONSOR_API, SponsorBlockDataSource.DEFAULT_API_URL)
                .orEmpty()
                .ifBlank { SponsorBlockDataSource.DEFAULT_API_URL },
            sponsorCategoryModes = sponsorModes,
            sponsorWhitelist = prefs.getStringSet(KEY_SPONSOR_WHITELIST, emptySet())
                .orEmpty()
                .toSet(),
            captionsEnabled = prefs.getBoolean(KEY_CAPTIONS, false),
            captionLanguageTag = prefs.getString(KEY_CAPTION_LANGUAGE, "").orEmpty(),
            hideComments = prefs.getBoolean(KEY_HIDE_COMMENTS, false),
            hideRelated = prefs.getBoolean(KEY_HIDE_RELATED, false),
            hideDescription = prefs.getBoolean(KEY_HIDE_DESCRIPTION, false)
        )

        // Cài đặt cũ lưu nguyên bộ giá trị mặc định cũ. Migrate một lần để các
        // mặc định mới (không thông báo/không nút, Mở đầu & Kết thúc tự động)
        // thực sự có hiệu lực sau khi nâng cấp, nhưng không ghi đè lựa chọn
        // người dùng đã lưu ở các phiên sau.
        if (migrateLegacySponsorDefaults) {
            prefs.edit()
                .putInt(KEY_SETTINGS_VERSION, SETTINGS_VERSION)
                .putBoolean(KEY_SPONSOR_NOTIFY, settings.sponsorNotify)
                .putBoolean(KEY_SPONSOR_SHOW_BUTTON, settings.sponsorShowSkipButton)
                .putStringSet(
                    KEY_SPONSOR_MODES,
                    SettingsCodec.encodeSponsorModesStringSet(settings.sponsorCategoryModes)
                )
                .apply()
        }
        settings
    }

    /** Mở đầu/Kết thúc trước đây mặc định Tắt; chuyển sang Tự động một lần. */
    private fun Map<String, SponsorMode>.withAutomaticIntroOutro(): Map<String, SponsorMode> {
        val migrated = toMutableMap()
        SponsorCategory.entries
            .filter { it == SponsorCategory.INTRO || it == SponsorCategory.OUTRO }
            .forEach { category ->
                if (migrated[category.apiName] == SponsorMode.DISABLED) {
                    migrated[category.apiName] = SponsorMode.AUTO
                }
            }
        return migrated
    }

    fun save(settings: AppSettings) {
        synchronized(lock) {
            prefs.edit()
                .putInt(KEY_SETTINGS_VERSION, SETTINGS_VERSION)
                .putBoolean(KEY_BACKGROUND_PLAY, settings.backgroundPlay)
                .putBoolean(KEY_AUTO_NEXT, settings.autoNext)
                .putBoolean(KEY_AUTO_PIP, settings.autoPiP)
                .putFloat(KEY_SPEED, settings.playbackSpeed)
                .putFloat(KEY_PITCH, settings.playbackPitch)
                .putBoolean(KEY_SKIP_SILENCE, settings.skipSilence)
                .putInt(KEY_QUALITY, settings.qualityHeight)
                .putInt(KEY_QUALITY_MOBILE, settings.qualityMobileHeight)
                .putBoolean(KEY_DATA_SAVER, settings.dataSaver)
                .putBoolean(KEY_RESUME, settings.resumePlayback)
                .putBoolean(KEY_SKIP_SPONSORS, settings.skipSponsors)
                .putBoolean(KEY_SPONSOR_NOTIFY, settings.sponsorNotify)
                .putBoolean(KEY_SPONSOR_SHOW_BUTTON, settings.sponsorShowSkipButton)
                .putBoolean(KEY_SPONSOR_GRACEFUL_REWIND, settings.sponsorGracefulRewind)
                .putString(
                    KEY_SPONSOR_API,
                    settings.sponsorApiUrl.ifBlank { SponsorBlockDataSource.DEFAULT_API_URL }
                )
                .putStringSet(
                    KEY_SPONSOR_MODES,
                    SettingsCodec.encodeSponsorModesStringSet(settings.sponsorCategoryModes)
                )
                .putStringSet(KEY_SPONSOR_WHITELIST, settings.sponsorWhitelist)
                .putBoolean(KEY_CAPTIONS, settings.captionsEnabled)
                .putString(KEY_CAPTION_LANGUAGE, settings.captionLanguageTag)
                .putBoolean(KEY_HIDE_COMMENTS, settings.hideComments)
                .putBoolean(KEY_HIDE_RELATED, settings.hideRelated)
                .putBoolean(KEY_HIDE_DESCRIPTION, settings.hideDescription)
                .apply()
        }
    }

    private companion object {
        const val PREFS_NAME = "puretube_settings"
        /** Tăng khi thay đổi mặc định cần migrate dữ liệu đã lưu. */
        const val KEY_SETTINGS_VERSION = "settings_version"
        const val SETTINGS_VERSION = 2
        const val SETTINGS_VERSION_LEGACY = 1
        const val KEY_BACKGROUND_PLAY = "background_play"
        const val KEY_AUTO_NEXT = "auto_next"
        const val KEY_AUTO_PIP = "auto_pip"
        const val KEY_SPEED = "playback_speed"
        const val KEY_PITCH = "playback_pitch"
        const val KEY_SKIP_SILENCE = "skip_silence"
        const val KEY_QUALITY = "quality_height"
        const val KEY_QUALITY_MOBILE = "quality_mobile_height"
        const val KEY_DATA_SAVER = "data_saver"
        const val KEY_RESUME = "resume_playback"
        const val KEY_SKIP_SPONSORS = "skip_sponsors"
        const val KEY_SPONSOR_NOTIFY = "sponsor_notify"
        const val KEY_SPONSOR_SHOW_BUTTON = "sponsor_show_skip_button"
        const val KEY_SPONSOR_GRACEFUL_REWIND = "sponsor_graceful_rewind"
        const val KEY_SPONSOR_API = "sponsor_api_url"
        const val KEY_SPONSOR_MODES = "sponsor_category_modes"
        const val KEY_SPONSOR_WHITELIST = "sponsor_channel_whitelist"
        const val KEY_CAPTIONS = "captions_enabled"
        const val KEY_CAPTION_LANGUAGE = "caption_language"
        const val KEY_HIDE_COMMENTS = "hide_comments"
        const val KEY_HIDE_RELATED = "hide_related"
        const val KEY_HIDE_DESCRIPTION = "hide_description"
    }
}

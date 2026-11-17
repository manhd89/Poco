package com.luxysiv.puretube.data.local

import com.luxysiv.puretube.data.model.SponsorCategory
import com.luxysiv.puretube.data.model.SponsorMode
import com.luxysiv.puretube.data.model.defaultSponsorCategoryModes
import com.luxysiv.puretube.data.repository.SponsorBlockDataSource
import com.luxysiv.puretube.error.runAppCatchingBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Codec dùng chung cho [AppSettings].
 *
 * - `SettingsStore` dùng dạng key/value phẳng trong SharedPreferences.
 * - `BackupManager` dùng JSON object.
 *
 * Gom mapping ở một nơi để giảm lệch schema/default giữa lưu cục bộ và sao lưu.
 */
internal object SettingsCodec {

    fun toJson(settings: AppSettings): JSONObject = JSONObject().apply {
        put("backgroundPlay", settings.backgroundPlay)
        put("autoNext", settings.autoNext)
        put("autoPiP", settings.autoPiP)
        put("playbackSpeed", settings.playbackSpeed.toDouble())
        put("playbackPitch", settings.playbackPitch.toDouble())
        put("skipSilence", settings.skipSilence)
        put("qualityHeight", settings.qualityHeight)
        put("qualityMobileHeight", settings.qualityMobileHeight)
        put("dataSaver", settings.dataSaver)
        put("resumePlayback", settings.resumePlayback)
        put("skipSponsors", settings.skipSponsors)
        put("sponsorNotify", settings.sponsorNotify)
        put("sponsorShowSkipButton", settings.sponsorShowSkipButton)
        put("sponsorGracefulRewind", settings.sponsorGracefulRewind)
        put(
            "sponsorApiUrl",
            settings.sponsorApiUrl.ifBlank { SponsorBlockDataSource.DEFAULT_API_URL }
        )
        put(
            "sponsorCategoryModes",
            JSONObject().apply {
                settings.sponsorCategoryModes.forEach { entry ->
                    put(entry.key, entry.value.name)
                }
            }
        )
        put("sponsorWhitelist", JSONArray().apply { settings.sponsorWhitelist.forEach { put(it) } })
        put("captionsEnabled", settings.captionsEnabled)
        put("captionLanguageTag", settings.captionLanguageTag)
        put("hideComments", settings.hideComments)
        put("hideRelated", settings.hideRelated)
        put("hideDescription", settings.hideDescription)
    }

    fun fromJson(json: JSONObject?): AppSettings {
        val defaults = AppSettings()
        val source = json ?: return defaults

        return defaults.copy(
            backgroundPlay = source.optBoolean("backgroundPlay", defaults.backgroundPlay),
            autoNext = source.optBoolean("autoNext", defaults.autoNext),
            autoPiP = source.optBoolean("autoPiP", defaults.autoPiP),
            playbackSpeed = source.optDouble("playbackSpeed", defaults.playbackSpeed.toDouble())
                .toFloat(),
            playbackPitch = source.optDouble("playbackPitch", defaults.playbackPitch.toDouble())
                .toFloat(),
            skipSilence = source.optBoolean("skipSilence", defaults.skipSilence),
            qualityHeight = source.optInt("qualityHeight", defaults.qualityHeight),
            qualityMobileHeight = source.optInt("qualityMobileHeight", defaults.qualityMobileHeight),
            dataSaver = source.optBoolean("dataSaver", defaults.dataSaver),
            resumePlayback = source.optBoolean("resumePlayback", defaults.resumePlayback),
            skipSponsors = source.optBoolean("skipSponsors", defaults.skipSponsors),
            sponsorNotify = source.optBoolean("sponsorNotify", defaults.sponsorNotify),
            sponsorShowSkipButton = source.optBoolean(
                "sponsorShowSkipButton",
                defaults.sponsorShowSkipButton
            ),
            sponsorGracefulRewind = source.optBoolean(
                "sponsorGracefulRewind",
                defaults.sponsorGracefulRewind
            ),
            sponsorApiUrl = source.optString("sponsorApiUrl")
                .ifBlank { SponsorBlockDataSource.DEFAULT_API_URL },
            sponsorCategoryModes = parseSponsorModes(source.optJSONObject("sponsorCategoryModes")),
            sponsorWhitelist = parseWhitelist(source.optJSONArray("sponsorWhitelist")),
            captionsEnabled = source.optBoolean("captionsEnabled", defaults.captionsEnabled),
            captionLanguageTag = source.optString("captionLanguageTag", defaults.captionLanguageTag),
            hideComments = source.optBoolean("hideComments", defaults.hideComments),
            hideRelated = source.optBoolean("hideRelated", defaults.hideRelated),
            hideDescription = source.optBoolean("hideDescription", defaults.hideDescription)
        )
    }

    fun parseSponsorModes(json: JSONObject?): Map<String, SponsorMode> {
        val modes = defaultSponsorCategoryModes().toMutableMap()
        json ?: return modes
        SponsorCategory.entries.forEach { category ->
            val rawMode = json.optString(category.apiName)
            if (rawMode.isBlank()) return@forEach
            val mode = runAppCatchingBlocking { SponsorMode.valueOf(rawMode) }.getOrNull()
                ?: return@forEach
            modes[category.apiName] = mode
        }
        return modes
    }

    fun encodeSponsorModesStringSet(modes: Map<String, SponsorMode>): Set<String> =
        modes.entries.mapTo(mutableSetOf()) { (category, mode) -> "$category=${mode.name}" }

    fun parseSponsorModesStringSet(raw: Set<String>?): Map<String, SponsorMode> {
        val defaults = defaultSponsorCategoryModes()
        val entries = raw ?: return defaults
        val parsed = entries.mapNotNull { entry ->
            val separator = entry.indexOf('=')
            if (separator <= 0 || separator == entry.lastIndex) return@mapNotNull null
            val category = SponsorCategory.fromApiName(entry.substring(0, separator))
                ?: return@mapNotNull null
            val mode = runAppCatchingBlocking {
                SponsorMode.valueOf(entry.substring(separator + 1))
            }.getOrNull() ?: return@mapNotNull null
            category.apiName to mode
        }.toMap()
        return defaults + parsed
    }

    fun parseWhitelist(array: JSONArray?): Set<String> {
        array ?: return emptySet()
        return buildSet {
            for (index in 0 until array.length()) {
                array.optString(index).trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let(::add)
            }
        }
    }
}

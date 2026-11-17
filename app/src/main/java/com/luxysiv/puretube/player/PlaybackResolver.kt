@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.luxysiv.puretube.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.luxysiv.puretube.data.model.PlaybackData
import com.luxysiv.puretube.data.model.PlaybackPlan
import com.luxysiv.puretube.data.model.VideoItem
import com.luxysiv.puretube.data.repository.YouTubeDataRepository
import com.luxysiv.puretube.error.AppError
import com.luxysiv.puretube.error.AppResult
import com.luxysiv.puretube.error.runAppCatchingBlocking

/**
 * Chuyển [VideoItem] + [PlaybackPlan] thành [MediaItem] cho ExoPlayer.
 *
 * Resolver là nơi DUY NHẤT dựng metadata. Metadata giữ nguyên từng trường so
 * với bản 1.5.0 (title, artist, artworkUri) để Bluetooth/AVRCP không bị regress.
 */
class PlaybackResolver(
    private val loadPlaybackData: suspend (VideoItem) -> AppResult<PlaybackData> = {
        YouTubeDataRepository.getPlaybackData(it)
    }
) {

    /** Resolve URL stream + related/details; lỗi được mang theo trong kết quả. */
    suspend fun resolve(video: VideoItem): PlaybackResolution =
        loadPlaybackData(video).fold(
            onSuccess = { data ->
                if (data.plan == null) {
                    PlaybackResolution(
                        plan = null,
                        related = data.related,
                        details = data.details,
                        error = AppError.Unavailable()
                    )
                } else {
                    PlaybackResolution(
                        plan = data.plan,
                        related = data.related,
                        details = data.details
                    )
                }
            },
            onFailure = { error ->
                PlaybackResolution(plan = null, error = error)
            }
        )

    /**
     * Dựng MediaItem cho candidate hiện tại. Trả null khi plan không có link phát
     * hoặc candidateIndex nằm ngoài danh sách.
     */
    fun buildMediaItem(
        video: VideoItem,
        plan: PlaybackPlan,
        candidateIndex: Int = 0
    ): MediaItem? {
        val source = plan.candidates.getOrNull(candidateIndex) ?: return null
        return MediaItem.Builder()
            .setMediaId(video.id)
            .setUri(source.uri)
            .setMimeType(source.mimeType)
            .setMediaMetadata(buildMetadata(video))
            .apply {
                val subtitles = buildSubtitleConfigurations(plan)
                if (subtitles.isNotEmpty()) setSubtitleConfigurations(subtitles)
            }
            .build()
    }

    /** Candidate kế tiếp để fallback khi stream hiện tại lỗi. */
    fun nextCandidate(resolved: ResolvedMedia): ResolvedMedia? {
        val nextIndex = resolved.candidateIndex + 1
        if (nextIndex >= resolved.plan.candidates.size) return null
        return resolved.copy(candidateIndex = nextIndex)
    }

    /** Metadata y hệt bản 1.5.0: title, artist, artworkUri. */
    fun buildMetadata(video: VideoItem): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(video.title)
            .setArtist(video.channelTitle)
            .apply {
                if (video.thumbnailUrl.isNotBlank()) {
                    runAppCatchingBlocking { setArtworkUri(Uri.parse(video.thumbnailUrl)) }
                }
            }
            .build()

    private fun buildSubtitleConfigurations(plan: PlaybackPlan): List<MediaItem.SubtitleConfiguration> =
        plan.subtitles.map { subtitle ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.uri))
                .setMimeType(subtitle.mimeType)
                .setSelectionFlags(
                    if (subtitle.isDefault) C.SELECTION_FLAG_DEFAULT else 0
                )
                .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
                .apply {
                    if (subtitle.languageTag.isNotBlank()) {
                        setLanguage(subtitle.languageTag)
                        setId(subtitle.languageTag)
                    }
                    if (subtitle.label.isNotBlank()) setLabel(subtitle.label)
                }
                .build()
        }
}

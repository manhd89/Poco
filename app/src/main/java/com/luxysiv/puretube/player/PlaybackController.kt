package com.luxysiv.puretube.player

import android.content.Context
import androidx.media3.common.Player
import com.luxysiv.puretube.data.local.AppSettings
import com.luxysiv.puretube.data.model.PlaybackPlan
import com.luxysiv.puretube.data.model.PlaylistItem
import com.luxysiv.puretube.data.model.SponsorCategory
import com.luxysiv.puretube.data.model.SponsorMode
import com.luxysiv.puretube.data.model.VideoDetails
import com.luxysiv.puretube.data.model.VideoItem
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Facade mỏng cho UI/ViewModel: chỉ chuyển lệnh xuống [PlaybackCoordinator]
 * và expose StateFlow. Không chứa logic phát.
 *
 * Nhờ lớp này, các ViewModel của từng trang không còn biết ExoPlayer, queue hay
 * SponsorBlock; mọi thứ sống ở cấp process và vẫn chạy khi UI bị huỷ.
 */
class PlaybackController(context: Context) {

    private val coordinator = PlaybackCoordinatorProvider.get(context.applicationContext)

    val player: Player get() = coordinator.player

    val playback: StateFlow<PlaybackUiState> = coordinator.playback
    val position: StateFlow<PlaybackPosition> = coordinator.position
    val plan: StateFlow<PlaybackPlan?> = coordinator.plan
    val details: StateFlow<VideoDetails?> = coordinator.details
    val libraryFlags: StateFlow<LibraryFlags> = coordinator.libraryFlags
    val settings: StateFlow<AppSettings> = coordinator.settings
    val queueState: StateFlow<QueueUiState> = coordinator.queueState
    val sponsorState: StateFlow<SponsorUiState> = coordinator.sponsorState
    val isMeteredNetwork: StateFlow<Boolean> = coordinator.isMeteredNetwork
    val messages: SharedFlow<String> = coordinator.messages

    // ------------------------------------------------------------------
    // Lệnh phát
    // ------------------------------------------------------------------

    fun play(
        video: VideoItem,
        origin: PlaybackOrigin = PlaybackOrigin.USER,
        addToBackStack: Boolean = true
    ) = coordinator.play(video, origin, addToBackStack)

    /** Phát video trong ngữ cảnh playlist: Next/Previous đi theo danh sách. */
    fun playPlaylist(
        video: VideoItem,
        playlist: PlaylistItem,
        videos: List<VideoItem>,
        nextToken: String = ""
    ) = coordinator.playPlaylist(video, playlist, videos, nextToken)

    fun retry() = coordinator.retry()

    fun next(force: Boolean = false) = coordinator.next(force)

    fun previous() = coordinator.previous()

    fun togglePlayPause() = coordinator.togglePlayPause()

    fun close() = coordinator.close()

    fun onAppBackgrounded() = coordinator.onAppBackgrounded()

    fun onAppForegrounded() = coordinator.onAppForegrounded()

    fun seekTo(seconds: Float) = coordinator.seekTo(seconds)

    fun forward10Seconds() = coordinator.forward10Seconds()

    fun rewind10Seconds() = coordinator.rewind10Seconds()

    // ------------------------------------------------------------------
    // Cài đặt phát
    // ------------------------------------------------------------------

    fun setPlaybackSpeed(speed: Float) = coordinator.setPlaybackSpeed(speed)

    fun setPlaybackPitch(pitch: Float) = coordinator.setPlaybackPitch(pitch)

    fun toggleSkipSilence() = coordinator.toggleSkipSilence()

    fun resetPlaybackParameters() = coordinator.resetPlaybackParameters()

    fun setQuality(height: Int?) = coordinator.setQuality(height)

    fun setWifiQuality(height: Int?) = coordinator.setWifiQuality(height)

    fun setMobileQuality(height: Int?) = coordinator.setMobileQuality(height)

    fun toggleDataSaver() = coordinator.toggleDataSaver()

    fun toggleBackgroundPlay() = coordinator.toggleBackgroundPlay()

    fun toggleAutoNext() = coordinator.toggleAutoNext()

    fun setAutoPlayMode(mode: AutoPlayMode) = coordinator.setAutoPlayMode(mode)

    fun toggleAutoPiP() = coordinator.toggleAutoPiP()

    fun toggleResumePlayback() = coordinator.toggleResumePlayback()

    fun toggleCaptions() = coordinator.toggleCaptions()

    fun setCaptionsEnabled(enabled: Boolean) = coordinator.setCaptionsEnabled(enabled)

    fun setCaptionLanguage(languageTag: String) = coordinator.setCaptionLanguage(languageTag)

    fun toggleSponsorBlock() = coordinator.toggleSponsorBlock()

    fun setSponsorCategoryMode(category: SponsorCategory, mode: SponsorMode) =
        coordinator.setSponsorCategoryMode(category, mode)

    fun setSponsorNotify(enabled: Boolean) = coordinator.setSponsorNotify(enabled)

    fun setSponsorShowSkipButton(enabled: Boolean) =
        coordinator.setSponsorShowSkipButton(enabled)

    fun setSponsorGracefulRewind(enabled: Boolean) =
        coordinator.setSponsorGracefulRewind(enabled)

    fun setSponsorApiUrl(url: String) = coordinator.setSponsorApiUrl(url)

    fun toggleCurrentChannelSponsorWhitelist() =
        coordinator.toggleCurrentChannelSponsorWhitelist()

    fun clearSponsorWhitelist() = coordinator.clearSponsorWhitelist()

    fun skipCurrentSponsorSegment() = coordinator.skipCurrentSponsorSegment()

    fun unskipCurrentSponsorSegment() = coordinator.unskipCurrentSponsorSegment()

    fun setHideComments(enabled: Boolean) = coordinator.setHideComments(enabled)

    fun setHideRelated(enabled: Boolean) = coordinator.setHideRelated(enabled)

    fun setHideDescription(enabled: Boolean) = coordinator.setHideDescription(enabled)

    fun setSleepTimer(minutes: Int) = coordinator.setSleepTimer(minutes)

    fun replaceSettings(settings: AppSettings) = coordinator.replaceSettings(settings)

    // ------------------------------------------------------------------
    // Hàng đợi / thư viện
    // ------------------------------------------------------------------

    fun addToQueue(video: VideoItem) = coordinator.addToQueue(video)

    fun playNextInQueue(video: VideoItem) = coordinator.playNextInQueue(video)

    fun cycleRepeatMode() = coordinator.cycleRepeatMode()

    fun setTrending(videos: List<VideoItem>) = coordinator.setTrending(videos)

    fun refreshLibraryFlags(videoId: String) = coordinator.refreshLibraryFlags(videoId)

    fun refreshDetails(video: VideoItem) = coordinator.refreshDetails(video)
}

/** Singleton cấp process để mọi ViewModel/Activity dùng chung một facade. */
object PlaybackControllerProvider {
    @Volatile
    private var instance: PlaybackController? = null

    fun get(context: Context): PlaybackController =
        instance ?: synchronized(this) {
            instance ?: PlaybackController(context.applicationContext).also { instance = it }
        }

    /** Trả về facade nếu đã được tạo, không khởi tạo coordinator mới. */
    fun peek(): PlaybackController? = instance
}

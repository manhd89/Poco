package com.luxysiv.puretube.player

import com.luxysiv.puretube.data.database.PlaylistHistoryDao
import com.luxysiv.puretube.data.database.PlaylistHistoryEntity
import com.luxysiv.puretube.data.database.VideoDao
import com.luxysiv.puretube.data.database.VideoEntity
import com.luxysiv.puretube.data.model.VideoItem
import com.luxysiv.puretube.data.model.parseDurationSeconds

/** Kết quả ghi nhận lúc bắt đầu phát: vị trí resume + cờ thư viện. */
data class HistoryStart(
    val resumeSeconds: Long,
    val isFavorite: Boolean,
    val isWatchLater: Boolean
)

/**
 * Ghi lịch sử/tiến độ và tính vị trí resume.
 *
 * Video luôn được ghi vào lịch sử riêng; khi phát trong ngữ cảnh playlist thì
 * ghi thêm lịch sử playlist (video cuối + vị trí) để mở lại và resume đúng chỗ.
 * Tách khỏi ViewModel để ticker/history vẫn chạy khi UI đã chết.
 */
class PlaybackHistoryRepository(
    private val videoDao: VideoDao,
    private val playlistDao: PlaylistHistoryDao,
    private val resumeEnabled: () -> Boolean
) {

    suspend fun onStarted(video: VideoItem, applyResume: Boolean = true): HistoryStart {
        val existing = videoDao.getVideoById(video.id)
        val durationSeconds = parseDurationSeconds(video.duration).toLong()
        val savedProgress = existing?.watchProgressSeconds ?: 0L
        val resumeSeconds = if (
            applyResume &&
            resumeEnabled() &&
            savedProgress > 5L &&
            (durationSeconds <= 0L || savedProgress < durationSeconds - 5L)
        ) {
            savedProgress
        } else {
            0L
        }

        // Giữ nguyên tiến độ cũ thay vì ghi đè 0 khi bắt đầu xem lại.
        videoDao.insertOrUpdate(
            VideoEntity.fromVideoItem(
                item = video,
                isFavorite = existing?.isFavorite ?: false,
                isWatchLater = existing?.isWatchLater ?: false,
                lastWatchedTimestamp = System.currentTimeMillis(),
                progress = resumeSeconds,
                favoriteAddedAt = existing?.favoriteAddedAt ?: 0L,
                watchLaterAddedAt = existing?.watchLaterAddedAt ?: 0L
            )
        )
        return HistoryStart(
            resumeSeconds = resumeSeconds,
            isFavorite = existing?.isFavorite ?: false,
            isWatchLater = existing?.isWatchLater ?: false
        )
    }

    /**
     * Ghi/cập nhật playlist đang phát: giữ metadata danh sách và đánh dấu video
     * hiện tại là video cuối để lần sau mở lại resume đúng chỗ.
     */
    suspend fun onPlaylistStarted(
        playlist: PlaybackPlaylistContext,
        video: VideoItem,
        positionSeconds: Long
    ) {
        if (playlist.id.isBlank()) return
        val existing = playlistDao.getById(playlist.id)
        playlistDao.upsert(
            PlaylistHistoryEntity(
                playlistId = playlist.id,
                title = playlist.title.ifBlank { existing?.title.orEmpty() },
                channelTitle = playlist.channelTitle.ifBlank {
                    existing?.channelTitle.orEmpty()
                },
                channelId = playlist.channelId.ifBlank { existing?.channelId.orEmpty() },
                thumbnailUrl = playlist.thumbnailUrl.ifBlank {
                    existing?.thumbnailUrl.orEmpty()
                },
                videoCount = maxOf(
                    playlist.videoCount,
                    playlist.videos.size,
                    existing?.videoCount ?: 0
                ),
                lastVideoId = video.id,
                lastVideoTitle = video.title,
                lastPositionSeconds = positionSeconds,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun saveProgress(videoId: String, positionMs: Long) {
        // Bỏ qua vị trí quá nhỏ: tránh ghi đè tiến độ cũ bằng 0 khi vừa chuyển
        // bài hoặc vừa mở video rồi thoát ngay.
        if (videoId.isBlank() || positionMs < MIN_PROGRESS_SAVE_MS) return
        videoDao.updateWatchHistory(
            videoId = videoId,
            timestamp = System.currentTimeMillis(),
            progress = positionMs / 1000L
        )
    }

    /**
     * Cập nhật vị trí xem của playlist nếu video vẫn là video cuối trong lịch
     * sử. Video cũ không thể ghi đè vị trí của video mới sau auto-next.
     */
    suspend fun savePlaylistProgress(playlistId: String, videoId: String, positionMs: Long) {
        if (playlistId.isBlank() || videoId.isBlank() || positionMs < MIN_PROGRESS_SAVE_MS) return
        playlistDao.updateProgress(
            playlistId = playlistId,
            videoId = videoId,
            progress = positionMs / 1000L,
            timestamp = System.currentTimeMillis()
        )
    }

    suspend fun flush(videoId: String?, positionMs: Long) {
        val id = videoId ?: return
        saveProgress(id, positionMs)
    }

    suspend fun flushPlaylist(playlistId: String?, videoId: String?, positionMs: Long) {
        val playlist = playlistId ?: return
        val video = videoId ?: return
        savePlaylistProgress(playlist, video, positionMs)
    }

    private companion object {
        const val MIN_PROGRESS_SAVE_MS = 1_000L
    }
}

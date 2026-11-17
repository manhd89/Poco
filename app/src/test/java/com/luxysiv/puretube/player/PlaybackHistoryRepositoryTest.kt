package com.luxysiv.puretube.player

import com.luxysiv.puretube.data.database.PlaylistHistoryDao
import com.luxysiv.puretube.data.database.PlaylistHistoryEntity
import com.luxysiv.puretube.data.database.VideoDao
import com.luxysiv.puretube.data.database.VideoEntity
import com.luxysiv.puretube.data.model.VideoItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kiểm tra ghi lịch sử video + playlist khi phát trong ngữ cảnh playlist. */
class PlaybackHistoryRepositoryTest {

    private val video = VideoItem(id = "v1", title = "Video 1", channelTitle = "Kênh")
    private val otherVideo = VideoItem(id = "v2", title = "Video 2", channelTitle = "Kênh")

    private fun playlist(
        id: String = "PL1",
        title: String = "Danh sách",
        videoCount: Int = 10,
        videos: List<VideoItem> = listOf(video)
    ) = PlaybackPlaylistContext(
        id = id,
        title = title,
        channelTitle = "Kênh",
        videoCount = videoCount,
        videos = videos
    )

    @Test
    fun `playlist start records last video and keeps video history`() = runBlocking {
        val videoDao = FakeVideoDao()
        val playlistDao = FakePlaylistHistoryDao()
        val repository = PlaybackHistoryRepository(videoDao, playlistDao) { false }

        val start = repository.onStarted(video, applyResume = false)
        repository.onPlaylistStarted(playlist(), video, start.resumeSeconds)

        val history = playlistDao.entities.getValue("PL1")
        assertEquals("Danh sách", history.title)
        assertEquals("v1", history.lastVideoId)
        assertEquals("Video 1", history.lastVideoTitle)
        assertEquals(10, history.videoCount)
        assertTrue(history.updatedAt > 0L)

        val videoHistory = videoDao.videos.getValue("v1")
        assertNotNull(videoHistory)
        assertTrue(videoHistory.lastWatchedTimestamp > 0L)
    }

    @Test
    fun `playlist start keeps metadata and grows video count`() = runBlocking {
        val playlistDao = FakePlaylistHistoryDao()
        val repository = PlaybackHistoryRepository(FakeVideoDao(), playlistDao) { false }

        repository.onPlaylistStarted(playlist(videoCount = 10), video, 0L)
        repository.onPlaylistStarted(
            playlist(
                title = "",
                videoCount = 0,
                videos = listOf(video, otherVideo)
            ),
            otherVideo,
            0L
        )

        val history = playlistDao.entities.getValue("PL1")
        assertEquals("Danh sách", history.title)
        assertEquals(10, history.videoCount)
        assertEquals("v2", history.lastVideoId)
    }

    @Test
    fun `progress only updates while the same video is the last one`() = runBlocking {
        val playlistDao = FakePlaylistHistoryDao()
        val repository = PlaybackHistoryRepository(FakeVideoDao(), playlistDao) { false }

        repository.onPlaylistStarted(playlist(), video, 0L)
        repository.savePlaylistProgress("PL1", "v1", 30_000L)
        assertEquals(30L, playlistDao.entities.getValue("PL1").lastPositionSeconds)

        // Auto-next đã chuyển sang video khác: ticker của video cũ không được
        // ghi đè vị trí mới.
        repository.onPlaylistStarted(playlist(videos = listOf(video, otherVideo)), otherVideo, 0L)
        repository.savePlaylistProgress("PL1", "v1", 60_000L)
        assertEquals(0L, playlistDao.entities.getValue("PL1").lastPositionSeconds)

        repository.savePlaylistProgress("PL1", "v2", 45_000L)
        assertEquals(45L, playlistDao.entities.getValue("PL1").lastPositionSeconds)
    }

    @Test
    fun `tiny progress is ignored`() = runBlocking {
        val playlistDao = FakePlaylistHistoryDao()
        val repository = PlaybackHistoryRepository(FakeVideoDao(), playlistDao) { false }

        repository.onPlaylistStarted(playlist(), video, 0L)
        repository.savePlaylistProgress("PL1", "v1", 500L)

        assertEquals(0L, playlistDao.entities.getValue("PL1").lastPositionSeconds)
    }

    private class FakePlaylistHistoryDao : PlaylistHistoryDao {
        val entities = linkedMapOf<String, PlaylistHistoryEntity>()

        override fun getHistory(): Flow<List<PlaylistHistoryEntity>> =
            MutableStateFlow(entities.values.toList())

        override suspend fun getById(playlistId: String): PlaylistHistoryEntity? =
            entities[playlistId]

        override suspend fun upsert(entity: PlaylistHistoryEntity) {
            entities[entity.playlistId] = entity
        }

        override suspend fun updateProgress(
            playlistId: String,
            videoId: String,
            progress: Long,
            timestamp: Long
        ) {
            val current = entities[playlistId] ?: return
            if (current.lastVideoId != videoId) return
            entities[playlistId] = current.copy(
                lastPositionSeconds = progress,
                updatedAt = timestamp
            )
        }

        override suspend fun clearHistory() = entities.clear()

        override suspend fun delete(playlistId: String) {
            entities.remove(playlistId)
        }

        override suspend fun getAllOnce(): List<PlaylistHistoryEntity> = entities.values.toList()

        override suspend fun insertAll(playlists: List<PlaylistHistoryEntity>) {
            playlists.forEach { entities[it.playlistId] = it }
        }
    }

    private class FakeVideoDao : VideoDao {
        val videos = linkedMapOf<String, VideoEntity>()

        override fun getFavorites(): Flow<List<VideoEntity>> =
            MutableStateFlow(videos.values.filter { it.isFavorite })

        override fun getHistory(): Flow<List<VideoEntity>> =
            MutableStateFlow(videos.values.filter { it.lastWatchedTimestamp > 0L })

        override fun getWatchLater(): Flow<List<VideoEntity>> =
            MutableStateFlow(videos.values.filter { it.isWatchLater })

        override suspend fun getWatchedVideoIds(): List<String> =
            videos.values.filter { it.lastWatchedTimestamp > 0L }.map { it.videoId }

        override fun getFavoriteIds(): Flow<List<String>> =
            MutableStateFlow(videos.values.filter { it.isFavorite }.map { it.videoId })

        override fun getWatchLaterIds(): Flow<List<String>> =
            MutableStateFlow(videos.values.filter { it.isWatchLater }.map { it.videoId })

        override fun observeWatchedVideoIds(): Flow<List<String>> =
            MutableStateFlow(videos.values.filter { it.lastWatchedTimestamp > 0L }.map { it.videoId })

        override suspend fun getVideoById(videoId: String): VideoEntity? = videos[videoId]

        override fun observeVideoById(videoId: String): Flow<VideoEntity?> =
            MutableStateFlow(videos[videoId])

        override suspend fun insertOrUpdate(entity: VideoEntity) {
            videos[entity.videoId] = entity
        }

        override suspend fun setFavorite(videoId: String, isFavorite: Boolean) {
            videos[videoId]?.let { videos[videoId] = it.copy(isFavorite = isFavorite) }
        }

        override suspend fun setWatchLater(videoId: String, isWatchLater: Boolean) {
            videos[videoId]?.let { videos[videoId] = it.copy(isWatchLater = isWatchLater) }
        }

        override suspend fun updateWatchHistory(videoId: String, timestamp: Long, progress: Long) {
            videos[videoId]?.let {
                videos[videoId] = it.copy(
                    lastWatchedTimestamp = timestamp,
                    watchProgressSeconds = progress
                )
            }
        }

        override suspend fun clearHistory() {
            videos.keys.toList().forEach { videoId ->
                videos[videoId] = videos.getValue(videoId).copy(
                    lastWatchedTimestamp = 0L,
                    watchProgressSeconds = 0L
                )
            }
        }

        override suspend fun clearProgress(videoId: String) {
            videos[videoId]?.let {
                videos[videoId] = it.copy(lastWatchedTimestamp = 0L, watchProgressSeconds = 0L)
            }
        }

        override suspend fun delete(videoId: String) {
            videos.remove(videoId)
        }

        override suspend fun getAllVideosOnce(): List<VideoEntity> = videos.values.toList()

        override suspend fun insertAll(videos: List<VideoEntity>) {
            videos.forEach { this.videos[it.videoId] = it }
        }
    }
}

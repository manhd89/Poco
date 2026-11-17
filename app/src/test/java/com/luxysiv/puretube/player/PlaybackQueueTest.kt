package com.luxysiv.puretube.player

import com.luxysiv.puretube.data.model.MixPlaylist
import com.luxysiv.puretube.data.model.PlaylistPage
import com.luxysiv.puretube.data.model.VideoItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kiểm tra thứ tự ưu tiên, vòng lặp và playStack của hàng đợi logic. */
class PlaybackQueueTest {

    private fun video(id: String) = VideoItem(
        id = id,
        title = "Video $id",
        channelTitle = "Channel"
    )

    private val a = video("a")
    private val b = video("b")
    private val c = video("c")
    private val related = video("related")
    private val queued = video("queued")

    @Test
    fun `user queue is used before mix and related`() {
        val queue = PlaybackQueue()
        queue.onPlayStarted(a, PlaybackOrigin.USER, previous = null)
        queue.setRelated(listOf(related))

        assertEquals(related, queue.nextTarget())

        queue.addToQueue(queued)
        assertEquals(queued, queue.nextTarget())
    }

    @Test
    fun `adding to queue rejects the current video and duplicates`() {
        val queue = PlaybackQueue()
        queue.onPlayStarted(a, PlaybackOrigin.USER, previous = null)

        assertEquals(QueueAddResult.ALREADY_PLAYING, queue.addToQueue(a))
        assertEquals(QueueAddResult.ADDED, queue.addToQueue(b))
        assertEquals(QueueAddResult.ALREADY_QUEUED, queue.addToQueue(b))
        assertEquals(listOf(b), queue.state.value.queue)
    }

    @Test
    fun `play next inserts at the front and removes an existing copy`() {
        val queue = PlaybackQueue()
        queue.addToQueue(b)
        queue.addToQueue(c)

        assertEquals(QueueAddResult.ADDED, queue.playNextInQueue(c))
        assertEquals(listOf(c, b), queue.state.value.queue)
    }

    @Test
    fun `repeat all replays the cycle before falling back to the mix`() {
        val queue = PlaybackQueue()
        queue.onPlayStarted(a, PlaybackOrigin.USER, previous = null)
        queue.onPlayStarted(b, PlaybackOrigin.USER, previous = a)
        queue.onPlayStarted(c, PlaybackOrigin.USER, previous = b)
        queue.setRepeatMode(RepeatMode.ALL)
        queue.setRelated(listOf(related))

        // Đang ở c: quay vòng các bài đã phát trước khi dùng related.
        assertEquals(a, queue.nextTarget())
    }

    @Test
    fun `repeat off does not replay the cycle`() {
        val queue = PlaybackQueue()
        queue.onPlayStarted(a, PlaybackOrigin.USER, previous = null)
        queue.onPlayStarted(b, PlaybackOrigin.USER, previous = a)
        queue.setRelated(listOf(related))

        assertEquals(related, queue.nextTarget())
    }

    @Test
    fun `play stack supplies previous and is consumed by previous origin`() {
        val queue = PlaybackQueue()
        queue.onPlayStarted(a, PlaybackOrigin.USER, previous = null)
        queue.onPlayStarted(b, PlaybackOrigin.USER, previous = a)

        assertEquals(a, queue.previousTarget())

        queue.onPlayStarted(a, PlaybackOrigin.PREVIOUS, previous = b, addToBackStack = false)
        assertNull(queue.previousTarget())
    }

    @Test
    fun `previous follows the mix index when the video belongs to the mix`() {
        val mix = MixPlaylist(id = "mix", videos = listOf(a, b, c))
        val queue = PlaybackQueue(mixLoader = { mix })

        queue.onPlayStarted(c, PlaybackOrigin.USER, previous = null)
        runBlocking { assertTrue(queue.loadMix(c.id)) }

        assertEquals(b, queue.previousTarget())
        // Đang ở cuối trang mix và chưa có related: không tự quay vòng.
        assertNull(queue.nextTarget())
    }

    @Test
    fun `loading more mix merges pages without duplicates`() {
        val first = MixPlaylist(id = "mix", videos = listOf(a, b), hasMore = true)
        val second = MixPlaylist(id = "mix", videos = listOf(b, c), hasMore = false)
        val queue = PlaybackQueue(
            mixLoader = { first },
            moreMixLoader = { second }
        )

        queue.onPlayStarted(a, PlaybackOrigin.USER, previous = null)
        runBlocking {
            assertTrue(queue.loadMix(a.id))
            assertTrue(queue.loadMoreMix())
        }

        val merged = queue.state.value.mix
        assertEquals(listOf(a, b, c), merged?.videos)
        assertEquals(false, merged?.hasMore)
    }

    @Test
    fun `native advance consumes the user queue and the play stack`() {
        val queue = PlaybackQueue()
        queue.onPlayStarted(a, PlaybackOrigin.USER, previous = null)
        queue.onPlayStarted(b, PlaybackOrigin.USER, previous = a)
        queue.addToQueue(c)

        // ExoPlayer tự chuyển sang c (đã nằm trong playlist native).
        queue.onAdvancedTo(c)

        assertTrue(queue.state.value.queue.isEmpty())
        assertEquals(c, queue.current)
    }

    @Test
    fun `native advance inside the mix keeps it`() {
        val mix = MixPlaylist(id = "mix", videos = listOf(a, b))
        val queue = PlaybackQueue(mixLoader = { mix })
        queue.onPlayStarted(a, PlaybackOrigin.USER, previous = null)
        runBlocking { queue.loadMix(a.id) }

        val needsNewMix = queue.onAdvancedTo(b)

        assertFalse(needsNewMix)
        assertEquals(mix, queue.state.value.mix)
    }

    @Test
    fun `native advance outside the mix drops the stale mix`() {
        val mix = MixPlaylist(id = "mix", videos = listOf(a, b))
        val queue = PlaybackQueue(mixLoader = { mix })
        queue.onPlayStarted(a, PlaybackOrigin.USER, previous = null)
        runBlocking { queue.loadMix(a.id) }

        val needsNewMix = queue.onAdvancedTo(queued)

        assertTrue(needsNewMix)
        assertNull(queue.state.value.mix)
    }

    @Test
    fun `next navigation prefers the native playlist item`() {
        val queue = PlaybackQueue()
        queue.onPlayStarted(a, PlaybackOrigin.USER, previous = null)
        queue.addToQueue(b)

        assertEquals(PlaybackNavigation.NativeNext, queue.nextNavigation(hasNativeNext = true))
    }

    @Test
    fun `next navigation plays the queue target with next origin`() {
        val queue = PlaybackQueue()
        queue.onPlayStarted(a, PlaybackOrigin.USER, previous = null)
        queue.addToQueue(b)

        val navigation = queue.nextNavigation(hasNativeNext = false)

        assertTrue(navigation is PlaybackNavigation.Play)
        navigation as PlaybackNavigation.Play
        assertEquals(b, navigation.video)
        assertEquals(PlaybackOrigin.NEXT, navigation.origin)
    }

    @Test
    fun `next navigation asks for more mix before giving up`() {
        val mix = MixPlaylist(id = "mix", videos = listOf(a), hasMore = true)
        val queue = PlaybackQueue(mixLoader = { mix })
        queue.onPlayStarted(a, PlaybackOrigin.USER, previous = null)
        runBlocking { assertTrue(queue.loadMix(a.id)) }

        assertEquals(
            PlaybackNavigation.LoadMoreMix,
            queue.nextNavigation(hasNativeNext = false)
        )
    }

    @Test
    fun `next navigation reports none when everything is exhausted`() {
        val queue = PlaybackQueue()
        queue.onPlayStarted(a, PlaybackOrigin.USER, previous = null)

        assertEquals(
            PlaybackNavigation.None,
            queue.nextNavigation(hasNativeNext = false)
        )
    }

    @Test
    fun `previous navigation prefers the native playlist item`() {
        val queue = PlaybackQueue()
        queue.onPlayStarted(b, PlaybackOrigin.USER, previous = a)

        assertEquals(
            PlaybackNavigation.NativePrevious,
            queue.previousNavigation(
                hasNativePrevious = true,
                positionMs = 60_000L,
                seekThresholdMs = 3_000L
            )
        )
    }

    @Test
    fun `previous navigation seeks to start after the threshold`() {
        val queue = PlaybackQueue()
        queue.onPlayStarted(b, PlaybackOrigin.USER, previous = a)

        assertEquals(
            PlaybackNavigation.SeekToStart,
            queue.previousNavigation(
                hasNativePrevious = false,
                positionMs = 10_000L,
                seekThresholdMs = 3_000L
            )
        )
    }

    @Test
    fun `previous navigation plays the stack target with previous origin`() {
        val queue = PlaybackQueue()
        queue.onPlayStarted(a, PlaybackOrigin.USER, previous = null)
        queue.onPlayStarted(b, PlaybackOrigin.USER, previous = a)

        val navigation = queue.previousNavigation(
            hasNativePrevious = false,
            positionMs = 1_000L,
            seekThresholdMs = 3_000L
        )

        assertTrue(navigation is PlaybackNavigation.Play)
        navigation as PlaybackNavigation.Play
        assertEquals(a, navigation.video)
        assertEquals(PlaybackOrigin.PREVIOUS, navigation.origin)
    }

    @Test
    fun `playlist order drives next and previous targets`() {
        val queue = PlaybackQueue()
        queue.setPlaylist(
            PlaybackPlaylistContext(id = "PL1", videos = listOf(a, b, c))
        )
        queue.onPlayStarted(b, PlaybackOrigin.USER, previous = null)

        assertEquals(c, queue.nextTarget())
        assertEquals(a, queue.previousTarget())
    }

    @Test
    fun `playlist is used before the mix`() {
        val mix = MixPlaylist(id = "mix", videos = listOf(a, related))
        val queue = PlaybackQueue(mixLoader = { mix })
        queue.setPlaylist(
            PlaybackPlaylistContext(id = "PL1", videos = listOf(a, b))
        )
        queue.onPlayStarted(a, PlaybackOrigin.USER, previous = null)
        runBlocking { assertTrue(queue.loadMix(a.id)) }

        assertEquals(b, queue.nextTarget())
    }

    @Test
    fun `playing a video outside the playlist clears the playlist context`() {
        val queue = PlaybackQueue()
        queue.setPlaylist(
            PlaybackPlaylistContext(id = "PL1", videos = listOf(a, b))
        )
        queue.onPlayStarted(a, PlaybackOrigin.USER, previous = null)

        queue.onPlayStarted(queued, PlaybackOrigin.USER, previous = a)

        assertNull(queue.currentPlaylist)
    }

    @Test
    fun `advancing inside the playlist keeps it and skips mix loading`() {
        val queue = PlaybackQueue()
        queue.setPlaylist(
            PlaybackPlaylistContext(id = "PL1", videos = listOf(a, b))
        )
        queue.onPlayStarted(a, PlaybackOrigin.USER, previous = null)

        val needsNewMix = queue.onAdvancedTo(b)

        assertFalse(needsNewMix)
        assertEquals("PL1", queue.currentPlaylist?.id)
        assertEquals(b, queue.current)
    }

    @Test
    fun `next navigation asks for more playlist before mix`() {
        val queue = PlaybackQueue()
        queue.setPlaylist(
            PlaybackPlaylistContext(
                id = "PL1",
                videos = listOf(a),
                nextToken = "token"
            )
        )
        queue.onPlayStarted(a, PlaybackOrigin.USER, previous = null)

        assertEquals(
            PlaybackNavigation.LoadMorePlaylist,
            queue.nextNavigation(hasNativeNext = false)
        )
    }

    @Test
    fun `loading more playlist merges pages without duplicates`() {
        val first = PlaybackPlaylistContext(
            id = "PL1",
            videos = listOf(a, b),
            nextToken = "page2"
        )
        val second = PlaylistPage(videos = listOf(b, c), nextToken = "page3")
        val queue = PlaybackQueue(
            playlistMoreLoader = { id, token ->
                if (id == "PL1" && token == "page2") second else null
            }
        )
        queue.setPlaylist(first)
        queue.onPlayStarted(a, PlaybackOrigin.USER, previous = null)

        runBlocking { assertTrue(queue.loadMorePlaylist()) }

        assertEquals(listOf(a, b, c), queue.currentPlaylist?.videos)
        assertEquals("page3", queue.currentPlaylist?.nextToken)
        assertEquals(b, queue.nextTarget())
    }

    @Test
    fun `repeat all does not interrupt playlist order`() {
        val queue = PlaybackQueue()
        queue.setPlaylist(
            PlaybackPlaylistContext(id = "PL1", videos = listOf(a, b, c))
        )
        queue.onPlayStarted(a, PlaybackOrigin.USER, previous = null)
        queue.onPlayStarted(b, PlaybackOrigin.USER, previous = a)
        queue.setRepeatMode(RepeatMode.ALL)

        assertEquals(c, queue.nextTarget())
    }

    @Test
    fun `previous navigation falls back to seeking when no target exists`() {
        val queue = PlaybackQueue()
        queue.onPlayStarted(a, PlaybackOrigin.USER, previous = null)

        assertEquals(
            PlaybackNavigation.SeekToStart,
            queue.previousNavigation(
                hasNativePrevious = false,
                positionMs = 1_000L,
                seekThresholdMs = 3_000L
            )
        )
    }
}

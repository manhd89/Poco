@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.luxysiv.puretube.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.luxysiv.puretube.data.model.VideoItem

/**
 * Quản lý "cửa sổ playlist" của ExoPlayer: `[previous, current, next]`.
 *
 * Không quyết định bài kế về mặt logic (việc đó thuộc [PlaybackQueue]); lớp này
 * chỉ giữ playlist native đúng với quyết định đó, prefetch trước 1 bài và thay
 * candidate khi stream lỗi.
 */
class PlaybackQueueManager(
    private val engine: PlaybackEngine,
    private val resolver: PlaybackResolver
) {

    private val player: Player get() = engine.player
    private val resolvedById = mutableMapOf<String, ResolvedMedia>()

    fun videoFor(mediaId: String?): VideoItem? =
        mediaId?.let { resolvedById[it]?.video }

    fun resolvedFor(mediaId: String?): ResolvedMedia? =
        mediaId?.let { resolvedById[it] }

    fun currentMediaId(): String? = player.currentMediaItem?.mediaId

    /**
     * Nạp bài hiện tại. Chỉ dùng khi đổi bài chủ động (không dùng khi đang phát
     * và chuyển bài native).
     */
    fun loadCurrent(
        resolved: ResolvedMedia,
        startPositionMs: Long,
        previous: ResolvedMedia? = null,
        includePrevious: Boolean = true,
        autoPlay: Boolean = true
    ) {
        val currentItem = resolver.buildMediaItem(
            resolved.video,
            resolved.plan,
            resolved.candidateIndex
        ) ?: return
        resolvedById[resolved.video.id] = resolved

        val items = ArrayList<MediaItem>(2)
        if (includePrevious &&
            previous != null &&
            previous.video.id != resolved.video.id
        ) {
            resolver.buildMediaItem(
                previous.video,
                previous.plan,
                previous.candidateIndex
            )?.let {
                resolvedById[previous.video.id] = previous
                items += it
            }
        }
        items += currentItem

        player.setMediaItems(items, items.lastIndex, startPositionMs.coerceAtLeast(0L))
        player.prepare()
        if (autoPlay) player.play()
    }

    /** Thêm bài kế tiếp vào cuối playlist nếu chưa có. */
    fun prefetchNext(next: ResolvedMedia?) {
        if (next == null) {
            removeNext()
            return
        }
        if (player.mediaItemCount == 0) return
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex < 0) return

        val existing = if (currentIndex + 1 < player.mediaItemCount) {
            player.getMediaItemAt(currentIndex + 1)
        } else {
            null
        }
        if (existing?.mediaId == next.video.id) {
            resolvedById[next.video.id] = next
            return
        }

        removeNext()
        val item = resolver.buildMediaItem(next.video, next.plan, next.candidateIndex)
            ?: return
        resolvedById[next.video.id] = next
        player.addMediaItem(item)
    }

    /** Bỏ mọi item sau current (dùng khi không còn bài kế hoặc khi queue đổi). */
    fun removeNext() {
        if (player.mediaItemCount == 0) return
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex < 0) return
        while (player.mediaItemCount > currentIndex + 1) {
            val removeIndex = player.mediaItemCount - 1
            resolvedById.remove(player.getMediaItemAt(removeIndex).mediaId)
            player.removeMediaItem(removeIndex)
        }
    }

    /**
     * Giữ đúng cửa sổ 3 bài sau khi chuyển bài: bỏ item cũ trước previous và
     * mọi item thừa sau current. Không gọi setMediaItems để tránh gián đoạn.
     */
    fun updateWindow() {
        if (player.mediaItemCount == 0) return
        while (player.currentMediaItemIndex > 1) {
            resolvedById.remove(player.getMediaItemAt(0).mediaId)
            player.removeMediaItem(0)
        }
        while (player.mediaItemCount > player.currentMediaItemIndex + 1) {
            val removeIndex = player.mediaItemCount - 1
            resolvedById.remove(player.getMediaItemAt(removeIndex).mediaId)
            player.removeMediaItem(removeIndex)
        }
    }

    fun hasNext(videoId: String): Boolean {
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex < 0 || currentIndex + 1 >= player.mediaItemCount) return false
        return player.getMediaItemAt(currentIndex + 1).mediaId == videoId
    }

    fun hasPrevious(): Boolean = player.hasPreviousMediaItem()

    fun nextMediaId(): String? {
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex < 0 || currentIndex + 1 >= player.mediaItemCount) return null
        return player.getMediaItemAt(currentIndex + 1).mediaId
    }

    /**
     * Thay candidate đang lỗi bằng candidate kế tiếp, giữ nguyên vị trí xem.
     * Trả false khi đã hết candidate.
     */
    fun replaceCandidate(
        mediaId: String?,
        positionMs: Long
    ): Boolean {
        val id = mediaId ?: currentMediaId() ?: return false
        val current = resolvedById[id] ?: return false
        val next = resolver.nextCandidate(current) ?: return false
        val item = resolver.buildMediaItem(next.video, next.plan, next.candidateIndex)
            ?: return false

        val index = player.currentMediaItemIndex
        if (index < 0) return false
        resolvedById[id] = next
        player.replaceMediaItem(index, item)
        player.seekTo(positionMs.coerceAtLeast(0L))
        player.prepare()
        player.play()
        return true
    }

    /**
     * Áp lại chất lượng cho các nguồn progressive MP4 trong cửa sổ playlist.
     *
     * Với DASH/HLS, chất lượng do track selector quyết định trên cùng một
     * MediaItem nên bỏ qua. Với progressive, mỗi file là một chất lượng cố
     * định, buộc phải thay MediaItem nhưng giữ nguyên vị trí đang xem.
     *
     * @return ResolvedMedia mới của bài đang phát nếu item đó bị thay.
     */
    fun applyProgressiveQuality(height: Int?): ResolvedMedia? {
        if (player.mediaItemCount == 0) return null
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex < 0) return null
        val currentPositionMs = player.currentPosition.coerceAtLeast(0L)
        val wasPlaying = player.playWhenReady
        var updatedCurrent: ResolvedMedia? = null

        // Duyệt từ cuối để chỉ số các item phía trước không đổi khi replace.
        for (index in player.mediaItemCount - 1 downTo 0) {
            val mediaId = player.getMediaItemAt(index).mediaId
            val resolved = resolvedById[mediaId] ?: continue
            if (resolved.plan.hasAdaptiveSource) continue

            val target = resolved.copy(
                candidateIndex = resolved.plan.candidateIndexFor(height)
            )
            if (target.candidateIndex == resolved.candidateIndex) continue

            val item = resolver.buildMediaItem(
                target.video,
                target.plan,
                target.candidateIndex
            ) ?: continue
            resolvedById[mediaId] = target
            player.replaceMediaItem(index, item)
            if (index == currentIndex) updatedCurrent = target
        }

        if (updatedCurrent != null) {
            player.seekTo(currentIndex, currentPositionMs)
            player.prepare()
            if (wasPlaying) player.play()
        }
        return updatedCurrent
    }

    fun clear() {
        resolvedById.clear()
    }
}

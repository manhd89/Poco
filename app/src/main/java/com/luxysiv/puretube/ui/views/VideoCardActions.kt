package com.luxysiv.puretube.ui.views

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.PopupMenu
import com.luxysiv.puretube.R
import com.luxysiv.puretube.data.model.VideoItem

/**
 * Các hành động dùng chung cho thẻ video native.
 *
 * Giữ cùng bộ menu với bản Compose cũ (Yêu thích, Xem sau, Phát tiếp theo,
 * Thêm vào hàng đợi, Chia sẻ, Xoá khỏi lịch sử). Các callback nhận chính video
 * của thẻ nên một instance có thể dùng cho cả danh sách.
 */
class VideoCardActions(
    val onClick: (VideoItem) -> Unit,
    val onToggleFavorite: ((VideoItem) -> Unit)? = null,
    val onToggleWatchLater: ((VideoItem) -> Unit)? = null,
    val onShare: ((VideoItem) -> Unit)? = null,
    val onRemove: ((VideoItem) -> Unit)? = null,
    val removeLabel: String? = null,
    val currentVideoId: String? = null,
    val onPlayNext: ((VideoItem) -> Unit)? = null,
    val onAddToQueue: ((VideoItem) -> Unit)? = null
)

/**
 * Bộ lắp hành động chuẩn cho thẻ video.
 *
 * Mọi màn (Trang chủ, Đăng ký, Bạn, Tìm kiếm, Trình phát) dùng chung hàm này
 * thay vì tự bọc lại từng callback. Nhờ đó:
 * - callback nullable được truyền thẳng, không cần `?.let { { v -> it(v) } }`;
 * - `onRemove` nhận `videoId` (đúng nhu cầu xoá lịch sử), tránh mỗi màn tự map;
 * - hành vi thiếu callback (ví dụ chia sẻ) rơi về mặc định trong
 *   [showVideoCardMenu] một cách nhất quán.
 */
fun videoCardActions(
    onClick: (VideoItem) -> Unit,
    onToggleFavorite: ((VideoItem) -> Unit)? = null,
    onToggleWatchLater: ((VideoItem) -> Unit)? = null,
    onPlayNext: ((VideoItem) -> Unit)? = null,
    onAddToQueue: ((VideoItem) -> Unit)? = null,
    onRemove: ((String) -> Unit)? = null,
    onShare: ((VideoItem) -> Unit)? = null,
    removeLabel: String? = null,
    currentVideoId: String? = null
): VideoCardActions = VideoCardActions(
    onClick = onClick,
    onToggleFavorite = onToggleFavorite,
    onToggleWatchLater = onToggleWatchLater,
    onShare = onShare,
    onRemove = onRemove?.let { callback -> { video: VideoItem -> callback(video.id) } },
    removeLabel = removeLabel,
    currentVideoId = currentVideoId,
    onPlayNext = onPlayNext,
    onAddToQueue = onAddToQueue
)

/** Chia sẻ link video qua ứng dụng khác, giống hành động mặc định của Compose. */
fun shareVideo(context: Context, video: VideoItem) {
    val sendIntent = Intent().apply {
        action = Intent.ACTION_SEND
        putExtra(Intent.EXTRA_TEXT, "${video.title}\n${video.shareUrl}")
        type = "text/plain"
    }
    context.startActivity(
        Intent.createChooser(sendIntent, context.getString(R.string.video_share_chooser))
    )
}

/** Menu ngữ cảnh của một thẻ video, dựng lại đúng các mục như bản Compose. */
fun showVideoCardMenu(
    anchor: View,
    context: Context,
    video: VideoItem,
    isFavorite: Boolean,
    isWatchLater: Boolean,
    actions: VideoCardActions
) {
    val popup = PopupMenu(context, anchor)
    val favoriteId = 1
    val watchLaterId = 2
    val playNextId = 3
    val addQueueId = 4
    val shareId = 5
    val removeId = 6

    if (actions.onToggleFavorite != null) {
        popup.menu.add(0, favoriteId, 0, context.getString(
            if (isFavorite) R.string.video_remove_favorite else R.string.video_save_favorite
        )).setIcon(if (isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
    }
    if (actions.onToggleWatchLater != null) {
        popup.menu.add(0, watchLaterId, 0, context.getString(
            if (isWatchLater) R.string.video_remove_watch_later
            else R.string.video_save_watch_later
        )).setIcon(
            if (isWatchLater) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border
        )
    }
    if (actions.onPlayNext != null && video.id != actions.currentVideoId) {
        popup.menu.add(0, playNextId, 0, R.string.video_play_next)
            .setIcon(R.drawable.ic_playlist_play)
    }
    if (actions.onAddToQueue != null && video.id != actions.currentVideoId) {
        popup.menu.add(0, addQueueId, 0, R.string.video_add_queue)
            .setIcon(R.drawable.ic_queue_music)
    }
    popup.menu.add(0, shareId, 0, R.string.video_share).setIcon(R.drawable.ic_share)
    if (actions.onRemove != null) {
        popup.menu.add(0, removeId, 0, actions.removeLabel ?: context.getString(
            R.string.video_remove_history
        )).setIcon(R.drawable.ic_delete)
    }

    popup.setOnMenuItemClickListener { item ->
        when (item.itemId) {
            favoriteId -> actions.onToggleFavorite?.invoke(video)
            watchLaterId -> actions.onToggleWatchLater?.invoke(video)
            playNextId -> actions.onPlayNext?.invoke(video)
            addQueueId -> actions.onAddToQueue?.invoke(video)
            shareId -> actions.onShare?.invoke(video) ?: shareVideo(context, video)
            removeId -> actions.onRemove?.invoke(video)
        }
        true
    }
    popup.show()
}

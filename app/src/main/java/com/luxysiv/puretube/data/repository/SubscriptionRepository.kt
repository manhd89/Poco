package com.luxysiv.puretube.data.repository

import android.content.Context
import com.luxysiv.puretube.data.database.AppDatabase
import com.luxysiv.puretube.data.database.SubscriptionEntity
import com.luxysiv.puretube.data.database.SubscriptionVideoEntity
import com.luxysiv.puretube.data.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Đăng ký cục bộ: lưu kênh + feed video mới trên thiết bị.
 *
 * Cách làm tham khảo PipePipe/NewPipe:
 *  - `subscriptions` là nguồn sự thật của kênh (giống `SubscriptionEntity`);
 *  - feed video được lưu riêng (giống `FeedEntity` + `StreamEntity`) để mở lại
 *    không phải gọi mạng và vẫn xem được ngoại tuyến;
 *  - chỉ tải lại kênh khi cache quá cũ, tránh gọi hàng chục request mỗi lần mở tab.
 */
class SubscriptionRepository(context: Context) {

    private val database = AppDatabase.getDatabase(context)
    private val subscriptionDao = database.subscriptionDao()
    private val videoDao = database.subscriptionVideoDao()

    /** Các kênh đã đăng ký, sắp theo tên. */
    val subscriptions: Flow<List<SubscriptionEntity>> = subscriptionDao.observeAll()

    /** Feed video mới nhất trước, đã gộp từ mọi kênh. */
    val videos: Flow<List<SubscriptionVideoEntity>> = videoDao.observeAll()

    /** Còn ít nhất một kênh có trang cũ hơn để cuộn tới. */
    val hasMore: Flow<Boolean> = subscriptionDao.observeAll().map { channels ->
        channels.any { it.nextPageToken.isNotBlank() }
    }

    /** Tập channelId để nút Đăng ký trên trình phát biết trạng thái hiện tại. */
    val subscribedChannelIds: Flow<Set<String>> =
        subscriptionDao.observeChannelIds().map { it.toSet() }

    data class RefreshResult(val updated: Int, val failed: Int)

    /**
     * Đăng ký hoặc hủy đăng ký kênh của [video].
     *
     * @return `true` nếu sau thao tác kênh đang được đăng ký.
     */
    suspend fun toggleSubscription(video: VideoItem): Boolean = withContext(Dispatchers.IO) {
        val channelId = video.channelId
        if (channelId.isBlank()) throw IllegalArgumentException("Video has no channel id")

        val existing = subscriptionDao.getById(channelId)
        if (existing != null) {
            removeSubscription(channelId)
            false
        } else {
            val now = System.currentTimeMillis()
            val fallbackTitle = video.channelTitle.ifBlank { "YouTube" }
            // Tạo bản ghi tạm để UI phản hồi ngay, rồi cố gắng lấy thông tin kênh
            // chuẩn từ InnerTube (avatar/tên/subscriber) giống cách YouTube hiển thị.
            subscriptionDao.upsert(
                SubscriptionEntity(
                    channelId = channelId,
                    channelTitle = fallbackTitle,
                    avatarUrl = video.channelAvatarUrl,
                    subscriberCount = 0L,
                    addedAt = now,
                    lastFetchedAt = 0L
                )
            )
            runCatching { ChannelPageDataSource.fetch(channelId) }.getOrNull()?.let { page ->
                val title = page.title.ifBlank { fallbackTitle }
                val avatar = page.avatarUrl.ifBlank { video.channelAvatarUrl }
                val subs = InnerTubeJson.parseCount(page.subscriberCount)
                subscriptionDao.upsert(
                    SubscriptionEntity(
                        channelId = channelId,
                        channelTitle = title,
                        avatarUrl = avatar,
                        subscriberCount = subs,
                        addedAt = now,
                        lastFetchedAt = 0L
                    )
                )
            }
            true
        }
    }

    suspend fun unsubscribe(channelId: String) = withContext(Dispatchers.IO) {
        removeSubscription(channelId)
    }

    private suspend fun removeSubscription(channelId: String) {
        subscriptionDao.delete(channelId)
        videoDao.deleteByChannel(channelId)
    }

    /**
     * Tải feed của mọi kênh quá hạn (hoặc tất cả nếu [force]).
     *
     * Chạy theo lô 4 request song song: đủ nhanh khi có nhiều kênh nhưng không
     * dội một lúc hàng chục request lên YouTube.
     */
    suspend fun refresh(force: Boolean = false): RefreshResult = refreshLock.withLock {
        withContext(Dispatchers.IO) {
            val channels = subscriptionDao.getAllOnce()
            if (channels.isEmpty()) {
                videoDao.deleteAll()
                return@withContext RefreshResult(updated = 0, failed = 0)
            }

            val now = System.currentTimeMillis()
            val stale = channels.filter { channel ->
                force || now - channel.lastFetchedAt >= FEED_CACHE_TTL_MS
            }
            if (stale.isEmpty()) return@withContext RefreshResult(updated = 0, failed = 0)

            var updated = 0
            var failed = 0
            for (batch in stale.chunked(PARALLEL_REQUESTS)) {
                val results = coroutineScope {
                    batch.map { channel -> async { fetchAndStore(channel.channelId) } }.awaitAll()
                }
                results.forEach { success -> if (success) updated++ else failed++ }
            }

            // Kênh bị hủy giữa lúc refresh, hoặc video quá cũ, không nên giữ lại.
            videoDao.deleteOrphans()
            videoDao.deleteOlderThan(now - MAX_FEED_AGE_MS)
            RefreshResult(updated = updated, failed = failed)
        }
    }

    /** Tải ngay feed của một kênh vừa đăng ký (không chặn thao tác UI). */
    suspend fun refreshChannel(channelId: String): Boolean = withContext(Dispatchers.IO) {
        fetchAndStore(channelId)
    }

    /**
     * Tải thêm một trang cũ hơn cho mọi kênh còn token.
     *
     * Mỗi kênh tiến một trang; kênh lỗi giữ token để lần cuộn/thử lại sau không
     * bỏ sót video. Kết quả tách [RefreshResult.updated] (kênh tải được) và
     * [RefreshResult.failed] để ViewModel chỉ hiện footer lỗi khi tất cả đều lỗi.
     */
    suspend fun loadMore(): RefreshResult = refreshLock.withLock {
        withContext(Dispatchers.IO) {
            val channels = subscriptionDao.getAllOnce()
                .filter { it.nextPageToken.isNotBlank() }
            if (channels.isEmpty()) {
                return@withContext RefreshResult(updated = 0, failed = 0)
            }

            var updated = 0
            var failed = 0
            for (batch in channels.chunked(PARALLEL_REQUESTS)) {
                val results = coroutineScope {
                    batch.map { channel ->
                        async { fetchMoreAndStore(channel) }
                    }.awaitAll()
                }
                results.forEach { success -> if (success) updated++ else failed++ }
            }

            // Không dọn video cũ ở đây: trang vừa tải có thể đã cũ hơn ngưỡng
            // 90 ngày, xoá ngay sẽ làm cuộn vô cực không thêm được gì. Việc dọn
            // theo tuổi do lần làm mới trang đầu đảm nhiệm.
            RefreshResult(updated = updated, failed = failed)
        }
    }

    private suspend fun fetchAndStore(channelId: String): Boolean {
        val page = SubscriptionFeedDataSource.fetchPage(channelId) ?: return false
        val now = System.currentTimeMillis()
        videoDao.replaceChannel(
            channelId = channelId,
            videos = page.entries.map { entry ->
                SubscriptionVideoEntity(
                    videoId = entry.videoId,
                    channelId = channelId,
                    title = entry.title,
                    thumbnailUrl = entry.thumbnailUrl,
                    publishedAt = entry.publishedAt,
                    viewCount = entry.viewCount,
                    duration = entry.duration,
                    fetchedAt = now
                )
            }
        )
        subscriptionDao.setFeedState(channelId, now, page.nextToken)
        return true
    }

    /** Nối thêm một trang cũ, không xoá các trang đã tải trước đó. */
    private suspend fun fetchMoreAndStore(channel: SubscriptionEntity): Boolean {
        val page = SubscriptionFeedDataSource.fetchPage(
            channelId = channel.channelId,
            continuation = channel.nextPageToken
        ) ?: return false
        val now = System.currentTimeMillis()
        if (page.entries.isNotEmpty()) {
            videoDao.insertAll(
                page.entries.map { entry ->
                    SubscriptionVideoEntity(
                        videoId = entry.videoId,
                        channelId = channel.channelId,
                        title = entry.title,
                        thumbnailUrl = entry.thumbnailUrl,
                        publishedAt = entry.publishedAt,
                        viewCount = entry.viewCount,
                        duration = entry.duration,
                        fetchedAt = now
                    )
                }
            )
        }
        // Trang rỗng mà token không đổi nghĩa là không còn tiến được nữa; xoá
        // token để `hasMore` tắt thay vì cuộn lặp lại cùng một trang.
        val nextToken = page.nextToken
            .takeUnless { it == channel.nextPageToken && page.entries.isEmpty() }
            .orEmpty()
        subscriptionDao.setNextPageToken(channel.channelId, nextToken)
        return true
    }

    /** Danh sách kênh để xuất bản sao lưu (không kèm feed đã tải). */
    suspend fun getSubscriptionsForBackup(): List<SubscriptionEntity> =
        withContext(Dispatchers.IO) { subscriptionDao.getAllOnce() }

    /**
     * Gộp danh sách kênh từ bản sao lưu vào dữ liệu hiện có.
     *
     * Dùng REPLACE theo `channelId` nên kênh trùng chỉ được cập nhật tên/avatar.
     * `lastFetchedAt` được đặt lại 0 vì backup không chứa feed: máy mới sẽ không
     * có video nào, và mốc cũ (có thể mới) sẽ khiến tab Đăng ký đợi hết TTL mới
     * tải. Đặt 0 bảo đảm lần mở tab kế tiếp tải feed ngay.
     */
    suspend fun restoreSubscriptions(entities: List<SubscriptionEntity>) =
        withContext(Dispatchers.IO) {
            if (entities.isNotEmpty()) {
                // Backup không chứa feed, nên token cũ cũng vô nghĩa và có thể
                // trỏ vào trang mà máy này chưa từng tải.
                subscriptionDao.insertAll(
                    entities.map { it.copy(lastFetchedAt = 0L, nextPageToken = "") }
                )
            }
        }

    /**
     * Ghi đè toàn bộ danh sách kênh (dùng khi hoàn tác import thất bại).
     *
     * Feed của kênh không còn trong danh sách bị dọn để không hiển thị video
     * của kênh người dùng vừa bỏ đăng ký.
     */
    suspend fun replaceSubscriptions(entities: List<SubscriptionEntity>) =
        withContext(Dispatchers.IO) {
            subscriptionDao.deleteAll()
            if (entities.isNotEmpty()) subscriptionDao.insertAll(entities)
            videoDao.deleteOrphans()
        }

    private companion object {
        /**
         * Dùng chung cho mọi instance repository.
         *
         * Mỗi trang tạo ViewModel (và repository) riêng, nên nếu lock nằm ở
         * instance thì hai trang có thể cùng tải feed một lúc. Lock cấp process
         * bảo đảm chỉ một lần làm mới chạy tại một thời điểm.
         */
        val refreshLock = Mutex()

        /** 15 phút: đủ mới để thấy video vừa đăng, đủ cũ để không gọi mạng liên tục. */
        const val FEED_CACHE_TTL_MS = 15 * 60 * 1000L

        /** Feed RSS chỉ có ~15 video/kênh; giữ 90 ngày để bộ đệm không phình mãi. */
        const val MAX_FEED_AGE_MS = 90L * 24 * 60 * 60 * 1000

        const val PARALLEL_REQUESTS = 4
    }
}

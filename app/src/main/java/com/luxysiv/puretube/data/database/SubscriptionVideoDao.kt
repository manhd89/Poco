package com.luxysiv.puretube.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Bộ đệm feed video mới của các kênh đã đăng ký. */
@Dao
abstract class SubscriptionVideoDao {

    @Query("SELECT * FROM subscription_videos ORDER BY publishedAt DESC, videoId DESC")
    abstract fun observeAll(): Flow<List<SubscriptionVideoEntity>>

    @Query("SELECT * FROM subscription_videos")
    abstract suspend fun getAllOnce(): List<SubscriptionVideoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(videos: List<SubscriptionVideoEntity>)

    @Query("DELETE FROM subscription_videos WHERE channelId = :channelId")
    abstract suspend fun deleteByChannel(channelId: String)

    /**
     * Thay toàn bộ video của một kênh trong một transaction.
     *
     * Nếu feed mới rỗng (kênh chưa có video), dữ liệu cũ cũng bị xoá để không
     * hiển thị video đã biến mất khỏi feed.
     */
    @Transaction
    open suspend fun replaceChannel(channelId: String, videos: List<SubscriptionVideoEntity>) {
        deleteByChannel(channelId)
        if (videos.isNotEmpty()) insertAll(videos)
    }

    @Query("DELETE FROM subscription_videos WHERE publishedAt < :cutoff")
    abstract suspend fun deleteOlderThan(cutoff: Long)

    /** Dọn video của kênh vừa bị hủy đăng ký (phòng khi xoá kênh thất bại giữa chừng). */
    @Query(
        "DELETE FROM subscription_videos WHERE channelId NOT IN (SELECT channelId FROM subscriptions)"
    )
    abstract suspend fun deleteOrphans()

    @Query("DELETE FROM subscription_videos")
    abstract suspend fun deleteAll()
}

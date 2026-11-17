package com.luxysiv.puretube.data.local

import android.content.Context
import com.luxysiv.puretube.data.model.VideoItem
import com.luxysiv.puretube.error.getOrElse
import com.luxysiv.puretube.error.runAppCatchingBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lưu hàng đợi qua các phiên.
 *
 * Hàng đợi trước đây chỉ nằm trong RAM nên tắt app là mất. Giờ danh sách được
 * serialize thành JSON trong SharedPreferences; dữ liệu chỉ là metadata công
 * khai của video (không có lịch sử xem hay tài khoản).
 */
class QueueStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    fun load(): List<VideoItem> = synchronized(lock) {
        val raw = prefs.getString(KEY_QUEUE, null) ?: return@synchronized emptyList()
        runAppCatchingBlocking {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    item.toVideoItem()?.let(::add)
                }
            }
        }.getOrElse(emptyList())
    }

    fun save(queue: List<VideoItem>) {
        synchronized(lock) {
            val array = JSONArray()
            queue.forEach { array.put(it.toJson()) }
            prefs.edit().putString(KEY_QUEUE, array.toString()).apply()
        }
    }

    /**
     * Lưu video đang phát + vị trí để khôi phục sau khi process bị kill.
     * Không lưu URL stream (URL YouTube hết hạn); chỉ lưu metadata công khai.
     */
    fun saveNowPlaying(video: VideoItem?, positionMs: Long) {
        synchronized(lock) {
            if (video == null) {
                clearNowPlayingLocked()
                return@synchronized
            }
            prefs.edit()
                .putString(KEY_NOW_PLAYING, video.toJson().toString())
                .putLong(KEY_NOW_PLAYING_POSITION, positionMs.coerceAtLeast(0L))
                .apply()
        }
    }

    fun loadNowPlaying(): NowPlayingState? = synchronized(lock) {
        val raw = prefs.getString(KEY_NOW_PLAYING, null) ?: return@synchronized null
        val video = runAppCatchingBlocking { JSONObject(raw).toVideoItem() }
            .getOrNull() ?: return@synchronized null
        NowPlayingState(
            video = video,
            positionMs = prefs.getLong(KEY_NOW_PLAYING_POSITION, 0L)
        )
    }

    fun clearNowPlaying() {
        synchronized(lock) { clearNowPlayingLocked() }
    }

    private fun clearNowPlayingLocked() {
        prefs.edit()
            .remove(KEY_NOW_PLAYING)
            .remove(KEY_NOW_PLAYING_POSITION)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "puretube_queue"
        const val KEY_QUEUE = "pending_queue"
        const val KEY_NOW_PLAYING = "now_playing"
        const val KEY_NOW_PLAYING_POSITION = "now_playing_position"
    }
}

/** Video + vị trí đã lưu để khôi phục phiên sau khi process bị kill. */
data class NowPlayingState(
    val video: VideoItem,
    val positionMs: Long
)

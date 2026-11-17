package com.luxysiv.puretube.error

import android.database.SQLException
import org.json.JSONException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Lỗi dùng chung cho toàn bộ app.
 *
 * Trước đây mỗi tầng tự nuốt exception và trả `null`/`emptyList`, khiến UI không
 * phân biệt được "không có dữ liệu" với "mất mạng" hay "dữ liệu hỏng". Mọi lỗi
 * đi qua data layer giờ được quy về một trong các nhóm dưới đây; UI chỉ cần ánh
 * xạ nhóm lỗi sang tiêu đề/nội dung (xem `AppErrorText`).
 */
sealed interface AppError {

    /** Exception gốc (nếu có) để ghi log/chi tiết kỹ thuật. */
    val cause: Throwable?

    /** Thiết bị không có kết nối mạng tại thời điểm gọi. */
    data class Offline(override val cause: Throwable? = null) : AppError

    /** Có mạng nhưng không kết nối được tới máy chủ. */
    data class Network(override val cause: Throwable? = null) : AppError

    /** Máy chủ phản hồi quá chậm hoặc quá thời gian chờ. */
    data class Timeout(override val cause: Throwable? = null) : AppError

    /** Máy chủ trả về thành công nhưng không có nội dung dùng được. */
    data class Unavailable(override val cause: Throwable? = null) : AppError

    /** Phản hồi/JSON không đọc được hoặc sai định dạng. */
    data class InvalidData(override val cause: Throwable? = null) : AppError

    /** Không đọc/ghi được dữ liệu cục bộ (Room, SharedPreferences, tệp). */
    data class Storage(override val cause: Throwable? = null) : AppError

    /** Tệp sao lưu không phải của PureTube hoặc sai cấu trúc. */
    data class InvalidBackup(override val cause: Throwable? = null) : AppError

    /** Trình phát/ExoPlayer báo lỗi khi phát video. */
    data class Playback(override val cause: Throwable? = null) : AppError

    /** Lỗi chưa phân loại được. */
    data class Unknown(override val cause: Throwable? = null) : AppError

    companion object {
        /**
         * Quy một exception bất kỳ về nhóm lỗi chung.
         *
         * `CancellationException` không bao giờ được truyền vào đây: các helper
         * `runAppCatching` luôn ném lại để coroutine cha huỷ đúng cách.
         */
        fun from(throwable: Throwable): AppError = when (throwable) {
            is SocketTimeoutException,
            is InterruptedIOException -> Timeout(throwable)

            is FileNotFoundException,
            is SQLException,
            is SecurityException -> Storage(throwable)

            is UnknownHostException,
            is ConnectException,
            is NoRouteToHostException,
            is SocketException,
            is IOException -> Network(throwable)

            is JSONException -> InvalidData(throwable)

            else -> Unknown(throwable)
        }
    }
}

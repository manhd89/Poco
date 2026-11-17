package com.luxysiv.puretube.error

import kotlinx.coroutines.CancellationException

/**
 * Kết quả thành công/thất bại dùng chung giữa data layer và ViewModel.
 *
 * Khác với `Result` của Kotlin, [Failure] mang [AppError] đã phân loại nên UI có
 * thể hiển thị đúng nguyên nhân mà không phải đoán từ `null`.
 */
sealed interface AppResult<out T> {

    data class Success<out T>(val value: T) : AppResult<T>

    data class Failure(val error: AppError) : AppResult<Nothing>

    val isSuccess: Boolean get() = this is Success

    val isFailure: Boolean get() = this is Failure

    fun getOrNull(): T? = (this as? Success)?.value

    fun errorOrNull(): AppError? = (this as? Failure)?.error

    fun <R> map(transform: (T) -> R): AppResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    fun <R> flatMap(transform: (T) -> AppResult<R>): AppResult<R> = when (this) {
        is Success -> transform(value)
        is Failure -> this
    }

    fun <R> fold(
        onSuccess: (T) -> R,
        onFailure: (AppError) -> R
    ): R = when (this) {
        is Success -> onSuccess(value)
        is Failure -> onFailure(error)
    }
}

/**
 * Giá trị khi thành công, ngược lại [default].
 *
 * Đây là extension thay vì hàm thành viên vì [AppResult.Failure] triển khai
 * `AppResult<Nothing>`: hàm thành viên nhận `T` sẽ bị trình biên dịch sinh cầu
 * nối ép kiểu về `Void`, làm hỏng giá trị mặc định lúc chạy.
 */
fun <T> AppResult<T>.getOrElse(default: T): T =
    when (this) {
        is AppResult.Success -> value
        is AppResult.Failure -> default
    }

/**
 * `runCatching` nhưng tôn trọng huỷ coroutine.
 *
 * `kotlin.runCatching` bắt luôn `CancellationException`, khiến coroutine đã bị
 * huỷ vẫn tiếp tục chạy phần xử lý lỗi. Helper này ném lại cancellation và chỉ
 * quy các exception thật về [AppError].
 */
suspend fun <T> runAppCatching(block: suspend () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (throwable: Throwable) {
    AppResult.Failure(AppError.from(throwable))
}

/** Phiên bản không suspend cho các store đồng bộ (JSON/SharedPreferences...). */
fun <T> runAppCatchingBlocking(block: () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (throwable: Throwable) {
    AppResult.Failure(AppError.from(throwable))
}

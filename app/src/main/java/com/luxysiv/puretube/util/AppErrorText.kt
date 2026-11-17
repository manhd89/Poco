package com.luxysiv.puretube.util

import android.content.Context
import androidx.annotation.StringRes
import com.luxysiv.puretube.R
import com.luxysiv.puretube.error.AppError

/** Tiêu đề ngắn của lỗi, dùng cho overlay/empty state. */
@get:StringRes
val AppError.titleRes: Int
    get() = when (this) {
        is AppError.Offline -> R.string.error_offline_title
        is AppError.Network -> R.string.error_network_title
        is AppError.Timeout -> R.string.error_timeout_title
        is AppError.Unavailable -> R.string.error_unavailable_title
        is AppError.InvalidData -> R.string.error_invalid_data_title
        is AppError.Storage -> R.string.error_storage_title
        is AppError.InvalidBackup -> R.string.error_invalid_backup_title
        is AppError.Playback -> R.string.error_playback_title
        is AppError.Unknown -> R.string.error_unknown_title
    }

/** Nội dung hướng dẫn người dùng xử lý lỗi. */
@get:StringRes
val AppError.messageRes: Int
    get() = when (this) {
        is AppError.Offline -> R.string.error_offline_message
        is AppError.Network -> R.string.error_network_message
        is AppError.Timeout -> R.string.error_timeout_message
        is AppError.Unavailable -> R.string.error_unavailable_message
        is AppError.InvalidData -> R.string.error_invalid_data_message
        is AppError.Storage -> R.string.error_storage_message
        is AppError.InvalidBackup -> R.string.error_invalid_backup_message
        is AppError.Playback -> R.string.error_playback_message
        is AppError.Unknown -> R.string.error_unknown_message
    }

fun AppError.userTitle(context: Context): String = context.getString(titleRes)

fun AppError.userMessage(context: Context): String = context.getString(messageRes)

/**
 * Nội dung hiển thị đầy đủ cho một bề mặt lỗi (overlay, empty state, snackbar).
 *
 * Gom tiêu đề, hướng dẫn và nhãn nút thử lại vào một chỗ để mọi màn hình dùng
 * cùng câu chữ và cùng hành vi, tránh mỗi view tự ghép chuỗi khác nhau.
 */
data class ErrorPresentation(
    val title: String,
    val message: String,
    val retryLabel: String
)

fun AppError.presentation(context: Context): ErrorPresentation = ErrorPresentation(
    title = userTitle(context),
    message = userMessage(context),
    retryLabel = context.getString(R.string.action_retry)
)

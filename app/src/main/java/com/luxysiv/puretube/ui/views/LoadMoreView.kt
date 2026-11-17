package com.luxysiv.puretube.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.luxysiv.puretube.R
import com.luxysiv.puretube.error.AppError
import com.luxysiv.puretube.util.presentation

/**
 * Footer dùng chung cho danh sách cuộn vô hạn.
 *
 * [bind] với `error = null` hiện spinner "Đang tải thêm…"; truyền lỗi để hiện
 * thông báo ngắn kèm nút thử lại. Footer tự gọi lại [onRetry] khi người dùng bấm.
 */
class LoadMoreView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val progress: ProgressBar
    private val message: TextView
    private val retry: TextView

    private var onRetry: (() -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.view_load_more, this, true)
        progress = findViewById(R.id.load_more_progress)
        message = findViewById(R.id.load_more_message)
        retry = findViewById(R.id.load_more_retry)
        retry.setOnClickListener { onRetry?.invoke() }
    }

    fun bind(error: AppError?, onRetry: () -> Unit) {
        this.onRetry = onRetry
        if (error == null) {
            progress.visibility = View.VISIBLE
            message.text = context.getString(R.string.load_more_loading)
            retry.visibility = View.GONE
        } else {
            val presentation = error.presentation(context)
            progress.visibility = View.GONE
            message.text = presentation.title
            retry.text = presentation.retryLabel
            retry.visibility = View.VISIBLE
        }
    }
}

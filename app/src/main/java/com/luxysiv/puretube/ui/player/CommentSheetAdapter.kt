package com.luxysiv.puretube.ui.player

import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.luxysiv.puretube.R
import com.luxysiv.puretube.data.model.CommentItem
import com.luxysiv.puretube.ui.views.ChannelAvatarView
import com.luxysiv.puretube.ui.views.dp

/** Hành động trong sheet bình luận; adapter chỉ phát id, view giữ logic tải. */
internal enum class CommentAction {
    NONE,
    LOAD_MORE,
    RETRY_COMMENTS,
    LOAD_REPLIES,
    RETRY_REPLIES,
    LOAD_MORE_REPLIES
}

/**
 * Một dòng phẳng trong sheet bình luận.
 *
 * Dùng dữ liệu thuần (không lambda) để DiffUtil so sánh nội dung ổn định và
 * không dựng lại cả danh sách khi chỉ một phản hồi đổi trạng thái.
 */
internal data class CommentSheetItem(
    val key: String,
    val kind: Kind,
    val comment: CommentItem? = null,
    val indent: Boolean = false,
    val label: String = "",
    val action: CommentAction = CommentAction.NONE,
    val actionCommentId: String = ""
) {
    enum class Kind {
        COMMENT,
        ACTION,
        STATUS
    }
}

internal class CommentSheetAdapter(
    private val onAction: (CommentAction, String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<CommentSheetItem> = emptyList()

    fun submit(newItems: List<CommentSheetItem>) {
        val old = items
        val result = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size

            override fun getNewListSize(): Int = newItems.size

            override fun areItemsTheSame(oldPosition: Int, newPosition: Int): Boolean =
                old[oldPosition].key == newItems[newPosition].key

            override fun areContentsTheSame(oldPosition: Int, newPosition: Int): Boolean =
                old[oldPosition] == newItems[newPosition]
        })
        items = newItems
        result.dispatchUpdatesTo(this)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position].kind) {
        CommentSheetItem.Kind.COMMENT -> TYPE_COMMENT
        CommentSheetItem.Kind.ACTION -> TYPE_ACTION
        CommentSheetItem.Kind.STATUS -> TYPE_STATUS
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val context = parent.context
        return when (viewType) {
            TYPE_COMMENT -> CommentHolder(
                LayoutInflater.from(context)
                    .inflate(R.layout.view_player_comment_row, parent, false)
            )

            else -> TextHolder(TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                val value = TypedValue()
                if (context.theme.resolveAttribute(
                        android.R.attr.selectableItemBackground,
                        value,
                        true
                    )
                ) {
                    setBackgroundResource(value.resourceId)
                }
            })
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is CommentHolder -> holder.bind(item)
            is TextHolder -> holder.bind(item)
        }
    }

    private inner class CommentHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(item: CommentSheetItem) {
            val comment = item.comment ?: return
            bindCommentRow(itemView, comment, compact = false, reply = item.indent)
        }
    }

    private inner class TextHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val text: TextView = view as TextView

        fun bind(item: CommentSheetItem) {
            text.text = item.label
            val isAction = item.kind == CommentSheetItem.Kind.ACTION &&
                item.action != CommentAction.NONE
            text.isClickable = isAction
            text.isFocusable = isAction
            text.setTextColor(
                ContextCompat.getColor(
                    text.context,
                    if (isAction) R.color.pure_tertiary else R.color.pure_on_surface_variant
                )
            )
            text.setTypeface(
                text.typeface,
                if (isAction) Typeface.BOLD else Typeface.NORMAL
            )
            // Dòng "Xem câu trả lời" nằm dưới bình luận mẹ nên thụt vào cùng
            // mốc chữ của bình luận, không chạy ra sát mép sheet.
            val start = if (item.indent) text.context.dp(68f) else text.context.dp(16f)
            text.setPadding(start, text.context.dp(8f), text.context.dp(16f), text.context.dp(8f))
            text.setOnClickListener {
                if (isAction) onAction(item.action, item.actionCommentId)
            }
        }
    }

    private companion object {
        const val TYPE_COMMENT = 0
        const val TYPE_ACTION = 1
        const val TYPE_STATUS = 2
    }
}

/**
 * Bind dữ liệu bình luận vào row dùng chung cho preview và sheet.
 *
 * [compact] dùng cho preview trong card (avatar nhỏ hơn), [reply] dùng cho
 * phản hồi trong sheet (avatar 24dp và thụt lề theo bình luận mẹ).
 */
internal fun bindCommentRow(
    row: View,
    comment: CommentItem,
    compact: Boolean = false,
    reply: Boolean = false
) {
    val context = row.context
    val avatar = row.findViewById<ChannelAvatarView>(R.id.comment_avatar)
    val author = row.findViewById<TextView>(R.id.comment_author)
    val verified = row.findViewById<ImageView>(R.id.comment_verified)
    val time = row.findViewById<TextView>(R.id.comment_time)
    val text = row.findViewById<TextView>(R.id.comment_text)
    val likes = row.findViewById<TextView>(R.id.comment_likes)
    val ownerBadge = row.findViewById<TextView>(R.id.comment_owner_badge)
    val hearted = row.findViewById<ImageView>(R.id.comment_hearted)

    avatar.bind(comment.author, comment.avatarUrl)
    author.text = buildString {
        append(comment.author)
        if (comment.isPinned) {
            append(" · ")
            append(context.getString(R.string.player_comment_pinned))
        }
    }
    author.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 12f else 13f)
    verified.visibility = if (comment.isVerified) View.VISIBLE else View.GONE
    time.text = comment.publishedTime
    time.visibility = if (comment.publishedTime.isBlank()) View.GONE else View.VISIBLE
    time.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 11f else 12f)
    text.text = comment.text
    text.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 13f else 14f)
    ownerBadge.visibility = if (comment.isChannelOwner) View.VISIBLE else View.GONE
    hearted.visibility = if (comment.isHeartedByUploader) View.VISIBLE else View.GONE
    val likeLabel = comment.likeText.ifBlank {
        if (comment.likeCount > 0) comment.likeCount.toString() else ""
    }
    likes.text = likeLabel
    likes.visibility = if (likeLabel.isBlank()) View.GONE else View.VISIBLE
    likes.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 11f else 12f)

    val avatarSize = when {
        reply -> 24
        compact -> 32
        else -> 40
    }
    val sizePx = context.dp(avatarSize.toFloat())
    // Row được tái dùng, gán lại LayoutParams dù kích thước không đổi sẽ gây
    // requestLayout cho mỗi lần bind (đặc biệt khi cuộn danh sách bình luận).
    val avatarParams = avatar.layoutParams
    if (avatarParams.width != sizePx || avatarParams.height != sizePx) {
        avatarParams.width = sizePx
        avatarParams.height = sizePx
        avatar.layoutParams = avatarParams
    }

    val start = when {
        compact -> 0
        reply -> 68
        else -> 16
    }
    row.setPadding(
        context.dp(start.toFloat()),
        row.paddingTop,
        context.dp(if (compact) 0f else 16f),
        row.paddingBottom
    )
}

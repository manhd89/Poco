package com.luxysiv.puretube.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.luxysiv.puretube.R
import com.luxysiv.puretube.data.database.PlaylistHistoryEntity
import com.luxysiv.puretube.data.database.SavedPlaylistEntity
import com.luxysiv.puretube.data.database.VideoEntity
import com.luxysiv.puretube.data.model.PlaylistItem
import com.luxysiv.puretube.data.model.VideoItem
import com.luxysiv.puretube.ui.components.dayLabel
import com.luxysiv.puretube.ui.components.watchProgressFraction

/**
 * Nội dung trang "Bạn" native.
 *
 * Ba bộ sưu tập Lịch sử / Video đã thích / Xem sau dùng chung một RecyclerView:
 * hồ sơ, dải chip, tiêu đề ngày, thẻ video/playlist hàng ngang và trạng thái rỗng.
 * Tab Lịch sử trộn video và playlist theo đúng thời điểm xem gần nhất.
 */
class LibraryNativeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val clearHistoryButton: ImageButton
    private val list: RecyclerView
    private val adapter = LibraryAdapter()

    private var favorites: List<VideoEntity> = emptyList()
    private var history: List<VideoEntity> = emptyList()
    private var playlists: List<PlaylistHistoryEntity> = emptyList()
    private var savedPlaylists: List<SavedPlaylistEntity> = emptyList()
    private var savedPlaylistIds: Set<String> = emptySet()
    private var watchLater: List<VideoEntity> = emptyList()
    private var selectedTabIndex = 0
    private var columns = 1

    private var actions: VideoCardActions? = null

    var onVideoClick: ((VideoItem) -> Unit)? = null
    var onPlaylistClick: ((PlaylistHistoryEntity) -> Unit)? = null
    var onSavedPlaylistClick: ((PlaylistItem) -> Unit)? = null
    var onToggleFavorite: ((VideoItem) -> Unit)? = null
    var onToggleWatchLater: ((VideoItem) -> Unit)? = null
    var onToggleSavePlaylist: ((PlaylistItem) -> Unit)? = null
    var onClearHistory: (() -> Unit)? = null
    var onRemoveHistory: ((String) -> Unit)? = null
    var onOpenSettings: (() -> Unit)? = null
    var onSearchClick: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_library, this, true)

        clearHistoryButton = findViewById(R.id.library_clear_history)
        list = findViewById(R.id.library_list)

        list.adapter = adapter
        updateLayoutMetrics()

        clearHistoryButton.setOnClickListener { onClearHistory?.invoke() }
        findViewById<ImageButton>(R.id.library_search).setOnClickListener {
            onSearchClick?.invoke()
        }
        findViewById<ImageButton>(R.id.library_settings).setOnClickListener {
            onOpenSettings?.invoke()
        }
    }

    fun updateLayoutMetrics() {
        val widthDp = resources.configuration.screenWidthDp
        val compact = widthDp < 600
        val newColumns = if (widthDp >= 720) 2 else 1
        val padding = context.dp(if (compact) 16f else 24f)

        if (newColumns != columns || list.layoutManager == null) {
            columns = newColumns
            val manager = GridLayoutManager(context, columns)
            manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (adapter.isFullSpan(position)) columns else 1
            }
            list.layoutManager = manager
        }
        if (list.itemDecorationCount == 0) {
            list.addItemDecoration(SpacingItemDecoration(context.dp(12f)))
        }
        val edge = padding - context.dp(6f)
        list.setPadding(edge, context.dp(8f), edge, context.dp(32f))
    }

    // ------------------------------------------------------------------
    // Trạng thái từ ViewModel
    // ------------------------------------------------------------------

    fun setContent(
        favorites: List<VideoEntity>,
        history: List<VideoEntity>,
        playlists: List<PlaylistHistoryEntity>,
        savedPlaylists: List<SavedPlaylistEntity>,
        savedPlaylistIds: Set<String>,
        watchLater: List<VideoEntity>
    ) {
        this.favorites = favorites
        this.history = history
        this.playlists = playlists
        this.savedPlaylists = savedPlaylists
        this.savedPlaylistIds = savedPlaylistIds
        this.watchLater = watchLater
        render()
    }

    fun selectTab(index: Int) {
        selectedTabIndex = index.coerceIn(0, 2)
        render()
    }

    fun selectedTab(): Int = selectedTabIndex

    private fun render() {
        actions = videoCardActions(
            onClick = { onVideoClick?.invoke(it) },
            onToggleFavorite = onToggleFavorite,
            onToggleWatchLater = onToggleWatchLater,
            onRemove = onRemoveHistory,
            removeLabel = context.getString(R.string.video_remove_history)
        )

        val hasHistory = history.isNotEmpty() || playlists.isNotEmpty()
        clearHistoryButton.visibility =
            if (selectedTabIndex == 0 && hasHistory) View.VISIBLE else View.GONE

        adapter.submit(buildRows())
    }

    private fun buildRows(): List<LibraryRow> {
        val rows = ArrayList<LibraryRow>()
        rows += LibraryRow.Profile
        rows += LibraryRow.Chips(selectedTabIndex)

        if (selectedTabIndex == 0) {
            buildHistoryRows(rows)
            return rows
        }

        if (selectedTabIndex == 1) {
            buildFavoriteRows(rows)
        } else {
            buildWatchLaterRows(rows)
        }
        return rows
    }

    private fun buildFavoriteRows(rows: MutableList<LibraryRow>) {
        if (favorites.isEmpty()) {
            rows += LibraryRow.Empty(1)
            return
        }
        favorites.groupBy { dayLabel(it.favoriteAddedAt) }.forEach { (label, entities) ->
            rows += LibraryRow.DayHeader(label)
            entities.forEach { rows += LibraryRow.Video(it, removable = false) }
        }
    }

    /**
     * Xem sau tách rõ playlist và video: playlist đã lưu là một bộ sưu tập,
     * video lẻ là bộ sưu tập khác. Khi chỉ có một loại, giữ danh sách gọn như
     * trước; khi có cả hai mới thêm tiêu đề phân mục.
     */
    private fun buildWatchLaterRows(rows: MutableList<LibraryRow>) {
        if (savedPlaylists.isEmpty() && watchLater.isEmpty()) {
            rows += LibraryRow.Empty(2)
            return
        }

        val showSections = savedPlaylists.isNotEmpty() && watchLater.isNotEmpty()
        if (savedPlaylists.isNotEmpty()) {
            if (showSections) {
                rows += LibraryRow.DayHeader(context.getString(R.string.library_saved_playlists))
            }
            savedPlaylists.forEach { rows += LibraryRow.SavedPlaylist(it) }
        }
        if (watchLater.isNotEmpty()) {
            if (showSections) {
                rows += LibraryRow.DayHeader(context.getString(R.string.library_saved_videos))
            }
            watchLater.groupBy { dayLabel(it.watchLaterAddedAt) }.forEach { (label, entities) ->
                rows += LibraryRow.DayHeader(label)
                entities.forEach { rows += LibraryRow.Video(it, removable = false) }
            }
        }
    }

    /**
     * Lịch sử gộp video và playlist trên cùng một dòng thời gian.
     *
     * Người dùng mở tab Lịch sử là thấy ngay "vừa xem gì" — dù đó là một video
     * lẻ hay một playlist — thay vì phải đoán playlist nằm ở mục nào khác.
     */
    private fun buildHistoryRows(rows: MutableList<LibraryRow>) {
        if (history.isEmpty() && playlists.isEmpty()) {
            rows += LibraryRow.Empty(0)
            return
        }

        val entries = ArrayList<Pair<Long, LibraryRow>>(history.size + playlists.size)
        history.forEach { entity ->
            entries += entity.lastWatchedTimestamp to
                LibraryRow.Video(entity, removable = true)
        }
        playlists.forEach { entity ->
            entries += entity.updatedAt to LibraryRow.Playlist(
                entity = entity,
                isSaved = savedPlaylistIds.contains(entity.playlistId)
            )
        }
        entries.sortByDescending { it.first }

        var lastLabel: String? = null
        entries.forEach { (timestamp, row) ->
            val label = dayLabel(timestamp)
            if (label != lastLabel) {
                rows += LibraryRow.DayHeader(label)
                lastLabel = label
            }
            rows += row
        }
        rows += LibraryRow.ClearAction
    }

    // ------------------------------------------------------------------
    // Adapter
    // ------------------------------------------------------------------

    private sealed interface LibraryRow {
        data object Profile : LibraryRow
        data class Chips(val selectedIndex: Int) : LibraryRow
        data class DayHeader(val label: String) : LibraryRow
        data class Video(val entity: VideoEntity, val removable: Boolean) : LibraryRow
        data class Playlist(val entity: PlaylistHistoryEntity, val isSaved: Boolean) : LibraryRow
        data class SavedPlaylist(val entity: SavedPlaylistEntity) : LibraryRow
        data class Empty(val tabIndex: Int) : LibraryRow
        data object ClearAction : LibraryRow
    }

    private inner class LibraryAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var rows: List<LibraryRow> = emptyList()

        fun submit(newRows: List<LibraryRow>) {
            val oldRows = rows
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldRows.size
                override fun getNewListSize(): Int = newRows.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val old = oldRows[oldItemPosition]
                    val new = newRows[newItemPosition]
                    return when {
                        old is LibraryRow.Video && new is LibraryRow.Video ->
                            old.entity.videoId == new.entity.videoId && old.removable == new.removable
                        old is LibraryRow.Playlist && new is LibraryRow.Playlist ->
                            old.entity.playlistId == new.entity.playlistId
                        old is LibraryRow.SavedPlaylist && new is LibraryRow.SavedPlaylist ->
                            old.entity.playlistId == new.entity.playlistId
                        old is LibraryRow.DayHeader && new is LibraryRow.DayHeader -> old.label == new.label
                        old is LibraryRow.Empty && new is LibraryRow.Empty -> old.tabIndex == new.tabIndex
                        old is LibraryRow.Chips && new is LibraryRow.Chips -> true
                        old is LibraryRow.Profile && new is LibraryRow.Profile -> true
                        old is LibraryRow.ClearAction && new is LibraryRow.ClearAction -> true
                        else -> false
                    }
                }

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    oldRows[oldItemPosition] == newRows[newItemPosition]
            })
            rows = newRows
            diff.dispatchUpdatesTo(this)
        }

        fun isFullSpan(position: Int): Boolean = when (rows.getOrNull(position)) {
            is LibraryRow.Video,
            is LibraryRow.Playlist,
            is LibraryRow.SavedPlaylist -> false
            else -> true
        }

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            LibraryRow.Profile -> TYPE_PROFILE
            is LibraryRow.Chips -> TYPE_CHIPS
            is LibraryRow.DayHeader -> TYPE_DAY
            is LibraryRow.Video -> TYPE_VIDEO
            is LibraryRow.Playlist -> TYPE_PLAYLIST
            is LibraryRow.SavedPlaylist -> TYPE_SAVED_PLAYLIST
            is LibraryRow.Empty -> TYPE_EMPTY
            LibraryRow.ClearAction -> TYPE_CLEAR
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_PROFILE -> SimpleHolder(inflater.inflate(R.layout.view_library_profile, parent, false))
                TYPE_CHIPS -> ChipsHolder(inflater.inflate(R.layout.view_library_chip_row, parent, false))
                TYPE_DAY -> DayHolder(inflater.inflate(R.layout.view_library_day_header, parent, false))
                TYPE_VIDEO -> {
                    val card = CompactVideoCardView(parent.context)
                    card.layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    VideoHolder(card)
                }

                TYPE_PLAYLIST, TYPE_SAVED_PLAYLIST -> {
                    val card = PlaylistCardView(parent.context)
                    card.layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    PlaylistHolder(card)
                }

                TYPE_EMPTY -> EmptyHolder(inflater.inflate(R.layout.view_library_empty, parent, false))
                else -> ClearHolder(inflater.inflate(R.layout.view_library_clear_action, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                LibraryRow.Profile -> Unit
                is LibraryRow.Chips -> (holder as ChipsHolder).bind(row.selectedIndex)
                is LibraryRow.DayHeader -> (holder as DayHolder).label.text = row.label
                is LibraryRow.Video -> {
                    val currentActions = actions ?: return
                    val video = row.entity.toVideoItem()
                    val card = (holder as VideoHolder).card
                    card.setPadding(0, context.dp(4f), 0, context.dp(4f))
                    card.bind(
                        video = video,
                        isFavorite = row.entity.isFavorite,
                        isWatchLater = row.entity.isWatchLater,
                        progressFraction = watchProgressFraction(row.entity),
                        actions = currentActions
                    )
                }

                is LibraryRow.Empty -> (holder as EmptyHolder).bind(row.tabIndex)
                is LibraryRow.Playlist -> {
                    val card = (holder as PlaylistHolder).card
                    card.setPadding(0, context.dp(4f), 0, context.dp(4f))
                    val item = row.entity.toPlaylistItem()
                    card.bind(
                        playlist = item,
                        onClick = { onPlaylistClick?.invoke(row.entity) },
                        isSaved = row.isSaved,
                        onToggleSave = onToggleSavePlaylist
                    )
                }

                is LibraryRow.SavedPlaylist -> {
                    val card = (holder as PlaylistHolder).card
                    card.setPadding(0, context.dp(4f), 0, context.dp(4f))
                    val item = row.entity.toPlaylistItem()
                    card.bind(
                        playlist = item,
                        onClick = { onSavedPlaylistClick?.invoke(item) },
                        isSaved = true,
                        onToggleSave = onToggleSavePlaylist
                    )
                }

                LibraryRow.ClearAction -> {
                    (holder as ClearHolder).action.setOnClickListener { onClearHistory?.invoke() }
                }
            }
        }
    }

    private class SimpleHolder(view: View) : RecyclerView.ViewHolder(view)

    private class DayHolder(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.library_day_label)
    }

    private inner class ChipsHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val recycler: RecyclerView = view.findViewById(R.id.library_chip_list)
        private val chipAdapter = LibraryChipAdapter()

        init {
            recycler.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
            recycler.adapter = chipAdapter
        }

        fun bind(selectedIndex: Int) {
            chipAdapter.submit(selectedIndex)
        }
    }

    private inner class LibraryChipAdapter : RecyclerView.Adapter<ChipHolder>() {
        private var selectedIndex = 0
        private val labels = listOf(
            R.string.library_tab_history,
            R.string.library_tab_favorites,
            R.string.library_tab_watch_later
        )

        fun submit(index: Int) {
            val normalized = index.coerceIn(0, labels.lastIndex)
            if (selectedIndex == normalized) return
            val oldIndex = selectedIndex
            selectedIndex = normalized
            notifyItemChanged(oldIndex)
            notifyItemChanged(selectedIndex)
        }

        override fun getItemCount(): Int = labels.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChipHolder {
            val chip = LayoutInflater.from(parent.context)
                .inflate(R.layout.view_category_chip, parent, false) as TextView
            return ChipHolder(chip)
        }

        override fun onBindViewHolder(holder: ChipHolder, position: Int) {
            val chip = holder.chip
            val selected = position == selectedIndex
            chip.text = context.getString(labels[position])
            chip.setBackgroundResource(
                if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip
            )
            chip.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (selected) R.color.pure_surface else R.color.pure_on_surface
                )
            )
            chip.applyBold(selected)
            val params = chip.layoutParams as ViewGroup.MarginLayoutParams
            params.marginEnd = context.dp(8f)
            chip.layoutParams = params
            chip.setOnClickListener { selectTab(position) }
        }
    }

    private class ChipHolder(val chip: TextView) : RecyclerView.ViewHolder(chip)

    private class VideoHolder(val card: CompactVideoCardView) : RecyclerView.ViewHolder(card)

    private class PlaylistHolder(val card: PlaylistCardView) : RecyclerView.ViewHolder(card)

    private inner class EmptyHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.library_empty_icon)
        private val title: TextView = view.findViewById(R.id.library_empty_title)
        private val message: TextView = view.findViewById(R.id.library_empty_message)

        fun bind(tabIndex: Int) {
            val data = when (tabIndex) {
                0 -> Triple(
                    R.drawable.ic_history,
                    R.string.library_empty_history_title,
                    R.string.library_empty_history_message
                )

                1 -> Triple(
                    R.drawable.ic_favorite_border,
                    R.string.library_empty_favorites_title,
                    R.string.library_empty_favorites_message
                )

                else -> Triple(
                    R.drawable.ic_bookmark_border,
                    R.string.library_empty_watch_later_title,
                    R.string.library_empty_watch_later_message
                )
            }
            icon.setImageResource(data.first)
            title.setText(data.second)
            message.setText(data.third)
        }
    }

    private class ClearHolder(view: View) : RecyclerView.ViewHolder(view) {
        val action: TextView = view.findViewById(R.id.library_clear_action)
    }

    private companion object {
        const val TYPE_PROFILE = 0
        const val TYPE_CHIPS = 1
        const val TYPE_DAY = 2
        const val TYPE_VIDEO = 3
        const val TYPE_EMPTY = 4
        const val TYPE_CLEAR = 5
        const val TYPE_PLAYLIST = 6
        const val TYPE_SAVED_PLAYLIST = 7
    }
}

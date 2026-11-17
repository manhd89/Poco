package com.luxysiv.puretube.ui.views

import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.luxysiv.puretube.R
import com.luxysiv.puretube.data.model.PlaylistItem
import com.luxysiv.puretube.data.model.SearchItem
import com.luxysiv.puretube.data.model.VideoItem
import com.luxysiv.puretube.error.AppError
import com.luxysiv.puretube.util.presentation

/**
 * Nội dung Tìm kiếm native.
 *
 * Giữ nguyên các trạng thái của bản Compose: trống (lịch sử + gợi ý), đang gõ
 * (gợi ý), đang tải và kết quả kèm chip sắp xếp. Activity chỉ cần đẩy trạng
 * thái từ ViewModel vào các hàm `set...`.
 */
class SearchNativeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val backButton: ImageButton
    private val input: EditText
    private val clearButton: ImageButton
    private val micButton: ImageButton
    private val loading: ProgressBar
    private val list: RecyclerView

    private val adapter = SearchAdapter()

    // Trạng thái do Activity đẩy vào.
    private var query: String = ""
    private var isSearching = false
    private var isShowingResults = false
    private var searchError: AppError? = null
    private var suggestions: List<String> = emptyList()
    private var recentSearches: List<String> = emptyList()
    private var popularSearches: List<String> = emptyList()
    private var results: List<SearchItem> = emptyList()
    private var watchedIds: Set<String> = emptySet()
    private var favoriteIds: Set<String> = emptySet()
    private var watchLaterIds: Set<String> = emptySet()
    private var savedPlaylistIds: Set<String> = emptySet()
    private var watchProgress: Map<String, Float> = emptyMap()
    private var sortIndex = 0
    private var columns = 1

    private var suppressTextWatcher = false
    private var actions: VideoCardActions? = null

    var onBackClick: (() -> Unit)? = null
    var onQueryChange: ((String) -> Unit)? = null
    var onSubmit: ((String) -> Unit)? = null
    var onClearRecentSearches: (() -> Unit)? = null
    var onRemoveHistory: ((String) -> Unit)? = null
    var onMicClick: (() -> Unit)? = null
    var onVideoClick: ((VideoItem) -> Unit)? = null
    var onPlaylistClick: ((PlaylistItem) -> Unit)? = null
    var onToggleFavorite: ((VideoItem) -> Unit)? = null
    var onToggleWatchLater: ((VideoItem) -> Unit)? = null
    var onToggleSavePlaylist: ((PlaylistItem) -> Unit)? = null

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_search, this, true)

        backButton = findViewById(R.id.search_back)
        input = findViewById(R.id.search_input)
        clearButton = findViewById(R.id.search_clear)
        micButton = findViewById(R.id.search_mic)
        loading = findViewById(R.id.search_loading)
        list = findViewById(R.id.search_list)

        list.adapter = adapter
        list.addItemDecoration(SearchResultDecoration())
        updateLayoutMetrics()

        backButton.setOnClickListener { onBackClick?.invoke() }
        clearButton.setOnClickListener {
            setQuery("")
            onQueryChange?.invoke("")
            input.requestFocus()
        }
        micButton.setOnClickListener { onMicClick?.invoke() }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (suppressTextWatcher) return
                val text = s?.toString().orEmpty()
                if (text == query) return
                query = text
                clearButton.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                onQueryChange?.invoke(text)
            }
        })

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submitCurrent()
                true
            } else {
                false
            }
        }

        clearButton.visibility = View.GONE
    }

    fun updateLayoutMetrics() {
        val widthDp = resources.configuration.screenWidthDp
        val compact = widthDp < 600
        val newColumns = if (widthDp >= 840) 2 else 1

        if (newColumns != columns || list.layoutManager == null) {
            columns = newColumns
            val manager = GridLayoutManager(context, columns)
            manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (adapter.isFullSpan(position)) columns else 1
            }
            list.layoutManager = manager
        }
        val extraHorizontal = if (compact) 0 else context.dp(8f)
        list.setPadding(extraHorizontal, 0, extraHorizontal, context.dp(24f))
    }

    // ------------------------------------------------------------------
    // Trạng thái từ ViewModel
    // ------------------------------------------------------------------

    fun setQuery(value: String) {
        query = value
        if (input.text?.toString().orEmpty() != value) {
            suppressTextWatcher = true
            input.setText(value)
            input.setSelection(value.length)
            suppressTextWatcher = false
        }
        clearButton.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
        render()
    }

    fun setSearching(value: Boolean) {
        isSearching = value
        render()
    }

    fun setShowingResults(value: Boolean) {
        isShowingResults = value
        render()
    }

    fun setSearchError(value: AppError?) {
        searchError = value
        render()
    }

    fun setSuggestions(values: List<String>) {
        suggestions = values
        render()
    }

    fun setRecentSearches(values: List<String>) {
        recentSearches = values
        render()
    }

    fun setPopularSearches(values: List<String>) {
        popularSearches = values
        render()
    }

    fun setResults(values: List<SearchItem>) {
        results = values
        render()
    }

    /** Cập nhật danh sách playlist đã lưu để card hiện đúng trạng thái. */
    fun setSavedPlaylistIds(values: Set<String>) {
        savedPlaylistIds = values
        render()
    }

    fun setLibrary(
        favorites: Set<String>,
        watchLater: Set<String>,
        watched: Set<String>,
        progress: Map<String, Float>
    ) {
        favoriteIds = favorites
        watchLaterIds = watchLater
        watchedIds = watched
        watchProgress = progress
        render()
    }

    /** true nếu Back nên xoá tìm kiếm thay vì rời trang. */
    fun hasActiveSearch(): Boolean = query.isNotEmpty() || isShowingResults

    /** Từ khoá hiện tại, dùng để lưu/khôi phục state. */
    fun currentQuery(): String = query

    fun clearSearchAndFocus() {
        setQuery("")
        onQueryChange?.invoke("")
        input.requestFocus()
    }

    fun focusSearch() {
        input.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    fun clearFocusAndHideKeyboard() {
        input.clearFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    /** Điền từ khoá (dùng cho tìm bằng giọng nói) và tìm ngay. */
    fun submitText(value: String) {
        setQuery(value)
        onQueryChange?.invoke(value)
        onSubmit?.invoke(value)
        clearFocusAndHideKeyboard()
    }

    private fun submitCurrent() {
        val text = query.trim()
        if (text.isEmpty()) return
        onSubmit?.invoke(text)
        clearFocusAndHideKeyboard()
    }

    // ------------------------------------------------------------------
    // Dựng danh sách
    // ------------------------------------------------------------------

    private fun render() {
        loading.visibility = if (isSearching) View.VISIBLE else View.GONE
        list.visibility = if (isSearching) View.GONE else View.VISIBLE

        actions = videoCardActions(
            onClick = { onVideoClick?.invoke(it) },
            onToggleFavorite = onToggleFavorite,
            onToggleWatchLater = onWatchLaterIdsCallback()
        )

        val rows = when {
            isSearching -> emptyList()
            query.isBlank() -> buildIdleRows()
            !isShowingResults -> buildSuggestionRows()
            else -> buildResultRows()
        }

        adapter.submit(rows, this)
    }

    private fun onWatchLaterIdsCallback(): ((VideoItem) -> Unit)? =
        onToggleWatchLater?.let { callback -> { video -> callback(video) } }

    private fun buildIdleRows(): List<SearchRow> {
        val rows = ArrayList<SearchRow>()
        if (recentSearches.isNotEmpty()) {
            rows += SearchRow.Header(
                title = context.getString(R.string.search_recent),
                iconRes = R.drawable.ic_history,
                actionLabel = context.getString(R.string.search_clear_all),
                action = { onClearRecentSearches?.invoke() }
            )
            recentSearches.forEach { search ->
                rows += SearchRow.Suggestion(
                    text = search,
                    highlight = null,
                    iconRes = R.drawable.ic_history,
                    trailingRemove = search
                )
            }
        }
        if (popularSearches.isNotEmpty()) {
            rows += SearchRow.Header(
                title = context.getString(R.string.search_popular),
                iconRes = R.drawable.ic_trending_up
            )
            popularSearches.forEach { suggestion ->
                rows += SearchRow.Suggestion(
                    text = suggestion,
                    highlight = null,
                    iconRes = R.drawable.ic_search,
                    trailingRemove = null
                )
            }
        }
        return rows
    }

    private fun buildSuggestionRows(): List<SearchRow> {
        if (suggestions.isEmpty()) {
            return listOf(
                SearchRow.Suggestion(
                    text = context.getString(R.string.search_query_fallback, query),
                    highlight = query,
                    iconRes = R.drawable.ic_search,
                    trailingRemove = null
                )
            )
        }
        return suggestions.map { suggestion ->
            SearchRow.Suggestion(
                text = suggestion,
                highlight = query,
                iconRes = R.drawable.ic_search,
                trailingRemove = null
            )
        }
    }

    private fun buildResultRows(): List<SearchRow> {
        val rows = ArrayList<SearchRow>()
        rows += SearchRow.SortChips(sortIndex)

        val error = searchError
        if (error != null && results.isEmpty()) {
            rows += SearchRow.Error(error)
        } else if (results.isEmpty()) {
            rows += SearchRow.Empty(query)
        }

        sortedResults().forEach { item ->
            rows += when (item) {
                is SearchItem.Video -> SearchRow.Video(item.video)
                is SearchItem.Playlist -> SearchRow.Playlist(
                    playlist = item.playlist,
                    isSaved = savedPlaylistIds.contains(item.playlist.id)
                )
            }
        }
        return rows
    }

    /**
     * Sắp xếp chỉ các video; playlist giữ nguyên vị trí xen kẽ của YouTube vì
     * không có ngày đăng/lượt xem để so sánh.
     */
    private fun sortedResults(): List<SearchItem> {
        val videos = results.filterIsInstance<SearchItem.Video>().map { it.video }
        if (videos.isEmpty()) return results

        val sorted = when (sortIndex) {
            1 -> videos.sortedBy { parseAgeHours(it.publishedTime) }
            2 -> videos.sortedByDescending { parseViewCount(it.viewCount) }
            else -> videos
        }.sortedBy { if (it.id in watchedIds) 0 else 1 }

        val queue = ArrayDeque(sorted)
        return results.map { item ->
            if (item is SearchItem.Video) SearchItem.Video(queue.removeFirst()) else item
        }
    }

    private fun submitSearch(value: String) {
        setQuery(value)
        onQueryChange?.invoke(value)
        onSubmit?.invoke(value)
        clearFocusAndHideKeyboard()
    }

    private fun selectSort(index: Int) {
        sortIndex = index
        render()
    }

    // ------------------------------------------------------------------
    // Adapter
    // ------------------------------------------------------------------

    private sealed interface SearchRow {
        data class Error(val error: AppError) : SearchRow

        data class Header(
            val title: String,
            val iconRes: Int,
            val actionLabel: String? = null,
            val action: (() -> Unit)? = null
        ) : SearchRow

        data class Suggestion(
            val text: String,
            val highlight: String?,
            val iconRes: Int,
            val trailingRemove: String?
        ) : SearchRow

        data class SortChips(val selectedIndex: Int) : SearchRow

        data class Video(val video: VideoItem) : SearchRow

        data class Playlist(val playlist: PlaylistItem, val isSaved: Boolean = false) : SearchRow

        data class Empty(val query: String) : SearchRow
    }

    private inner class SearchAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var rows: List<SearchRow> = emptyList()
        private var host: SearchNativeView? = null

        fun submit(newRows: List<SearchRow>, host: SearchNativeView) {
            val oldRows = rows
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldRows.size
                override fun getNewListSize(): Int = newRows.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val old = oldRows[oldItemPosition]
                    val new = newRows[newItemPosition]
                    return when {
                        old is SearchRow.Video && new is SearchRow.Video -> old.video.id == new.video.id
                        old is SearchRow.Playlist && new is SearchRow.Playlist ->
                            old.playlist.id == new.playlist.id
                        old is SearchRow.Suggestion && new is SearchRow.Suggestion ->
                            old.text == new.text && old.trailingRemove == new.trailingRemove
                        old is SearchRow.Header && new is SearchRow.Header -> old.title == new.title
                        old is SearchRow.Empty && new is SearchRow.Empty -> old.query == new.query
                        old is SearchRow.Error && new is SearchRow.Error -> true
                        old is SearchRow.SortChips && new is SearchRow.SortChips -> true
                        else -> false
                    }
                }

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    oldRows[oldItemPosition] == newRows[newItemPosition]
            })
            rows = newRows
            this.host = host
            diff.dispatchUpdatesTo(this)
        }

        fun isFullSpan(position: Int): Boolean = when (rows.getOrNull(position)) {
            is SearchRow.Video, is SearchRow.Playlist -> false
            else -> true
        }

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is SearchRow.Header -> TYPE_HEADER
            is SearchRow.Suggestion -> TYPE_SUGGESTION
            is SearchRow.SortChips -> TYPE_CHIPS
            is SearchRow.Video -> TYPE_VIDEO
            is SearchRow.Playlist -> TYPE_PLAYLIST
            is SearchRow.Empty -> TYPE_EMPTY
            is SearchRow.Error -> TYPE_ERROR
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_HEADER -> HeaderHolder(inflater.inflate(R.layout.view_search_header, parent, false))
                TYPE_SUGGESTION -> SuggestionHolder(
                    inflater.inflate(R.layout.view_search_suggestion, parent, false)
                )

                TYPE_CHIPS -> ChipsHolder(inflater.inflate(R.layout.view_search_chips, parent, false))

                TYPE_VIDEO -> {
                    val card = CompactVideoCardView(parent.context)
                    card.layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    VideoHolder(card)
                }

                TYPE_PLAYLIST -> {
                    val card = PlaylistCardView(parent.context)
                    card.layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    PlaylistHolder(card)
                }

                TYPE_EMPTY -> EmptyHolder(inflater.inflate(R.layout.view_search_empty, parent, false))
                else -> ErrorHolder(inflater.inflate(R.layout.view_search_error, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is SearchRow.Header -> (holder as HeaderHolder).bind(row)
                is SearchRow.Suggestion -> (holder as SuggestionHolder).bind(row)
                is SearchRow.SortChips -> (holder as ChipsHolder).bind(row.selectedIndex)
                is SearchRow.Video -> {
                    val currentActions = actions ?: return
                    val video = row.video
                    (holder as VideoHolder).card.bind(
                        video = video,
                        isFavorite = favoriteIds.contains(video.id),
                        isWatchLater = watchLaterIds.contains(video.id),
                        progressFraction = watchProgress[video.id] ?: 0f,
                        showSnippet = true,
                        actions = currentActions
                    )
                }

                is SearchRow.Playlist -> (holder as PlaylistHolder).card.bind(
                    playlist = row.playlist,
                    onClick = { onPlaylistClick?.invoke(row.playlist) },
                    isSaved = row.isSaved,
                    onToggleSave = onToggleSavePlaylist
                )

                is SearchRow.Empty -> (holder as EmptyHolder).bind(row.query)
                is SearchRow.Error -> (holder as ErrorHolder).bind(row.error) {
                    host?.submitSearch(query)
                }
            }
        }
    }

    private inner class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.search_header_icon)
        private val title: TextView = view.findViewById(R.id.search_header_title)
        private val action: TextView = view.findViewById(R.id.search_header_action)

        fun bind(row: SearchRow.Header) {
            icon.visibility = View.VISIBLE
            icon.setImageResource(row.iconRes)
            title.text = row.title
            val actionCallback = row.action
            if (row.actionLabel != null && actionCallback != null) {
                action.visibility = View.VISIBLE
                action.text = row.actionLabel
                action.setOnClickListener { actionCallback() }
            } else {
                action.visibility = View.GONE
            }
        }
    }

    private inner class SuggestionHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val root: View = view
        private val icon: ImageView = view.findViewById(R.id.search_suggestion_icon)
        private val text: TextView = view.findViewById(R.id.search_suggestion_text)
        private val trailing: ImageButton = view.findViewById(R.id.search_suggestion_trailing)

        fun bind(row: SearchRow.Suggestion) {
            icon.setImageResource(row.iconRes)
            text.text = highlightMatch(row.text, row.highlight)
            root.setOnClickListener { submitSearch(row.text) }
            if (row.trailingRemove != null) {
                trailing.visibility = View.VISIBLE
                trailing.setOnClickListener { onRemoveHistory?.invoke(row.trailingRemove) }
            } else {
                trailing.visibility = View.GONE
            }
        }
    }

    private inner class ChipsHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val container: LinearLayout = view.findViewById(R.id.search_sort_chips)

        fun bind(selectedIndex: Int) {
            container.removeAllViews()
            val labels = listOf(
                R.string.search_sort_relevance,
                R.string.search_sort_newest,
                R.string.search_sort_views
            )
            labels.forEachIndexed { index, labelRes ->
                val chip = LayoutInflater.from(context)
                    .inflate(R.layout.view_category_chip, container, false) as TextView
                chip.text = context.getString(labelRes)
                styleChip(chip, index == selectedIndex)
                chip.setOnClickListener { selectSort(index) }
                val params = chip.layoutParams as LinearLayout.LayoutParams
                params.marginEnd = context.dp(8f)
                chip.layoutParams = params
                container.addView(chip)
            }
        }

        private fun styleChip(chip: TextView, selected: Boolean) {
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
        }
    }

    private inner class SearchResultDecoration : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            val position = parent.getChildAdapterPosition(view)
            if (position == RecyclerView.NO_POSITION || adapter.isFullSpan(position)) {
                outRect.set(0, 0, 0, 0)
                return
            }
            val outer = context.dp(16f)
            if (columns <= 1) {
                outRect.set(outer, 0, outer, 0)
            } else {
                val halfGap = context.dp(8f)
                val lp = view.layoutParams as? GridLayoutManager.LayoutParams
                val spanIndex = lp?.spanIndex ?: 0
                outRect.set(
                    if (spanIndex == 0) outer else halfGap,
                    0,
                    if (spanIndex == columns - 1) outer else halfGap,
                    0
                )
            }
        }
    }

    private class VideoHolder(val card: CompactVideoCardView) : RecyclerView.ViewHolder(card)

    private class PlaylistHolder(val card: PlaylistCardView) : RecyclerView.ViewHolder(card)

    private inner class EmptyHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.search_empty_title)

        fun bind(query: String) {
            title.text = context.getString(R.string.search_no_results, query)
        }
    }

    private inner class ErrorHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.search_error_title)
        private val message: TextView = view.findViewById(R.id.search_error_message)
        private val retry: TextView = view.findViewById(R.id.search_error_retry)

        fun bind(error: AppError, onRetry: () -> Unit) {
            val presentation = error.presentation(context)
            title.text = presentation.title
            message.text = presentation.message
            retry.text = presentation.retryLabel
            retry.setOnClickListener { onRetry() }
        }
    }

    private fun highlightMatch(text: String, query: String?): CharSequence {
        if (query.isNullOrBlank()) return text
        val start = text.indexOf(query, ignoreCase = true)
        if (start < 0) return text
        val span = SpannableString(text)
        span.setSpan(
            StyleSpan(Typeface.BOLD),
            start,
            start + query.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return span
    }

    private fun parseViewCount(raw: String): Long {
        val cleaned = raw.lowercase()
        val number = Regex("([0-9]+(?:[.,][0-9]+)?)").find(cleaned)?.value
            ?.replace(",", ".") ?: return 0
        val value = number.toDoubleOrNull() ?: return 0
        return when {
            cleaned.contains("tỷ") || cleaned.contains("b") -> (value * 1_000_000_000).toLong()
            cleaned.contains("triệu") || cleaned.contains("m") -> (value * 1_000_000).toLong()
            cleaned.contains("nghìn") || cleaned.contains("k") -> (value * 1_000).toLong()
            else -> value.toLong()
        }
    }

    private fun parseAgeHours(raw: String): Long {
        val cleaned = raw.lowercase()
        val number = Regex("([0-9]+)").find(cleaned)?.value?.toLongOrNull() ?: return Long.MAX_VALUE
        return when {
            cleaned.contains("giây") -> 0
            cleaned.contains("phút") -> number
            cleaned.contains("giờ") -> number * 24
            cleaned.contains("ngày") -> number * 24 * 24
            cleaned.contains("tuần") -> number * 7 * 24 * 24
            cleaned.contains("tháng") -> number * 30 * 24 * 24
            cleaned.contains("năm") -> number * 365 * 24 * 24
            else -> number
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_SUGGESTION = 1
        const val TYPE_CHIPS = 2
        const val TYPE_VIDEO = 3
        const val TYPE_EMPTY = 4
        const val TYPE_ERROR = 5
        const val TYPE_PLAYLIST = 6
    }
}

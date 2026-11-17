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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.luxysiv.puretube.BuildConfig
import com.luxysiv.puretube.R
import com.luxysiv.puretube.data.model.SponsorCategory
import com.luxysiv.puretube.data.model.SponsorMode
import com.luxysiv.puretube.player.PurePlayerState
import com.luxysiv.puretube.ui.adaptive.WindowWidthClass
import com.luxysiv.puretube.ui.adaptive.windowWidthClassForWidth
import com.luxysiv.puretube.util.Formatters

/** Toàn bộ trạng thái cài đặt mà màn hình cần hiển thị. */
data class SettingsState(
    val isBackgroundPlayEnabled: Boolean = false,
    val isAutoNextEnabled: Boolean = false,
    val isAutoPiPEnabled: Boolean = false,
    val isResumeEnabled: Boolean = false,
    val isSponsorBlockEnabled: Boolean = true,
    val isSkipSilenceEnabled: Boolean = false,
    val sleepTimerMinutes: Int = 0,
    val playbackSpeed: Float = 1f,
    val playbackPitch: Float = 1f,
    val wifiQualityLabel: String = "",
    val mobileQualityLabel: String = "",
    val wifiQualityHeight: Int = 0,
    val mobileQualityHeight: Int = 0,
    val isDataSaverEnabled: Boolean = false,
    val availableQualities: List<Int> = emptyList(),
    val sponsorNotify: Boolean = false,
    val sponsorShowSkipButton: Boolean = false,
    val sponsorGracefulRewind: Boolean = true,
    val sponsorApiUrl: String = "",
    val sponsorCategoryModes: Map<String, SponsorMode> = emptyMap(),
    val sponsorWhitelistCount: Int = 0,
    val isCurrentChannelSponsorWhitelisted: Boolean = false,
    val hideComments: Boolean = false,
    val hideRelated: Boolean = false,
    val hideDescription: Boolean = false
)

/**
 * Chuyển trạng thái phát/cài đặt thành [SettingsState] hiển thị.
 *
 * Trước đây Activity chép tay từng trường; gom vào một mapping duy nhất để
 * thêm trường mới không bị bỏ sót và có thể test mà không cần Activity.
 */
fun PurePlayerState.toSettingsState(): SettingsState = SettingsState(
    isBackgroundPlayEnabled = isBackgroundPlayEnabled,
    isAutoNextEnabled = isAutoNextEnabled,
    isAutoPiPEnabled = isAutoPiPEnabled,
    isResumeEnabled = isResumeEnabled,
    isSponsorBlockEnabled = isSponsorBlockEnabled,
    isSkipSilenceEnabled = isSkipSilenceEnabled,
    sleepTimerMinutes = sleepTimerMinutes,
    playbackSpeed = playbackSpeed,
    playbackPitch = playbackPitch,
    wifiQualityLabel = wifiQualityLabel,
    mobileQualityLabel = mobileQualityLabel,
    wifiQualityHeight = wifiQualityHeight,
    mobileQualityHeight = mobileQualityHeight,
    isDataSaverEnabled = isDataSaverEnabled,
    availableQualities = availableQualities,
    sponsorNotify = sponsorNotify,
    sponsorShowSkipButton = sponsorShowSkipButton,
    sponsorGracefulRewind = sponsorGracefulRewind,
    sponsorApiUrl = sponsorApiUrl,
    sponsorCategoryModes = sponsorCategoryModes,
    sponsorWhitelistCount = sponsorWhitelistCount,
    isCurrentChannelSponsorWhitelisted = isCurrentChannelSponsorWhitelisted,
    hideComments = hideComments,
    hideRelated = hideRelated,
    hideDescription = hideDescription
)

/**
 * Nội dung Cài đặt native.
 *
 * Mỗi mục là một dòng trong RecyclerView (công tắc hoặc lựa chọn). Các hộp
 * thoại lựa chọn/xác nhận do Activity dựng để dùng chung MaterialAlertDialog.
 */
class SettingsNativeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val list: RecyclerView
    private val adapter = SettingsAdapter()

    private var state = SettingsState()
    private var columns = 1

    var onBack: (() -> Unit)? = null
    var onToggleBackgroundPlay: (() -> Unit)? = null
    var onToggleAutoNext: (() -> Unit)? = null
    var onToggleAutoPiP: (() -> Unit)? = null
    var onToggleResume: (() -> Unit)? = null
    var onToggleSponsorBlock: (() -> Unit)? = null
    var onToggleSkipSilence: (() -> Unit)? = null
    var onToggleDataSaver: (() -> Unit)? = null
    var onSetSponsorNotify: ((Boolean) -> Unit)? = null
    var onSetSponsorShowSkipButton: ((Boolean) -> Unit)? = null
    var onSetSponsorGracefulRewind: ((Boolean) -> Unit)? = null
    var onSetHideComments: ((Boolean) -> Unit)? = null
    var onSetHideRelated: ((Boolean) -> Unit)? = null
    var onSetHideDescription: ((Boolean) -> Unit)? = null
    var onToggleCurrentChannelSponsorWhitelist: (() -> Unit)? = null
    var onPickSleepTimer: (() -> Unit)? = null
    var onPickWifiQuality: (() -> Unit)? = null
    var onPickMobileQuality: (() -> Unit)? = null
    var onPickSpeed: (() -> Unit)? = null
    var onPickPitch: (() -> Unit)? = null
    var onPickSponsorApi: (() -> Unit)? = null
    var onPickSponsorCategory: ((SponsorCategory) -> Unit)? = null
    var onPickSponsorWhitelist: (() -> Unit)? = null
    var onClearHistory: (() -> Unit)? = null
    var onClearSearchHistory: (() -> Unit)? = null
    var onExportBackup: (() -> Unit)? = null
    var onImportBackup: (() -> Unit)? = null
    var onOpenSource: (() -> Unit)? = null
    var onOpenLicense: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_settings, this, true)

        list = findViewById(R.id.settings_list)
        list.adapter = adapter
        findViewById<ImageButton>(R.id.settings_back).setOnClickListener {
            onBack?.invoke()
        }

        updateLayoutMetrics()
    }

    // ------------------------------------------------------------------
    // Trạng thái từ ViewModel
    // ------------------------------------------------------------------

    fun setState(state: SettingsState) {
        this.state = state
        adapter.submit(buildRows(state))
    }

    fun updateLayoutMetrics() {
        val widthClass = windowWidthClassForWidth(resources.configuration.screenWidthDp)
        val newColumns = if (widthClass == WindowWidthClass.Expanded) 2 else 1
        val padding = context.dp(if (widthClass == WindowWidthClass.Compact) 16f else 24f)

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
            list.addItemDecoration(SpacingItemDecoration(context.dp(2f)))
        }
        list.setPadding(
            padding - context.dp(8f),
            0,
            padding - context.dp(8f),
            context.dp(40f)
        )
        adapter.notifyDataSetChanged()
    }

    private fun buildRows(state: SettingsState): List<SettingsRow> {
        val rows = ArrayList<SettingsRow>()
        rows += SettingsRow.Profile
        rows += SettingsRow.Section(context.getString(R.string.settings_section_playback))

        rows += toggle(
            R.drawable.ic_headphones,
            "Phát trong nền",
            "Tiếp tục nghe khi chuyển ứng dụng hoặc tắt màn hình",
            state.isBackgroundPlayEnabled
        ) { onToggleBackgroundPlay?.invoke() }

        rows += toggle(
            R.drawable.ic_skip_next,
            "Tự phát video kế tiếp",
            "Khi video kết thúc, tự động phát video liên quan đầu tiên",
            state.isAutoNextEnabled
        ) { onToggleAutoNext?.invoke() }

        rows += toggle(
            R.drawable.ic_picture_in_picture,
            "Tự chuyển hình trong hình",
            "Thu nhỏ vào cửa sổ nổi khi bạn rời ứng dụng lúc đang phát",
            state.isAutoPiPEnabled
        ) { onToggleAutoPiP?.invoke() }

        rows += toggle(
            R.drawable.ic_restore,
            "Tiếp tục xem từ vị trí đã dừng",
            "Tự tua đến chỗ đang xem dở khi mở lại video",
            state.isResumeEnabled
        ) { onToggleResume?.invoke() }

        rows += toggle(
            R.drawable.ic_block,
            "Bỏ qua đoạn tài trợ",
            "Bật SponsorBlock để tự tua qua quảng cáo/tài trợ. Tắt là tắt hoàn toàn: " +
                "không gọi máy chủ, không hiện marker hay nút bỏ qua.",
            state.isSponsorBlockEnabled
        ) { onToggleSponsorBlock?.invoke() }

        // Khi SponsorBlock tắt thì ẩn toàn bộ cấu hình liên quan: người dùng chỉ
        // còn thấy công tắc tổng, tránh hiểu nhầm là các tuỳ chọn vẫn tác dụng.
        if (state.isSponsorBlockEnabled) {
            rows += SettingsRow.Section(context.getString(R.string.settings_section_sponsor))

            rows += toggle(
                R.drawable.ic_block,
                "Miễn trừ kênh đang phát",
                "Không tự tua đoạn tài trợ của kênh đang xem",
                state.isCurrentChannelSponsorWhitelisted
            ) { onToggleCurrentChannelSponsorWhitelist?.invoke() }

            rows += toggle(
                R.drawable.ic_info,
                "Thông báo khi bỏ qua",
                "Hiện thông báo ngắn mỗi lần tự tua qua đoạn tài trợ",
                state.sponsorNotify
            ) { onSetSponsorNotify?.invoke(it) }

            rows += toggle(
                R.drawable.ic_skip_next,
                "Nút Bỏ qua / Hoàn tác",
                "Hiện nút bỏ qua đoạn thủ công và hoàn tác trong 5 giây",
                state.sponsorShowSkipButton
            ) { onSetSponsorShowSkipButton?.invoke(it) }

            rows += toggle(
                R.drawable.ic_restore,
                "Tôn trọng thao tác tua lại",
                "Tua lại vào đoạn đã bỏ qua thì cho xem hết đoạn đó",
                state.sponsorGracefulRewind
            ) { onSetSponsorGracefulRewind?.invoke(it) }

            rows += SettingsRow.Choice(
                iconRes = R.drawable.ic_code,
                title = "Máy chủ SponsorBlock",
                description = "Dùng API chính thức hoặc máy chủ tự host",
                valueText = if (state.sponsorApiUrl.isBlank() ||
                    state.sponsorApiUrl.contains("sponsor.ajay.app")
                ) {
                    context.getString(R.string.settings_default)
                } else {
                    context.getString(R.string.settings_custom)
                },
                onClick = { onPickSponsorApi?.invoke() }
            )

            rows += SettingsRow.Section(
                context.getString(R.string.settings_section_sponsor_categories)
            )

            SponsorCategory.entries.forEach { category ->
                val mode = state.sponsorCategoryModes[category.apiName] ?: category.defaultMode
                rows += SettingsRow.Choice(
                    iconRes = R.drawable.ic_block,
                    title = category.label,
                    description = "Cách xử lý đoạn do cộng đồng đánh dấu",
                    valueText = mode.label,
                    onClick = { onPickSponsorCategory?.invoke(category) }
                )
            }

            rows += SettingsRow.Choice(
                iconRes = R.drawable.ic_delete_sweep,
                title = "Kênh miễn trừ",
                description = "Bật/tắt miễn trừ cho kênh đang xem trong trình phát",
                valueText = if (state.sponsorWhitelistCount > 0) {
                    context.getString(R.string.settings_k_channel, state.sponsorWhitelistCount)
                } else {
                    context.getString(R.string.settings_empty)
                },
                onClick = { onPickSponsorWhitelist?.invoke() }
            )
        }

        rows += SettingsRow.Section(context.getString(R.string.settings_section_content))

        rows += toggle(
            R.drawable.ic_block,
            "Ẩn bình luận",
            "Không tải và không hiển thị phần bình luận",
            state.hideComments
        ) { onSetHideComments?.invoke(it) }

        rows += toggle(
            R.drawable.ic_block,
            "Ẩn video liên quan",
            "Ẩn video gợi ý dưới trang xem",
            state.hideRelated
        ) { onSetHideRelated?.invoke(it) }

        rows += toggle(
            R.drawable.ic_block,
            "Ẩn mô tả video",
            "Ẩn thẻ mô tả trên trang xem",
            state.hideDescription
        ) { onSetHideDescription?.invoke(it) }

        rows += SettingsRow.Choice(
            iconRes = R.drawable.ic_wifi,
            title = "Chất lượng trên Wi-Fi",
            description = "Giới hạn độ phân giải tối đa khi dùng Wi-Fi",
            valueText = state.wifiQualityLabel,
            onClick = { onPickWifiQuality?.invoke() }
        )

        rows += SettingsRow.Choice(
            iconRes = R.drawable.ic_signal_cellular,
            title = "Chất lượng trên dữ liệu di động",
            description = "Giới hạn độ phân giải khi dùng 4G/5G",
            valueText = state.mobileQualityLabel,
            onClick = { onPickMobileQuality?.invoke() }
        )

        rows += toggle(
            R.drawable.ic_data_saver,
            "Tiết kiệm dữ liệu",
            "Trên mạng di động, hạ chất lượng \"Tự động\" xuống 480p",
            state.isDataSaverEnabled
        ) { onToggleDataSaver?.invoke() }

        rows += SettingsRow.Choice(
            iconRes = R.drawable.ic_play_circle,
            title = "Tốc độ phát",
            description = "Tăng/giảm tốc độ phát video",
            valueText = "${Formatters.speed(state.playbackSpeed)}x",
            onClick = { onPickSpeed?.invoke() }
        )

        rows += SettingsRow.Choice(
            iconRes = R.drawable.ic_graphic_eq,
            title = "Cao độ",
            description = "Điều chỉnh giọng đọc khi xem ở tốc độ khác",
            valueText = "${Formatters.speed(state.playbackPitch)}x",
            onClick = { onPickPitch?.invoke() }
        )

        rows += toggle(
            R.drawable.ic_volume_off,
            "Bỏ qua khoảng lặng",
            "Tự tua nhanh qua đoạn video không có âm thanh",
            state.isSkipSilenceEnabled
        ) { onToggleSkipSilence?.invoke() }

        rows += SettingsRow.Choice(
            iconRes = R.drawable.ic_timer,
            title = "Hẹn giờ tắt",
            description = if (state.sleepTimerMinutes > 0) {
                "Đang hẹn giờ: ${state.sleepTimerMinutes} phút"
            } else {
                "Tự dừng phát sau một khoảng thời gian"
            },
            valueText = if (state.sleepTimerMinutes > 0) {
                context.getString(R.string.settings_minutes_short, state.sleepTimerMinutes)
            } else {
                context.getString(R.string.settings_off)
            },
            onClick = { onPickSleepTimer?.invoke() }
        )

        rows += SettingsRow.Section(context.getString(R.string.settings_section_data))

        rows += SettingsRow.Choice(
            iconRes = R.drawable.ic_delete,
            title = "Xóa lịch sử xem",
            description = "Dọn dẹp video đã xem và tiến độ trên thiết bị",
            valueText = context.getString(R.string.settings_delete),
            onClick = { onClearHistory?.invoke() }
        )

        rows += SettingsRow.Choice(
            iconRes = R.drawable.ic_delete_sweep,
            title = "Xóa lịch sử tìm kiếm",
            description = "Xóa các từ khóa đã tìm gần đây",
            valueText = context.getString(R.string.settings_delete),
            onClick = { onClearSearchHistory?.invoke() }
        )

        rows += SettingsRow.Choice(
            iconRes = R.drawable.ic_restore,
            title = "Xuất bản sao lưu",
            description = "Lưu cài đặt, video đã lưu và lịch sử tìm kiếm ra tệp JSON",
            valueText = context.getString(R.string.settings_export),
            onClick = { onExportBackup?.invoke() }
        )

        rows += SettingsRow.Choice(
            iconRes = R.drawable.ic_restore,
            title = "Khôi phục bản sao lưu",
            description = "Chọn tệp JSON đã xuất để khôi phục dữ liệu trên thiết bị này",
            valueText = context.getString(R.string.settings_import),
            onClick = { onImportBackup?.invoke() }
        )

        rows += SettingsRow.Section(context.getString(R.string.settings_section_about))
        rows += SettingsRow.About
        rows += SettingsRow.Disclaimer
        return rows
    }

    private fun toggle(
        iconRes: Int,
        title: String,
        description: String,
        checked: Boolean,
        onToggle: (Boolean) -> Unit
    ): SettingsRow.Toggle = SettingsRow.Toggle(
        iconRes = iconRes,
        title = title,
        description = description,
        checked = checked,
        onToggle = onToggle
    )

    // ------------------------------------------------------------------
    // Adapter
    // ------------------------------------------------------------------

    private sealed interface SettingsRow {
        data object Profile : SettingsRow
        data class Section(val title: String) : SettingsRow
        data class Toggle(
            val iconRes: Int,
            val title: String,
            val description: String,
            val checked: Boolean,
            val onToggle: (Boolean) -> Unit
        ) : SettingsRow

        data class Choice(
            val iconRes: Int,
            val title: String,
            val description: String,
            val valueText: String,
            val onClick: () -> Unit
        ) : SettingsRow

        data object About : SettingsRow
        data object Disclaimer : SettingsRow
    }

    private inner class SettingsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var rows: List<SettingsRow> = emptyList()

        fun submit(newRows: List<SettingsRow>) {
            val oldRows = rows
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldRows.size
                override fun getNewListSize(): Int = newRows.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val old = oldRows[oldItemPosition]
                    val new = newRows[newItemPosition]
                    return when {
                        old is SettingsRow.Section && new is SettingsRow.Section -> old.title == new.title
                        old is SettingsRow.Toggle && new is SettingsRow.Toggle ->
                            old.title == new.title && old.iconRes == new.iconRes
                        old is SettingsRow.Choice && new is SettingsRow.Choice ->
                            old.title == new.title && old.iconRes == new.iconRes
                        old is SettingsRow.Profile && new is SettingsRow.Profile -> true
                        old is SettingsRow.About && new is SettingsRow.About -> true
                        old is SettingsRow.Disclaimer && new is SettingsRow.Disclaimer -> true
                        else -> false
                    }
                }

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val old = oldRows[oldItemPosition]
                    val new = newRows[newItemPosition]
                    return when {
                        old is SettingsRow.Toggle && new is SettingsRow.Toggle ->
                            old.iconRes == new.iconRes && old.title == new.title &&
                                old.description == new.description && old.checked == new.checked
                        old is SettingsRow.Choice && new is SettingsRow.Choice ->
                            old.iconRes == new.iconRes && old.title == new.title &&
                                old.description == new.description && old.valueText == new.valueText
                        else -> old == new
                    }
                }
            })
            rows = newRows
            diff.dispatchUpdatesTo(this)
        }

        fun isFullSpan(position: Int): Boolean = when (rows.getOrNull(position)) {
            SettingsRow.Profile, is SettingsRow.Section, SettingsRow.About,
            SettingsRow.Disclaimer -> true

            else -> false
        }

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            SettingsRow.Profile -> TYPE_PROFILE
            is SettingsRow.Section -> TYPE_SECTION
            is SettingsRow.Toggle -> TYPE_TOGGLE
            is SettingsRow.Choice -> TYPE_CHOICE
            SettingsRow.About -> TYPE_ABOUT
            SettingsRow.Disclaimer -> TYPE_DISCLAIMER
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_PROFILE -> ProfileHolder(inflater.inflate(R.layout.view_settings_profile, parent, false))
                TYPE_SECTION -> SectionHolder(inflater.inflate(R.layout.view_settings_section, parent, false))
                TYPE_TOGGLE -> ToggleHolder(inflater.inflate(R.layout.view_settings_row_toggle, parent, false))
                TYPE_CHOICE -> ChoiceHolder(inflater.inflate(R.layout.view_settings_row_choice, parent, false))
                TYPE_ABOUT -> AboutHolder(inflater.inflate(R.layout.view_settings_about, parent, false))
                else -> SimpleHolder(inflater.inflate(R.layout.view_settings_disclaimer, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                SettingsRow.Profile -> (holder as? ProfileHolder)?.bind()
                is SettingsRow.Section -> (holder as SectionHolder).title.text = row.title
                is SettingsRow.Toggle -> (holder as ToggleHolder).bind(row)
                is SettingsRow.Choice -> (holder as ChoiceHolder).bind(row)
                SettingsRow.About -> (holder as AboutHolder).bind()
                SettingsRow.Disclaimer -> Unit
            }
        }
    }

    private class ProfileHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val subtitle: TextView = view.findViewById(R.id.settings_account_subtitle)

        fun bind() {
            subtitle.text = itemView.context.getString(
                R.string.settings_account_subtitle,
                BuildConfig.VERSION_NAME
            )
        }
    }

    private class SectionHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.settings_section_title)
    }

    private inner class ToggleHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.settings_row_icon)
        private val title: TextView = view.findViewById(R.id.settings_row_title)
        private val description: TextView = view.findViewById(R.id.settings_row_description)
        private val switch: MaterialSwitch = view.findViewById(R.id.settings_row_switch)

        fun bind(row: SettingsRow.Toggle) {
            icon.setImageResource(row.iconRes)
            androidx.core.widget.ImageViewCompat.setImageTintList(
                icon,
                android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(
                        itemView.context,
                        if (row.checked) R.color.pure_primary else R.color.pure_on_surface_variant
                    )
                )
            )
            title.text = row.title
            description.text = row.description
            switch.setOnCheckedChangeListener(null)
            switch.isChecked = row.checked
            switch.setOnCheckedChangeListener { _, checked -> row.onToggle(checked) }
            itemView.setOnClickListener { row.onToggle(!switch.isChecked) }
        }
    }

    private class ChoiceHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.settings_row_icon)
        private val title: TextView = view.findViewById(R.id.settings_row_title)
        private val description: TextView = view.findViewById(R.id.settings_row_description)
        private val value: TextView = view.findViewById(R.id.settings_row_value)

        fun bind(row: SettingsRow.Choice) {
            icon.setImageResource(row.iconRes)
            title.text = row.title
            description.text = row.description
            value.text = row.valueText
            itemView.setOnClickListener { row.onClick() }
        }
    }

    private inner class AboutHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.settings_about_title)
        private val source: View = view.findViewById(R.id.settings_source_button)
        private val license: View = view.findViewById(R.id.settings_license_button)

        init {
            source.setOnClickListener { onOpenSource?.invoke() }
            license.setOnClickListener { onOpenLicense?.invoke() }
        }

        fun bind() {
            title.text = itemView.context.getString(
                R.string.settings_about_title,
                BuildConfig.VERSION_NAME
            )
        }
    }

    private class SimpleHolder(view: View) : RecyclerView.ViewHolder(view)

    private companion object {
        const val TYPE_PROFILE = 0
        const val TYPE_SECTION = 1
        const val TYPE_TOGGLE = 2
        const val TYPE_CHOICE = 3
        const val TYPE_ABOUT = 4
        const val TYPE_DISCLAIMER = 5
    }
}

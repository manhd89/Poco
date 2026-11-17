package com.luxysiv.puretube.ui.settings

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.luxysiv.puretube.R
import com.luxysiv.puretube.data.model.SponsorCategory
import com.luxysiv.puretube.data.model.SponsorMode
import com.luxysiv.puretube.data.repository.SponsorBlockDataSource
import com.luxysiv.puretube.ui.base.PageActivity
import com.luxysiv.puretube.ui.navigation.AppTab
import com.luxysiv.puretube.ui.navigation.PageNavigator
import com.luxysiv.puretube.ui.views.SettingsNativeView
import com.luxysiv.puretube.ui.views.SettingsState
import com.luxysiv.puretube.ui.views.toSettingsState
import com.luxysiv.puretube.util.Formatters
import kotlinx.coroutines.launch

/** Trang cài đặt - Activity riêng, dùng [SettingsViewModel] và giao diện XML native. */
class SettingsActivity : PageActivity() {

    override val viewModel: SettingsViewModel by viewModels()

    override val currentTab: AppTab = AppTab.SETTINGS

    private lateinit var settingsView: SettingsNativeView

    private var currentState = SettingsState()

    private val backupExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { viewModel.exportBackup(applicationContext, it) }
    }

    private val backupImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importBackup(applicationContext, it) }
    }

    override fun createPageView(): View {
        val view = SettingsNativeView(this)
        settingsView = view
        view.onBack = {
            PageNavigator.open(this, AppTab.LIBRARY)
            finish()
        }
        view.onToggleBackgroundPlay = viewModel::toggleBackgroundPlay
        view.onToggleAutoNext = viewModel::toggleAutoNext
        view.onToggleAutoPiP = viewModel::toggleAutoPiP
        view.onToggleResume = viewModel::toggleResumePlayback
        view.onToggleSponsorBlock = viewModel::toggleSponsorBlock
        view.onToggleSkipSilence = viewModel::toggleSkipSilence
        view.onToggleDataSaver = viewModel::toggleDataSaver
        view.onSetSponsorNotify = viewModel::setSponsorNotify
        view.onSetSponsorShowSkipButton = viewModel::setSponsorShowSkipButton
        view.onSetSponsorGracefulRewind = viewModel::setSponsorGracefulRewind
        view.onSetHideComments = viewModel::setHideComments
        view.onSetHideRelated = viewModel::setHideRelated
        view.onSetHideDescription = viewModel::setHideDescription
        view.onToggleCurrentChannelSponsorWhitelist =
            viewModel::toggleCurrentChannelSponsorWhitelist
        view.onPickSleepTimer = { showSleepTimerDialog() }
        view.onPickWifiQuality = { showQualityDialog(isWifi = true) }
        view.onPickMobileQuality = { showQualityDialog(isWifi = false) }
        view.onPickSpeed = { showSpeedDialog() }
        view.onPickPitch = { showPitchDialog() }
        view.onPickSponsorApi = { showSponsorApiDialog() }
        view.onPickSponsorCategory = { category -> showSponsorCategoryDialog(category) }
        view.onPickSponsorWhitelist = {
            if (currentState.sponsorWhitelistCount > 0) showClearWhitelistDialog()
        }
        view.onClearHistory = { showClearHistoryDialog() }
        view.onClearSearchHistory = { showClearSearchDialog() }
        view.onExportBackup = { backupExportLauncher.launch("puretube-backup.json") }
        view.onImportBackup = {
            backupImportLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        view.onOpenSource = { openUrl("https://github.com/luxysiv/PureTube") }
        view.onOpenLicense = { openUrl("https://www.gnu.org/licenses/gpl-3.0.html") }
        return view
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        observeSettingsState()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::settingsView.isInitialized) settingsView.updateLayoutMetrics()
    }

    private fun observeSettingsState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.playerState.collect { player ->
                    currentState = player.toSettingsState()
                    settingsView.setState(currentState)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Hộp thoại
    // ------------------------------------------------------------------

    private fun showSleepTimerDialog() {
        val options = listOf(
            0 to getString(R.string.settings_off),
            15 to "15 phút",
            30 to "30 phút",
            45 to "45 phút",
            60 to "60 phút"
        )
        val selected = options.indexOfFirst { it.first == currentState.sleepTimerMinutes }
            .coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_sleep_title)
            .setSingleChoiceItems(
                options.map { it.second }.toTypedArray(),
                selected
            ) { dialog, which ->
                viewModel.setSleepTimer(options[which].first)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.settings_close, null)
            .show()
    }

    private fun qualityOptions(): List<Pair<String, Int?>> {
        // Danh sách thật của video hiện tại + mức đã lưu cho từng loại mạng.
        // Không bịa thêm 1080/720/480/360 vì nguồn có thể không có các mức đó.
        val heights = (
            currentState.availableQualities +
                currentState.wifiQualityHeight +
                currentState.mobileQualityHeight
            )
            .filter { it > 0 }
            .distinct()
            .sortedDescending()
        return buildList {
            add(getString(R.string.settings_auto) to null)
            heights.forEach { add("${it}p" to it) }
        }
    }

    private fun showQualityDialog(isWifi: Boolean) {
        val options = qualityOptions()
        val currentHeight = if (isWifi) {
            currentState.wifiQualityHeight
        } else {
            currentState.mobileQualityHeight
        }
        val selected = options.indexOfFirst { it.second == currentHeight }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(
                if (isWifi) R.string.settings_wifi_quality else R.string.settings_mobile_quality
            )
            .setSingleChoiceItems(
                options.map { it.first }.toTypedArray(),
                selected
            ) { dialog, which ->
                val height = options[which].second
                if (isWifi) viewModel.setWifiQuality(height) else viewModel.setMobileQuality(height)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.settings_close, null)
            .show()
    }

    private fun showSpeedDialog() {
        val speeds = listOf(
            0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f,
            2.0f, 2.25f, 2.5f, 2.75f, 3.0f
        )
        showSpeedChoiceDialog(
            title = getString(R.string.settings_speed),
            values = speeds,
            selected = currentState.playbackSpeed,
            onSelect = viewModel::setPlaybackSpeed
        )
    }

    private fun showPitchDialog() {
        val pitches = listOf(0.85f, 0.9f, 0.95f, 1.0f, 1.05f, 1.1f, 1.15f)
        showSpeedChoiceDialog(
            title = getString(R.string.settings_pitch),
            values = pitches,
            selected = currentState.playbackPitch,
            onSelect = viewModel::setPlaybackPitch
        )
    }

    private fun showSpeedChoiceDialog(
        title: String,
        values: List<Float>,
        selected: Float,
        onSelect: (Float) -> Unit
    ) {
        val labels = values.map { "${Formatters.speed(it)}x" }
        val selectedIndex = values.indexOfFirst { it == selected }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), selectedIndex) { dialog, which ->
                onSelect(values[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.settings_close, null)
            .show()
    }

    private fun showSponsorCategoryDialog(category: SponsorCategory) {
        val modes = SponsorMode.entries
        val current = currentState.sponsorCategoryModes[category.apiName] ?: category.defaultMode
        val selected = modes.indexOf(current).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(category.label)
            .setSingleChoiceItems(
                modes.map { it.label }.toTypedArray(),
                selected
            ) { dialog, which ->
                viewModel.setSponsorCategoryMode(category, modes[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.settings_close, null)
            .show()
    }

    private fun showSponsorApiDialog() {
        val container = LayoutInflater.from(this)
            .inflate(R.layout.view_settings_sponsor_api_dialog, null) as View
        val input = container.findViewById<EditText>(R.id.settings_sponsor_api_input)
        input.setText(currentState.sponsorApiUrl)
        input.setHint(SponsorBlockDataSource.DEFAULT_API_URL)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_sponsor_api_title)
            .setView(container)
            .setPositiveButton(R.string.settings_save) { _, _ ->
                viewModel.setSponsorApiUrl(input.text.toString().trim())
            }
            .setNegativeButton(R.string.settings_cancel, null)
            .show()
    }

    private fun showClearWhitelistDialog() {
        confirmDialog(
            title = R.string.settings_clear_whitelist_title,
            message = R.string.settings_clear_whitelist_message,
            confirm = R.string.settings_delete
        ) { viewModel.clearSponsorWhitelist() }
    }

    private fun showClearHistoryDialog() {
        confirmDialog(
            title = R.string.settings_clear_history_title,
            message = R.string.settings_clear_history_message,
            confirm = R.string.settings_clear_history_confirm
        ) { viewModel.clearWatchHistory() }
    }

    private fun showClearSearchDialog() {
        confirmDialog(
            title = R.string.settings_clear_search_title,
            message = R.string.settings_clear_search_message,
            confirm = R.string.settings_delete
        ) { viewModel.clearSearchHistory() }
    }

    private fun confirmDialog(
        title: Int,
        message: Int,
        confirm: Int,
        onConfirm: () -> Unit
    ) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(confirm) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.settings_cancel, null)
            .show()
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
}

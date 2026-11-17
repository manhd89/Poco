package com.luxysiv.puretube.ui.settings

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.luxysiv.puretube.data.database.SubscriptionEntity
import com.luxysiv.puretube.data.database.VideoEntity
import com.luxysiv.puretube.data.local.AppSettings
import com.luxysiv.puretube.data.local.BackupManager
import com.luxysiv.puretube.error.AppError
import com.luxysiv.puretube.error.getOrElse
import com.luxysiv.puretube.error.runAppCatching
import com.luxysiv.puretube.ui.base.PureTubeViewModel
import com.luxysiv.puretube.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel riêng cho trang cài đặt.
 *
 * Các cờ cài đặt vẫn đến từ [playerState] (nguồn sự thật là coordinator), còn
 * phần sao lưu/khôi phục và xoá dữ liệu được xử lý tại đây.
 */
class SettingsViewModel(application: Application) : PureTubeViewModel(application) {

    /** Xuất cài đặt + video đã lưu + lịch sử tìm kiếm + kênh đăng ký ra JSON. */
    fun exportBackup(context: Context, uri: Uri) {
        viewModelScope.launch {
            val videos = runAppCatching { videoDao.getAllVideosOnce() }
                .getOrElse(emptyList())
            val subscriptions = runAppCatching {
                subscriptionRepository.getSubscriptionsForBackup()
            }.getOrElse(emptyList())
            val json = BackupManager.export(
                settings = settings,
                videos = videos,
                recentSearches = searchHistoryStore.load(),
                subscriptions = subscriptions
            )
            val write = withContext(Dispatchers.IO) {
                runAppCatching {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("Không mở được tệp đích")
                }
            }
            if (write.isSuccess) {
                postMessage(getApplication<Application>().getString(R.string.backup_export_success))
            } else {
                postMessage(getApplication<Application>().getString(R.string.backup_export_failed))
            }
        }
    }

    /** Khôi phục cài đặt + video đã lưu + lịch sử tìm kiếm + kênh đăng ký từ JSON. */
    fun importBackup(context: Context, uri: Uri) {
        viewModelScope.launch {
            val raw = withContext(Dispatchers.IO) {
                runAppCatching {
                    context.contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }
                }
            }
            if (raw.isFailure) {
                postError(raw.errorOrNull() ?: AppError.Storage())
                return@launch
            }
            // Kiểm tra toàn bộ tệp trước khi chạm vào dữ liệu người dùng.
            val payload = raw.getOrNull()?.let { BackupManager.parse(it) }
            if (payload == null) {
                postError(AppError.InvalidBackup())
                return@launch
            }

            // Chụp trạng thái hiện tại để hoàn tác nếu ghi dở dang.
            val previousSettings = settings
            val previousSearches = searchHistoryStore.load()
            val previousVideos = runAppCatching { videoDao.getAllVideosOnce() }
                .getOrElse(emptyList())
            val previousSubscriptions = runAppCatching {
                subscriptionRepository.getSubscriptionsForBackup()
            }.getOrElse(emptyList())

            val restored = runAppCatching {
                if (payload.videos.isNotEmpty()) {
                    videoDao.insertAll(payload.videos)
                }
                subscriptionRepository.restoreSubscriptions(payload.subscriptions)
                searchHistoryStore.replaceAll(payload.recentSearches)
                // Coordinator tự áp cài đặt vào player, SponsorBlock và network quality.
                playbackController.replaceSettings(payload.settings)
            }

            if (restored.isFailure) {
                rollbackImport(
                    previousSettings,
                    previousSearches,
                    previousVideos,
                    previousSubscriptions
                )
                postMessage(
                    getApplication<Application>()
                        .getString(R.string.backup_import_rollback)
                )
                return@launch
            }
            postMessage(getApplication<Application>().getString(R.string.backup_import_success))
        }
    }

    /**
     * Trả video/cài đặt/lịch sử tìm kiếm/kênh đăng ký về ảnh chụp trước khi import.
     *
     * Chỉ là best-effort: nếu chính bước hoàn tác lỗi thì vẫn báo lỗi import cho
     * người dùng thay vì crash, và dữ liệu còn lại vẫn nhất quán theo từng bảng.
     */
    private suspend fun rollbackImport(
        previousSettings: AppSettings,
        previousSearches: List<String>,
        previousVideos: List<VideoEntity>,
        previousSubscriptions: List<SubscriptionEntity>
    ) {
        runAppCatching {
            val previousIds = previousVideos.mapTo(HashSet()) { it.videoId }
            // Bản ghi mới thêm bởi bản sao lưu không có trong ảnh chụp -> xoá.
            videoDao.getAllVideosOnce()
                .filterNot { it.videoId in previousIds }
                .forEach { videoDao.delete(it.videoId) }
            // Bản ghi bị REPLACE -> ghi đè lại đúng ảnh chụp cũ.
            previousVideos.forEach { videoDao.insertOrUpdate(it) }
            // Danh sách kênh ghi đè hoàn toàn để không giữ kênh mới từ bản sao lưu.
            subscriptionRepository.replaceSubscriptions(previousSubscriptions)
            searchHistoryStore.replaceAll(previousSearches)
            playbackController.replaceSettings(previousSettings)
        }
    }
}

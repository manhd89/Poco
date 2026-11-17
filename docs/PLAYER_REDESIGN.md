# Thiết kế lại Player của PureTube

- **Ngày:** 04/11/2026
- **Trạng thái:** giai đoạn 1–3 đã triển khai; giai đoạn 4 bỏ qua theo D12; giai đoạn 5 chưa bắt đầu
- **Phạm vi:** tầng phát (player module), không bao gồm feed/search/comments/library
- **Mốc tham chiếu:** Media3 `1.11.1`, commit `cc72f27`
- **Cập nhật 1.7.5:** SponsorBlock và cao độ vẫn hoạt động đầy đủ, nhưng các mục điều khiển đã được bỏ khỏi bảng cài đặt trong trình phát; cấu hình chỉ còn trong Cài đặt chung. Marker và nút Bỏ qua/Hoàn tác trên trình phát vẫn giữ nguyên.
- **Cập nhật 1.7.6:** Bỏ mục PiP khỏi bảng cài đặt trong trình phát (đã có nút PiP riêng trên thanh điều khiển). Mặc định mới cho cài đặt chung: phát nền bật, tự chuyển PiP tắt.
- **Cập nhật 1.8.0:** Client `VISIONOS` lấy link phát ổn định hơn (GAPIS + visitorData + `cpn`), giữ iOS/Android/Android VR làm dự phòng. Đổi chất lượng chỉ đặt mức trần thay vì dựng lại media source, nên không còn khựng. Phụ đề không phụ thuộc trạng thái bật/tắt: menu luôn liệt kê ngôn ngữ. Mỗi trang tách thành Activity/ViewModel riêng.
- **Cập nhật 1.8.1:** Sửa chọn chất lượng: mức chọn là **đích** (`min = max`) thay vì chỉ mức trần, nên 1080p/1440p/2160p đổi đúng cả khi đang phát thấp hơn; nguồn thiếu đúng mức (HLS/decoder) tự nới về mức trần. Đổi chất lượng có thể khựng ngắn khi nạp representation mới nhưng không tua lại vị trí.
- **Cập nhật 1.8.3:** HUD dùng lại bố cục YouTube: nút play và vòng loading luôn đồng tâm (kể cả chế độ nhúng), icon chuyển sang bộ nét tròn Hugeicons (MIT) đồng nhất toàn app.
- **Cập nhật 1.8.3b:** Cụm play trung tâm luôn nằm đúng tâm video ở mọi hướng (kể cả dọc) thay vì canh giữa lại khoảng trống giữa hai thanh; phản hồi double-tap được làm lại kiểu YouTube: vòng tròn tối chứa icon Hugeicons theo hướng (tua 10 giây trái/phải, play/pause ở giữa) kèm dòng chữ dưới icon.
- **Cập nhật 2.0.0:** Giao diện chuyển hoàn toàn sang XML View native (bỏ Compose); trình phát hợp nhất vào `PageActivity` dưới dạng lớp phủ trong cửa sổ, thu nhỏ/mở rộng bằng cử chỉ, dùng chung surface cho fullscreen, mini player và PiP; hợp nhất mô hình lỗi, ánh xạ dữ liệu, cache và hàng đợi phát.
- **Tài liệu liên quan:** [`TUBULAR_RESEARCH.md`](./TUBULAR_RESEARCH.md)

---

## 1. Kết luận điều hành

Player hiện tại chạy được nhưng đang gánh ba vai trò cùng lúc:

1. **UI orchestration** — `MainViewModel` vừa quản lý feed/search/comments vừa quyết định phát bài nào, hàng đợi, mix, retry, SponsorBlock, lịch sử.
2. **Media session host** — `PlaybackService` dựng `MediaSession` nhưng UI lại điều khiển thẳng `engine.player`, không qua session.
3. **Playlist giả lập** — ExoPlayer chỉ giữ **một** `MediaItem`; next/previous, notification, metadata xe đều phải vá bằng callback và `SessionCommand`.

Hướng đã duyệt là **đưa player về đúng mô hình Media3**:

- ExoPlayer sở hữu **playlist thật** (cửa sổ 3 bài: trước – hiện tại – kế tiếp).
- Một `PlaybackCoordinator` cấp process sở hữu toàn bộ logic phát, queue, mix, related, SponsorBlock, lịch sử, retry.
- UI chỉ **đọc StateFlow** và **gửi command**.
- `PlaybackService` chỉ còn là host của `MediaSession` + notification.
- **Không bọc `ForwardingPlayer`** — đây là thứ từng làm hỏng metadata Bluetooth/AVRCP.

Mục tiêu cuối cùng: notification, tai nghe Bluetooth, màn hình khóa, Android Auto/AVRCP và phát nền hoạt động bằng **lệnh native của Media3**, không còn workaround.

---

## 2. Chẩn đoán hiện trạng

### 2.1. Số liệu

| File | Dòng | Vai trò hiện tại |
|---|---:|---|
| `app/src/main/java/com/luxysiv/puretube/ui/MainViewModel.kt` | ~2240 | Feed, search, comments, library, **và toàn bộ playback** |
| `app/src/main/java/com/luxysiv/puretube/ui/player/PlayerDetailSheet.kt` | ~2856 | Trang xem, bottom sheet, queue, mix, comments |
| `app/src/main/java/com/luxysiv/puretube/ui/player/PureVideoPlayerView.kt` | ~1631 | HUD, timeline, settings sheet, overlay lỗi |
| `app/src/main/java/com/luxysiv/puretube/player/PlaybackEngine.kt` | ~508 | ExoPlayer, candidate fallback, callback `var` |
| `app/src/main/java/com/luxysiv/puretube/player/PlaybackService.kt` | ~212 | `MediaSessionService`, custom command, `addSession` |
| `app/src/main/java/com/luxysiv/puretube/player/PurePlayerState.kt` | ~119 | Trộn player + settings + sponsor + network + library |

### 2.2. Vấn đề cụ thể

| Vấn đề | Biểu hiện trong code | Hệ quả |
|---|---|---|
| **God ViewModel** | `MainViewModel` chứa `playVideo`, `playNextVideo`, `playPreviousVideo`, queue, mix, SponsorBlock, history, retry | Khó test; sửa player dễ vỡ feed/search; logic chết theo `viewModelScope` |
| **Callback mutable** | `PlaybackEngine.onEnded`, `onIsPlayingChanged`, `onBufferingChanged`, `onNextRequested`, `onPreviousRequested` là `var`; `onCleared()` set `null` | Auto-next/SponsorBlock/history phụ thuộc ViewModel còn sống; xoay màn hình hoặc thoát UI là mất logic |
| **Playlist giả lập** | `PlaybackEngine.loadCandidate()` luôn `player.setMediaItem(item)` | Không có queue thật; `seekToNext/Previous` không dùng được; notification phải tự thêm nút |
| **Hai hệ state** | `player.isPlaying/playbackState` và `_playerState.isPlaying/isBuffering` cập nhật thủ công | Dễ lệch trạng thái; recompose thừa; bug "đang phát nhưng UI báo dừng" |
| **Retry hai tầng** | Engine tự thử candidate; ViewModel backoff mạng 1s → 3s → 6s → 12s | Khó biết lỗi thật do URL, do mạng hay do nguồn dữ liệu |
| **Workaround notification** | `SessionCommand` + `CommandButton` + `onCustomCommand` + `onMediaButtonEvent` | Đúng chức năng nhưng là triệu chứng của việc thiếu playlist/controller chuẩn |
| **Queue nằm trong UI** | `_queue`, `queueCycle`, `playStack`, `_mixPlaylist`, `_relatedVideos`, `_trendingVideos` đều ở ViewModel | Service/notification không biết bài kế; phát nền không tự chuyển bài đúng |
| **State trộn** | `PurePlayerState` chứa cả `isAutoPiPEnabled`, `sponsorApiUrl`, `hideComments`, `isFavorite`, `wifiQualityLabel`... | Không rõ nguồn sự thật; khó chia nhỏ UI |
| **UI quá lớn** | `PlayerDetailSheet` ~2856 dòng, `PureVideoPlayerView` ~1631 dòng, ~50 callback tham số | Khó bảo trì, khó test, khó thêm tính năng |

### 2.3. Vì sao notification từng không hiện

UI dùng trực tiếp `PlaybackEngineProvider.get().player`, **không có `MediaController` nào kết nối** vào `PlaybackService`. Media3 chỉ tạo "media notification controller" khi session được `addSession()`. Vì vậy `PlaybackService.onCreate()` phải gọi `addSession(session)` (đã sửa ở `cc72f27`).

Đây là bản vá đúng, nhưng về dài hạn nó chỉ giải quyết **triệu chứng**. Gốc rễ là player chưa có playlist thật và chưa có ranh giới session/UI rõ ràng.

---

## 3. Nguyên tắc thiết kế

1. **ExoPlayer playlist là nguồn sự thật** về “bài đang phát / bài kế tiếp / bài trước”, không phải `_queue` trong ViewModel.
2. **Logical queue** (user queue → mix → related → trending, repeat, history) nằm trong `PlaybackQueue` thuộc player module.
3. **Không bọc `ForwardingPlayer`.** `MediaSession` phải bọc **raw `engine.player`** để legacy session (Bluetooth/AVRCP) đọc đúng metadata.
4. **UI chỉ đọc StateFlow và gửi command.** Không tự gọi `player.setMediaItem`, `player.stop`, `player.seekToNext`...
5. **Service chỉ là host.** Dựng `MediaSession`, `addSession`, notification, foreground lifecycle; không chứa logic queue.
6. **Player sống ở cấp process.** Logic auto-next/SponsorBlock/history phải chạy được khi UI đã chết.
7. **Chỉ resolve trước 1 bài.** URL YouTube có hạn; không resolve cả queue.
8. **Giữ nguyên hành vi người dùng.** Resume, mix, queue, SponsorBlock, PiP, sleep timer, phát nền không được regress.

---

## 4. Kiến trúc đích

```
┌────────────────────────────── App process ──────────────────────────────┐
│                                                                        │
│  Compose UI ──▶ PlaybackController (facade)                            │
│                      │ commands              ▲ StateFlow               │
│                      ▼                       │                         │
│              PlaybackCoordinator ────────────┘                         │
│                      │                                                 │
│                      ├─ PlaybackQueue          (logical queue)         │
│                      ├─ PlaybackQueueManager   (playlist window)       │
│                      ├─ PlaybackResolver       (VideoItem → MediaItem) │
│                      ├─ SponsorBlockController                         │
│                      ├─ PlaybackHistoryRepository                     │
│                      └─ PlaybackEngine         (1 ExoPlayer)           │
│                                                                        │
│  PlaybackService ── MediaSession(engine.player) ── Notification/AVRCP  │
└────────────────────────────────────────────────────────────────────────┘
```

### 4.1. Trách nhiệm từng module

| Module | Trách nhiệm | Không làm |
|---|---|---|
| `PlaybackController` | Facade mỏng cho ViewModel/UI: command + StateFlow | Không chứa logic |
| `PlaybackCoordinator` | Orchestration: play/next/previous, listener, auto-next, retry, sleep timer, state | Không dựng UI, không đọc Compose |
| `PlaybackQueue` | Logical queue: user queue, mix, related, trending, repeat, playStack, strategy next/previous | Không giữ `MediaItem`, không gọi ExoPlayer |
| `PlaybackQueueManager` | Cửa sổ playlist `[previous, current, next]`, prefetch, map `mediaId ↔ VideoItem`, candidate fallback | Không quyết định bài kế về mặt logic |
| `PlaybackResolver` | `VideoItem` → `PlaybackPlan` + `MediaItem` (metadata, subtitle, candidates) | Không giữ state UI |
| `PlaybackEngine` | Cấu hình ExoPlayer, audio attributes, HTTP data source, seek parameters | Không biết queue/mix/UI |
| `SponsorBlockController` | Tải đoạn, auto skip, manual skip, unskip, grace rewind | Không phụ thuộc ViewModel |
| `PlaybackHistoryRepository` | Ghi lịch sử/tiến độ, resume position, favorite/watch-later flags | Không quyết định phát |
| `PlaybackService` | `MediaSession`, `addSession`, notification, foreground, `onTaskRemoved` | Không chứa queue/auto-next |

### 4.2. Ranh giới UI ↔ player

UI hiện tại nhận `player: Player` và nhiều callback. Giai đoạn 1–3 giữ nguyên chữ ký này, nhưng nguồn dữ liệu chuyển sang coordinator:

- `player` vẫn là `engine.player` (đọc trực tiếp cho timeline/seek).
- Các callback `onNext`, `onTogglePlayPause`... delegate sang `PlaybackController`.
- State đọc từ các flow mới thay vì `PurePlayerState` khổng lồ.

Giai đoạn 4 (tuỳ chọn) có thể truyền `MediaController` vào cùng chữ ký `Player` mà **không sửa UI**, vì `MediaController` implements `Player`.

---

## 5. Mô hình trạng thái

`PurePlayerState` hiện trộn 5 nhóm. Tách thành các flow riêng, mỗi flow chỉ cập nhật khi đúng nhóm dữ liệu thay đổi.

### 5.1. `PlaybackUiState`

```kotlin
data class PlaybackUiState(
    val video: VideoItem? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isLive: Boolean = false,
    val durationMs: Long = 0L,
    val availableQualities: List<Int> = emptyList(),
    val selectedQuality: String = "Tự động",
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val skipSilence: Boolean = false,
    val captionsEnabled: Boolean = false,
    val captionLanguageTag: String = "",
    val isWaitingForNetwork: Boolean = false,
    val isAutoNextEnabled: Boolean = true,
    val isResumeEnabled: Boolean = true,
    val sleepTimerMinutes: Int = 0,
    val failure: PlaybackFailure? = null
)

data class PlaybackFailure(
    val message: String,
    val detail: String? = null,
    val canRetry: Boolean = true
)
```

### 5.2. `QueueUiState`

```kotlin
data class QueueUiState(
    val queue: List<VideoItem> = emptyList(),
    val mix: MixPlaylist? = null,
    val related: List<VideoItem> = emptyList(),
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false
)
```

### 5.3. `SponsorUiState`

```kotlin
data class SponsorUiState(
    val segments: List<SponsorSegment> = emptyList(),
    val manualSegment: SponsorSegment? = null,
    val lastSkipped: SponsorSegment? = null,
    val canUnskip: Boolean = false,
    val isCurrentChannelWhitelisted: Boolean = false
)
```

### 5.4. Các state còn lại

| Nhóm | Nguồn | Ghi chú |
|---|---|---|
| `PlaybackPosition` | Coordinator (ticker 500 ms) | Giữ nguyên, chỉ collect ở player/mini player |
| `AppSettings` / `SettingsUiState` | `SettingsStore` | `backgroundPlay`, `autoPiP`, `hideComments`, `qualityHeight`... không thuộc player state |
| `LibraryFlags` | Room | `isFavorite`, `isWatchLater`, `watchProgress` |
| `WatchPageState` | ViewModel | `videoDetails`, comments, replies — **không** chuyển vào player module |

### 5.5. Bảng di trú field

| Field hiện tại trong `PurePlayerState` | Chủ sở hữu mới |
|---|---|
| `currentVideo` | `PlaybackUiState.video` |
| `isPlaying`, `isBuffering` | `PlaybackUiState` |
| `playbackSpeed`, `playbackPitch`, `isSkipSilenceEnabled` | `PlaybackUiState` |
| `isFullscreen` | UI state (Compose), không thuộc player |
| `isBackgroundPlayEnabled`, `isAutoPiPEnabled`, `isResumeEnabled` | `AppSettings` |
| `isAutoNextEnabled` | `PlaybackUiState` (vì coordinator cần) |
| `isSponsorBlockEnabled`, `sponsorSegments`, `sponsorLastSkipped`, `sponsorCanUnskip`, `sponsorManualSegment`, `sponsorCategoryModes`, `sponsorNotify`, `sponsorShowSkipButton`, `sponsorGracefulRewind`, `sponsorApiUrl`, `sponsorWhitelistCount`, `isCurrentChannelSponsorWhitelisted` | `SponsorUiState` + `AppSettings` (phần cấu hình) |
| `sleepTimerMinutes` | `PlaybackUiState` |
| `isFavorite`, `isWatchLater` | `LibraryFlags` |
| `selectedQuality`, `wifiQualityLabel`, `mobileQualityLabel`, `isDataSaverEnabled`, `isMeteredNetwork` | `AppSettings` + `PlaybackUiState` (chỉ `selectedQuality`) |
| `availableQualities` | `PlaybackUiState` |
| `captionsEnabled`, `captionLanguageTag` | `PlaybackUiState` |
| `isWaitingForNetwork` | `PlaybackUiState` |
| `hideComments`, `hideRelated`, `hideDescription` | `AppSettings` |

> Lưu ý: trong giai đoạn chuyển tiếp, có thể giữ `PurePlayerState` như một **view model tổng hợp** được build từ các flow mới. UI migrate dần; xoá `PurePlayerState` khi tất cả màn hình đã chuyển.

---

## 6. Mô hình playlist thật

### 6.1. Cửa sổ 3 bài

`PlaybackQueueManager` giữ playlist ExoPlayer ở dạng:

```
index:   0          1           2
item:  previous   current      next
```

- `current` là bài đang phát.
- `previous` là bài vừa phát (đã resolve sẵn).
- `next` là bài kế tiếp (resolve trước).
- Khi chưa có previous/next, playlist có thể chỉ còn `[current]` hoặc `[current, next]`.

### 6.2. Vì sao dùng cửa sổ 3 bài

`Player.seekToPrevious()` của Media3 đã cài đúng hành vi YouTube:

```java
// MediaSessionStub → Player.seekToPrevious()
if (currentPosition > maxSeekToPreviousPositionMs) {
    seekTo(0);
} else if (hasPreviousMediaItem()) {
    seekToPreviousMediaItem();
}
```

`maxSeekToPreviousPositionMs` mặc định là **3000 ms**. Vì vậy:

- Đang xem quá 3 giây → bấm previous → về đầu bài hiện tại.
- Đang ở đầu bài → bấm previous → sang bài trước trong playlist.

Không cần tự viết lại logic 3 giây, không cần `SessionCommand`, không cần bọc player.

### 6.3. Cập nhật cửa sổ khi chuyển bài

Không dùng `setMediaItems()` khi đang phát vì sẽ làm gián đoạn item hiện tại. Thay vào đó:

1. Khi `onMediaItemTransition` báo bài mới, xác định `previous` (bài vừa phát) và `next` (từ `PlaybackQueue`).
2. Xoá các item **sau** current nếu không còn đúng.
3. Chèn `previous` vào **trước** current (index 0). Thao tác này không làm dừng current, chỉ dịch index.
4. Resolve `next` bất đồng bộ rồi `addMediaItem()` vào cuối.
5. Nếu resolver chậm, playlist tạm thời là `[previous, current]`; nút next trên notification sẽ mất cho tới khi resolve xong. Chấp nhận được trong 1–3 giây; UI hiện trạng thái "đang chuẩn bị bài kế".

> Khi thêm item trước current, phải dùng `player.addMediaItem(index, item)` hoặc `moveMediaItem`; tuyệt đối không gọi `setMediaItems` lên toàn bộ playlist đang phát.

### 6.4. Prefetch

- Resolve `next` ngay khi current bắt đầu phát.
- Nếu thời lượng current > 60 giây, có thể resolve lại/kiểm tra `next` khi còn ~30 giây để URL còn hạn.
- Khi user bấm next trước khi resolve xong: coordinator hiện buffering, chờ resolver, rồi `player.seekToNext()` nếu item đã có; nếu chưa, `play(target)` trực tiếp.
- Không resolve quá 1 bài trước. URL YouTube hết hạn nhanh; resolve nhiều là vô ích và tốn pin/mạng.

### 6.5. Repeat và auto-next

- `player.repeatMode = Player.REPEAT_MODE_OFF` **luôn luôn**. Repeat là logic của coordinator, không để ExoPlayer tự lặp.
- `player.pauseAtEndOfMediaItems = !autoNextEnabled`:
  - Auto-next bật → ExoPlayer tự chuyển sang `next` khi hết bài (native, gapless).
  - Auto-next tắt → ExoPlayer dừng ở cuối bài hiện tại; user vẫn bấm next thủ công được.
- `RepeatMode.ONE` → coordinator `seekTo(0)` + `play()` khi hết bài, không đưa vào playlist repeat của ExoPlayer.
- `RepeatMode.ALL` → khi `PlaybackQueue` hết bài, quay vòng `queueCycle` (giữ hành vi hiện tại).
- Sleep timer dừng phát → coordinator đặt cờ `suppressAutoAdvance`; nếu cần, xoá `next` khỏi playlist để ExoPlayer không tự chạy tiếp.

### 6.6. Candidate fallback

Mỗi `VideoItem` có thể có nhiều `PlaybackSource` (DASH → HLS → progressive). `PlaybackResolver` giữ map:

```kotlin
data class ResolvedMedia(
    val video: VideoItem,
    val plan: PlaybackPlan,
    val candidateIndex: Int = 0,
    val startPositionMs: Long = 0L
)
```

Khi `onPlayerError`:

1. Nếu còn candidate cho `mediaId` hiện tại:
   - Lấy vị trí hiện tại.
   - Build `MediaItem` mới từ candidate kế.
   - `player.replaceMediaItem(currentIndex, newItem)` (hoặc remove + add tại đúng index).
   - `player.seekTo(positionMs)`, `prepare()`, `play()`.
2. Nếu hết candidate:
   - `PlaybackUiState.failure = ...`
   - Kích hoạt retry backoff hoặc "chờ mạng".

Không để `PlaybackEngine` tự quyết định candidate; engine chỉ là cấu hình ExoPlayer.

---

## 7. Luồng phát

### 7.1. `play(video, origin)`

1. `PlaybackController.play(video, origin)` → `PlaybackCoordinator`.
2. Huỷ resolve/retry cũ; `player.pause()` (không `stop`, để notification/service còn sống).
3. `PlaybackService.start(context)`.
4. `PlaybackQueue.onPlayStarted(video, origin)`:
   - Nếu video đang nằm trong user queue → bỏ khỏi queue (tránh phát lặp).
   - Nếu origin không phải `NEXT/PREVIOUS` → push video cũ vào `playStack`.
   - Nếu video thuộc mix hiện tại → giữ mix; nếu không → clear mix + bắt đầu tải mix mới.
   - Reset `related`.
5. `PlaybackHistoryRepository.onStarted(video)`:
   - Đọc `watchProgress`.
   - Tính `resumeSeconds` (bỏ qua nếu đã xem gần hết, hoặc resume tắt).
   - Ghi lịch sử với `lastWatchedTimestamp`, giữ favorite/watch-later.
6. `PlaybackResolver.resolve(video)`:
   - Gọi `YouTubeDataRepository.getPlaybackData(video)`.
   - Trả `PlaybackPlan`, `related`, `details`.
7. Cập nhật `PlaybackUiState.video`, `isBuffering = true`, qualities, details.
8. `PlaybackQueueManager.loadCurrent(video, plan, startPositionMs)`:
   - Build `MediaItem` với `mediaId = video.id`, metadata title/artist/artwork, subtitle.
   - Đặt làm item hiện tại; nếu có previous thì chèn trước.
   - `prepare()`, `seekTo(resume)`, `play()`.
9. `PlaybackCoordinator` khởi động ticker + SponsorBlock load.
10. `PlaybackQueueManager.prefetchNext()` resolve bài kế.

### 7.2. `next(force)`

```
if (!force && !autoNextEnabled) return
target = queue.next(current)
if (target == null):
    if (repeat == ALL && queueCycle not empty) target = queueCycle.first()
    else -> dừng / giữ trạng thái ENDED
if (player.hasNextMediaItem() && player.nextMediaItem.mediaId == target.video.id):
    player.seekToNext()          // native, không resolve lại
else:
    coordinator.play(target, origin = NEXT)
```

- Nếu mix còn trang (`hasMore`) và chưa có bài kế: coordinator gọi `queue.loadMoreMix()` (suspend), rồi mới phát.
- Nếu mix lỗi/hết: fallback related → trending.
- Khi `seekToNext()` native chạy, `onMediaItemTransition` cập nhật logical queue, history, SponsorBlock, prefetch.

### 7.3. `previous()`

```
if (player.hasPreviousMediaItem()):
    player.seekToPrevious()      // Media3 tự xử lý 3 giây
else:
    previous = queue.previous(current)   // mix index - 1 → playStack
    if (previous != null) coordinator.play(previous, origin = PREVIOUS)
    else player.seekTo(0)
```

- Vì cửa sổ có `previous`, trường hợp thường dùng là native `seekToPrevious()`.
- `queue.previous()` giữ đúng thứ tự mix hiện tại; nếu không có mix thì lấy `playStack`.
- Không tự viết lại ngưỡng 3 giây; `maxSeekToPreviousPositionMs` giữ mặc định.

### 7.4. `onMediaItemTransition`

```kotlin
override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
    val video = queueManager.videoFor(mediaItem?.mediaId) ?: return
    when (reason) {
        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> {
            if (!autoNextEnabled || suppressAutoAdvance) {
                player.pause()
                return
            }
            history.onEnded(previousVideo)
            queue.onAdvancedTo(video)
        }
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> {
            queue.onSeekedTo(video)
        }
    }
    updateState(video)
    sponsor.resetFor(video.id)
    sponsor.load(video.id)
    queueManager.prefetchNext()
    history.onStarted(video)
    startTicker()
}
```

- `reason == AUTO` là auto-next native.
- `reason == SEEK` là user bấm next/previous hoặc chọn bài.
- Luôn reset SponsorBlock theo `mediaId` để tránh skip nhầm.

### 7.5. `onPlaybackStateChanged(STATE_ENDED)`

- `RepeatMode.ONE` → `seekTo(0)` + `play()`.
- `RepeatMode.ALL` và còn `queueCycle` → phát bài đầu chu kỳ.
- `autoNextEnabled` → `next()` (có thể fetch mix).
- Ngược lại → giữ trạng thái kết thúc, không tự phát.

Với playlist thật, `STATE_ENDED` chỉ xảy ra khi hết cửa sổ playlist. Nếu còn bài kế, ExoPlayer tự chuyển và không báo `ENDED`.

### 7.6. Phát nền / UI chết

- `PlaybackCoordinator` là singleton cấp process, scope `SupervisorJob() + Dispatchers.Main.immediate`.
- SponsorBlock, history, auto-next, retry, sleep timer chạy trong scope này, **không** dùng `viewModelScope`.
- `PlaybackService` giữ foreground khi đang phát.
- Khi UI mở lại: coordinator đã có state; ViewModel chỉ collect flow và hiển thị.
- Khi process bị kill: `PlaybackService` có thể được tạo lại; coordinator khôi phục `nowPlaying`/queue từ `QueueStore` (tối thiểu queue + video hiện tại + vị trí đã lưu). Không tự phát nếu người dùng chưa bấm play.

### 7.7. Live stream

- Live vẫn dùng **một item** (`isLive = true`).
- Không thêm previous/next; ẩn nút next/previous trên UI và notification.
- Không auto-next; SponsorBlock có thể tắt cho live nếu dữ liệu không phù hợp (giữ hành vi hiện tại).
- Khi live kết thúc → `STATE_ENDED`, coordinator dừng; không fallback sang related.

### 7.8. SponsorBlock

`SponsorBlockController` chạy trong coordinator:

- `load(videoId)`: gọi `SponsorBlockDataSource`, lọc category theo `AppSettings`.
- Ticker 500 ms (hoặc `Player.Listener` + handler) kiểm tra vị trí:
  - Auto mode: vào đầu đoạn (trong ~2 giây) → `seekToExact(endMs)`.
  - Manual mode: cập nhật `manualSegment` để UI hiện nút "Bỏ qua".
  - Grace rewind: user tua lại vào đoạn đã skip → tạm ngừng auto skip tới khi ra khỏi đoạn.
  - Unskip: trong 5 giây sau khi skip → cho phép quay lại.
- Reset toàn bộ state khi `mediaId` đổi.
- Không phụ thuộc UI; chạy được khi màn hình tắt.

### 7.9. Retry / mất mạng

- `PlaybackNetworkMonitor` (hoặc `ConnectivityManager` trong coordinator) theo dõi mạng.
- Khi resolve lỗi hoặc player error hết candidate:
  - Không có mạng → `isWaitingForNetwork = true`, chờ callback mạng trở lại rồi retry ngay.
  - Có mạng → backoff 1s → 3s → 6s → 12s, sau đó dừng và hiện "Thử lại".
- Retry phải tôn trọng video hiện tại: nếu user đã đổi video, bỏ qua kết quả retry cũ (dùng `requestId`/`generation`).

---

## 8. Tích hợp Media3

### 8.1. `MediaSession` và notification

- `PlaybackService.onCreate()`:
  - `MediaSession.Builder(this, engine.player)` — **raw player**, không `ForwardingPlayer`.
  - `setSessionActivity(...)`, `setCallback(...)`.
  - `addSession(session)` — bắt buộc để Media3 tạo notification controller.
- `onGetSession()` trả session hiện tại.
- `onTaskRemoved()`: pause + stop + clear + `stopSelf()` (giữ hành vi hiện tại).
- `onDestroy()`: `mediaSession.release()`.

### 8.2. Nút notification

Khi playlist có previous/next, ExoPlayer quảng bá:

- `COMMAND_SEEK_TO_PREVIOUS`
- `COMMAND_SEEK_TO_NEXT`

`DefaultMediaNotificationProvider` tự thêm hai nút. Không cần:

- `SessionCommand` custom
- `CommandButton` / `setMediaButtonPreferences`
- `onCustomCommand`
- `onMediaButtonEvent` thủ công

→ Giai đoạn 2 xoá toàn bộ workaround này khỏi `PlaybackService`.

### 8.3. Metadata cho Bluetooth/AVRCP

Giữ **nguyên byte** cách build metadata hiện tại:

```kotlin
MediaMetadata.Builder()
    .setTitle(video.title)
    .setArtist(video.channelTitle)
    .apply {
        if (video.thumbnailUrl.isNotBlank()) {
            runCatching { setArtworkUri(Uri.parse(video.thumbnailUrl)) }
        }
    }
    .build()
```

- `mediaId = video.id`.
- Không đặt metadata rỗng/null cho item kế tiếp.
- Không bọc player, không override `getMediaMetadata()`.
- Test trên xe/Bluetooth thật sau mỗi giai đoạn.

### 8.4. `MediaController` (giai đoạn 4, tuỳ chọn)

Hiện UI dùng `engine.player` trực tiếp. Giai đoạn 4 có thể:

- UI build `MediaController` từ `SessionToken` của `PlaybackService`.
- Truyền `MediaController` vào `PlayerDetailSheet(player = controller, ...)` — không sửa UI vì `MediaController : Player`.
- Service sở hữu vòng đời player; UI không cần `PlaybackEngineProvider`.
- Lợi ích: tách hẳn UI/service, chuẩn Media3, hỗ trợ app khác điều khiển.
- Rủi ro: `MediaController` build bất đồng bộ, cần xử lý reconnect, latency command, và state ban đầu.

Chỉ làm nếu thật sự cần; giai đoạn 1–3 đã giải quyết notification/car/queue.

---

## 9. Cấu trúc file đề xuất

```
app/src/main/java/com/luxysiv/puretube/player/
  PlaybackService.kt              // session + notification + lifecycle
  PlaybackEngine.kt               // ExoPlayer config (thu gọn)
  PlaybackController.kt           // facade cho ViewModel/UI
  PlaybackCoordinator.kt          // orchestration, listener, auto-next, retry
  PlaybackQueue.kt                // logical queue + strategy
  PlaybackQueueManager.kt         // playlist window + prefetch + candidate fallback
  PlaybackResolver.kt             // VideoItem -> PlaybackPlan/MediaItem
  PlaybackState.kt                // PlaybackUiState, QueueUiState, SponsorUiState...
  SponsorBlockController.kt       // sponsor skip (background-safe)
  PlaybackHistoryRepository.kt    // history/progress/resume
  PlaybackNetworkMonitor.kt       // (tuỳ chọn) network callback
```

`MainViewModel` còn lại:

- Feed/trending/search.
- Comments/replies.
- Library/favorites/watch later.
- Settings/navigation.
- Delegate playback sang `PlaybackController`.

Không chuyển `VideoDetails`, comments, feed vào player module — chúng không thuộc vòng đời phát.

---

## 10. Lộ trình di trú

### Giai đoạn 0 — Ổn định hiện tại (đã xong)

| Việc | Commit | Kết quả |
|---|---|---|
| Khôi phục metadata Bluetooth, bỏ `ForwardingPlayer` | `16dd55f` | Metadata/AVRCP trở lại như 1.5.0 |
| Thêm nút previous/next bằng media button preferences | `16dd55f` | Notification có đủ nút |
| `addSession(session)` | `cc72f27` | Notification hệ thống hiện |

Đây là baseline; mọi thay đổi sau phải giữ được các hành vi này.

### Giai đoạn 1 — Extract (không đổi hành vi)

Mục tiêu: giảm ViewModel, tách state/resolver/queue/coordinator, test được.

1. Tạo `PlaybackState.kt` với các data class mới.
2. Tạo `PlaybackResolver`:
   - Chuyển logic build `MediaMetadata`, `SubtitleConfiguration`, `MediaItem` từ `PlaybackEngine.loadCandidate()`.
   - Chuyển candidate list/subtitle ra khỏi engine.
3. Tạo `PlaybackQueue`:
   - Chuyển `_queue`, `queueCycle`, `playStack`, `_mixPlaylist`, `_relatedVideos`, `_trendingVideos`, `_repeatMode`.
   - Chuyển `playQueuedNext`, `playNextVideo`, `playNextFallback`, `playPreviousVideo`, queue CRUD, repeat.
   - Viết unit test cho strategy next/previous/repeat/dedupe.
4. Tạo `SponsorBlockController`:
   - Chuyển `sponsorSegments`, `skippedSponsorSegments`, `gracedSponsorSegments`, `lastSponsorSkipKeys`, `skipSponsorIfNeeded`, `updateSponsorUi`, `loadSponsorSegments`, unskip.
5. Tạo `PlaybackHistoryRepository`:
   - Chuyển ticker history save, `watchProgress`, resume calculation.
6. Tạo `PlaybackCoordinator`:
   - Sở hữu `Player.Listener` duy nhất.
   - Thay `PlaybackEngine.onEnded/onIsPlayingChanged/...` bằng listener nội bộ.
   - Chuyển retry/backoff/network monitor.
   - Scope cấp process.
7. `MainViewModel` chỉ còn delegate + watch-page state.
8. Giữ `PurePlayerState` tạm thời; build nó từ flow mới để UI chưa phải sửa nhiều.

**Tiêu chí xong:** hành vi giống hệt trước; unit test queue/sponsor/resolver pass; ViewModel giảm đáng kể; không đổi notification/car.

### Giai đoạn 2 — Playlist thật + transport native

Mục tiêu: sửa gốc notification/car/queue.

1. Tạo `PlaybackQueueManager`:
   - Cửa sổ `[previous, current, next]`.
   - `loadCurrent`, `prefetchNext`, `updateWindow`, `replaceCandidate`.
2. `PlaybackCoordinator.next()` dùng `player.seekToNext()` khi item kế khớp logical queue.
3. `PlaybackCoordinator.previous()` dùng `player.seekToPrevious()`; fallback `queue.previous()`.
4. `onMediaItemTransition` cập nhật queue/history/sponsor/prefetch.
5. `player.repeatMode = OFF`; `pauseAtEndOfMediaItems = !autoNext`.
6. Xoá custom command/button/media button khỏi `PlaybackService`.
7. Giữ `addSession`, metadata, `onTaskRemoved`.

**Tiêu chí xong:**

- Notification có previous/play-pause/next **native**.
- Bấm next/previous đổi bài, metadata xe cập nhật.
- Previous > 3s về đầu bài; ≤ 3s sang bài trước.
- Không còn `SessionCommand`/`CommandButton` trong service.
- Car/AVRCP vẫn đúng metadata.

### Giai đoạn 3 — Background hardening

Mục tiêu: phát nền đúng nghĩa, không phụ thuộc UI.

1. Đảm bảo coordinator là singleton process, không `viewModelScope`.
2. SponsorBlock/history/auto-next/retry/sleep timer chạy khi UI chết.
3. Flush history khi pause/close/transition; tránh ghi đè 0.
4. Xử lý process death/service restart:
   - Khôi phục queue từ `QueueStore`.
   - Khôi phục `nowPlaying` + position đã lưu.
   - Không auto-play nếu chưa có lệnh người dùng.
5. Kiểm tra Android 13/14/15: `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `addSession`.

**Tiêu chí xong:** tắt màn hình/chuyển app → nhạc tiếp, tự chuyển bài, sponsor skip, history vẫn ghi; mở lại UI thấy đúng trạng thái.

### Giai đoạn 4 — `MediaController` (tuỳ chọn)

1. UI build `MediaController` từ `SessionToken`.
2. Service sở hữu player; bỏ `PlaybackEngineProvider` khỏi UI.
3. Xử lý reconnect, state ban đầu, latency.
4. Giữ nguyên chữ ký `Player` trong Compose.

Chỉ làm sau khi 1–3 ổn định.

### Giai đoạn 5 — Chia nhỏ UI/state

1. Xoá `PurePlayerState`; UI đọc `PlaybackUiState`/`QueueUiState`/`SponsorUiState`.
2. Tách `PlayerDetailSheet`:
   - `PlayerDetailSheet` (layout).
   - `PlayerMetaSection` (mô tả/bình luận).
   - `PlayerQueueSection` / `MixQueueSheet`.
3. Tách `PureVideoPlayerView`:
   - `PlayerHud`, `PlayerTimeline`, `PlayerSettingsSheet`, `PlaybackError`, `SeekFlash`.
4. Gom ~50 callback thành `PlayerActions` (data class) hoặc `PlaybackController` trực tiếp.
5. Tách `PlayerDetailSheet` khỏi comments state (truyền `WatchPageState`).

**Tiêu chí xong:** file < ~800 dòng, không đổi UI/UX.

---

## 11. Quyết định đã chốt

| ID | Quyết định | Lý do |
|---|---|---|
| D1 | Làm hướng đầy đủ, giai đoạn 1 → 3, tuỳ chọn 4 | Sửa gốc thay vì vá triệu chứng |
| D2 | Previous giữ hành vi YouTube (quá 3 giây về đầu bài) | Người dùng đã quen; Media3 hỗ trợ native |
| D3 | Queue/mix/related chuyển hẳn vào player module | UI không còn là nguồn sự thật; phát nền mới đúng |
| D4 | Không dùng `ForwardingPlayer` | Từng làm hỏng metadata Bluetooth/AVRCP |
| D5 | Cửa sổ playlist 3 bài `[previous, current, next]` | Giữ previous native, không phải tự viết logic |
| D6 | Metadata giữ nguyên byte so với 1.5.0 | Không regress car/AVRCP |
| D7 | Giai đoạn 2 xoá custom command/media button | Playlist thật đã quảng bá lệnh native |
| D8 | `player.repeatMode = OFF`, repeat do coordinator quyết | Tránh xung đột next thủ công và repeat one |
| D9 | Auto-next dùng `pauseAtEndOfMediaItems` khi tắt | Vẫn giữ nút next thủ công |
| D10 | Chỉ prefetch 1 bài | URL YouTube hết hạn, tiết kiệm pin/mạng |
| D11 | Coordinator cấp process, không theo ViewModel | Phát nền/auto-next/history không phụ thuộc UI |
| D12 | Chưa làm giai đoạn 4 nếu 1–3 đủ | Tránh tăng độ phức tạp không cần thiết |

---

## 12. Rủi ro và cách giảm

| Rủi ro | Mức | Cách giảm |
|---|---|---|
| URL prefetch hết hạn | Cao | Chỉ prefetch 1 bài; resolve lại khi `onPlayerError`; kiểm tra lại khi gần hết bài |
| Thao tác playlist làm gián đoạn current | Cao | Không `setMediaItems` khi đang phát; chỉ remove/insert quanh current; test kỹ transition |
| Previous item URL hết hạn | Trung bình | Giữ candidate map; re-resolve nếu lỗi; fallback `seekTo(0)` |
| Metadata car regress | Cao | Raw player, metadata giữ nguyên; test xe/Bluetooth mỗi giai đoạn |
| Repeat/auto-next sai | Trung bình | Ma trận test: OFF/ONE/ALL × autoNext on/off × queue rỗng/có bài |
| Mix pagination race | Trung bình | `generation`/`requestId`; bỏ kết quả nếu user đã đổi video |
| Sponsor skip nhầm sau chuyển bài | Trung bình | Reset state theo `mediaId`; bỏ load cũ |
| Process death mất state | Trung bình | Persist queue + current + position; không auto-play |
| Live stream vào playlist nhiều item | Thấp | Nhánh `isLive` riêng, một item |
| Notification Android 13+ | Trung bình | Giữ `addSession`; test permission + foreground service type |
| UI quá lớn khi migrate | Thấp | Làm giai đoạn 5 riêng, không trộn với logic |

---

## 13. Chiến lược kiểm thử

### 13.1. Unit test

- `PlaybackQueue`:
  - User queue ưu tiên hơn mix/related/trending.
  - `playNext` chèn đầu queue, dedupe.
  - `playQueueItem` bỏ các bài đã chọn khỏi queue.
  - `remove/move/shuffle/clear`.
  - `RepeatMode.OFF/ALL/ONE`.
  - `playStack` previous; mix index - 1.
- `PlaybackResolver`:
  - `MediaItem` có `mediaId`, title, artist, artwork, subtitle đúng.
  - Candidate order DASH tự dựng (video-only + audio) → HLS → progressive cho VOD; live bỏ qua manifest tự dựng vì không có duration cố định.
- `SponsorBlockController`:
  - Auto skip đúng đoạn, không skip khi user vừa tua lại.
  - Manual segment, unskip 5 giây, grace rewind, chain.
  - Whitelist/category mode.
- `PlaybackHistoryRepository`:
  - Resume > 5s và < duration - 5s.
  - Không ghi đè progress cũ khi bắt đầu xem.
  - Flush khi pause/close.
- `PlaybackPosition` (đã có).

### 13.2. Robolectric

- `PlaybackService` tạo `MediaSession`, `addSession` được gọi.
- `onGetSession` trả đúng session.
- `onTaskRemoved` dừng player + `stopSelf`.
- Có thể dùng `MediaSessionService` test utils nếu thêm dependency.

### 13.3. Test tay trên thiết bị

- Notification: hiện/ẩn đúng, đủ 3 nút, bấm đúng.
- Android 13/14/15: cấp quyền thông báo, foreground service.
- Bluetooth/AVRCP: title, channel, artwork; next/previous; play/pause.
- Android Auto nếu có.
- Tai nghe có dây: phím media.
- Màn hình khóa: controls.
- Tắt màn hình: auto-next, SponsorBlock, history.
- Mạng yếu/mất mạng: chờ mạng, retry, không crash.
- Chuyển Wi-Fi ↔ 4G: quality/data saver.
- Live stream: không next/previous, không auto-next.
- Queue: thêm/phát tiếp/xoá/di chuyển/xáo trộn/xoá hết/lặp.
- Mix: chuyển bài đúng thứ tự, tải trang kế, hết mix fallback related.
- Previous: > 3s về đầu; ≤ 3s bài trước; ở bài đầu về 0.
- PiP, thu nhỏ, xoay màn hình, process death.
- SponsorBlock: auto/manual/unskip/grace/whitelist.
- Sleep timer: dừng đúng, không auto-next.

### 13.4. CI

- Hiện workflow chỉ build release APK.
- Đề xuất thêm job `./gradlew test` (unit test) trước khi build APK.
- Khi không có JDK/Android SDK cục bộ, mọi xác minh biên dịch dựa vào GitHub Actions.

---

## 14. Câu hỏi mở

1. Có cần hiển thị **queue** trên notification/Android Auto không? Media3 không có UI queue chuẩn; cần custom layout nếu muốn.
2. Có persist `playStack`/mix qua phiên không, hay chỉ persist user queue như hiện tại?
3. Giai đoạn 4 (`MediaController`) có thật sự cần cho mục tiêu hiện tại không?
4. Có giữ `PlaybackEngineProvider` singleton sau giai đoạn 4, hay chuyển hẳn vào service?
5. Có cần đồng bộ vị trí phát giữa nhiều cửa sổ/app khác không?

---

## 15. Tham chiếu

- Media3 `MediaSessionService.addSession()` — điều kiện để tạo notification controller.
- Media3 `MediaSessionImpl.onMediaButtonEvent()` — map `KEYCODE_MEDIA_NEXT/PREVIOUS` → `seekToNext/Previous`.
- Media3 `MediaSessionStub.seekToPreviousForControllerInfo()` → `Player.seekToPrevious()`.
- Media3 `Player.seekToPrevious()` — dùng `maxSeekToPreviousPositionMs` (mặc định 3000 ms).
- Media3 `DefaultMediaNotificationProvider` — thêm nút theo command khả dụng.
- `docs/TUBULAR_RESEARCH.md` — tham chiếu SponsorBlock/UX.

---

## 16. Trạng thái triển khai

| Hạng mục | Trạng thái |
|---|---|
| Tài liệu thiết kế | **Đã viết** |
| Giai đoạn 0 — notification + metadata | **Đã xong** (`16dd55f`, `cc72f27`) |
| Giai đoạn 1 — extract | **Đã xong** |
| Giai đoạn 2 — playlist thật | **Đã xong** |
| Giai đoạn 3 — background | **Đã xong** |
| Giai đoạn 4 — MediaController | **Bỏ qua** (D12) |
| Giai đoạn 5 — UI/state split | **Chưa bắt đầu** |
| Kiểm thử đơn vị hàng đợi | **Đã thêm** (`PlaybackQueueTest`, CI `testDebugUnitTest`) |

### Sai lệch / ghi chú triển khai

- `PurePlayerState` vẫn được giữ như state tổng hợp cho Compose trong giai đoạn chuyển tiếp; nguồn dữ liệu là các `StateFlow` của coordinator. Tài liệu cho phép bước chuyển này.
- `PlaybackCoordinator` sở hữu thêm `PlaybackHistoryRepository`, `SponsorBlockController`, `PlaybackNetworkMonitor`, sleep timer và auto-retry để các phần này chạy cả khi UI bị huỷ.
- Khi process bị kill, coordinator khôi phục metadata + vị trí now-playing nhưng **không tự phát**; người dùng bấm play mới resolve lại URL.
- Auto-next tắt vẫn giữ hành vi cũ: `Lặp tất cả` không tự chuyển bài, còn `Lặp một` vẫn phát lại video hiện tại.
- Chỉ prefetch đúng một video kế tiếp; cửa sổ playlist native được trim còn `[previous, current, next]`.
- Sau mỗi thao tác hàng đợi còn lại (thêm, phát tiếp theo, đổi chế độ lặp, cập nhật trending), coordinator prefetch lại bài kế để playlist native luôn khớp hàng đợi logic.
- Từ đợt làm lại giao diện player, HUD không còn nút hàng đợi và sheet quản lý hàng đợi (đã có nút danh sách kết hợp), cũng không còn hai nút tua 10 giây vì đã có double-tap. Hàng đợi logic vẫn tồn tại để phục vụ "Phát tiếp theo", "Thêm vào hàng đợi" và tự động chuyển bài.
- HUD được dựng lại theo phong cách YouTube: scrim trên/dưới, thời gian hiện tại/tổng/còn lại, thanh tua có vùng đệm và marker SponsorBlock bo tròn, bong bóng thời gian khi kéo, badge TRỰC TIẾP, hàng nút phụ (play/pause, video kế, mix, phụ đề, cài đặt, toàn màn hình) và hiệu ứng flash double-tap có chuyển động. Bố cục compact (trình phát nhúng) và full (toàn màn hình/PiP) có số đo riêng; API công khai và callback của `PlayerHudView` giữ nguyên nên `PlayerNativeView`/`PlayerActivity` không phải sửa.
- `PlaybackQueue.onAdvancedTo` báo lại khi auto-next native chuyển sang video nằm ngoài mix hiện tại; coordinator xoá mix cũ và tải mix mới cho video đó.
- Hàng đợi, mix và related chỉ tồn tại trong `PlaybackCoordinator.queueState`; `MainViewModel`/`MainActivity` đọc trực tiếp flow này thay vì giữ bản sao, nên không còn hai nguồn sự thật cho danh sách mix.
- Từ 1.7.0, app bỏ hẳn NewPipe Extractor: `YouTubePlayerDataSource` gọi InnerTube `player` (IOS → ANDROID → ANDROID_VR), `YouTubeMetadataDataSource` lấy chi tiết/liên quan/bình luận từ `next(videoId)`, `YouTubeCommentsDataSource` phân trang bình luận bằng continuation, còn mix dùng `YouTubeWebDataSource.mixPageBlocking`. Không còn phụ thuộc JitPack/extractor.
- Request `player` luôn kèm query `params=2AMB` (nhánh "testsuite"). Không có tham số này, googlevideo chỉ phục vụ khoảng 60–80 giây đầu của mỗi URL adaptive: request `Range` xa hơn trả `403`, video dừng giữa chừng rồi retry loop (đây là lỗi gặp ở video dài như `6yqgzydxZ1Y`). Có `2AMB`, URL dùng được hết thời lượng và response có thêm `hlsManifestUrl`. Nếu YouTube tạm thời trả HTTP 400 cho tham số này, client được gọi lại một lần với URL không tham số để vẫn phát được phần đầu.
- Chọn chất lượng: `DashManifestBuilder` ghép các luồng video-only + track audio tốt nhất (AAC gốc, không DRC) lấy từ InnerTube `player` thành MPD nhiều `Representation` dùng `SegmentBase`/`Initialization` byte range; MPD được truyền qua `data:` URI nên `PlaybackEngine` dùng `DefaultDataSource` (vừa đọc `data:` vừa tải segment HTTPS). Manifest chỉ chứa **một họ codec** video, và họ đó phải giải mã được trên máy: `DeviceVideoCodecs` dò `MediaCodecList`, `DashManifestBuilder` lọc các luồng thuộc họ không hỗ trợ trước khi chọn họ có dải chất lượng cao nhất. Trước đây chọn theo độ phân giải cao nhất nên máy không có AV1/VP9 vẫn nhận manifest toàn AV1/VP9 → track selector không có track video hợp lệ, chỉ còn tiếng. Media3 chỉ chọn một `AdaptationSet` cho video, nên trộn mp4 + webm sẽ khiến đổi chất lượng nhảy `TrackGroup` và dựng lại toàn bộ sample stream (khựng). Mức người dùng chọn được quy đổi qua `PlaybackPlan.resolveHeight()` về chiều cao **có thật** trong nguồn (mức cao nhất ≤ yêu cầu, hoặc mức thấp nhất nếu yêu cầu thấp hơn cả thang), rồi `PlaybackEngine` đặt `min = max = mức đó` để track selector chọn đúng representation ngay (mức chọn là **đích**, không chỉ là mức trần như bản 1.8.0 — nếu chỉ đặt max thì chọn 1080p/1440p/2160p khi đang phát 720p sẽ không thay đổi gì). Đổi selection trong cùng `TrackGroup` không cần resolve lại URL hay dựng lại media source, nhưng vì tập track được chọn thay đổi, Media3 thay sample stream video tại đúng vị trí đang phát (luồng audio giữ nguyên): có thể khựng ngắn lúc tải initialization/segment của representation mới, đổi lại vị trí phát không bị tua lại và chất lượng đổi đúng ở cả hai chiều. Nếu nguồn không có đúng mức (HLS khác thang DASH, hoặc decoder không hỗ trợ độ phân giải đó), engine tự nới về chỉ giới hạn `setMaxVideoSize` để không tắt hình. Khi chỉ có progressive (mỗi file một chất lượng), `PlaybackPlan.candidateIndexFor()` chọn đúng file theo chiều cao đã lưu và `PlaybackQueueManager.applyProgressiveQuality()` thay `MediaItem` tại chỗ; không đặt giới hạn kích thước cho nguồn một-track vì có thể làm track selector tắt hình.

> Tài liệu này là bản ghi quyết định. Khi triển khai, cập nhật lại trạng thái và ghi chú các sai lệch so với thiết kế.

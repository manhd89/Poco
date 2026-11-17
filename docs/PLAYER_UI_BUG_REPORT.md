# Báo cáo lỗi thiết kế tính năng & UI — Player YouTube (PureTube)

- **Phạm vi:** module trình phát (`ui/player`, `player/`, layout player). Không bao gồm feed, tìm kiếm, thư viện.
- **Mốc mã nguồn:** `1242f79` (nhánh hiện tại), `versionName = 1.8.3`, `targetSdk = 36`.
- **Phương pháp:** đọc mã nguồn + layout, đối chiếu với ý đồ thiết kế trong `docs/PLAYER_REDESIGN.md`. Mọi kết luận đều kèm `file:dòng`.
- **Trạng thái:** đã sửa **đợt 1** (P0-2, P0-3, P1-3, P1-4, P1-5, P2-5, P2-6), **đợt 2** (P0-1, P1-1, P1-2, P1-6, P2-7) và **đợt 3** (P2-1, P2-2, P2-3, P2-4, P2-8, P2-9, P2-10, P3-1…P3-5). Toàn bộ 24 mục đã được xử lý.

### Đợt 1 đã sửa

| ID | Thay đổi chính |
|---|---|
| P0-2 | `PlayerNativeView.renderDetails()` dùng `DetailsSignature` để chỉ dựng lại khi cấu trúc đổi; `bindInfo()` cập nhật tại chỗ. Sheet cài đặt có `settingsSignature`; avatar chỉ bind lại khi đổi kênh/URL. |
| P0-3 | `PlayerActivity` gọi `enableEdgeToEdge()` + `ViewCompat.setOnApplyWindowInsetsListener`; bật `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`; `PlayerNativeView.applyWindowInsets()` chừa inset cho `detailsScroll` và HUD (đáy khi HUD chạm đáy màn hình: fullscreen/PiP/landscape). |
| P1-3 | Nút "Video trước" luôn bật; bỏ `hasPrevious` khỏi `PlayerHudView.render()` — coordinator tự tua về đầu nếu đang phát > 3 giây. |
| P1-4 | `resetPlaybackParameters()` đặt lại cả `playbackPitch` và gọi `engine.setPitch(1.0f)`. |
| P1-5 | Thêm `AutoPlayMode.ALL`; nút tự động phát xoay vòng OFF → NEXT → ALL → ONE, thêm icon `ic_repeat.xml`. |
| P2-5 | Flash double-tap lấy `wasPlaying` trước khi toggle để icon khớp trạng thái kết quả. |
| P2-6 | `hideRunnable`/`scheduleAutoHide()` bỏ qua khi `isSeeking`. |

### Đợt 2 đã sửa

| ID | Thay đổi chính |
|---|---|
| P0-1 | Thêm nút hàng đợi trên HUD (`hud_queue`, icon `ic_queue_music`) và `BottomSheetDialog` liệt kê `queue.queue`; mỗi dòng có phát, đưa lên/xuống, xoá; đầu sheet có "Xáo trộn"/"Xoá tất cả". Nối đủ `playQueueItem`, `removeQueueItem`, `moveQueueItem`, `shuffleQueue`, `clearQueue` vào ViewModel. Sheet tự cập nhật qua `updateQueue()`. |
| P1-1 | Bỏ danh sách chất lượng bịa khỏi `PlayerNativeView` và `SettingsActivity`; chỉ dùng `availableQualities` thật + mức đã lưu. Thêm `qualityPreferenceHeight` để dialog tick đúng lựa chọn kể cả khi video hiện tại không có mức đó; nhãn hiển thị mức hiệu lực. |
| P1-2 | `PlayerHudView.render()` ẩn previous/next/rewind/forward và mix khi `isLive`; `onStopTrackingTouch()`/`finishDrag()` thêm guard `!isLive`. Seekbar + nhãn thời gian vẫn ẩn như cũ, chỉ còn badge "TRỰC TIẾP". |
| P1-6 | `updateSubtitleLayout()` tính lại `bottomPaddingFraction` theo trạng thái điều khiển: chừa ~96dp khi HUD hiện, ~24dp khi ẩn; gọi lại mỗi khi `applyControlsVisibility()` đổi trạng thái. |
| P2-7 | `selectedQualityLabel()` dùng `effectiveQualityHeight()`; khi Data Saver hạ trần 480p lúc để "Tự động", nhãn thành "Tự động · 480p" và dialog chất lượng có ghi chú. |

### Đợt 3 đã sửa

| ID | Thay đổi chính |
|---|---|
| P2-1 | Gom toàn bộ state bình luận vào `CommentsUiState` + một `StateFlow` (`PlayerViewModel.commentsState`), Activity chỉ còn một collector. Sheet bình luận chuyển sang `RecyclerView` + `CommentSheetAdapter`/`DiffUtil` (header là một dòng), phân trang bằng `addOnScrollListener`; preview vẫn 3 dòng nhưng dùng chung `bindCommentRow()`. |
| P2-2 | `view_player_comment_row.xml` dùng `ChannelAvatarView` + ảnh thật (`avatarUrl`), tick xác minh (`ic_verified`), badge "Tác giả" và icon tim; `bindCommentRow()` bind đủ `isVerified`, `isChannelOwner`, `isHeartedByUploader`. |
| P2-3 | `CompactVideoCardView.bind()` nhận `isCurrent`; mix sheet so `mixVideo.id == currentVideo.id`, hiện badge "Đang phát" và bấm video đang phát là no-op. |
| P2-4 | `PlaybackUiState`/`PurePlayerState` thêm `sleepTimerRemainingSeconds`; `PlaybackCoordinator.setSleepTimer()` cập nhật mỗi giây, pill hiện "Còn mm:ss", hết giờ phát snackbar `player_sleep_finished`. |
| P2-8 | PiP có `RemoteAction` play/pause/previous/next qua `PlayerPipActionReceiver` (broadcast tường minh), aspect lấy từ `PlayerHudView.videoAspectRatio` (fallback 16:9), `setAutoEnterEnabled` trên API 31+, đóng mọi sheet trước khi vào PiP. |
| P2-9 | `PlayerNativeView.updateLayoutMetrics()` thêm bố cục 2 cột khi rộng ≥ 600dp (không fullscreen/PiP): video 60% bên trái, thông tin/bình luận/liên quan cuộn độc lập bên phải; inset trái được bỏ ở cột phải. |
| P2-10 | `PlaybackCoordinator` thêm `isResolving` + `pendingPlayAfterResolve`; `togglePlayPause()` khi đang resolve chỉ ghi nhận ý định rồi áp dụng trong `finally` sau khi nạp xong, không phát lại video cũ. |
| P3-1 | Resource hoá chuỗi player/HUD/bình luận/hàng đợi/chất lượng/hẹn giờ trong `strings.xml`; `view_player_hud.xml` dùng `@string/...` cho toàn bộ `contentDescription`/`text`. |
| P3-2 | `PlayerNativeView` không còn so sánh literal `"Tự động"`; nhãn chất lượng lấy từ state có cấu trúc và resource. |
| P3-3 | `PlayerNativeView` kế thừa `CoordinatorLayout` để `Snackbar` neo đúng và không đè lên điều khiển. |
| P3-4 | `PlayerSeekBar.onDraw()` vẽ marker SponsorBlock **sau** `super.onDraw()` để không bị lớp progress phủ. |
| P3-5 | Xoá `progressFromFraction`, `commentsSheetContainer`/`rebuildCommentsSheet`, `currentDetails`, `isInPiP`, `pipRequested` và các import chết. |

## Thang mức độ

| Mức | Ý nghĩa |
|---|---|
| **P0 – Nghiêm trọng** | Sai hành vi rõ ràng, mất dữ liệu/không dùng được tính năng, hoặc vỡ giao diện trên thiết bị phổ biến. |
| **P1 – Cao** | Trải nghiệm bị phá vỡ thường xuyên (giật, mất vị trí cuộn, điều khiển sai). |
| **P2 – Trung bình** | Thiếu tính năng so với kỳ vọng YouTube, nhãn/hành vi gây nhầm lẫn. |
| **P3 – Thấp** | Chi tiết UI, chuỗi hardcode, mã chết, nhất quán. |

## Bảng tổng hợp

| ID | Mức | Lỗi | Vị trí chính |
|---|---|---|---|
| P0-1 | P0 | Không có UI quản lý hàng đợi (queue) dù state có sẵn | `PlaybackState.kt:44`, `PureTubeViewModel.kt:336` |
| P0-2 | P0 | Toàn bộ trang xem bị dựng lại mỗi ~5 giây, mất vị trí cuộn | `PlayerNativeView.kt:297,431,517` |
| P0-3 | P0 | Tràn viền (edge-to-edge) không xử lý inset trên Android 15+ | `PlayerActivity.kt` (không có `enableEdgeToEdge`) |
| P1-1 | P1 | Danh sách chất lượng bịa + nhãn chọn lệch thực tế | `PlayerNativeView.kt:1021` |
| P1-2 | P1 | Live stream vẫn hiện nút trước/sau, seek, cử chỉ tua | `PlayerHudView.kt:511,588,607` |
| P1-3 | P1 | Nút "Video trước" bị tắt khi không có video trước → không thể tua lại đầu | `PlayerHudView.kt:203,497` |
| P1-4 | P1 | "Đặt lại tốc độ và cao độ" chỉ đặt lại tốc độ | `PlaybackCoordinator.kt:641`, `strings.xml:160` |
| P1-5 | P1 | Repeat ALL không thể chọn; HUD hiển thị sai thành NEXT | `PlayerNativeView.kt:407`, `PlaybackCoordinator.kt:888` |
| P1-6 | P1 | Phụ đề bị HUD che khi hiện điều khiển | `PlayerHudView.kt:185,715` |
| P2-1 | P2 | Sheet bình luận dựng lại toàn bộ, dùng `ScrollView` thay `RecyclerView` | `PlayerNativeView.kt:647,660` |
| P2-2 | P2 | Bỏ qua avatar, tick xác minh, tim của chủ kênh trong bình luận | `view_player_comment_row.xml`, `PlayerNativeView.kt` |
| P2-3 | P2 | Mix sheet không đánh dấu video đang phát | `PlayerNativeView.kt:888` |
| P2-4 | P2 | Đồng hồ ngủ không đếm ngược, nhãn mơ hồ | `PlayerNativeView.kt:479` |
| P2-5 | P2 | Icon flash double-tap dùng trạng thái trước khi toggle | `PlayerHudView.kt:320-322` |
| P2-6 | P2 | Thanh điều khiển tự ẩn khi đang kéo seekbar | `PlayerHudView.kt:126` |
| P2-7 | P2 | Data saver 480p vẫn gắn nhãn "Tự động" | `PlaybackCoordinator.kt:1290` |
| P2-8 | P2 | PiP không có action, aspect cố định 16:9, không auto-enter | `PlayerActivity.kt:344,369` |
| P2-9 | P2 | Không có layout 2 cột cho tablet/landscape | `PlayerNativeView.kt:389` |
| P2-10 | P2 | Race play/pause trong lúc resolve video mới | `PlaybackCoordinator.kt:527` |
| P3-1 | P3 | Nhiều chuỗi tiếng Việt hardcode trong Kotlin | `PlayerHudView.kt`, `PlayerNativeView.kt` |
| P3-2 | P3 | So sánh chuỗi `"Tự động"` dễ vỡ khi đổi ngôn ngữ | `PlayerNativeView.kt:964` |
| P3-3 | P3 | Snackbar không có `CoordinatorLayout`, đè lên điều khiển | `PlayerActivity.kt:230` |
| P3-4 | P3 | Marker SponsorBlock có thể bị lớp progress vẽ đè | `PlayerSeekBar.kt` |
| P3-5 | P3 | Trường/import/method chết | `PlayerActivity.kt:33,54,74` |

---

## P0 — Nghiêm trọng

### P0-1. Không có UI quản lý hàng đợi dù state đã có sẵn — ✅ đã sửa (đợt 2)

**Bằng chứng**
- `PlaybackState.kt:44-51` — `QueueUiState` có `queue`, `hasNext`, `hasPrevious`, `repeatMode`.
- `PureTubeViewModel.kt:336-340` — đã expose `shuffleQueue()`, `clearQueue()`, `cycleRepeatMode()`.
- `PlayerActivity.kt:183` — chỉ dùng `queueState` để đẩy vào `playerView.updateQueue(...)`.
- `PlayerNativeView.kt` — không hề đọc `queue.queue`, `hasNext`; chỉ dùng `queue.mix`, `hasPrevious`, `repeatMode` (`:409-417`).
- Không có layout/sheet nào liệt kê hàng đợi; không có nút "Xoá hàng đợi", "Trộn", "Kéo để sắp xếp".

**Ảnh hưởng**
Người dùng không thể xem, sắp xếp, xoá hay trộn danh sách phát. Đây là tính năng cốt lõi của một trình phát YouTube/Media3 và đã có sẵn logic phía dưới nhưng "vô hình" với người dùng. `hasNext` bị tính toán nhưng bỏ phí.

**Hướng sửa**
- Thêm nút "Hàng đợi" trong HUD (`view_player_hud.xml`) hoặc trong sheet cài đặt.
- Tạo `BottomSheetDialog` dùng `RecyclerView` hiển thị `queue.queue`, highlight item đang phát.
- Nối `onNext`, `onPrevious`, `onRemove`, `onMove`, `onShuffle`, `onClear` vào các hàm ViewModel đã có.
- Dùng `hasNext` để bật/tắt nút Next thay vì luôn bật.

---

### P0-2. Toàn bộ trang xem bị dựng lại mỗi ~5 giây, mất vị trí cuộn — ✅ đã sửa (đợt 1)

**Bằng chứng**
- `PlayerNativeView.kt:297-311` — `updatePlayerState()` luôn gọi `renderDetails()`.
- `PlayerNativeView.kt:318-330` — `updateQueue()` luôn gọi `renderDetails()`.
- `PlayerNativeView.kt:332-335` — `updateDetails()` gọi `renderDetails()`.
- `PlayerNativeView.kt:431-439` — `renderDetails()` gọi `bindInfo()` + `buildDetails()` + `refreshSettingsSheet()`.
- `PlayerNativeView.kt:517` — `buildDetails()` bắt đầu bằng `detailsContainer.removeAllViews()` rồi inflate lại toàn bộ (mô tả, preview bình luận, video liên quan).
- `PlayerActivity.kt:213-216` — `viewModel.history.collect { ... syncLibraryState() }`.
- `PlaybackCoordinator.kt:1337` — `HISTORY_SAVE_INTERVAL_MS = 5_000L`; mỗi lần lưu tiến độ (`:1149`), Room phát lại `history`.
- `PlayerActivity.kt:249` — `syncLibraryState()` (được gọi từ 3 collector ở `:202,:208,:214`) cuối cùng gọi `playerView.updateQueue(...)` → `renderDetails()`.

**Ảnh hưởng**
Cứ ~5 giây (mỗi tick lưu lịch sử) hoặc mỗi khi state/queue đổi, toàn bộ nội dung dưới player bị xoá và dựng lại. Hậu quả:
- Mất vị trí cuộn khi đang đọc mô tả/bình luận.
- Nhấp nháy, tụt FPS, cấp phát View liên tục.
- Ảnh thumbnail/avatar bị nạp lại.
- Nếu đang mở sheet bình luận, `refreshSettingsSheet()`/rebuild làm giật.

**Hướng sửa**
- Tách `renderPlayer()` (HUD) khỏi `renderDetails()`; chỉ gọi `renderDetails()` khi video hiện tại hoặc dữ liệu chi tiết thực sự đổi (so sánh `video.id` + hash của details/queue).
- `updateQueue()` chỉ cập nhật các phần phụ thuộc queue (nút mix, related list) bằng diff, không `removeAllViews()`.
- Không đẩy `history` vào `updateQueue` mỗi 5 giây; chỉ cập nhật badge tiến độ của item liên quan.
- Chuyển danh sách bình luận/liên quan sang `RecyclerView` + `ListAdapter`/`DiffUtil`.

---

### P0-3. Tràn viền (edge-to-edge) không xử lý inset trên Android 15+ — ✅ đã sửa (đợt 1)

**Bằng chứng**
- `app/build.gradle.kts` — `targetSdk = 36` (Android 16), `compileSdk 36`.
- `PlayerActivity.kt:78-86` — chỉ đặt background trong suốt, **không** gọi `enableEdgeToEdge()`.
- `PlayerActivity.kt:318-329` — chỉ ẩn/hiện `systemBars` khi fullscreen, không áp `WindowInsets` cho HUD/nội dung.
- So sánh: `ui/base/PageActivity.kt:72` gọi `enableEdgeToEdge()` và `:336-338` áp `setOnApplyWindowInsetsListener` cho `systemBars() or displayCutout()`; `PlayerActivity` không có.
- `view_player_hud.xml` — `hud_bottom_controls` neo `layout_gravity="bottom"` với padding cố định, không có `fitsSystemWindows`/inset listener.
- `PlayerNativeView.kt:379-403` — tính chiều cao video theo `width * 9 / 16`, không trừ status bar.

**Ảnh hưởng**
Từ Android 15 (API 35) edge-to-edge là mặc định với `targetSdk >= 35`. Trên máy có thanh điều hướng cử chỉ hoặc tai thỏ:
- Thanh seek + hàng nút dưới bị thanh navigation che.
- Tiêu đề/kênh ở HUD trên bị status bar/tai thỏ che.
- Nội dung dưới player bị cắt ở đáy.
Đây là lỗi nhìn thấy ngay trên thiết bị phổ biến, không chỉ ở fullscreen.

**Hướng sửa**
- Gọi `enableEdgeToEdge()` trong `onCreate` (hoặc cấu hình theme).
- Áp `ViewCompat.setOnApplyWindowInsetsListener` cho `PlayerNativeView`: cộng `systemBars` + `displayCutout` vào padding của HUD top/bottom và bottom của `detailsScroll`.
- Trong fullscreen, dùng `WindowInsetsControllerCompat` như hiện tại nhưng vẫn chừa cutout hai bên (landscape tai thỏ).

---

## P1 — Cao

### P1-1. Danh sách chất lượng bịa + nhãn chọn lệch thực tế — ✅ đã sửa (đợt 2)

**Bằng chứng**
- `PlayerNativeView.kt:1021` — `(state.availableQualities + listOf(1080, 720, 480, 360))`: luôn thêm 4 mức cố định dù video không có.
- `PlayerNativeView.kt:1030-1035` — `selected` so khớp `options.first == state.selectedQuality`; nếu không khớp rơi về index 0 ("Tự động").
- `PlaybackCoordinator.kt:1326-1333` — `selectedQualityLabel()` trả nhãn đã quy đổi theo chất lượng thực (`resolveHeight`), ví dụ lưu 1080p nhưng video max 720p → nhãn "720p".
- `PlaybackCoordinator.kt:1250` — khi resolve xong ghi đè `selectedQuality` bằng nhãn quy đổi.
- `PlaybackCoordinator.kt:1290-1300` — data saver trả 480p nhưng `selectedQualityLabel` vẫn có thể là "Tự động" vì `configured <= 0`.

**Ảnh hưởng**
- Người dùng thấy các mức 1080/720/480/360 cho video không hề có, chọn xong không có tác dụng.
- Mở dialog: mục được tick không phải mức đang phát thực (do nhãn quy đổi không khớp danh sách bịa).
- Chọn một mức có thể âm thầm ghi đè preference đã lưu.
- Bật data saver: đang phát 480p nhưng UI ghi "Tự động" → không minh bạch.

**Hướng sửa**
- Chỉ liệt kê `state.availableQualities` thật từ `PlaybackPlan`/`VideoDetails`; bỏ danh sách cố định.
- Lưu riêng "preference" (mong muốn) và "effective" (thực tế); dialog tick theo preference, nhãn hiển thị effective.
- Khi data saver bật, hiển thị "Tự động (tiết kiệm dữ liệu) · 480p".

---

### P1-2. Live stream vẫn hiện nút trước/sau, seek và cử chỉ tua — ✅ đã sửa (đợt 2)

**Bằng chứng**
- `PlayerHudView.kt:511-512` — `rewindButton`/`forwardButton` chỉ dựa `isFullscreen`, không loại trừ `isLive`.
- `PlayerHudView.kt:607-618` — `updateSeekBarMode()` chỉ ẩn `seekBar` + nhãn thời gian khi live; **không** ẩn previous/next/rewind/forward.
- `PlayerHudView.kt:203-207` — previous vẫn bấm được nếu `hasPrevious`.
- `PlayerHudView.kt:345` — `SWIPE_DOWN` bị chặn khi live, nhưng `finishDrag()` (`:392-397`) không kiểm tra `isLive` trước khi `onSeekTo` (dù `dragMode == SEEK` đã bị chặn ở `:352`).
- `docs/PLAYER_REDESIGN.md` — ý đồ: live phải ẩn next/previous.

**Ảnh hưởng**
Trên live, người dùng thấy nút "Video trước/tiếp" và nút tua 10s (fullscreen) nhưng không có ý nghĩa; bấm vào gây nhảy video hoặc không phản hồi. Trải nghiệm sai mô hình so với YouTube.

**Hướng sửa**
- Trong `render()`: nếu `isLive` → ẩn `previousButton`, `nextButton`, `rewindButton`, `forwardButton`; chỉ giữ play/pause, captions, settings, fullscreen, PiP.
- Trong `finishDrag()` thêm guard `!isLive` cho nhánh seek (dù đã chặn phía trên, nên phòng thủ).
- Cân nhắc hiện badge "TRỰC TIẾP" thay cho cụm seek.

---

### P1-3. Nút "Video trước" bị tắt khi không có video trước → không thể tua lại đầu — ✅ đã sửa (đợt 1)

**Bằng chứng**
- `PlayerHudView.kt:203-207` — `previousButton` chỉ gọi `onPrevious()` khi `hasPrevious == true`.
- `PlayerHudView.kt:497-498` — `alpha = 0.3f`, `isEnabled = hasPrevious`.
- `PlaybackState.kt:50` — `hasPrevious` do queue tính; video đầu tiên trong queue → `false`.
- `PlaybackCoordinator.kt:1339` — hằng `MAX_SEEK_TO_PREVIOUS_POSITION_MS = 3_000L`; `:511` cho thấy ý đồ "previous = về đầu nếu đang phát > 3s", nhưng UI chặn trước khi tới logic.

**Ảnh hưởng**
Hành vi chuẩn của YouTube: bấm "previous" khi đang phát >3s sẽ đưa về đầu video hiện tại; chỉ khi ở đầu mới chuyển video trước. Hiện tại nút bị disable hoàn toàn với video đầu, mất thao tác phổ biến.

**Hướng sửa**
- Luôn enable nút previous; trong `onPrevious()`: nếu `position > 3s` → seek về 0; ngược lại nếu `hasPrevious` → chuyển bài; nếu không → rung nhẹ/no-op.
- Chỉ giảm alpha khi vừa ở đầu **và** không có bài trước.

---

### P1-4. "Đặt lại tốc độ và cao độ" chỉ đặt lại tốc độ — ✅ đã sửa (đợt 1)

**Bằng chứng**
- `strings.xml:160` — `player_reset_params` = "Đặt lại tốc độ và cao độ".
- `PlaybackCoordinator.kt:641-644` — `resetPlaybackParameters()` chỉ `copy(playbackSpeed = 1.0f)` và `engine.setSpeed(1.0f)`; không đụng `playbackPitch`.

**Ảnh hưởng**
Nhãn hứa đặt lại cả cao độ nhưng cao độ giữ nguyên → người dùng tưởng lỗi, âm thanh vẫn méo.

**Hướng sửa**
- Đặt lại cả `playbackPitch = 1.0f` và gọi API pitch của engine (nếu có), hoặc đổi nhãn thành "Đặt lại tốc độ phát".

---

### P1-5. Repeat ALL không thể chọn; HUD hiển thị sai thành NEXT — ✅ đã sửa (đợt 1)

**Bằng chứng**
- `PlayerNativeView.kt:407-411` — ánh xạ chỉ có `RepeatMode.ONE → AutoPlayMode.ONE`, còn lại `isAutoNextEnabled → NEXT` hoặc `OFF`. `RepeatMode.ALL` không có nhánh.
- `PlaybackCoordinator.kt:888-893` — `cycleRepeatMode()` tồn tại và xoay vòng OFF→ALL→ONE, nhưng không được nối vào UI nào.
- `PureTubeViewModel.kt:340` — `cycleRepeatMode()` có nhưng không nơi nào gọi từ player.
- `PlaybackCoordinator.kt:688-700` — `setAutoPlayMode()` chỉ set OFF/ONE, không bao giờ set ALL.
- `PlayerHudView.kt:471-477` — icon autoPlay chỉ phân biệt ONE vs còn lại (autorenew).

**Ảnh hưởng**
Không có cách bật "lặp toàn bộ danh sách". Nếu state đang ALL (ví dụ từ nơi khác), HUD hiển thị như NEXT → sai trạng thái.

**Hướng sửa**
- Mở rộng `AutoPlayMode` thêm `ALL`, hoặc dùng trực tiếp `RepeatMode` cho HUD.
- Nút autoPlay xoay vòng OFF → NEXT → ALL → ONE, icon riêng cho ALL (`ic_repeat`).
- `contentDescription` tương ứng.

---

### P1-6. Phụ đề bị HUD che khi hiện điều khiển — ✅ đã sửa (đợt 2)

**Bằng chứng**
- `PlayerHudView.kt:185-190` — `subtitleView.setPadding(side, 0, side, 0)` (không có padding đáy).
- `PlayerHudView.kt:715-724` — `setBottomPaddingFraction((24f/heightDp).coerceIn(0.04f, 0.10f))`: đáy phụ đề cố định ~4-10% chiều cao.
- `view_player_hud.xml` — `hud_bottom_controls` cao ~24dp seek + 48dp nút + nhãn thời gian, neo đáy, không đẩy phụ đề lên.

**Ảnh hưởng**
Khi điều khiển hiện (3.5s sau thao tác), phụ đề nằm ngay dưới/đè lên thanh seek và hàng nút → không đọc được. YouTube đẩy phụ đề lên trên cụm điều khiển.

**Hướng sửa**
- Khi `controlsVisible`, tăng `subtitleView.bottomPaddingFraction` (hoặc set padding đáy) đủ để vượt cụm điều khiển; trả lại khi ẩn.
- Hoặc đặt subtitle trong container có margin bottom động theo chiều cao `hud_bottom_controls`.

---

## P2 — Trung bình

### P2-1. Sheet bình luận dựng lại toàn bộ, dùng `ScrollView` — ✅ đã sửa (đợt 3)

**Bằng chứng**
- `PlayerNativeView.kt:647-660` — `showCommentsSheet()` dùng `ScrollView` + `LinearLayout`, không `RecyclerView`.
- `PlayerNativeView.kt:660-...` — `rebuildCommentsSheet()` `container.removeAllViews()` mỗi lần.
- `PlayerActivity.kt:217-225` — 10 collector riêng (`comments`, `commentsLoading`, `commentsHasMore`, `commentsTotal`, `commentsDisabled`, `commentsError`, `commentReplies`, `replyLoading`, `replyHasMore`, `replyError`) đều gọi `renderComments()`.
- `PlayerActivity.kt` `renderComments()` cuối cùng rebuild cả preview và sheet.

**Ảnh hưởng**
Mỗi thay đổi nhỏ (ví dụ đang tải thêm reply) xoá và dựng lại toàn bộ danh sách bình luận; mất vị trí cuộn, giật mạnh với video nhiều bình luận; không tái dùng View.

**Hướng sửa**
- Gom state bình luận thành một `data class CommentsUiState` + một `StateFlow`, một collector duy nhất.
- Dùng `RecyclerView` + `ListAdapter`/`DiffUtil` cho sheet và preview.
- Phân trang bằng `addOnScrollListener` thay vì nút "Tải thêm".

### P2-2. Bỏ qua avatar, tick xác minh, tim chủ kênh trong bình luận — ✅ đã sửa (đợt 3)

**Bằng chứng**
- `view_player_comment_row.xml` — `comment_avatar` là `TextView` 32dp, nền xám, chữ cái; không `ImageView`.
- `WatchData.kt:23,32-34` — `CommentItem` có `avatarUrl`, `isHeartedByUploader`, `isVerified`, `isChannelOwner`, nhưng `PlayerNativeView` render comment không dùng bất kỳ trường nào trong số này.
- `view_player_comment_row.xml` — không có view cho badge/tick/tim.

**Ảnh hưởng**
Bình luận trông "nghèo" so với YouTube; mất tín hiệu quan trọng (chủ kênh, đã xác minh, được tác giả tim) → khó đánh giá độ tin cậy.

**Hướng sửa**
- Thêm `ShapeableImageView` cho avatar, bind `avatarUrl` (fallback chữ cái như hiện tại).
- Thêm icon verified cạnh tên; badge "Tác giả" cho `isChannelOwner`; icon tim cho `isHeartedByUploader`.

### P2-3. Mix sheet không đánh dấu video đang phát — ✅ đã sửa (đợt 3)

**Bằng chứng**
- `PlayerNativeView.kt:888-900` — lặp `mix.videos`, `card.bind(...)` với `onClick = { sheet.dismiss(); onSelectVideo(it) }`; không truyền `isPlaying`/`isCurrent`.
- `ui/views/CompactVideoCardView.kt:76-83` — chữ ký `bind()` không có tham số đánh dấu "đang phát".

**Ảnh hưởng**
Trong danh sách kết hợp, người dùng không biết mình đang ở video nào; dễ bấm lại video đang phát gây reload.

**Hướng sửa**
- So sánh `mixVideo.id == state.currentVideo?.id`, thêm nền/viền nhấn + nhãn "Đang phát", và no-op khi bấm chính video đó.

### P2-4. Đồng hồ ngủ không đếm ngược, nhãn mơ hồ — ✅ đã sửa (đợt 3)

**Bằng chứng**
- `PlayerNativeView.kt:479-484` — pill hiển thị `"${state.sleepTimerMinutes} phút"` hoặc `settings_sleep_title`; không có thời gian còn lại.
- `PlayerNativeView.kt:118-119` — có `onShowSleepTimer`/`onSetSleepTimer` nhưng UI chỉ hiện phút đã đặt.

**Ảnh hưởng**
Người dùng không biết còn bao lâu mới tắt; dễ hiểu nhầm "15 phút" là đang đếm.

**Hướng sửa**
- Hiển thị `mm:ss` còn lại, cập nhật mỗi giây (hoặc mỗi 10s) từ state.
- Khi hết giờ, hiện snackbar/toast "Đã tắt trình phát theo hẹn giờ".

### P2-5. Icon flash double-tap dùng trạng thái trước khi toggle — ✅ đã sửa (đợt 1)

**Bằng chứng**
- `PlayerHudView.kt:320-322` — `onTogglePlayPause()` được gọi trước, rồi `showFlash(if (state?.isPlaying == true) "❚❚" else "▶")`.
- `state` trong HUD chỉ được cập nhật qua `render()` từ collector StateFlow (bất đồng bộ trên main); tại thời điểm `showFlash` chạy đồng bộ, `state` vẫn là giá trị **trước** toggle.
- Vì vậy icon luôn mô tả *hành động vừa thực hiện* chứ không phải *trạng thái kết quả*. Nếu toggle không có hiệu lực (đang resolve video mới — xem P2-10) thì flash báo sai hoàn toàn.

**Ảnh hưởng**
Phản hồi thị giác không nhất quán với trạng thái thực tế trong các tình huống toggle bị trễ/không áp dụng; người dùng khó biết video đang phát hay dừng.

**Hướng sửa**
- Chọn dứt khoát ngữ nghĩa: hiện icon của **trạng thái sau** toggle (`if (state.isPlaying) "▶" else "❚❚"` sau khi đã cập nhật) hoặc icon của **hành động**; đừng trộn.
- Nếu chọn trạng thái sau, cập nhật flash trong `render()`/một lần observe kế tiếp thay vì đọc `state` cũ ngay sau khi gọi toggle.

### P2-6. Thanh điều khiển tự ẩn khi đang kéo seekbar — ✅ đã sửa (đợt 1)

**Bằng chứng**
- `PlayerHudView.kt:126-131` — `hideRunnable` chỉ kiểm tra `controlsVisible && !isLocked && !settingsOpen && state?.isPlaying`, **không** kiểm tra `isSeeking`.
- `PlayerHudView.kt:261-282` — khi kéo seek, `isSeeking = true` và `showControls()` (reset hẹn giờ), nhưng nếu người dùng giữ >3.5s thì `hideRunnable` vẫn chạy.

**Ảnh hưởng**
Đang kéo seekbar (đặc biệt tua xa) thì thanh điều khiển biến mất giữa thao tác → không thấy vị trí đang tua.

**Hướng sửa**
- Thêm `&& !isSeeking` vào điều kiện `hideRunnable`; trong `onStopTrackingTouch` mới `scheduleAutoHide()`.

### P2-7. Data saver 480p vẫn gắn nhãn "Tự động" — ✅ đã sửa (đợt 2)

**Bằng chứng**
- `PlaybackCoordinator.kt:1290-1300` — `effectiveQualityHeight()` trả 480 khi mạng metered + configured <= 0 + `dataSaver`.
- `PlaybackCoordinator.kt:1326-1333` — `selectedQualityLabel()` chỉ dựa `configuredQualityHeight()`; nếu <= 0 trả "Tự động", bỏ qua data saver.

**Ảnh hưởng**
Người dùng bật tiết kiệm dữ liệu, video phát 480p nhưng UI ghi "Tự động" → không biết data saver có hiệu lực.

**Hướng sửa**
- `selectedQualityLabel` phản ánh `effectiveQualityHeight()` (hoặc thêm hậu tố "· tiết kiệm dữ liệu").
- Trong dialog chất lượng, hiển thị dòng ghi chú khi data saver đang bật.

### P2-8. PiP không có action, aspect cố định 16:9, không auto-enter — ✅ đã sửa (đợt 3)

**Bằng chứng**
- `PlayerActivity.kt:340-346` — `PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))`, không `setActions(...)`.
- `PlayerActivity.kt:369-373` — `onUserLeaveHint()` chỉ vào PiP nếu `shouldAutoEnterPiP()`; không dùng `setAutoEnterEnabled` (API 31+) nên cử chỉ home không mượt.
- `PlayerActivity.kt` — không kiểm tra `commentsDialog`/`mixDialog`/`settingsDialog` khi vào PiP; sheet có thể còn mở.

**Ảnh hưởng**
Trong PiP không có nút play/pause/next; video dọc (Shorts) bị letterbox nặng do ép 16:9; chuyển PiP có thể để sheet treo.

**Hướng sửa**
- Thêm `RemoteAction` play/pause, next, previous (API 26+).
- Tính aspect theo `videoSize` thực tế (fallback 16:9).
- Dùng `setAutoEnterEnabled(true)` khi API >= 31; đóng mọi sheet trước khi vào PiP.

### P2-9. Không có layout 2 cột cho tablet/landscape — ✅ đã sửa (đợt 3)

**Bằng chứng**
- `PlayerNativeView.kt:379-403` — `updateLayoutMetrics()`: nếu không fullscreen thì `videoHeight = width * 9 / 16`, `detailsScroll` đặt `topMargin = videoHeight`, luôn 1 cột dọc.
- Không có nhánh dựa `smallestScreenWidthDp`/`Configuration.orientation` để tách player + danh sách liên quan.

**Ảnh hưởng**
Trên tablet/landscape, player chiếm toàn bộ chiều rộng với dải đen hai bên (video 16:9) và nội dung bên dưới phải cuộn rất xa; lãng phí không gian ngang.

**Hướng sửa**
- Với `w >= 600dp` hoặc landscape: bố cục 2 cột — player bên trái (hoặc trên) + related/comments bên phải.
- Dùng `ConstraintLayout`/`layout-sw600dp` thay vì tính `topMargin` thủ công.

### P2-10. Race play/pause trong lúc resolve video mới — ✅ đã sửa (đợt 3)

**Bằng chứng**
- `PlaybackCoordinator.kt:271-...` — `play()` resolve bất đồng bộ; trước khi resolve xong chỉ `engine.pause()` media cũ (không `clearMediaItems`).
- `PlaybackCoordinator.kt:527-548` — `togglePlayPause()`: nếu `mediaItemCount != 0` và `!isPlaying` → `engine.play()` ngay, có thể phát lại **video cũ** trong lúc video mới đang resolve.
- `PlaybackCoordinator.kt` — `generation` tăng khi đổi video, nhưng `togglePlayPause` không kiểm tra generation/đang resolve.

**Ảnh hưởng**
Người dùng bấm video mới rồi nhanh tay bấm play → nghe/nhìn thoáng video cũ, hoặc nút play hiển thị sai trạng thái.

**Hướng sửa**
- Thêm cờ `isResolving`; `togglePlayPause()` khi đang resolve thì lưu ý định (pendingPlay) và áp sau khi resolve xong, hoặc no-op.
- Hoặc `engine.player.stop()`/`clearMediaItems()` ngay khi bắt đầu resolve video mới.

---

## P3 — Thấp

### P3-1. Nhiều chuỗi tiếng Việt hardcode trong Kotlin — ✅ đã sửa (đợt 3)

**Bằng chứng**
- `PlayerHudView.kt:492-494` — "Tự động phát: tắt/bài kế tiếp/lặp một bài".
- `PlayerHudView.kt` — "Tạm dừng", "Phát", "Thoát toàn màn hình", "Toàn màn hình", "‹‹ 10 giây", "10 giây ››".
- `PlayerNativeView.kt:521` — "Không có mô tả cho video này."; `:877` — "Danh sách kết hợp • N video"; `:966` — "Tự động · Np".
- `view_player_hud.xml` — `contentDescription` hardcode tiếng Việt.
- `view_player_description.xml` — "Xem thêm".

**Ảnh hưởng**
Không thể bản địa hoá; không nhất quán với `strings.xml` (một số chuỗi đã có resource).

**Hướng sửa**
- Chuyển toàn bộ sang `strings.xml` với placeholder (`%1$d`, `%1$s`).

### P3-2. So sánh chuỗi `"Tự động"` dễ vỡ khi đổi ngôn ngữ — ✅ đã sửa (đợt 3)

**Bằng chứng**
- `PlayerNativeView.kt:964` — `if (state.selectedQuality == "Tự động")`.
- `PlaybackCoordinator.kt:1329` — trả literal `"Tự động"`.
- `strings.xml:161` — `player_auto` = "Tự động".

**Ảnh hưởng**
Nếu đổi ngôn ngữ/đổi chuỗi `player_auto`, so sánh hỏng → nhãn chất lượng hiển thị sai, dialog tick sai.

**Hướng sửa**
- Dùng kiểu dữ liệu có cấu trúc (`QualitySelection.Auto` / `.Fixed(height)`) thay vì `String`; hoặc so sánh với `getString(R.string.player_auto)`.

### P3-3. Snackbar không có `CoordinatorLayout`, đè lên điều khiển — ✅ đã sửa (đợt 3)

**Bằng chứng**
- `PlayerActivity.kt:230` — `Snackbar.make(playerView, text, Snackbar.LENGTH_SHORT).show()`; root là `PlayerNativeView` (FrameLayout), không phải `CoordinatorLayout`.

**Ảnh hưởng**
Snackbar neo đáy `playerView`, có thể đè lên thanh seek/hàng nút hoặc nằm dưới thanh navigation (liên quan P0-3).

**Hướng sửa**
- Bọc root trong `CoordinatorLayout`, hoặc đặt snackbar trên `hud_bottom_controls` với margin inset.

### P3-4. Marker SponsorBlock có thể bị lớp progress vẽ đè — ✅ đã sửa (đợt 3)

**Bằng chứng**
- `PlayerSeekBar.kt:33-56` — `onDraw()` vẽ marker SponsorBlock **trước** `super.onDraw()` (dòng 56); `progressDrawable` (lớp progress) vẽ sau nên có thể phủ lên marker ở đoạn đã phát.

**Ảnh hưởng**
Marker đoạn tài trợ ở phần đã phát bị mờ/khuất, khó thấy.

**Hướng sửa**
- Vẽ marker **sau** `super.onDraw()` hoặc thêm lớp overlay riêng trên cùng.

### P3-5. Trường/import/method chết — ✅ đã sửa (đợt 3)

**Bằng chứng**
- `PlayerActivity.kt:33` — `import ...RepeatMode` không dùng.
- `PlayerActivity.kt:54` — `currentDetails` được gán nhưng không đọc.
- `PlayerActivity.kt:74` — `isInPiP` gán nhưng chỉ dùng nội bộ `onUserLeaveHint` (có thể thay bằng `isInPictureInPictureMode`).
- `PlayerSeekBar.progressFromFraction` — không nơi nào gọi.

**Ảnh hưởng**
Nợ kỹ thuật, dễ gây hiểu nhầm khi bảo trì.

**Hướng sửa**
- Xoá hoặc dùng đúng; bật cảnh báo unused trong CI (detekt/ktlint).

---

## Phụ lục A — Ưu tiên sửa đề xuất

**Đợt 1 (ổn định & đúng đắn, ~1–2 ngày) — ✅ đã hoàn tất**
1. P0-2: chặn rebuild toàn trang mỗi 5s (tách render, so sánh id).
2. P0-3: xử lý inset edge-to-edge.
3. P1-4, P1-3, P1-5: sửa hành vi nút nhỏ nhưng rõ ràng.
4. P2-6, P2-5: guard auto-hide và icon flash.

**Đợt 2 (tính năng thiếu, ~3–5 ngày) — ✅ đã hoàn tất**
5. P0-1: queue sheet + nối các lệnh đã có.
6. P1-1, P2-7: mô hình chất lượng có cấu trúc (Auto/Fixed + effective).
7. P1-2: chế độ live đúng.
8. P1-6: đẩy phụ đề khi hiện điều khiển.

**Đợt 3 (chất lượng & thẩm mỹ) — ✅ đã hoàn tất**
9. P2-1/P2-2: `RecyclerView` + avatar/badge bình luận.
10. P2-3, P2-4, P2-8, P2-9, P2-10.
11. P3-1…P3-5: resource hoá chuỗi, dọn mã chết.

## Phụ lục B — Ghi chú kiểm chứng

- Không tìm thấy bất kỳ tham chiếu UI nào tới `queue.queue`, `hasNext`, `shuffleQueue`, `clearQueue`, `cycleRepeatMode` trong `ui/` (chỉ ViewModel expose).
- `docs/PLAYER_REDESIGN.md` ghi rõ giai đoạn 5 (tách UI/state) **chưa bắt đầu** và live nên ẩn next/previous — nhiều lỗi trên là hệ quả trực tiếp của việc này.
- `targetSdk = 36` là nguyên nhân khiến P0-3 mang tính bắt buộc, không phải "nice to have".
- Đợt 1 và đợt 2 đã build xanh trên GitHub Actions (unit test + release APK). Đợt 3 mới được rà soát tĩnh trong môi trường không có JDK/Android SDK; cần chờ CI và kiểm tra thủ công: sheet bình luận (cuộn phân trang, avatar/tick/tim), mix sheet (badge "Đang phát"), đếm ngược hẹn giờ + snackbar, PiP (aspect video dọc, nút điều khiển, auto-enter trên Android 12+), bố cục 2 cột trên tablet/landscape và thao tác play/pause ngay khi chuyển video trước khi phát hành.

### Đợt 4 — lỗi tồn dư sau rà soát lại

Rà soát mới (không nằm trong 24 mục gốc) phát hiện và đã sửa:

- **Bố cục 2 cột**: `PlayerNativeView.applyWindowInsets` chỉ chừa inset trái/phải/đáy, cột phải bắt đầu từ `y = 0` nên tiêu đề/thông tin nằm dưới thanh trạng thái. Đã chừa `bars.top` cho `detailsScroll` khi ở bố cục 2 cột.
- **PiP**: `updatePipParams()` return sớm khi đang ở PiP nên icon play/pause không cập nhật sau khi bấm nút; tỉ lệ video dọc chỉ biết sau khi giải mã khung hình đầu nhưng không có hook làm mới. Đã bỏ return sớm (chỉ tắt auto-enter khi đã ở PiP), thêm `onVideoAspectRatioChanged` → `onPipParamsInvalidated` → `updatePipParams()`.
- **Live + vuốt xuống**: điều kiện `!isLive` chặn cả cử chỉ thu nhỏ trên video trực tiếp. Đã chỉ chặn tua ngang, vẫn nhận vuốt dọc để thu nhỏ.
- **Chuỗi hardcode**: thông báo lỗi phát, SponsorBlock (miễn trừ/áp dụng/hoàn tác/bỏ qua) và hàng đợi (thêm/đã có/phát tiếp/xáo trộn/xoá) cùng nhãn "Tự động · …" đã chuyển sang `strings.xml`; `SponsorBlockController` nhận `Context` để lấy chuỗi.
- **Sheet mix/hàng đợi**: khi video tự chuyển lúc sheet đang mở, dòng "Đang phát"/badge không được cập nhật. Đã thêm `refreshMixSheet()` và `refreshQueueSheetForVideo()` gọi từ `updatePlayerState()`.

Các thay đổi đợt 4 được xác minh bằng CI GitHub Actions (unit test + release APK) do môi trường phát triển không có JDK/Android SDK.

### Đợt 5 — thanh trạng thái che vùng video (báo cáo mới)

**Bằng chứng**
- `PlayerNativeView.updateLayoutMetrics()` đặt `videoContainer` tại `topMargin = 0` và `detailsScroll.topMargin = videoHeight`; Activity dùng `enableEdgeToEdge()` nên trong portrait video 16:9 bị vẽ ngay dưới `y = 0`, tức nằm sau thanh trạng thái.
- `PlayerHudView.applyWindowInsets()` chỉ dịch `topBar` xuống, không đẩy được hình video ra khỏi thanh trạng thái.

**Ảnh hưởng**
Phần trên của hình video và vùng tai thỏ bị thanh trạng thái che; nội dung/điều khiển khó nhìn trên máy có tai thỏ.

**Hướng sửa**
- Lưu `topInset` từ `WindowInsetsCompat.Type.systemBars() or displayCutout`, dùng làm `topMargin` cho `videoContainer` và cộng vào `detailsScroll.topMargin` ở chế độ thường; fullscreen/PiP vẫn tràn viền (`topMargin = 0`).
- Khi video đã được đẩy xuống, không truyền `bars.top` cho HUD nữa (tránh dịch hai lần); chỉ fullscreen/PiP mới dùng inset trực tiếp.
- Thanh trạng thái giờ nằm trên nền `pure_background`, nên đổi icon sáng/tối theo `UI_MODE_NIGHT_MASK` thay vì luôn dùng icon sáng.

### Đợt 6 — cụm điều khiển chồng nhau khi nhúng, xoay/thoát toàn màn hình

**Bằng chứng**
- `PlayerHudView` luôn dùng kích thước của trình phát toàn màn hình: thanh trên 56dp, nút play 72dp, thanh dưới ~96dp. Trình phát nhúng trong trang xem chỉ cao `width * 9 / 16` (máy 360dp rộng ≈ 202dp), nên tổng 224dp khiến thanh trên, nút play và thanh dưới đè lên nhau.
- `PlayerNativeView.updateLayoutMetrics()` tính chiều cao video và vị trí `detailsScroll` từ `width`/`height` của view. Khi xoay màn hình, `onConfigurationChanged()` chạy **trước** khi view được đo lại, nên bố cục bị tính bằng kích thước của hướng cũ → video và trang thông tin chồng lên nhau một nhịp.
- `PlayerNativeView.applyWindowInsets()` cũng dùng `width`/`height` cũ để tính inset đáy HUD, và không được áp lại khi chỉ đổi cấu hình (không có inset mới).
- `onConfigurationChanged()` chỉ gọi `updateLayoutMetrics()` (không áp lại inset); xoay từ fullscreen landscape về portrait vẫn giữ `isFullscreen = true` nên video phủ kín màn hình dọc, trang thông tin biến mất.

**Ảnh hưởng**
Trình phát nhúng trông rối, các nút chồng lên thanh tua và tiêu đề; thoát fullscreen thấy các thành phần nhảy/đè lên nhau; xoay máy trong fullscreen không thoát được chế độ toàn màn hình.

**Hướng sửa**
- `PlayerHudView.setCompactMode()`: chế độ gọn cho trình phát nhúng — thanh trên 44dp, ẩn dòng kênh, nút play 52dp, thanh nút 40dp, ẩn nút chuyển bài và nhãn thời gian còn lại; gradient trên/dưới thu nhỏ để không phủ kín video; cụm giữa được dịch lên đúng giữa khoảng trống giữa thanh trên và thanh dưới.
- `PlayerNativeView` bật/tắt chế độ gọn theo `isFullscreen`/`inPip` trong `updateLayoutMetrics()`.
- Bố cục và inset lấy kích thước từ `resources.displayMetrics` (kích thước cửa sổ hiện tại) thay vì `width`/`height` của view; `onSizeChanged()` tính lại cả khi chỉ chiều cao đổi.
- Thêm `PlayerNativeView.onWindowConfigurationChanged()` (tính lại bố cục + áp lại inset + `requestApplyInsets`) và gọi từ `PlayerActivity.onConfigurationChanged()`.
- Xoay về portrait khi đang fullscreen sẽ tự `setFullscreen(false)`, trả lại thanh hệ thống và trang thông tin thay vì giữ video phủ kín màn hình dọc.

### Đợt 7 — tinh chỉnh còn lại sau đợt 6

Rà soát tiếp sau khi đợt 6 lên CI phát hiện các điểm sau và đã sửa:

- **Chế độ gọn vẫn hơi chật**: thanh trên 44dp và nút play 52dp vẫn chiếm gần hết khung video nhúng (~202dp khi máy 360dp rộng). Hạ thanh trên còn 40dp, nút play 48dp để cụm giữa có khoảng thở rõ hơn.
- **Nút khoá và nút bỏ qua đoạn**: `hud_lock`/`hud_sponsor_skip` đặt cách đáy 88dp/96dp trong fullscreen, thấp hơn đỉnh thanh dưới (~90dp do dòng thời gian + thanh tua + hàng nút), nên chạm mép thanh tua/dòng thời gian. Nâng cả hai lên 104dp.
- **Icon thanh hệ thống trong fullscreen**: khi vuốt để hiện tạm thanh trạng thái/điều hướng, icon vẫn theo theme sáng (icon tối) nên gần như vô hình trên video tối. Fullscreen giờ luôn dùng icon sáng; thoát fullscreen trả lại theo `UI_MODE_NIGHT_MASK`.
- **Thoát fullscreen quá rộng**: `onConfigurationChanged()` thoát fullscreen với mọi thay đổi cấu hình khi hướng là portrait (đổi cỡ chữ, cửa sổ nhiều mảnh, gập/mở máy). Giờ theo dõi `lastOrientation` và chỉ thoát khi thực sự xoay ngang → dọc.
- **Chuỗi hardcode còn lại**: resource hoá "Đăng ký/Thích/Chia sẻ/Lưu/Phát nền/Hẹn giờ" (`view_player_info.xml`), "Bình luận/Xem tất cả" (`view_player_comments_preview.xml`), "Xem thêm" (`view_player_description.xml`), "Video liên quan" (`view_player_related_header.xml`) và placeholder thời gian trong HUD; thêm `player_dislike` cho mô tả nút không thích.
- **Marker SponsorBlock đè lên núm tua**: `PlayerSeekBar.onDraw()` vẽ marker sau `super` nên vạch màu cắt ngang núm tua khi trùng vị trí. Vẽ lại thumb sau marker và canh marker theo đúng khoảng di chuyển của tâm thumb (trừ nửa bề rộng thumb ở hai đầu).

### Đợt 8 — làm gọn giao diện player

**Bằng chứng**
- `view_player_hud.xml` có hai nút `hud_rewind`/`hud_forward` (chỉ hiện ở fullscreen) trùng chức năng với double-tap trái/phải đã có sẵn trong `PlayerHudView`.
- Nút `hud_queue` mở sheet "Hàng đợi" dựng thủ công trong `PlayerNativeView`; danh sách kết hợp (`hud_mix`) đã đảm nhận vai trò playlist.
- Sau khi bỏ hai nhóm nút, phần lớn mã của sheet hàng đợi, callback và các hàm quản lý hàng đợi ở `PlaybackCoordinator`/`PlaybackController`/`PureTubeViewModel`/`PlaybackQueue` trở thành mã chết.

**Hướng sửa**
- Bỏ `hud_rewind`, `hud_forward` khỏi layout và HUD; giữ double-tap 10 giây (vẫn dùng `onRewind10`/`onForward10`).
- Bỏ `hud_queue`, `onOpenQueue`, `showQueueSheet()`, `refreshQueueSheet*()`, `createQueueRow()`, `iconAction()`, `textAction()`, `sheetHint()` cùng các callback `onPlayQueueItem`/`onRemoveQueueItem`/`onMoveQueueItem`/`onShuffleQueue`/`onClearQueue`.
- Bỏ các hàm quản lý hàng đợi không còn nơi gọi: `playQueueItem`, `removeQueueItem`, `moveQueueItem`, `shuffleQueue`, `clearQueue` ở facade/coordinator; `prepareQueueItem`, `removeQueueItem`, `moveQueueItem`, `shuffleQueue`, `clearQueue` và tham số `clearPersistedQueue` ở `PlaybackQueue`; `QueueStore.clear()`.
- Giữ nguyên hàng đợi logic và hai thao tác còn dùng ở trang chủ: "Phát tiếp theo" và "Thêm vào hàng đợi".
- Xoá chuỗi/drawable chỉ phục vụ hai nhóm nút đã bỏ (`player_queue`, `player_rewind_10`, `player_forward_10`, `ic_replay_10`, `ic_forward_10`, mũi tên lên/xuống của sheet hàng đợi).

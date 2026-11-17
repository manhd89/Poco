# PureTube

Trình phát YouTube gọn nhẹ cho Android: xem video không quảng cáo, phát nền, hình trong hình (PiP) và lưu thư viện cá nhân ngay trên thiết bị.

PureTube lấy dữ liệu công khai từ YouTube qua InnerTube (giao thức web/ứng dụng của YouTube) và phát trực tiếp bằng AndroidX Media3 (ExoPlayer). Ứng dụng **không** dùng API key, không có tài khoản. Khi bật SponsorBlock (mặc định bật), ứng dụng chỉ gửi 4 ký tự đầu của SHA-256(video ID) tới máy chủ SponsorBlock tương thích hash-prefix để lấy danh sách đoạn cộng đồng đánh dấu — có thể tắt hoàn toàn trong Cài đặt hoặc tự host máy chủ.

## Tính năng

### Xem video
- Tìm kiếm video, gợi ý từ khóa thật từ YouTube, tìm bằng giọng nói.
- Trình phát DASH/HLS/MP4 với tự động chuyển nguồn khi một nguồn lỗi.
- Chọn chất lượng (Tự động, 2160p, 1440p, 1080p, 720p, 480p, 360p...), tốc độ phát 0.5x–3x, bật/tắt phụ đề có sẵn của video.
- Trình phát kiểu YouTube: nút play/pause lớn, tua 10 giây, video kế tiếp, thanh tiến trình đỏ kèm thời gian còn lại, khóa điều khiển khi xem toàn màn hình, tự ẩn HUD và chạm để hiện lại.
- Tua 10 giây bằng double-tap trái/phải (kiểu YouTube), double-tap giữa để phát/tạm dừng; kéo ngang trên video hoặc kéo thanh tua để tìm vị trí; xem trực tiếp (live) có nhãn riêng.
- Cử chỉ nâng cao khi xem toàn màn hình: vuốt dọc nửa trái để chỉnh độ sáng, nửa phải để chỉnh âm lượng, chạm giữ để khoá/mở khoá điều khiển và pinch-to-zoom khung hình.
- Bảng cài đặt trong trình phát gồm chất lượng, tốc độ phát, phụ đề và hẹn giờ tắt; PiP có nút riêng trên trình phát.
- Trang xem kiểu YouTube: mô tả đầy đủ lấy thật từ video, lượt xem/thích, avatar và số người đăng ký của kênh.
- "Video tiếp theo" dùng thumbnail rộng, thông tin kênh bên dưới và công tắc tự động phát thật; trên tablet chuyển thành danh sách gọn ở cột phải.
- "Danh sách kết hợp" mở ngay trong trình phát bằng nút playlist trên HUD, không còn chiếm thẻ trên trang xem.
- Bình luận thật từ YouTube (kể cả bình luận ghim, tác giả, lượt thích, phản hồi), mở rộng "Xem N câu trả lời" và tải thêm theo trang, có nút thử lại khi lỗi mạng.
- Tự bỏ qua đoạn tài trợ/quảng cáo trong video bằng dữ liệu cộng đồng SponsorBlock (sponsor, self-promo, tương tác...; hiện thông báo khi tua và có thể tắt trong Cài đặt).
- **SponsorBlock nâng cao:** marker màu theo từng danh mục trên thanh tua, chế độ Tự động / Thủ công / Chỉ đánh dấu / Tắt cho từng danh mục, nút "Bỏ qua" và "Hoàn tác" trong 5 giây, tôn trọng thao tác tua lại, miễn trừ theo kênh và API tự chọn (self-host). Cấu hình SponsorBlock nằm trong Cài đặt chung.
- Hàng đợi phát được lưu qua các phiên; thêm phát tiếp theo, sắp xếp, xáo trộn và xoá hàng đợi.
- Ẩn/hiện bình luận, video liên quan và mô tả video trên trang xem (Cài đặt → Nội dung trang xem).
- Xuất/khôi phục bản sao lưu JSON gồm cài đặt, video đã lưu, lịch sử xem, lịch sử tìm kiếm, danh sách kênh miễn trừ SponsorBlock và các kênh đăng ký cục bộ.
- Tự phát video liên quan khi hết video (có thể tắt trong Cài đặt).
- Tiếp tục xem từ vị trí đã dừng, thanh tiến độ hiển thị trên thẻ video và trong Thư viện.

### Phát nền & PiP
- Player dùng chung ở cấp tiến trình: thu nhỏ trình phát vẫn tiếp tục phát audio.
- `MediaSessionService` + notification điều khiển (phát/tạm dừng, chuyển bài) trên lock screen và tai nghe Bluetooth.
- Phát nền bật sẵn; tự chuyển PiP khi rời ứng dụng mặc định tắt (bật trong Cài đặt), hoặc bấm nút PiP trong trình phát. Khi bấm nút PiP, ứng dụng thu xuống nền và cửa sổ PiP nổi trên Home hoặc ứng dụng trước đó; mở rộng PiP rồi thoát sẽ quay lại đúng trang đang xem.
- Toàn màn hình ngang, ẩn thanh hệ thống, thoát bằng nút Back hoặc nút thu nhỏ.
- Hẹn giờ tắt (sleep timer) 15–60 phút.

### Đăng ký cục bộ
- Bấm **Đăng ký** ngay trên trang xem để theo dõi kênh mà không cần tài khoản YouTube; danh sách kênh chỉ lưu trên thiết bị.
- Tab **Đăng ký** gộp video mới nhất từ các kênh đã theo dõi, lọc theo kênh, kéo xuống để làm mới và mở **Quản lý kênh đăng ký** để hủy đăng ký.
- Cuộn gần cuối feed để nối thêm các trang video cũ hơn của từng kênh; token continuation được lưu cùng kênh trong Room nên mở lại app vẫn cuộn tiếp đúng chỗ.
- Feed lấy từ trang kênh qua InnerTube (RSS/Atom là dự phòng) và được đệm trong Room, nên mở lại tab không phải gọi mạng ngay; xem chi tiết thiết kế tại [`docs/local-subscriptions.md`](docs/local-subscriptions.md).

### Thư viện & dữ liệu
- Yêu thích và Xem sau lưu bằng Room, sắp xếp theo thời điểm thêm.
- Lịch sử xem kèm tiến độ, nhóm theo Hôm nay / Hôm qua / ngày cụ thể.
- Tìm kiếm trong Thư viện, xoá từng video khỏi lịch sử hoặc xoá toàn bộ.
- Lịch sử tìm kiếm lưu trên thiết bị, xoá từng từ khoá.
- Mọi cài đặt (phát nền, tự phát tiếp, PiP, tốc độ, chất lượng, resume, SponsorBlock, nội dung trang xem) được lưu qua các phiên.
- Xuất bản sao lưu JSON và khôi phục trên thiết bị khác qua bộ chọn tệp của hệ thống.

## Giao diện kiểu YouTube

- Toàn bộ giao diện dùng XML View native (AppCompat + Material Components) với RecyclerView và ViewBinding; không còn phụ thuộc Jetpack Compose.
- Bộ icon dùng nét tròn đồng nhất theo phong cách [Hugeicons](https://hugeicons.com) (MIT), gồm icon trình phát, điều hướng và hành động.
- Toàn bộ giao diện được thiết kế lại theo bố cục ứng dụng YouTube: thanh logo và hành động trên cùng, dải chip danh mục, feed thumbnail tràn viền, thẻ video kèm avatar kênh, mini player phẳng với vạch tiến độ đỏ.
- Trang chủ tách thành hai tab **Thịnh hành** và **Dành cho bạn** ngay dưới thanh logo; mỗi tab giữ vị trí cuộn riêng.
  - **Thịnh hành** gồm dải chip danh mục và feed thịnh hành. Cuộn gần cuối sẽ tự tải trang kế tiếp bằng continuation của YouTube; với chip **Tất cả**, các feed Âm nhạc/Trò chơi/Tin tức/Học tập cùng tiến một trang và được trộn lại. Đây là tab mặc định khi chưa có lịch sử xem.
  - **Dành cho bạn** gồm **Xem tiếp**, **Danh sách kết hợp** và **Đề xuất cho bạn**. Khi cuộn gần cuối, các video gợi ý còn lại của mix được nối trước, sau đó mỗi mix tải thêm một trang mới và được trộn đều, nên feed tiếp tục vô hạn mà không lặp video. Đây là tab mặc định khi đã có lịch sử xem; nếu lịch sử bị xoá sạch, trang chủ tự quay về Thịnh hành.
- Chip danh mục của tab Thịnh hành chỉ gồm những tab có feed công khai thật từ YouTube: **Tất cả · Âm nhạc · Trò chơi · Tin tức · Học tập**. Các danh mục không có feed công khai (Công nghệ, Lofi, Podcast) đã được bỏ thay vì giả lập bằng tìm kiếm; không còn danh sách video hard-code khi ngoại tuyến.
- Bảng màu cố định giống YouTube — nền trắng/đen, chữ đen/trắng, điểm nhấn đỏ `#FF0000`, liên kết xanh (`#065FD4` ở nền sáng, `#3EA6FF` ở nền tối). Không dùng Material You động để giao diện luôn đồng nhất.
- Tự theo chế độ Sáng/Tối của hệ thống, không có công tắc giao diện riêng.
- Thanh điều hướng dưới gồm **Trang chủ · Đăng ký · Bạn**; tab Đăng ký hiển thị feed thật của các kênh người dùng đã đăng ký cục bộ.
- Trang tìm kiếm toàn màn hình với ô tìm bo tròn, mic, gợi ý, lịch sử và bộ lọc sắp xếp.
- Trang "Bạn" có hồ sơ, chip bộ sưu tập (Lịch sử / Đã thích / Xem sau) và danh sách nhóm theo ngày; Cài đặt mở từ biểu tượng bánh răng.
- Ba lớp kích thước cửa sổ:
  - **Compact (< 600dp)**: thanh điều hướng dưới, mini player nằm ngay trên thanh điều hướng.
  - **Medium (600–839dp)**: navigation rail bên trái, nội dung rộng hơn.
  - **Expanded (>= 840dp)**: ngăn kéo điều hướng cố định bên trái, phù hợp tablet lớn và chế độ desktop.
- Lưới video tự chọn 1–4 cột theo bề rộng; thẻ video, header và khoảng cách được tinh chỉnh cho từng lớp.
- Trang xem video chuyển sang bố cục hai cột (trình phát + thông tin/đề xuất) trên tablet hoặc khi xoay ngang đủ rộng; điện thoại dọc giữ bố cục một cột.
- Màn hình Cài đặt chia hai cột trên thiết bị Expanded và giữ một cột trên điện thoại.
- Hỗ trợ đa nhiệm split-screen (`resizeableActivity`) và tự điều chỉnh khi bàn phím ảo mở.

## Yêu cầu

- Android 7.0 (API 24) trở lên.
- Kết nối mạng để tải feed, tìm kiếm và phát video.
- Để build: JDK 21, Android SDK Platform 36.1 + Build-Tools 36.1.0, Gradle wrapper đi kèm.

## Build

```bash
# APK debug
./gradlew assembleDebug

# APK release (cần keystore + biến môi trường bên dưới)
export KEYSTORE_PATH=/duong/dan/puretube-upload.jks
export STORE_PASSWORD=...
export KEY_PASSWORD=...
export KEY_ALIAS=upload
./gradlew assembleRelease
```

- APK debug: nếu có `KEYSTORE_PATH`, dùng luôn keystore đó; nếu không, dùng `debug.keystore` ở thư mục gốc dự án.
- APK release: bắt buộc `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD`; `KEY_ALIAS` mặc định là `upload`.
- Kết quả: `app/build/outputs/apk/debug/` và `app/build/outputs/apk/release/`.

Chạy kiểm thử:

```bash
./gradlew testDebugUnitTest
```

## Ký APK trên GitHub Actions

Workflow `.github/workflows/build-apk.yml` dùng keystore để ký **bản release**. Thêm các secret sau vào repo (`Settings → Secrets and variables → Actions`):

| Secret | Giá trị |
| --- | --- |
| `KEYSTORE_BASE64` | Nội dung file `.jks` mã hoá Base64 |
| `STORE_PASSWORD` | Mật khẩu keystore |
| `KEY_PASSWORD` | Mật khẩu của key |
| `KEY_ALIAS` | Alias của key (thường là `upload`) |

Có thể thêm nhanh bằng GitHub CLI:

```bash
base64 -w0 puretube-upload.jks | gh secret set KEYSTORE_BASE64 --repo luxysiv/PureTube
gh secret set STORE_PASSWORD --repo luxysiv/PureTube
gh secret set KEY_PASSWORD --repo luxysiv/PureTube
printf 'upload' | gh secret set KEY_ALIAS --repo luxysiv/PureTube
```

Workflow build và đính kèm `PureTube-release-apk`. Nếu repo không có đủ signing secrets (ví dụ PR từ fork), bước build release bị bỏ qua thay vì làm fail toàn bộ CI.

> Không commit keystore hay mật khẩu vào repo. File `*.jks`, `*.keystore` đã nằm trong `.gitignore`.

## Kiến trúc

```
HomeActivity ─────────── HomeViewModel
SubscriptionsActivity ── SubscriptionsViewModel
SearchActivity ───────── SearchViewModel
LibraryActivity ──────── LibraryViewModel
SettingsActivity ─────── SettingsViewModel
        │
        ├── PageActivity         # khung chung: nav + mini player + trình phát overlay
        │                        # (PlayerViewModel dùng chung engine cấp process)
        ├── PureTubeApplication  # đếm Activity để nhận biết app xuống nền
        ├── PageNavigator        # điều hướng giữa các Activity
        │
        └── PureTubeViewModel (lớp dùng chung: playback + thư viện)
                ├── PlaybackControllerProvider → PlaybackCoordinator (cấp process)
                ├── YouTubeDataRepository
                │       ├── YouTubePlayerDataSource   # luồng phát + phụ đề từ InnerTube player
                │       ├── YouTubeMetadataDataSource # chi tiết/liên quan/bình luận từ InnerTube next
                │       ├── YouTubeCommentsDataSource # bình luận + phản hồi (continuation)
                │       ├── YouTubeMixDataSource      # danh sách kết hợp
                │       ├── YouTubeWebDataSource      # feed/browse/tìm kiếm dự phòng
                │       └── SearchSuggestionDataSource
                ├── Room (VideoDao)                 # yêu thích, xem sau, lịch sử
                ├── SubscriptionRepository          # kênh đăng ký cục bộ + feed InnerTube/RSS đệm trong Room
                ├── SettingsStore / SearchHistoryStore / QueueStore / BackupManager
                ├── SponsorBlockDataSource          # API hash-prefix, cache, parse đoạn
                └── PlaybackEngineProvider          # ExoPlayer duy nhất
                        └── PlaybackService         # MediaSession + foreground notification
```

- Mỗi trang là một `Activity` + `ViewModel` riêng; chuyển trang dùng `PageNavigator` (reorder-to-front) nên trạng thái cuộn của từng trang được giữ lại.
- `PlaybackEngine` sở hữu một `ExoPlayer` duy nhất cho toàn bộ tiến trình. UI không tạo/giải phóng player, nên việc chuyển trang, thu nhỏ hay xoay màn hình không làm mất audio.
- `PlayerSurfaceHost` sở hữu một `PlayerView` duy nhất và **di chuyển** nó giữa HUD của trang xem, fullscreen, PiP và mini player. Trước đây mỗi nơi tự inflate một `PlayerView` riêng và tranh nhau `TextureView`, gây nháy đen khi chuyển màn hình.
- Cử chỉ lấy cảm hứng từ [Just Player](https://github.com/moneytoo/Player): double-tap tua 10 giây, kéo ngang để tìm vị trí, vuốt dọc trái/phải để chỉnh độ sáng/âm lượng khi fullscreen, pinch-to-zoom và chạm giữ để khoá điều khiển.
- Tiến độ phát nằm trong `PlaybackPosition` tách khỏi `PurePlayerState`, nên ticker 500 ms chỉ cập nhật phần HUD/mini player.
- Không còn phụ thuộc Firebase, Retrofit, Moshi hay plugin secrets.

## Quyền riêng tư

- Không có quảng cáo, không analytics, không tài khoản.
- Lịch sử xem, yêu thích, xem sau, danh sách kênh đăng ký, lịch sử tìm kiếm và cài đặt chỉ nằm trên thiết bị.
- Ứng dụng chỉ dùng mạng để lấy dữ liệu công khai từ YouTube.

## Giới hạn đã biết

- Chưa hỗ trợ Cast, tải video ngoại tuyến và trang kênh đầy đủ.
- Tab Đăng ký tải trang đầu qua InnerTube (khoảng 30 video mới nhất, có thời lượng) và cuộn thêm để nối các trang cũ hơn; RSS/Atom chỉ còn là dự phòng ~15 video và không phân trang. Vẫn chưa có thông báo video mới hay trang kênh đầy đủ. Danh sách kênh là cục bộ, không đồng bộ với tài khoản YouTube.
- Playlist/queue đầy đủ chưa có.
- Việc trích xuất phụ thuộc vào giao thức InnerTube của YouTube; khi YouTube thay đổi, một số video có thể cần bản cập nhật ứng dụng.

## Giấy phép & ghi công

- PureTube phát hành theo [GNU General Public License v3.0](LICENSE).
- [AndroidX Media3](https://github.com/androidx/media) © The Android Open Source Project, giấy phép Apache-2.0.
- PureTube không liên kết, không được tài trợ và không được xác nhận bởi YouTube/Google. Người dùng tự chịu trách nhiệm tuân thủ điều khoản dịch vụ của nền tảng.

Mã nguồn: <https://github.com/luxysiv/PureTube>

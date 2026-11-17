# Đăng ký cục bộ trong PureTube

Tài liệu này ghi lại cách PureTube triển khai tab **Đăng ký** mà không cần tài
khoản YouTube: kênh được lưu trong Room trên thiết bị, feed video mới lấy từ
trang kênh qua InnerTube (RSS/Atom là dự phòng) và chỉ gọi mạng khi bộ đệm đã cũ.

Thiết kế tham khảo PipePipe (bản fork GPL-3.0 của NewPipe):
<https://github.com/InfinityLoop1308/PipePipe>.

---

## 1. PipePipe/NewPipe đã làm gì

PipePipe thừa hưởng mô hình "local subscriptions" của NewPipe. Các thành phần
chính:

| Thành phần | Vai trò |
| --- | --- |
| `SubscriptionEntity` | Một kênh đã đăng ký: `uid`, `serviceId`, `url`, `name`, `avatarUrl`, `subscriberCount`, `notificationMode`. |
| `SubscriptionDAO` | Room DAO: quan sát danh sách kênh, thêm/xoá, đếm, tìm theo URL. |
| `SubscriptionManager` | Ghép `SubscriptionEntity` với dữ liệu kênh do extractor trả về; thêm/xoá kênh và phát sự kiện cho UI. |
| `FeedDatabaseManager` | Bảng feed/video đã tải (`FeedEntity`, `StreamEntity`): lưu video mới của từng kênh để mở lại không phải gọi mạng. |
| `FeedLoadManager` | Quyết định kênh nào cần tải lại, chạy request song song có giới hạn và ghi kết quả vào database. |
| `SubscriptionViewModel` / `FeedViewModel` | Chuyển dữ liệu Room thành trạng thái UI (danh sách kênh, feed gộp, đang tải, lỗi). |

Điểm quan trọng:

1. **Danh sách kênh là nguồn sự thật.** Feed chỉ là bộ đệm; xoá kênh thì feed
   của kênh đó cũng bị dọn.
2. **Feed được lưu riêng khỏi kênh.** Mở tab Đăng ký đọc Room trước, sau đó mới
   làm mới những kênh quá hạn. Nhờ vậy tab vẫn có nội dung khi ngoại tuyến và
   không tạo hàng chục request mỗi lần chuyển tab.
3. **Có hai cách lấy video mới:** gọi extractor trang kênh (nhiều request,
   phụ thuộc `ytInitialData`) hoặc dùng *feed extractor* (RSS/Atom). PipePipe
   dùng feed extractor cho đường tắt nhẹ; trang kênh đầy đủ vẫn dùng extractor
   thường.
4. **Tải theo lô.** `FeedLoadManager` giới hạn số request song song và có TTL
   cho từng kênh, tránh dồn request khi người dùng theo dõi nhiều kênh.
5. **Không có tài khoản.** Không có OAuth, cookie hay đồng bộ đám mây; bản sao
   lưu/khôi phục là cách mang danh sách kênh sang thiết bị khác.

---

## 2. Thiết kế của PureTube

PureTube giữ nguyên kiến trúc XML/View + Room + coroutine/Flow hiện có và chỉ
thêm đúng hai bảng:

```
subscriptions
  channelId (PK)  channelTitle  avatarUrl  subscriberCount  addedAt  lastFetchedAt  nextPageToken

subscription_videos
  videoId (PK)  channelId  title  thumbnailUrl  publishedAt  viewCount  duration  fetchedAt
```

- `subscriptions` tương đương `SubscriptionEntity` của PipePipe. Khoá chính là
  `channelId` (dạng `UC...`) để so khớp trực tiếp với `VideoItem.channelId`.
- `subscription_videos` tương đương phần feed đã đệm của
  `FeedDatabaseManager`. Tên/avatar kênh **không** lưu trùng ở đây mà đọc từ
  `subscriptions`, nên khi thông tin kênh đổi thì toàn bộ feed đổi theo.
- `lastFetchedAt` là mốc TTL 15 phút cho từng kênh; `nextPageToken` là
  continuation của trang cũ hơn trong feed kênh (rỗng = đã hết trang).
  `fetchedAt` là thời điểm video vào bộ đệm. Video cũ hơn 90 ngày bị dọn để
  bảng không phình mãi; việc dọn chỉ chạy ở lần làm mới trang đầu để không xoá
  mất video vừa nối thêm khi cuộn.

Các lớp chính:

| Tệp | Vai trò |
| --- | --- |
| `data/database/SubscriptionEntity.kt` | Bảng kênh đã đăng ký. |
| `data/database/SubscriptionVideoEntity.kt` | Bảng feed đệm + `toVideoItem(channel)`. |
| `data/database/SubscriptionDao.kt` | Quan sát/thêm/xoá kênh, cập nhật `lastFetchedAt` + `nextPageToken`. |
| `data/database/SubscriptionVideoDao.kt` | Quan sát feed, thay feed của một kênh trong transaction, nối thêm trang cũ, dọn video mồ côi. |
| `data/repository/SubscriptionFeedDataSource.kt` | Chọn nguồn feed: trang kênh InnerTube trước, Atom RSS dự phòng; trả kèm token trang sau. |
| `data/repository/ChannelVideoDataSource.kt` | Gọi `browse(channelId, tab Video)` hoặc `browse(continuation)` và parse video + token kèm thời lượng. |
| `data/repository/SubscriptionRepository.kt` | Đăng ký/hủy đăng ký, làm mới theo TTL, tải thêm một trang cho mọi kênh còn token, lô 4 request song song, dữ liệu cho backup. |
| `ui/subscriptions/SubscriptionsViewModel.kt` | Ghép kênh + feed thành `StateFlow` cho UI, quản lý trạng thái làm mới và tải thêm. |
| `ui/views/SubscriptionsNativeView.kt` | Chip lọc theo kênh, lưới video, kéo xuống làm mới, footer cuộn vô hạn, sheet quản lý/hủy đăng ký. |

### Luồng đăng ký

1. Người dùng bấm **Đăng ký** trên trang xem.
2. `PureTubeViewModel.toggleSubscription(video)` bổ sung `channelId` từ
   metadata trang xem khi thẻ video mở từ nguồn không có byline (bảng xếp hạng
   Âm nhạc, một số lockup), rồi gọi
   `SubscriptionRepository.toggleSubscription(video)`.
3. Nếu kênh chưa có, repository ghi `SubscriptionEntity` từ thông tin video
   (channelId, tên kênh, avatar) rồi tải feed ngay ở nền.
4. Nếu kênh đã có, repository xoá kênh và toàn bộ video đã đệm của kênh.
5. `subscribedChannelIds` là một `Flow<Set<String>>` từ Room; nút Đăng ký trên
   trang xem và tab Đăng ký cùng quan sát nên luôn khớp trạng thái. Trang xem
   cũng thu riêng snackbar của `PlayerViewModel` để thao tác luôn có phản hồi.

### Luồng tải feed

- Nguồn chính: InnerTube `browse` với `browseId=<channelId>` và tham số tab
  **Video** (`EgZ2aWRlb3PyBgQKAjoA`). Response dùng `lockupViewModel` giống
  trang kênh thật, trả ~30 video mới nhất kèm thời lượng; vị trí không có mốc
  thời gian được giữ đúng thứ tự bằng mốc giảm dần.
- Trang cũ hơn: response trang đầu (và mỗi trang continuation) chứa
  `continuationItemRenderer.continuationEndpoint.continuationCommand.token`.
  Token được lưu vào `subscriptions.nextPageToken`; khi người dùng cuộn gần
  cuối, mỗi kênh còn token được gọi thêm một trang và video mới được **nối
  thêm** vào bộ đệm (không xoá các trang đã tải). Hết token thì kênh dừng.
- Nguồn dự phòng: `https://www.youtube.com/feeds/videos.xml?channel_id=<channelId>`.
  Từ cuối 2025 máy chủ RSS của YouTube thường xuyên trả 404/500 hàng loạt nên
  không còn dùng làm nguồn duy nhất. RSS không có continuation, nên chỉ trang
  đầu mới có thể rơi vào nguồn này. Parser Atom đọc `entry/yt:videoId`,
  `title`, `published`, `media:group/media:thumbnail@url`,
  `media:group/media:community/media:statistics@views`; phần so khớp bỏ tiền tố
  namespace trước dấu `:` và tìm đệ quy các phần tử lồng.
- Chỉ tải kênh có `now - lastFetchedAt >= 15 phút` (hoặc tất cả khi người dùng
  kéo xuống làm mới). Lần làm mới thay toàn bộ video của kênh bằng trang đầu và
  đặt lại token, nên video đã biến mất khỏi feed cũng biến mất khỏi tab.
- Tối đa 4 request song song; một `Mutex` bảo đảm không có hai lần làm mới/tải
  thêm chạy chồng nhau.
- Lỗi mạng/JSON/XML của cả hai nguồn trả `null`: bộ đệm cũ được giữ nguyên
  thay vì xoá trắng. Khi tải thêm, kênh lỗi giữ nguyên token để lần thử lại sau
  không bỏ sót trang; footer **Thử lại** hiện khi không kênh nào tải được.

### UI

- Chip **Tất cả + từng kênh** lọc theo `channelId` (không theo tên, tránh nhầm
  hai kênh trùng tên).
- Kéo xuống để làm mới (`SwipeRefreshLayout`). Khi chưa có video, màn hình
  trống có nút **Làm mới**; khi chưa có kênh, nút **Khám phá video** mở Trang
  chủ.
- Cuộn gần cuối lưới để nối thêm video cũ hơn. Footer hiện spinner khi đang
  tải và nút **Thử lại** khi lỗi. Khi đang lọc theo một kênh, footer tạm ẩn vì
  phân trang hiện tiến tất cả kênh còn token cùng lúc.
- Nút **⋮** mở sheet **Kênh đã đăng ký** để hủy từng kênh. Hủy ở đây cũng xoá
  feed đệm tương ứng.

### Sao lưu

`BackupManager` phiên bản 2 thêm mảng `subscriptions` vào JSON. Bản sao lưu v1
vẫn đọc được (coi như không có kênh). Khi import:

- kênh trùng `channelId` được cập nhật (REPLACE); kênh mới được thêm;
- nếu import lỗi giữa chừng, `rollbackImport()` ghi đè lại đúng danh sách kênh
  trước đó và dọn feed mồ côi;
- feed không nằm trong backup — khi khôi phục, `lastFetchedAt` và
  `nextPageToken` được đặt lại 0/rỗng để lần mở tab kế tiếp tải feed ngay từ
  trang đầu, kể cả khi backup vừa được xuất.

---

## 3. Vì sao ưu tiên trang kênh InnerTube, RSS làm dự phòng

| | Trang kênh InnerTube (`browse`) | RSS/Atom |
| --- | --- | --- |
| Số request | 1 request/kênh | 1 request nhỏ/kênh |
| Độ ổn định | Dùng chung dữ liệu với trang kênh, ít bị chặn | Từ cuối 2025 thường xuyên 404/500 hàng loạt nhiều giờ |
| Dữ liệu | ~30 video mới nhất, tiêu đề, thumbnail, ngày đăng, lượt xem, thời lượng | ~15 video mới nhất, không có thời lượng |
| API key | Không | Không |
| Phân trang | Có continuation, đã dùng cho cuộn vô hạn | Không |

PureTube tải trang Video đầu tiên của kênh để có "có gì mới", rồi dùng
continuation khi người dùng cuộn gần cuối. RSS vẫn được giữ làm dự phòng vì
nhẹ và có mốc thời gian chính xác khi máy chủ feed hoạt động, nhưng chỉ dùng
cho trang đầu vì không có token.

---

## 4. Giới hạn đã biết

- Trang Video trả ~30 video mỗi trang; RSS dự phòng chỉ ~15 video và không phân
  trang, nên kênh chỉ lấy được dữ liệu từ RSS sẽ dừng sau trang đầu.
- Feed gộp tiến tất cả kênh còn token cùng lúc, chưa có phân trang riêng khi
  đang lọc theo một kênh.
- Không có thông báo video mới (chưa dùng WorkManager), không có trang kênh đầy
  đủ.
- Subscriber count của kênh đăng ký thường bằng 0; UI chỉ hiện số người đăng ký
  khi có dữ liệu từ metadata video đang xem.
- Nếu cả metadata trang xem lẫn `VideoItem.channelId` đều trống, nút Đăng ký
  báo "Video này không có thông tin kênh để đăng ký" thay vì lưu kênh rỗng.
- Xoá dữ liệu ứng dụng hoặc gỡ app sẽ mất danh sách kênh; dùng xuất bản sao lưu
  để giữ lại.

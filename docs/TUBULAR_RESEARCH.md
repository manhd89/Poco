# Nghiên cứu Tubular → đề xuất áp dụng cho PureTube

- **Ngày:** 04/11/2026
- **Đối tượng nghiên cứu:** [`polymorphicshade/Tubular`](https://github.com/polymorphicshade/Tubular), commit `e2ca29f`, phiên bản `0.28.4`
- **Trạng thái Tubular:** repo đã archive ngày 07/07/2026, không còn nhận cập nhật
- **Mục tiêu:** rút ra các ý tưởng tốt có thể áp dụng cho PureTube, **không port nguyên code** và không phụ thuộc vào Tubular
- **Phạm vi triển khai:** giữ tài liệu này làm tham chiếu nghiên cứu; đợt cải tiến hiện tại **loại trừ ReturnYouTubeDislike** theo quyết định của dự án, các hạng mục còn lại được đánh dấu trong lộ trình.

---

## 1. Kết luận điều hành

Tubular thực chất là **NewPipe + SponsorBlock + ReturnYouTubeDislike**. Giá trị lớn nhất của nó không nằm ở trình phát (NewPipe dùng ExoPlayer/Java/XML cũ), mà ở:

1. **Cách khai thác dữ liệu cộng đồng** (SponsorBlock, ReturnYouTubeDislike) và cách người dùng kiểm soát dữ liệu đó.
2. **Chi tiết UX quanh trình phát** (đánh dấu đoạn trên seek bar, skip thủ công, rewind ân hạn, tốc độ phát, cử chỉ).
3. **Hệ sinh thái NewPipe** (kênh, subscription không cần tài khoản, playlist, tải ngoại tuyến, backup).

PureTube hiện đã có nền tảng tốt hơn về kiến trúc: Compose, Media3, StateFlow, Room, player cấp tiến trình. Vì vậy nên **học ý tưởng và API**, không copy kiến trúc.

### Ưu tiên đề xuất

| Ưu tiên | Hạng mục | Giá trị | Công sức | Rủi ro |
|---|---|---|---|---|
| **P0** | ReturnYouTubeDislike | Cao | Nhỏ | Thấp |
| **P0** | SponsorBlock nâng cao: marker, category, manual skip | Cao | Vừa | Thấp–vừa |
| **P0** | Tốc độ phát > 2x + preset | Vừa | Rất nhỏ | Thấp |
| **P1** | Danh sách đoạn SponsorBlock + whitelist + API tự chọn | Cao | Vừa | Thấp |
| **P1** | Kênh + subscription cục bộ + feed | Rất cao | Lớn | Vừa |
| **P1** | Playlist + lưu hàng đợi qua các phiên | Cao | Vừa | Thấp |
| **P1** | Backup/restore + content settings | Vừa | Nhỏ–vừa | Thấp |
| **P2** | Tải ngoại tuyến | Cao | Rất lớn | Cao |
| **P2** | Seek preview, notification actions, popup mode | Vừa | Vừa | Vừa |
| **P3** | Vote/submit SponsorBlock, update checker | Thấp | Vừa | Cao |

**Ba việc nên làm ngay:** ReturnYouTubeDislike → SponsorBlock V2 → lưu hàng đợi/playlist.
**Một việc chỉ nên làm nếu thật sự cần:** tải ngoại tuyến.

### Trạng thái triển khai trong đợt này

| Hạng mục | Trạng thái | Ghi chú |
|---|---|---|
| ReturnYouTubeDislike | **Loại trừ** | Theo yêu cầu dự án, không triển khai trong đợt này. |
| SponsorBlock nâng cao | **Đã làm** | Marker theo category, 4 chế độ, Skip/Unskip 5 giây, graceful rewind, chain, `SeekParameters.EXACT`, whitelist, API tự chọn. Cấu hình nằm trong Cài đặt chung, không còn trong bảng cài đặt của trình phát. |
| Tốc độ phát > 2x | **Đã làm** | Preset 0.5x–3x trong cài đặt và trình phát. |
| Lưu hàng đợi qua phiên | **Đã làm** | JSON trong SharedPreferences (`QueueStore`). |
| Backup/restore | **Đã làm** | JSON gồm cài đặt, video đã lưu/lịch sử, lịch sử tìm kiếm, whitelist. |
| Content settings | **Đã làm một phần** | Ẩn/hiện mô tả, bình luận, video liên quan. Chưa có ngôn ngữ/quốc gia nội dung. |
| Playlist cục bộ | **Chưa làm** | Hàng đợi đã lưu bền nhưng chưa có playlist nhiều bộ sưu tập. |
| Kênh + subscription | **Chưa làm** | Cần extractor/kênh và Room schema mới. |
| Tải ngoại tuyến | **Chưa làm** | Ngoài phạm vi đợt cải tiến này. |
| Vote/submit, update checker | **Chưa làm** | Ưu tiên thấp. |

---

## 2. Tubular có gì đáng học

### 2.1. SponsorBlock: tích hợp sâu, không chỉ "tự tua"

Tubular cho người dùng kiểm soát gần như toàn bộ hành vi SponsorBlock.

#### Cấu hình

Các file đáng tham khảo:

- `app/src/main/res/xml/sponsor_block_settings.xml`
- `app/src/main/res/xml/sponsor_block_category_settings.xml`
- `app/src/main/java/org/schabi/newpipe/settings/SponsorBlockSettingsFragment.java`
- `app/src/main/java/org/schabi/newpipe/settings/SponsorBlockCategoriesSettingsFragment.java`

Tubular có:

- Bật/tắt toàn bộ SponsorBlock.
- **API URL tự chọn** (dùng server tự host hoặc official), kèm liên kết chính sách riêng tư.
- Bật/tắt thông báo khi tua.
- Bật/tắt nút skip/unskip thủ công.
- **Rewind pauses skipping**: tua lại vào đoạn đã tự bỏ qua thì tạm ngừng tự tua cho đến khi hết đoạn.
- Bật/tắt từng category, chọn **mode** cho từng category:
  - `Automatic`: tự tua.
  - `Manual`: chỉ hiện nút để người dùng bấm.
  - `Highlight Only`: không tua, chỉ đánh dấu/cho nhảy tới.
  - `Disabled`: bỏ qua category.
- **Màu riêng cho từng category** trên seek bar.
- Xoá whitelist.

Các category gồm:

`sponsor`, `intro`, `outro`, `interaction`, `poi_highlight` (highlight), `selfpromo`, `music_offtopic`, `preview`, `filler`, `pending`.

> Lưu ý: trong snapshot này, phần whitelist của Tubular **đã bị comment out** (`SponsorBlockDataManager` luôn trả về false). Đây là ý tưởng tốt nhưng code không dùng được — nếu PureTube làm thì phải làm lại đúng.

#### Hiển thị trên seek bar

- `app/src/main/java/org/schabi/newpipe/views/MarkableSeekBar.java`
- `app/src/main/java/org/schabi/newpipe/views/SeekBarMarker.java`

Tubular vẽ các đoạn đã đánh dấu thành các vạch màu chồng lên progress drawable. Người dùng nhìn thấy trước đoạn nào sắp bị tua, thay vì bị "nhảy" bất ngờ. Đây là cải thiện UX rất đáng làm trong Compose.

#### Hành vi trong player

- `app/src/main/java/org/schabi/newpipe/player/Player.java` (khoảng dòng 1077–1183)

Các điểm đáng học:

- Tìm đoạn theo `startTime`/`endTime`, có xử lý **chain** các đoạn liền nhau để không tua từng đoạn rời rạc.
- Dùng `SeekParameters.EXACT` tạm thời khi tua để tránh loop do keyframe.
- `lastSegment` + cửa sổ **5 giây** cho phép bấm "Un-skip" sau khi đã tua.
- Khi người dùng tua lại vào đoạn, có chế độ **graced rewind**: không tự tua lại ngay, để người dùng xem được đoạn đó.
- Toast thông báo loại đoạn đã bỏ qua, có thể tắt.
- Nút Skip/Unskip hiện trên player khi đoạn đang đi qua.

#### Tab "SponsorBlock" trong trang video

- `app/src/main/java/org/schabi/newpipe/fragments/list/sponsorblock/SponsorBlockFragment.java`
- `app/src/main/java/org/schabi/newpipe/fragments/list/sponsorblock/SponsorBlockSegmentListAdapter.java`

Tubular thêm một tab trong trang video cho phép:

- Xem **toàn bộ đoạn** của video: category, thời gian bắt đầu/kết thúc.
- Bấm vào mốc thời gian để nhảy tới đầu/cuối đoạn.
- **Vote up/down/reset** cho từng đoạn (long-press để reset).
- **Submit segment** do người dùng tự đánh dấu: chọn start/end, chọn category, gửi lên SponsorBlock.
- Bật/tắt skip cho riêng video.
- Whitelist kênh (trong snapshot bị hỏng, nhưng ý tưởng đúng).

#### API và quyền riêng tư

Tubular dùng extractor fork (`TubularExtractor`) với:

- `SponsorBlockExtractorHelper.java`
- `SponsorBlockCategory.java`
- `SponsorBlockAction.java`

Điểm đáng chú ý:

- Gọi endpoint dạng **hash prefix**:
  `skipSegments/{sha256(videoId).substring(0, 4)}?categories=...&actionTypes=["skip","poi"]`
  thay vì gửi thẳng `videoID=...`. Cách này giảm lộ ID video và tận dụng cache phân tán.
- Lấy cả `skip` và `poi`, nên hỗ trợ được **Highlight**.
- `poi_highlight` là category riêng, action là `poi`, không phải `skip`.
- Submit/vote cần `userID`; Tubular hiện tạo userID mới mỗi lần (không lý tưởng nếu muốn vote ổn định). Nếu PureTube làm, nên có userID ngẫu nhiên lưu bền và giải thích rõ.

### 2.2. ReturnYouTubeDislike: hiển thị số liệu thay vì mở web

Các file:

- `app/src/main/res/xml/return_youtube_dislikes_settings.xml`
- `app/src/main/java/org/schabi/newpipe/fragments/detail/VideoDetailFragment.java` (khoảng dòng 1602–1687)
- `TubularExtractor/.../ReturnYouTubeDislikeExtractorHelper.java`

Tubular hỗ trợ:

- Bật/tắt ReturnYouTubeDislike.
- API URL tự chọn.
- **Override like count** bằng số của RYD.
- **Override view count** bằng số của RYD.
- **Hiển thị dislike dưới dạng phần trăm** thay vì số tuyệt đối.
- Ẩn số dislike nếu API lỗi/không có dữ liệu.

API thực tế:

```text
GET https://returnyoutubedislikeapi.com/votes?videoId={videoId}
```

Response có các trường:

```json
{
  "id": "...",
  "likes": 19492176,
  "dislikes": 521565,
  "rating": 4.8957,
  "viewCount": 1833344472,
  "deleted": false
}
```

Điểm đáng học:

- Không chặn phát video; RYD là lớp dữ liệu phụ, lỗi thì fallback về số của YouTube.
- Giữ hành động "mở YouTube để dislike" vì app không có tài khoản; chỉ **hiển thị** số dislike.
- Có chế độ phần trăm rất phù hợp video tranh luận/đánh giá.
- Cần nói rõ đây là dịch vụ bên thứ ba: video ID được gửi tới server của RYD.

### 2.3. Chi tiết UX trình phát

Tubular/NewPipe có một số tinh chỉnh nhỏ nhưng hữu ích:

- **Max playback speed** chọn được 2x / 3x / 5x / 10x, thay vì giới hạn 2x.
- Nhiều preset tốc độ hơn; có thể gán **tốc độ phát vào cử chỉ giữa** (middle gesture).
- **Adjustable swipe areas**: đổi vùng trái/giữa/phải cho cử chỉ âm lượng/độ sáng/tốc độ.
- **Seek on hardware media button events**: dùng nút cứng để tua thay vì chuyển bài.
- **Ignore hardware media buttons** khi cần.
- **Seekbar preview thumbnail**: xem trước khung hình khi kéo seek bar.
- **Popup mode nhớ kích thước/vị trí**.
- **Notification actions tuỳ chỉnh** (Android < 13).
- **Resume on audio focus gain**: tự phát tiếp sau cuộc gọi/ngắt quãng.
- **Auto-queue**: tự thêm video liên quan vào hàng đợi.
- **Shuffle and play** cho playlist.
- **Hold to append**: giữ video để thêm vào hàng đợi.

PureTube đã có nhiều thứ trong danh sách này (PiP, background, sleep timer, queue, repeat, shuffle, captions, skip silence, pitch, data saver). Không cần làm lại; chỉ nên chọn các mục còn thiếu.

### 2.4. Hệ sinh thái nội dung kiểu NewPipe

Đây là các tính năng NewPipe mà Tubular thừa hưởng, không phải Tubular tự viết, nhưng vẫn đáng tham khảo:

- **Kênh**: xem trang kênh, tab video/shorts/live/playlist, subscribe không cần tài khoản.
- **Subscription cục bộ**: feed từ kênh đã đăng ký, thông báo video mới, import/export.
- **Playlist**: xem playlist từ YouTube, tạo playlist cục bộ, sửa/thêm/xoá/sắp xếp.
- **Tải ngoại tuyến**: video/audio/phụ đề, chọn thư mục, hàng đợi, retry, resume, giới hạn tải trên mobile.
- **Backup/restore**: export/import database hoặc JSON.
- **Content settings**: ngôn ngữ/nội dung quốc gia, ẩn/hiện mô tả, comment, video liên quan, age-restricted, restricted mode.
- **Tìm kiếm nâng cao**: lọc video/kênh/playlist, thời lượng, ngày đăng, sắp xếp.
- **Lịch sử**: bật/tắt ghi lịch sử, xoá lịch sử xem/tìm kiếm, xoá playback state.
- **Giao diện**: chọn tab, theme, mật độ danh sách, chất lượng ảnh.

---

## 3. Đối chiếu PureTube hiện tại

| Năng lực | Tubular | PureTube hiện tại | Khoảng trống |
|---|---|---|---|
| Phát video, PiP, nền, caption, sleep timer | Có | Có | — |
| Hàng đợi, repeat, shuffle | Có | Có (trong RAM) | Không lưu qua phiên |
| SponsorBlock tự tua cơ bản | Có | Có | Chỉ 5 category, không marker |
| SponsorBlock theo category + mode | Có | Không | Cần thêm |
| Marker màu trên seek bar | Có | Không | Cần thêm |
| Manual skip/unskip + rewind ân hạn | Có | Không | Cần thêm |
| Danh sách đoạn + jump + vote/submit | Có | Không | Cần thêm (P1/P3) |
| Whitelist kênh | Có (hỏng) | Không | Nên làm đúng |
| ReturnYouTubeDislike | Có | Không | Ưu tiên P0 |
| Trang kênh | Có | Không | Thiếu lớn |
| Subscription cục bộ + feed | Có | Có (Room + feed RSS, chưa có trang kênh) | — |
| Playlist YouTube/cục bộ | Có | Chỉ mix + queue tạm | Thiếu |
| Tải ngoại tuyến | Có | Không | Thiếu (P2) |
| Backup/restore | Có | Có (JSON; từ v2 gồm cả kênh đăng ký) | — |
| Search filter server-side | Có | Chỉ sort local | Thiếu |
| Content language/country | Có | Hardcode `vi`/`VN` | Thiếu |
| Notification actions | Có | Previous / play-pause / next (qua `QueueNavigationPlayer`) | Đủ cơ bản |
| Seek preview thumbnail | Có | Không | Thiếu |
| Tốc độ phát > 2x | Có | 0.5x–2x | Thiếu |
| Bình luận, mix, autoplay | Có | Có | — |

---

## 4. Đề xuất lộ trình

> Ước lượng công sức là tương đối cho một người quen codebase; chưa tính thời gian test thiết bị thật.

### P0 — Nên làm trong sprint tới

#### P0.1. ReturnYouTubeDislike (1–2 ngày)

**Mục tiêu:** hiện số dislike thật, không mở trình duyệt chỉ để xem.

**Phạm vi:**

- Tạo `data/repository/ReturnYouTubeDislikeDataSource.kt`:
  - OkHttp, timeout 3–5 giây, cache RAM theo `videoId` (TTL 10–15 phút).
  - Gọi `votes?videoId=...`, parse `likes`, `dislikes`, `rating`, `viewCount`, `deleted`.
  - Trả `null` khi lỗi/404; không làm hỏng luồng phát.
- Thêm dữ liệu vào `VideoDetails` hoặc một `StateFlow` riêng:
  - `dislikes: Long`
  - `rydLikes: Long`
  - `rydViews: Long`
  - `rating: Double`
  - `isDeleted: Boolean`
- `AppSettings` thêm:
  - `rydEnabled` (đề xuất mặc định **tắt**, opt-in rõ ràng)
  - `rydShowPercentage`
  - `rydOverrideLikes`
  - `rydOverrideViews`
  - `rydApiUrl`
- UI:
  - `PlayerDetailSheet.WatchLikeDislikePill`: hiện số dislike cạnh icon thumbs-down.
  - Nếu bật phần trăm: hiện `%` thay vì số.
  - Giữ hành động thumbs-down mở YouTube (không giả lập dislike).
  - Không thay đổi trạng thái "Yêu thích" cục bộ bằng số like của RYD.
- Settings:
  - Toggle, phần trăm, override like/view, API URL.
  - Ghi chú quyền riêng tư + link FAQ.
- Test:
  - Parse response đầy đủ, `deleted`, thiếu trường, JSON lỗi.
  - Cache hit/miss; timeout.
  - UI không crash khi `null`.

**Tiêu chí xong:** bật RYD thì số dislike hiện sau khi tải xong; tắt RYD thì hành vi giống hiện tại; offline không ảnh hưởng phát video.

#### P0.2. SponsorBlock nâng cao — bản MVP (3–5 ngày)

**Mục tiêu:** biến SponsorBlock từ "tự tua bí ẩn" thành tính năng nhìn thấy và kiểm soát được.

**Phạm vi:**

- Mở rộng `SponsorSegment`:
  - `actionType` (`skip` / `poi`)
  - `description` (nếu có)
  - `isHighlight`
- Sửa request:
  - Thêm `actionTypes=["skip","poi"]`.
  - Lấy category theo cấu hình người dùng, không hardcode.
  - Cân nhắc dùng endpoint hash prefix như Tubular để giảm lộ video ID.
- Parse:
  - Không bỏ qua `poi`; `poi_highlight` là highlight, không tự tua.
  - Bỏ qua đoạn không hợp lệ; xử lý UUID rỗng.
- Settings:
  - Bật/tắt toàn bộ.
  - Bật/tắt thông báo tua.
  - Với mỗi category: bật/tắt + mode `Automatic` / `Manual` / `Off` (có thể thêm `Highlight only` cho highlight).
  - Màu marker (có thể để mặc định trước, cho chỉnh sau).
- Player:
  - Vẽ marker màu trên progress bar.
  - Lọc đoạn theo mode trước khi tự tua.
  - Nút Skip/Unskip thủ công, cửa sổ 5 giây.
  - `graced rewind`: tua lại vào đoạn thì tạm ngừng tự tua đến khi hết đoạn.
  - Dùng `SeekParameters.EXACT` tạm thời khi tua để tránh loop.
  - Gộp các đoạn liền nhau (chain) để không tua nhiều lần.
- Trang xem:
  - Một sheet/tab "Đoạn" liệt kê category + thời gian; bấm để nhảy tới.
  - Chưa cần vote/submit ở MVP.

**Tiêu chí xong:** marker khớp thời gian; manual mode không tự tua; tua lại không bị "kéo" đi; không loop; tắt SponsorBlock thì không gọi mạng.

#### P0.3. Tốc độ phát và vài tinh chỉnh nhỏ (0.5–1 ngày)

- Preset tốc độ: `0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0` (có thể thêm 4x/5x nếu muốn).
- Cài đặt "tốc độ tối đa" kiểu Tubular: 2x / 3x / 5x / 10x.
- Toggle "thông báo khi tự tua" (hiện PureTube luôn hiện message).
- Giữ pitch độc lập khi đổi speed (PureTube đã có).

### P1 — Nên làm trong 1–2 sprint

#### P1.1. SponsorBlock bản đầy đủ (3–5 ngày)

- API URL tự chọn (self-host) + link privacy.
- `Highlight only`: chỉ đánh dấu, có nút nhảy tới highlight.
- Whitelist theo kênh (Room hoặc DataStore):
  - Thêm/xoá kênh khỏi whitelist.
  - Kênh trong whitelist thì không tự tua, nhưng vẫn hiện marker.
  - Nút "Whitelist kênh này" trong sheet đoạn.
- Danh sách đoạn:
  - Category, start/end, màu.
  - Bấm để seek.
  - (Tuỳ chọn) vote up/down/reset.
- (Tuỳ chọn) submit đoạn:
  - Chỉ làm nếu thật sự muốn đóng góp cộng đồng.
  - Cần userID ngẫu nhiên lưu bền, consent rõ ràng, xử lý lỗi/abuse.
- Toggle "hiện nút skip/unskip" và "rewind pauses skipping".

#### P1.2. Kênh + subscription cục bộ (1–2 tuần)

Đây là khoảng trống sản phẩm lớn nhất của PureTube.

**Giai đoạn A — xem kênh:**

- `ChannelInfo.getInfo` từ NewPipe Extractor.
- Màn hình kênh: avatar, tên, subscriber, mô tả, nút Subscribe.
- Tab: Video / Shorts / Live / Playlist (tối thiểu Video).
- Mở từ tên kênh/avatar trên thẻ video và trang xem.
- Phân trang danh sách video.

**Giai đoạn B — subscription + feed:**

- Room: `subscriptions`, `subscription_videos` (cache feed).
- Đã làm: tab "Đăng ký" hiện feed thật từ các kênh đã subscribe bằng feed RSS, không dùng video đã lưu (xem `docs/local-subscriptions.md`).
- Làm mới feed, đánh dấu đã xem, sắp xếp theo thời gian.
- Import/export subscription: đã gộp vào backup JSON (định dạng NewPipe vẫn để sau).
- Thông báo video mới: để sau, dùng WorkManager.

**Vì sao đáng làm:** tăng thời gian quay lại app, biến PureTube từ "trình phát link" thành "ứng dụng xem video" thực thụ.

#### P1.3. Playlist + lưu hàng đợi (5–7 ngày)

- Mở playlist YouTube bằng `PlaylistInfo.getInfo` (đã dùng cho mix, có thể tái sử dụng).
- Playlist cục bộ:
  - Tạo/xoá/đổi tên.
  - Thêm video, xoá, sắp xếp lại.
  - Phát toàn bộ, shuffle.
- Lưu hàng đợi hiện tại vào Room để mở lại app không mất.
- "Lưu hàng đợi thành playlist" — rất hữu ích và ít tốn kém.
- Trang playlist: tiêu đề, mô tả, tổng thời lượng, danh sách video.

#### P1.4. Backup/restore + content settings (3–5 ngày)

- Export/import JSON gồm:
  - Cài đặt.
  - Yêu thích, Xem sau.
  - Lịch sử xem (tuỳ chọn).
  - Lịch sử tìm kiếm.
  - Subscription, playlist (khi đã có).
  - Whitelist SponsorBlock.
- Content settings:
  - Ngôn ngữ nội dung + quốc gia (thay `Localization("vi","VN")` hardcode).
  - Ẩn/hiện mô tả, comment, video liên quan.
  - Chất lượng ảnh thumbnail.
  - Age-restricted / restricted mode (nếu extractor hỗ trợ ổn định).

### P2 — Cân nhắc sau

#### P2.1. Tải ngoại tuyến (2–4 tuần)

Chỉ làm nếu định vị sản phẩm có offline. Nếu mục tiêu là "gọn nhẹ", có thể bỏ qua.

**Pha 1 — đơn giản:**

- Tải progressive MP4 (video+audio sẵn) và audio M4A.
- Chọn thư mục bằng SAF.
- WorkManager + notification tiến độ, retry/resume.
- Trang "Đã tải" trong Thư viện.

**Pha 2 — đầy đủ:**

- DASH video-only + audio, ghép bằng Media3 Transformer (hoặc ffmpeg).
- Tải phụ đề.
- Tải playlist, giới hạn dung lượng, chỉ tải trên Wi-Fi.
- Kiểm tra quyền/ToS và dung lượng lưu trữ.

**Không nên** port nguyên download manager `us.shandian.giga` của NewPipe; quá lớn và lệch kiến trúc.

#### P2.2. Tinh chỉnh trình phát (khoảng 1 tuần)

- Seek preview thumbnail khi kéo seek bar (nếu extractor trả frameset).
- Notification actions tuỳ chỉnh (Media3 custom layout).
- Popup mode nhớ kích thước/vị trí.
- Resume sau audio focus gain.
- Chỉnh style phụ đề (cỡ chữ, nền, màu).
- Cử chỉ tuỳ chỉnh vùng trái/giữa/phải (nếu không xung đột với double-tap hiện tại).

#### P3 — Không ưu tiên

- SponsorBlock vote/submit (nếu chưa có nhu cầu cộng đồng).
- Update checker (có thể dùng GitHub releases; nhưng CI đã tạo APK).
- Cast/Kodi/external player.
- Hỗ trợ đa dịch vụ (PeerTube, SoundCloud...): không phù hợp định vị YouTube-first.

---

## 5. Ghi chú kỹ thuật & rủi ro

### 5.1. Extractor hiện tại không có SponsorBlock/RYD

PureTube từng dùng `NewPipeExtractor v0.26.5`; từ bản 1.7.0 ứng dụng gọi thẳng InnerTube và đã bỏ hoàn toàn dependency này. Khi còn dùng extractor, upstream **không có** package `sponsorblock` hay `returnyoutubedislike`; Tubular phải dùng extractor fork `TubularExtractor` mới có.

**Kết luận:** giữ hướng tự gọi API bằng OkHttp như `SponsorBlockDataSource` hiện tại. Không chuyển sang `TubularExtractor` vì:

- Đã archive, không còn cập nhật.
- Là fork lệch upstream, rủi ro bảo mật/bảo trì.
- PureTube chỉ cần một phần nhỏ, không cần cả extractor fork.

### 5.2. SponsorBlock API

- Endpoint hiện tại của PureTube dùng `videoID=`; Tubular dùng hash prefix. Có thể chuyển sang hash prefix để giảm lộ ID.
- Cần thêm `actionTypes=["skip","poi"]` nếu muốn highlight.
- Response có thể trả nhiều object; phải lọc đúng `videoID` nếu dùng hash prefix.
- `UUID` dùng để chống tua lặp; nếu rỗng phải sinh key dự phòng.
- Nên cache có TTL; cache RAM hiện tại mất khi kill app và không có negative cache.
- Khi API lỗi: im lặng, không chặn phát.

### 5.3. Logic tự tua

- Hiện tại `findSegmentToSkip` chỉ xử lý đoạn chứa vị trí và trong 2 giây đầu; dùng set UUID để không tua lại.
- Cần bổ sung:
  - Sắp xếp segment theo start.
  - Gộp chain.
  - Phân biệt `skip` và `poi`.
  - Tôn trọng thao tác seek tay.
  - Cửa sổ unskip 5 giây.
  - Tránh loop bằng `SeekParameters.EXACT`.
- Khi người dùng tua lại, không nên tự tua ngay; đây là khác biệt giữa "tiện" và "khó chịu".

### 5.4. ReturnYouTubeDislike

- Không đặt request RYD trên critical path của phát video.
- Cache theo video, TTL ngắn; tránh gọi lại khi xoay màn hình.
- `deleted=true` thì ẩn số liệu.
- Không dùng số like của RYD để thay trạng thái "Yêu thích" cục bộ.
- Nếu bật "override view count", có thể lệch với số YouTube đang hiển thị; nên mặc định tắt.
- Cập nhật README: hiện README nói "không gửi dữ liệu xem lên máy chủ nào", nhưng SponsorBlock/RYD có gửi video ID. Cần minh bạch và có toggle.

### 5.5. Quyền riêng tư

- SponsorBlock và RYD đều là dịch vụ bên thứ ba.
- Đề xuất:
  - Mặc định tắt RYD, hoặc bật sau khi người dùng đọc giải thích.
  - SponsorBlock có thể giữ bật mặc định như hiện tại, nhưng ghi rõ dữ liệu gửi đi.
  - Có mục "Dịch vụ bên thứ ba" trong Settings với link privacy/FAQ.
  - API URL tự chọn cho người muốn self-host.
- Không tạo userID nếu chỉ đọc dữ liệu; chỉ tạo khi vote/submit.

### 5.6. Hiệu năng UI

- Marker SponsorBlock chỉ nên tính lại khi `segments` hoặc `duration` đổi, không tính mỗi tick 500 ms.
- Dùng `Canvas`/`drawBehind` cho marker thay vì compose lại cả progress bar.
- RYD load sau khi player đã sẵn sàng; không block first frame.
- Tránh gọi API theo từng lần recompose.

### 5.7. Test

- Unit test parse SponsorBlock:
  - `skip`, `poi`, `poi_highlight`, `music_offtopic`.
  - JSON lỗi, 404, UUID rỗng, segment âm/ngược.
- Unit test chọn segment:
  - Đầu đoạn, giữa đoạn, sau đoạn.
  - Chain nhiều đoạn.
  - Rewind.
  - Category bị tắt.
- Unit test RYD:
  - Đầy đủ trường, `deleted`, thiếu trường, lỗi mạng.
- Test settings persistence/migration.
- Robolectric/Compose test cho marker và sheet đoạn (nếu có).

---

## 6. Những thứ không nên bê nguyên

1. **Kiến trúc Java/XML/ViewPager/RxJava** của NewPipe/Tubular. PureTube đang sạch hơn với Compose/Media3/Coroutines.
2. **Whitelist SponsorBlock** trong Tubular: code đã bị comment, luôn trả false.
3. **Download manager `us.shandian.giga`**: quá lớn, nhiều legacy, không cần cho một app gọn nhẹ.
4. **Submit/vote SponsorBlock** ngay từ đầu: cần userID, consent, xử lý abuse, testing cộng đồng.
5. **Update checker**: nếu phát hành qua GitHub Actions/F-Droid, có thể để store/CI lo.
6. **Phụ thuộc vào Tubular**: repo đã archive, không có bản vá extractor khi YouTube đổi.

---

## 7. Checklist hành động ngay

- [ ] Tạo `ReturnYouTubeDislikeDataSource` + model + cache.
- [ ] Thêm setting RYD (bật/tắt, phần trăm, API URL) + ghi chú privacy.
- [ ] Hiện số dislike trong `WatchLikeDislikePill`; giữ nút mở YouTube.
- [ ] Viết unit test parse RYD và trường hợp lỗi.
- [ ] Mở rộng `SponsorSegment` + request `actionTypes` + parse `poi`.
- [ ] Thêm setting category/mode + lọc trước khi tự tua.
- [ ] Vẽ marker màu trên progress bar.
- [ ] Thêm nút Skip/Unskip + graced rewind + exact seek.
- [ ] Thêm preset tốc độ > 2x và setting tốc độ tối đa.
- [ ] Cập nhật README (tính năng + privacy + giới hạn).

---

## 8. Nguồn tham chiếu

### Tubular

- Repo: <https://github.com/polymorphicshade/Tubular> (commit `e2ca29f`, đã archive)
- SponsorBlock settings XML: `app/src/main/res/xml/sponsor_block_settings.xml`
- SponsorBlock category settings: `app/src/main/res/xml/sponsor_block_category_settings.xml`
- SponsorBlock helper: `app/src/main/java/org/schabi/newpipe/util/SponsorBlockHelper.java`
- SponsorBlock player logic: `app/src/main/java/org/schabi/newpipe/player/Player.java`
- Seek bar marker: `app/src/main/java/org/schabi/newpipe/views/MarkableSeekBar.java`
- Segment list: `app/src/main/java/org/schabi/newpipe/fragments/list/sponsorblock/`
- RYD settings: `app/src/main/res/xml/return_youtube_dislikes_settings.xml`
- RYD display: `app/src/main/java/org/schabi/newpipe/fragments/detail/VideoDetailFragment.java`
- Extractor fork: <https://github.com/polymorphicshade/TubularExtractor>
  - `SponsorBlockExtractorHelper.java`
  - `ReturnYouTubeDislikeExtractorHelper.java`

### NewPipe

- Repo: <https://github.com/TeamNewPipe/NewPipe>
- README feature list (v0.28.4): <https://github.com/TeamNewPipe/NewPipe/blob/v0.28.4/README.md>
- Extractor: <https://github.com/TeamNewPipe/NewPipeExtractor> (v0.26.5 không có SponsorBlock/RYD)

### PureTube (đối chiếu)

- `app/src/main/java/com/luxysiv/puretube/data/repository/SponsorBlockDataSource.kt`
- `app/src/main/java/com/luxysiv/puretube/ui/MainViewModel.kt`
- `app/src/main/java/com/luxysiv/puretube/data/model/WatchData.kt`
- `app/src/main/java/com/luxysiv/puretube/ui/player/PlayerDetailSheet.kt`
- `app/src/main/java/com/luxysiv/puretube/ui/screens/SettingsScreen.kt`
- `app/build.gradle.kts`, `gradle/libs.versions.toml`
- `README.md` (mục Giới hạn đã biết và Quyền riêng tư)

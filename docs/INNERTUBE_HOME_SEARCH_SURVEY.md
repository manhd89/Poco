# Khảo sát: Trang chủ & Tìm kiếm dùng InnerTube, hiển thị playlist

- **Ngày:** 13/11/2026
- **Phạm vi:** tầng dữ liệu (`YouTubeWebDataSource`, `InnerTubeVideoMapping`, `YouTubeDataRepository`), ViewModel/UI Trang chủ & Tìm kiếm, luồng phát playlist.
- **Mục tiêu:** xác định hiện trạng, chỗ nghẽn và phương án để Trang chủ/Tìm kiếm lấy dữ liệu qua InnerTube và **hiển thị cả playlist** trong kết quả tìm kiếm.

---

## 1. Kết luận điều hành

1. **Trang chủ đã dùng InnerTube**, không scrape HTML cho feed. `HomeViewModel` → `YouTubeDataRepository.loadTrendingVideos` → `YouTubeWebDataSource.categoryVideos` → `youtubei/v1/browse` → `parseBrowseVideos`.
2. **Tìm kiếm cũng đã ưu tiên InnerTube**: `youtubei/v1/search` trước, chỉ scrape `youtube.com/results` khi InnerTube thất bại.
3. **Nút thắt thật sự không nằm ở transport mà ở model + parser + UI**:
   - Toàn bộ kết quả được ép về `VideoItem`.
   - `collectVideos` chỉ đọc `videoRenderer`, `videoWithContextRenderer`, `gridVideoRenderer`, `playlistVideoRenderer`.
   - `lockupVideoItem` (schema mới của YouTube) **chủ động loại** mọi `contentType` không phải video và bắt `contentId` dài 11 ký tự.
   - `SearchNativeView` chỉ có `SearchRow.Video`; không có card/row playlist.
4. **YouTube hiện trả playlist trong tìm kiếm dưới `lockupViewModel`** (`contentType = LOCKUP_CONTENT_TYPE_PLAYLIST`), không còn `playlistRenderer` như schema cũ. Đã xác minh bằng request thật ngày 13/11/2026.
5. **Không thể dùng feed trang chủ thật `FEwhat_to_watch` khi ẩn danh**: request trả về chỉ `feedNudgeRenderer`, không có video/playlist. Vì vậy hướng đúng là **giữ feed `browse` theo danh mục** (đang chạy tốt) và bổ sung playlist từ các feed có playlist thật (ví dụ chart YouTube Music), không thay bằng feed trang chủ.
6. **Phát playlist thật chưa được hỗ trợ**: trình phát chỉ có khái niệm mix `RD<videoId>` vô hạn (`YouTubeMixDataSource`). Playlist `PL...`/`VL...` cần thêm nguồn dữ liệu + hàng đợi, nếu không bấm vào card playlist sẽ không có hành vi đúng.

**Đề xuất:** làm theo 3 pha, trong đó Pha 1 (model + parser + test) an toàn và độc lập; Pha 2 (UI tìm kiếm) cần chốt hành vi khi bấm playlist; Pha 3 (Trang chủ + phát playlist) là thay đổi lớn hơn.

---

## 2. Hiện trạng chi tiết

### 2.1. Trang chủ

Luồng hiện tại:

```
HomeViewModel.observeFeed
  → YouTubeDataRepository.loadTrendingVideos(category)
    → YouTubeWebDataSource.categoryVideos(YouTubeBrowseFeed)
      → browseVideos(browseId, params)              // youtubei/v1/browse (client WEB)
        → parseBrowseVideos(root)
          → collectVideos(root)                    // chỉ lấy video renderer
```

- `YouTubeDataRepository.CATEGORY_FEEDS` (`YouTubeDataRepository.kt:69-83`):
  - `MUSIC` → `FEmusic_charts` (client `WEB_REMIX`)
  - `GAMING` → `FEgaming_destination` + `params=Egh0cmVuZGluZw==`
  - `NEWS` → `FEnews_destination`
  - `LEARNING` → `FElearning_destination`
  - `ALL` → tải song song các feed trên rồi trộn xen kẽ.
- `YouTubeWebDataSource.parseBrowseVideos` chỉ trả `List<VideoItem>`; mọi playlist/shelf bị bỏ.
- `YouTubeWebDataSource.musicChartVideos` lấy **playlist chart đầu tiên** (`browseId` dạng `VL...`) rồi chỉ lấy **các bài hát** trong đó. Playlist chart đó không bao giờ được hiển thị như một playlist.
- UI Trang chủ (`HomeAdapter`) chỉ có `HomeRow.Video`, `Continue`, `Mixes`, `Header`, `Empty`, `Skeleton`, `Offline`. `Mixes` là mix `RD` sinh từ lịch sử xem (`getHomeMix`), không phải playlist YouTube.
- Tab **Dành cho bạn** dùng mix + video gợi ý; tab **Thịnh hành** dùng feed danh mục. Không có kệ playlist thật.

**Kết luận:** Trang chủ dùng InnerTube đúng, nhưng "chỉ thấy video" vì parser và UI không có khái niệm playlist.

### 2.2. Tìm kiếm

Luồng hiện tại:

```
SearchViewModel.performSearch
  → YouTubeDataRepository.searchVideosWithStatus(query)
    → YouTubeWebDataSource.search(query)
      → innerTubeSearch(query)                 // youtubei/v1/search (client WEB)
        → collectVideos(root)
      → (dự phòng) scrapeSearchPage(query)     // tải /results, đọc ytInitialData
        → collectVideos(root)
```

Các điểm chặn playlist:

- `YouTubeWebDataSource.collectVideos` (`YouTubeWebDataSource.kt:517-521`) chỉ gom các renderer video.
- `InnerTubeVideoMapping.lockupVideoItem` (`InnerTubeVideoMapping.kt:128-133`) loại `contentType` khác `VIDEO` và yêu cầu `contentId` dài 11 ký tự → playlist `PL...`/`RD...` bị loại.
- `SearchViewModel` giữ `StateFlow<List<VideoItem>>`, `SearchActivity` chỉ nối `SearchRow.Video`.
- Bộ lọc sắp xếp hiện tại (Liên quan / Mới nhất / Lượt xem) chỉ áp dụng cho `VideoItem`.
- Fallback scrape dùng chung `collectVideos` nên cũng mất playlist.

**Kết luận:** Tìm kiếm đã dùng InnerTube, nhưng thiếu **model playlist**, **mapper playlist** và **row UI playlist**.

### 2.3. Luồng phát

- `MixPlaylist` chỉ mô tả mix `RD`; `YouTubeMixDataSource` tải trang watch `?v=...&list=RD...`, phân trang bằng video cuối.
- `PlaybackQueue`/`PlaybackCoordinator` hiểu mix vô hạn, không có khái niệm playlist hữu hạn `PL...`.
- Chưa có màn hình chi tiết playlist, chưa có API tải danh sách playlist qua `browseId=VL...`.

**Hệ quả:** nếu chỉ thêm card playlist vào tìm kiếm mà không chốt hành vi bấm, người dùng sẽ gặp card "chết" hoặc phát sai (chỉ phát video đầu rồi hết).

---

## 3. Bằng chứng từ InnerTube (request thật, 13/11/2026)

Các request dùng client `WEB`, `hl=vi`, `gl=VN`, không tài khoản.

### 3.1. Tìm kiếm trả playlist dưới `lockupViewModel`

- Query `playlist`, không filter:
  - 20 `videoRenderer`
  - 1 `lockupViewModel` với `contentType = LOCKUP_CONTENT_TYPE_PLAYLIST`
  - **0** `playlistRenderer`
- Query `playlist` + filter "Danh sách phát" (`params=EgIQAw%3D%3D`):
  - **20** `LOCKUP_CONTENT_TYPE_PLAYLIST`
  - **0** `playlistRenderer`

Cấu trúc một playlist lockup (rút gọn, giữ đúng đường dẫn):

```jsonc
{
  "lockupViewModel": {
    "contentId": "PL8F6B0753B2CCA128",
    "contentType": "LOCKUP_CONTENT_TYPE_PLAYLIST",
    "contentImage": {
      "collectionThumbnailViewModel": {
        "primaryThumbnail": {
          "thumbnailViewModel": {
            "image": { "sources": [ { "url": "...", "width": 720, "height": 404 } ] },
            "overlays": [
              {
                "thumbnailOverlayBadgeViewModel": {
                  "thumbnailBadges": [
                    { "thumbnailBadgeViewModel": { "text": "279 video" } }
                  ]
                }
              }
            ]
          }
        }
      }
    },
    "metadata": {
      "lockupMetadataViewModel": {
        "title": { "content": "Top 100+ Jazz Classics Playlist" },
        "metadata": {
          "contentMetadataViewModel": {
            "metadataRows": [
              {
                "metadataParts": [
                  { "text": { "content": "Electronics USA" } },
                  { "text": { "content": "Danh sách phát" } }
                ]
              },
              { "isSpacerRow": true },
              {
                "metadataParts": [
                  { "text": { "content": "Miles Davis - Freddie Freeloader · 9:48" } }
                ]
              }
            ]
          }
        }
      }
    },
    "rendererContext": {
      "commandContext": {
        "onTap": {
          "innertubeCommand": {
            "webCommandMetadata": { "url": "/watch?v=ZZcuSBouhVA&list=PL8F6B0753B2CCA128" },
            "watchEndpoint": {
              "videoId": "ZZcuSBouhVA",
              "playlistId": "PL8F6B0753B2CCA128"
            }
          }
        }
      }
    }
  }
}
```

Các trường cần map:

| Dữ liệu | Đường dẫn |
|---|---|
| playlist id | `contentId` (hoặc `watchEndpoint.playlistId`) |
| tiêu đề | `metadata.lockupMetadataViewModel.title.content` |
| kênh | row metadata đầu tiên, part đầu tiên |
| số video | badge `thumbnailBadgeViewModel.text` khớp `^[\d.,]+\s*video$` |
| thumbnail | `contentImage.collectionThumbnailViewModel.primaryThumbnail.thumbnailViewModel.image` |
| video đầu | `rendererContext.commandContext.onTap.innertubeCommand.watchEndpoint.videoId` |
| danh sách xem trước | các row metadata sau spacer (mỗi row là một video `· mm:ss`) |

> Lưu ý: playlist kết quả tìm kiếm có thể là **mix `RD...`** (vô hạn) hoặc **playlist `PL...`** (hữu hạn). UI/logic cần phân biệt, không được coi mix là playlist hữu hạn.

### 3.2. Trang chủ theo danh mục vẫn trả dữ liệu tốt

- `FEgaming_destination` + `params=Egh0cmVuZGluZw==`: 51 `gridVideoRenderer`.
- `FEnews_destination`: 170 `videoRenderer` trong `richItemRenderer`.
- `FElearning_destination`: 99 `videoRenderer` trong `richItemRenderer`.
- Không thấy playlist renderer/lockup playlist trong 3 feed này.

### 3.3. `FEwhat_to_watch` (feed trang chủ thật) không dùng được khi ẩn danh

- Request `browseId=FEwhat_to_watch` trả 200 nhưng nội dung chỉ có `feedNudgeRenderer`; **0 video, 0 playlist**.
- Thử thêm `visitorData`, header `X-Goog-Visitor-Id`, `X-Youtube-Bootstrap-Logged-In: false` vẫn chỉ có nudge.
- Vì vậy không nên thay feed danh mục bằng feed trang chủ; chỉ nên bổ sung nguồn playlist từ các feed `browse` có playlist thật.

### 3.4. Playlist detail qua `browseId=VL...` hoạt động

- `browseId=VLPL8F6B0753B2CCA128` trả 200, **100 `LOCKUP_CONTENT_TYPE_VIDEO`**, badge `279 video`, kèm `continuationCommand.token` để tải trang tiếp.
- Đây là nguồn phù hợp cho màn hình chi tiết playlist và/hoặc hàng đợi phát playlist.

### 3.5. YouTube Music charts có playlist thật

- `FEmusic_charts` (client `WEB_REMIX`) trả **3 `musicTwoRowItemRenderer`** và **3 playlist `VL...`**: chart Việt Nam, Toàn cầu và một playlist khác (`VLPL4fGSI1pDJn4FPCRZtojwqQro5GPY6cuV`).
- `FEmusic_home` trả 5 `musicTwoRowItemRenderer` / 5 playlist `VL...`; `FEmusic_explore` trả 24 item. Đây là nguồn playlist công khai, ổn định, không cần tìm kiếm giả lập.
- Code hiện tại đã dùng `findFirstChartPlaylistId` để lấy playlist chart đầu tiên; có thể tái sử dụng chính dữ liệu này để hiển thị **card playlist** ở Trang chủ (tab Âm nhạc) thay vì chỉ lấy bài hát.

---

## 4. Phương án kỹ thuật đề xuất

### 4.1. Model dữ liệu

Thêm model playlist **tách khỏi** `VideoItem` để không ảnh hưởng Room, player, backup:

```kotlin
data class PlaylistItem(
    val id: String,                 // PL..., RD..., VL...
    val title: String,
    val channelTitle: String = "",
    val channelId: String = "",
    val videoCount: Int = 0,        // 0 = không rõ (mix)
    val thumbnailUrl: String = "",
    val firstVideoId: String = "",
    val firstVideoTitle: String = "",
    val previewVideos: List<String> = emptyList()
) {
    val isMix: Boolean get() = id.startsWith("RD")
}
```

Và một kiểu kết quả có thứ tự:

```kotlin
sealed interface SearchItem {
    data class Video(val video: VideoItem) : SearchItem
    data class Playlist(val playlist: PlaylistItem) : SearchItem
}
```

- Giữ `YouTubeWebDataSource.search(): List<VideoItem>` cho tương thích, thêm `searchPage(): SearchPage` (hoặc `searchItems()`).
- Giữ `searchVideosWithStatus()` cho nơi khác đang dùng; thêm `searchItemsWithStatus()`.
- Không đưa playlist vào `VideoEntity`/Room để tránh migration và tránh lẫn playlist với video đã lưu.

### 4.2. Parser

Thêm vào `InnerTubeVideoMapping`:

- `playlistLockup(lockup: JSONObject): PlaylistItem?`
  - nhận `contentType == LOCKUP_CONTENT_TYPE_PLAYLIST`
  - map theo bảng ở §3.1
- `playlistRenderer(renderer: JSONObject): PlaylistItem?` (schema cũ, phòng khi client/region khác trả về)
  - `playlistId`, `title`, `videoCountText`/`videoCountShortText`, `thumbnails`, `navigationEndpoint.watchEndpoint`, `videos[].childVideoRenderer`.
- `gridPlaylistRenderer` nếu gặp ở feed.

Thêm collector riêng:

```kotlin
private fun collectSearchItems(root: JSONObject): List<SearchItem>
```

Nguyên tắc:

- Duyệt đệ quy nhưng **không** thêm video xem trước nằm trong playlist lockup thành kết quả video riêng.
- `lockupViewModel`:
  - `LOCKUP_CONTENT_TYPE_VIDEO` → `lockupVideoItem`
  - `LOCKUP_CONTENT_TYPE_PLAYLIST` → `playlistLockup`
  - các loại khác (`COURSE`, ...) → bỏ.
- `videoRenderer`/`gridVideoRenderer`/`videoWithContextRenderer` → như hiện tại.
- `playlistRenderer`/`gridPlaylistRenderer` → playlist.
- Giữ **đúng thứ tự YouTube** trong danh sách kết quả (YouTube thường chèn playlist giữa các video).
- Dedup theo khóa `video:<id>` / `playlist:<id>`.

### 4.3. Repository + ViewModel

- `YouTubeDataRepository.searchItemsWithStatus(query): AppResult<SearchPage>`.
  - `SearchPage(videos: List<VideoItem>, playlists: List<PlaylistItem>, items: List<SearchItem>)`.
- `SearchViewModel`:
  - thêm `searchItems: StateFlow<List<SearchItem>>` (hoặc thay `searchResults`).
  - `prioritizeWatched` chỉ áp dụng cho video; playlist giữ nguyên thứ tự.
  - bộ lọc sắp xếp: video theo tiêu chí hiện có; playlist không có `publishedTime`/`viewCount` nên giữ vị trí hoặc xếp cuối tùy quyết định UX.
- Lỗi/rỗng giữ nguyên contract hiện tại: phân biệt "không có kết quả" với lỗi mạng.

### 4.4. UI Tìm kiếm

- Thêm `SearchRow.Playlist` + `PlaylistCardView` (hoặc biến thể của `CompactVideoCardView`).
- Card playlist gồm: thumbnail (ưu tiên ảnh collection 16:9; có thể ghép 2x2 nếu có `previewVideos`), tiêu đề 2 dòng, tên kênh, nhãn `N video`, badge "Danh sách phát".
- `SearchAdapter`:
  - thêm `TYPE_PLAYLIST`, `DiffUtil.areItemsTheSame` theo `playlist.id`.
  - `onPlaylistClick: (PlaylistItem) -> Unit`.
- `SearchActivity` nối callback; `PageActivity` mở màn hình playlist hoặc phát playlist (xem §4.5).
- Thêm string resource cho nhãn "Danh sách phát", "N video", nội dung accessibility.

### 4.5. Hành vi khi bấm playlist (cần chốt)

| Phương án | Mô tả | Công sức | Rủi ro |
|---|---|---|---|
| **A. Màn hình chi tiết playlist** (khuyến nghị trước) | Mở `PlaylistActivity`/bottom sheet, tải `browseId=VL<id>` + continuation, liệt kê video; bấm video phát như hiện tại (chưa tự chuyển bài theo playlist). | Vừa | Thấp, độc lập player |
| **B. Phát cả playlist** | Thêm `PlaylistSession` (PL hữu hạn) bên cạnh mix `RD`; `PlaybackQueue` nhận playlist; `PlaybackCoordinator` tải trang tiếp; nút Next/Previous đi theo playlist. | Lớn | Vừa–cao, chạm player |
| C. Chỉ phát video đầu | Bấm playlist → phát `firstVideoId`; không có hàng đợi. | Rất nhỏ | Trải nghiệm sai, dễ gây hiểu nhầm |

- Với mix `RD...` trong kết quả tìm kiếm: có thể tái dùng luồng mix hiện tại (phát video đầu + mix vô hạn) — gần với hành vi YouTube.
- Với playlist `PL...`: nếu chọn A, cần hiển thị rõ "chưa phát liên tục"; nếu chọn B, nên làm sau khi A ổn định.

### 4.6. Trang chủ có playlist

- **Bước an toàn:** chỉ thêm playlist vào tab **Âm nhạc** từ `FEmusic_charts`:
  - `musicChartVideos()` hiện tìm playlist chart đầu tiên; bổ sung trả về cả `PlaylistItem` của chart đó (và các chart khác nếu có).
  - `YouTubeBrowseFeed`/`FeedFetch` cần mang thêm `playlists` hoặc một kiểu `FeedPage`.
- **Bước mở rộng:** thêm kệ "Danh sách phát" trên Trang chủ nếu tìm được feed `browse` công khai trả playlist ổn định. Không dùng tìm kiếm để giả lập feed (giữ nguyên nguyên tắc đã ghi trong README).
- Không dùng `FEwhat_to_watch` (xem §3.3).

---

## 5. Rủi ro & lưu ý

1. **Schema churn:** YouTube đã chuyển search playlist từ `playlistRenderer` sang `lockupViewModel`; cần hỗ trợ cả hai và test bằng fixture.
2. **Phân biệt mix/playlist:** `RD...` vô hạn, `PL...` hữu hạn, `VL...` là browse id của playlist. Không được dùng lẫn.
3. **Nested video:** `playlistRenderer.videos[].childVideoRenderer` và metadata preview trong lockup **không phải** kết quả tìm kiếm độc lập; tránh nhân đôi danh sách.
4. **Thiếu trường:** playlist có thể không có `firstVideoId`, không có thumbnail collection, hoặc badge số video ở định dạng khác (`279 videos`, `279 video`). Cần fallback thumbnail và parse linh hoạt.
5. **Continuation:** playlist detail cần token; hiện `YouTubeWebDataSource` chỉ có `extractInitialData`, chưa có hàm gọi `browse` với `continuation`. Phải thêm nếu muốn tải đủ playlist.
6. **Client/region:** `WEB` + `hl=vi` + `gl=VN` cho kết quả khác `WEB_REMIX`/Android. Không hard-code một schema duy nhất.
7. **Hiệu năng UI:** danh sách kết quả trộn video/playlist cần `DiffUtil` ổn định; card playlist nên tái dùng view để tránh nháy.
8. **Hợp đồng lỗi:** giữ nguyên `AppResult`; playlist parse lỗi không được biến cả tìm kiếm thành lỗi mạng.
9. **Phát playlist:** thay đổi `PlaybackQueue`/`YouTubeMixDataSource` có thể ảnh hưởng SponsorBlock, resume, auto-next; cần test hồi quy player.
10. **Pháp lý/nhãn:** card playlist nên ghi rõ "Danh sách phát"; không trộn với "Danh sách kết hợp" (mix) hiện có.

---

## 6. Kế hoạch triển khai theo pha

### Pha 1 — Model + parser + test (an toàn, không đổi UI)

- [x] Thêm `PlaylistItem` và `SearchItem`/`SearchPage` trong `data/model`.
- [x] Thêm `playlistLockup`, `playlistRenderer`, `gridPlaylistRenderer` vào `InnerTubeVideoMapping`.
- [x] Thêm `collectSearchItems` trong `YouTubeWebDataSource`; giữ `collectVideos` cho code cũ.
- [x] Thêm `YouTubeWebDataSource.searchPage()` + `YouTubeDataRepository.searchItemsWithStatus()`.
- [x] Test fixture JSON:
  - playlist lockup đầy đủ;
  - playlist lockup thiếu thumbnail/số video;
  - `playlistRenderer` schema cũ;
  - kết quả trộn video + playlist giữ đúng thứ tự, không nhân đôi video preview;
  - `LOCKUP_CONTENT_TYPE_COURSE` bị bỏ.
- [ ] Chạy `testDebugUnitTest` (máy local không có JDK/Android SDK; cần CI).

### Pha 2 — UI Tìm kiếm

- [x] `SearchRow.Playlist`, `PlaylistCardView` + layout.
- [x] `SearchAdapter` thêm type/diff/bind.
- [x] `SearchViewModel` phát `List<SearchItem>`; sort/filter áp dụng hợp lý.
- [x] `SearchActivity` nối `onPlaylistClick`.
- [x] Quyết định hành vi bấm: màn hình chi tiết playlist (A) — đã thêm `PlaylistActivity`/`PlaylistNativeView`, tải `VL...` + continuation; bấm video phát trong ngữ cảnh cả playlist (xem Pha 4).
- [x] Test inflate layout + DiffUtil.

> **Trạng thái:** Pha 1 và Pha 2 đã triển khai; Pha 4 (phát liên tục playlist) đã bổ sung ở mức cơ bản. Màn hình chi tiết playlist vẫn là nơi chọn video, nhưng bấm video giờ đưa cả playlist vào hàng đợi: Next/Previous và auto-next đi đúng thứ tự, tự tải continuation khi chạm cuối trang.

### Pha 3 — Trang chủ

- [ ] Mở rộng `FeedFetch`/`FeedPage` mang `playlists` (tối thiểu từ `FEmusic_charts`).
- [ ] `HomeRow.Playlist` + kệ/card playlist (tái dùng `PlaylistCardView` nếu được).
- [ ] Chỉ bật playlist ở tab có nguồn thật (Âm nhạc trước).
- [ ] Test parser feed + render.

### Pha 4 — Phát playlist

- [x] `YouTubeDataRepository.getPlaylistPage` tải `VL...` + continuation (dùng chung parser với Pha 2).
- [x] `PlaybackPlaylistContext` hữu hạn bên cạnh `MixPlaylist`; giữ nguyên thứ tự và token trang kế.
- [x] `PlaybackQueue`/`PlaybackCoordinator` hỗ trợ next/previous theo playlist và tự tải thêm trang khi chạm cuối danh sách.
- [x] Sheet danh sách trong trình phát hiển thị playlist đang phát (dùng lại UI mix).
- [x] Ghi lịch sử playlist (video cuối + vị trí) song song lịch sử video; tab Lịch sử gộp cả hai và mở lại đúng video đang xem dở.
- [x] Migration Room 3 → 4 thêm bảng `playlist_history`, giữ nguyên dữ liệu video hiện có.
- [x] Test hàng đợi: thứ tự next/previous, ưu tiên playlist hơn mix, rời playlist, merge trang continuation.
- [x] Test lịch sử: ghi metadata, giữ tiêu đề/số video khi cập nhật, tiến độ chỉ ghi cho video cuối.
- [ ] Kiểm chứng thủ công trên thiết bị: auto-next, resume, SponsorBlock, hàng đợi thủ công khi đang phát playlist.

---

## 7. Kiểm chứng thủ công đề xuất

- Tìm `playlist`, `mix nhạc`, `học tiếng anh`: kỳ vọng có card playlist/mix, bấm được.
- Tìm + filter "Danh sách phát" (`params=EgIQAw%3D%3D`): kỳ vọng danh sách chủ yếu là playlist.
- Kiểm tra playlist không có `firstVideoId` vẫn hiển thị (không crash).
- Kiểm tra Trang chủ tab Âm nhạc có card playlist chart.
- Kiểm tra offline: card cũ giữ nguyên hoặc hiện lỗi có nút thử lại, không crash.
- Kiểm tra `FEwhat_to_watch` **không** được dùng làm nguồn (vì rỗng khi ẩn danh).

---

## 8. Quyết định cần chốt

1. Playlist trong tìm kiếm chỉ **hiển thị** hay phải **phát liên tục**?
2. Trang chủ có cần playlist ngoài tab Âm nhạc không, hay chỉ Âm nhạc là đủ?
3. Có chấp nhận thêm màn hình chi tiết playlist (Pha A) trước khi làm hàng đợi phát playlist (Pha B) không?

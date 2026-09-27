# Phần 6 — MainActivity, giao diện thật ✅

(Xem `plan_app_wallpaper_live.txt` đã cập nhật — Phần 6 tương ứng Giai đoạn 9 mới thêm vào plan.)

## Vì sao cần phần này
- Máy hiện tại **không có mục live wallpaper bên thứ 3** trong Cài đặt > Hình nền, nên không
  thể chỉ trông cậy vào đường Settings như Phần 1 mô tả nữa — cần 1 màn hình thật trong app để
  bấm thẳng.
- `WallpaperService` (chạy trong `ChibiWallpaperService`) **không tự xin được quyền runtime**
  (`RECORD_AUDIO`) — chỉ `Activity` gọi `requestPermissions()` được. Phải có Activity trước khi
  làm Giai đoạn 6 (STT + Gemini).
- Cần chỗ lưu Gemini API key thay vì hardcode trong code.

## Đã làm
- **`AndroidManifest.xml`**:
  - Thêm `<uses-permission android:name="android.permission.RECORD_AUDIO" />`.
  - Thêm `<activity android:name=".ui.MainActivity">` với `intent-filter` MAIN/LAUNCHER —
    **đây là icon thật đầu tiên của app**, trước giờ chỉ có service nên không hiện gì ngoài
    home screen/app drawer.
- **`res/layout/activity_main.xml`** (mới): 3 khối UI đơn giản (không dùng RecyclerView/Fragment
  gì cho nhẹ) — Đặt hình nền / Quyền micro / API key — mỗi khối có 1 dòng trạng thái + 1 nút.
- **`res/values/strings.xml`**: thêm toàn bộ nhãn tiếng Việt cho 3 khối trên.
- **`ui/MainActivity.kt`** (mới, `android.app.Activity` thường — **không** thêm dependency
  `androidx.appcompat`):
  1. **Đặt hình nền**: `openLiveWallpaperPicker()` gọi thẳng
     `Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)` kèm
     `EXTRA_LIVE_WALLPAPER_COMPONENT` trỏ vào `ChibiWallpaperService` — mở thẳng màn hình xem
     trước/đặt của hệ thống, bỏ qua hẳn danh sách Live Wallpapers trong Settings. Có fallback
     `ACTION_LIVE_WALLPAPER_CHOOSER` nếu action đầu bị chặn, và thông báo lỗi rõ ràng nếu cả 2
     đều thất bại thay vì crash. `onResume()` tự kiểm tra lại trạng thái (qua
     `WallpaperManager.getInstance(this).wallpaperInfo`) mỗi lần quay lại màn hình.
  2. **Quyền micro**: dùng API permission cũ (`ActivityCompat.requestPermissions` +
     `onRequestPermissionsResult`) thay vì `registerForActivityResult` mới, vì API mới cần
     `androidx.activity:activity-ktx` (dependency chưa có) còn API cũ chạy thẳng với
     `androidx.core:core-ktx` đã có sẵn từ Phần 3.1. Có phân biệt "bị từ chối, còn hỏi lại
     được" và "bị chặn vĩnh viễn" (lúc đó đổi nút thành mở thẳng Cài đặt app).
  3. **API key Gemini**: lưu vào `SharedPreferences` tên `chibi_wallpaper_prefs`, key
     `gemini_api_key` (2 hằng số này để nguyên trong `MainActivity.Companion` — Giai đoạn 6
     `GeminiClient` sẽ đọc lại từ đây). Chưa gọi API Gemini nào ở Phần 6, chỉ lưu.
- **`app/build.gradle.kts`**: `versionName` -> `"0.4-phan6"`. Không thêm dependency mới nào.

## Cách kiểm tra
1. Chạy `app`. Giờ sẽ thấy **icon "Chibi Wallpaper" thật** trong app drawer (trước đây không
   có icon nào cả).
2. Mở app: thấy 3 khối UI, dòng trạng thái đầu tiên nên là "Chưa đặt làm hình nền" (trừ khi
   bạn đã đặt bằng tay từ trước qua Settings).
3. Bấm **"Đặt làm hình nền động"**: phải mở thẳng màn hình xem trước hình nền của hệ thống
   (không phải danh sách chọn live wallpaper). Bấm đặt xong, quay lại app: dòng trạng thái đổi
   thành "Đang đặt làm hình nền chính."
4. Bấm **"Cấp quyền micro"**: hộp thoại quyền hiện lên, đồng ý → dòng trạng thái đổi thành
   "Đã cấp quyền micro.". Nếu từ chối 1 lần rồi bấm lại: hộp thoại hiện lại bình thường. Từ
   chối kèm "Không hỏi lại": lần bấm sau nút sẽ mở thẳng Cài đặt app.
5. Nhập bậy 1 chuỗi vào ô API key, bấm **"Lưu API key"** → thấy "Đã lưu.". Đóng app mở lại: ô
   vẫn còn đúng chuỗi đó (xác nhận `SharedPreferences` hoạt động).
6. Logcat filter `ChibiMainActivity`: chỉ có log khi Intent đặt wallpaper thất bại (bình
   thường sẽ không thấy log nào ở đây).

## Việc CHƯA làm
- Chưa đọc/dùng `RECORD_AUDIO` hay API key thật ở đâu cả — Phần 6 chỉ chuẩn bị hạ tầng, dùng
  thật là Giai đoạn 6 (Phần 4: `SpeechToTextManager`, `GeminiClient`).
- Chưa polish UI (theme/màu sắc riêng) — để dành Giai đoạn 8 (Phần 5) nếu cần.

# PHẦN 5 — Bong bóng chat, bảng clipboard (text dài), phát video, polish

Tương ứng Giai đoạn 7 + 8 trong plan gốc.

## 1. Bong bóng chat (text ngắn, ≤ 60 ký tự)
- File mới: `render/OverlayTextureRenderer.kt` — vẽ 1 quad có texture (từ Bitmap Canvas) đè lên
  scene Cubism, làm việc thẳng bằng toạ độ pixel màn hình, dùng chung context GL với Cubism
  (không cần View/Window overlay riêng — phù hợp với live wallpaper).
- File mới: `render/ChatBubbleOverlay.kt` — dựng bong bóng bo góc + đuôi trỏ xuống, wrap chữ
  bằng `StaticLayout`, neo phía trên đầu Chibi.
- Chỉ redraw Bitmap khi nội dung text đổi (cache texture theo string) — tiết kiệm CPU/pin.

## 2. Text dài (> 60 ký tự) → Slime + bảng clipboard bay lên giữa màn hình
Đúng yêu cầu: khi Gemini trả lời dài, nhân vật **tự chuyển về dạng Slime**
(`MultiModelScene.applyState` kiểm tra `ChatBubbleOverlay.isLongText`), đồng thời bảng chạy
qua 3 pha animation (`ChatBubbleOverlay`):

1. **ICON** (0.3s) — icon clipboard nhỏ hiện ngay trên đầu Slime, có hiệu ứng nảy nhẹ lúc xuất hiện.
2. **FLYING** (0.45s) — phóng to dần + bay theo 1 cung parabol lên giữa màn hình ("quăng lên"),
   xoay nhẹ, crossfade từ icon sang bảng nội dung đầy đủ ở nửa sau animation.
3. **SHOWN** — đứng yên giữa màn hình, hiện toàn bộ text (thanh tiêu đề tím + nội dung wrap),
   có **nút X** ở góc trên-phải để đóng.

- Chạm đúng vùng nút X (`ChatBubbleOverlay.hitTestClose`, kiểm tra trong `MultiModelScene.onTouch`,
  chạy trên GL thread) → gọi `onBoardClosedByUser` → service post về main thread → 
  `stateMachine.backToRoaming()`.
- Trong lúc bảng đang hiện (bất kỳ pha nào): mọi chạm khác đều bị "nuốt" (không nudge Slime bên
  dưới), và **double-tap bị bỏ qua** — tránh xung đột thao tác, user phải bấm X trước.
- An toàn: nếu user không bấm X, bảng tự đóng sau `BOARD_AUTO_DISMISS_MS` = 45s (dài hơn bong
  bóng chat thường 8s, vì cần thời gian đọc).
- Vị trí xuất phát của animation lấy xấp xỉ từ toạ độ Slime hiện tại (`SlimeController`), quy đổi
  gần đúng sang pixel màn hình — đủ để hiệu ứng trông tự nhiên, không cần khớp tuyệt đối với
  matrix nội bộ của Cubism.

## 3. Phát video (function calling `play_video`)
- File mới: `render/VideoLayer.kt` — đúng theo plan gốc: `MediaPlayer` + `SurfaceTexture`
  (`GL_TEXTURE_EXTERNAL_OES`) dùng **chung 1 context OpenGL** với Cubism, không cần Activity/View
  riêng.
- `ActionRouter.route("play_video")` giờ quy đổi tên Gemini gọi (`"dance"`, `"wave"`...) sang tên
  file thật trong `assets/videos/` qua map `VIDEO_ASSETS`.
- Khi video phát xong tự nhiên (`MediaPlayer.OnCompletionListener`) → `VideoLayer.onPlaybackFinished`
  → `MultiModelScene.onVideoFinished` → service quay về `ROAMING` — thay hẳn cho timer giả 10 giây
  tạm thời ở Phần 4. Vẫn giữ 1 job fallback 15s phòng khi asset lỗi/thiếu (xem
  `assets/videos/README_ASSETS.txt` — **chưa có file .mp4 thật**, cần tự thêm vào).
- Mỗi lần phát: tạo `MediaPlayer` mới, `release()` ngay sau khi xong/lỗi — không giữ decoder chạy
  nền tốn pin.

## 4. Polish & tối ưu pin (giai đoạn 8)
- Texture bong bóng/bảng chỉ dựng lại khi text đổi (không phải mỗi frame).
- `VideoLayer` release `MediaPlayer` ngay sau khi phát xong thay vì giữ instance sống.
- Render/GL vẫn tắt hoàn toàn khi wallpaper không hiển thị (`GLRenderer.setVisible`, đã có từ
  Giai đoạn 2 — không đổi ở Phần 5).
- Particle effect & quyết định có dùng AccessibilityService hay không: **chưa làm** — để lại cho
  đợt polish sau cùng nếu cần, hiện tại ưu tiên tính năng lõi (bong bóng/bảng/video) trước.

## Các file đổi/thêm trong Phần 5
- Mới: `render/OverlayTextureRenderer.kt`, `render/ChatBubbleOverlay.kt`, `render/VideoLayer.kt`,
  `assets/videos/README_ASSETS.txt`
- Sửa: `render/MultiModelScene.kt` (tích hợp overlay + video, chặn touch khi bảng hiện),
  `ChibiWallpaperService.kt` (nối callback đóng bảng/video xong, auto-dismiss theo loại nội dung,
  chặn double-tap khi bảng hiện), `ai/ActionRouter.kt` (map tên video → file asset),
  `app/build.gradle.kts` (versionCode/versionName → 5 / "0.5-phan5")

## Còn thiếu / cần làm tay
- **File video thật** (`assets/videos/*.mp4`) — hiện chỉ có ghi chú placeholder.
- Ngưỡng "text dài" (`ChatBubbleOverlay.LONG_TEXT_THRESHOLD = 60` ký tự) là số tạm — có thể chỉnh
  lại sau khi test trên máy thật xem bong bóng chat thường chứa vừa bao nhiêu chữ trước khi bị chật.
- Vị trí neo bong bóng/board hiện dùng ước lượng tỉ lệ màn hình (30%/42%...) — nếu muốn khớp
  chính xác 100% với đầu model thật, cần đọc toạ độ từ chính ma trận Cubism thay vì xấp xỉ.

# Phần 15.4 — Text dài → bảng clipboard (Floating Pet)

## Đã làm

1. **`floating/TextBoardOverlay.kt` (mới)** — bảng clipboard cho floating pet, dùng **View THẬT**
   (TextView + nút X trong FrameLayout) thêm thẳng vào `WindowManager`, hiện giữa màn hình —
   đơn giản hơn nhiều so với port `ChatBubbleOverlay` (Canvas → GL texture) của live wallpaper,
   vì overlay nổi không cần đồng bộ animation với model bên dưới.
   - `show(text, onClosed)`: đóng bảng cũ (nếu có, không gọi `onClosed` của lần trước) → dựng
     view mới → `windowManager.addView(...)` → hẹn giờ auto-dismiss `AUTO_DISMISS_MS` (45s).
   - Nút X (`setOnClickListener`) và auto-dismiss đều gọi `dismissInternal()` rồi `onClosed()`.
   - `dismiss()`: đóng chủ động từ code, KHÔNG gọi `onClosed`.

2. **`floating/FloatingPetService.kt`**:
   - Ngưỡng dài/ngắn dùng LẠI `ChatBubbleOverlay.isLongText` (60 ký tự) — không định nghĩa lại
     hằng số ở chỗ khác.
   - `GeminiResponse.Text` và `RoutedAction.SpeakText` giờ đi qua `respondWithText(text)` thay vì
     gọi `renderer.showReply()` trực tiếp:
     - Ngắn → y hệt cũ (`renderer.showReply` + tự revert sau `revertDelay`).
     - Dài → `renderer.clearBubbleAndRevert()` ngay (không hiện bubble GL đè lên bảng) rồi
       `textBoard.show(text) { ... }` — bảng tự lo phần còn lại (đóng bằng X hoặc 45s).
   - `textBoard` khởi tạo 1 lần trong `addOverlayView()` (ngay sau khi có `windowManager`).
   - `removeOverlayView()` (đổi model / tắt overlay / `onDestroy`) gọi `textBoard?.dismiss()` để
     tránh view mồ côi bám lại trên `WindowManager`.

## Không đổi
- `FloatingPetRenderer` — không cần biết gì về bảng clipboard (nó là 1 window riêng, không phải
  GL overlay), chỉ được gọi `clearBubbleAndRevert()` như các trường hợp revert khác.
- Vị trí/kéo thả cửa sổ pet 130dp, live wallpaper — không đổi.

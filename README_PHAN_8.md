# Phần 8 — Slime "ngủ" tiết kiệm pin

Sau **25 giây không có tương tác nào** (không tap/double-tap, ở bất kỳ state nào), Slime tự đi về
1 góc trái hoặc phải màn hình (random), đứng yên tại đó và hiện chữ **"Z z z"** trôi nhẹ lên trên
đầu — mô phỏng đang ngủ. Chạm vào bất kỳ đâu trên màn hình sẽ đánh thức ngay, Slime roam lại bình
thường sau một khoảng nghỉ ngắn (~0.4-0.7s, không giật cứng).

## 1. Vì sao tiết kiệm pin thật sự (không chỉ hình thức)

Chỉ đứng yên không tự động tiết kiệm pin — chi phí chính của live wallpaper là **vẽ lại khung hình
liên tục** (30fps). Nên phần lõi của tính năng này là [`GLScene.wantsLowFrameRate()`](app/src/main/java/com/example/chibiwallpaper/render/GLScene.kt)
(interface mới, mặc định `false`) — [`GLRenderer`](app/src/main/java/com/example/chibiwallpaper/render/GLRenderer.kt)
hỏi scene mỗi frame, và **tự hạ khung hình/giây từ 30 xuống 6** khi Slime đang ngủ, tự trả lại 30
ngay khi thức dậy. Không cần Service/Engine can thiệp gì thêm — logic gói gọn hoàn toàn trong cặp
`GLRenderer` ⟷ `MultiModelScene.wantsLowFrameRate()`.

## 2. Logic di chuyển/ngủ — [`SlimeController`](app/src/main/java/com/example/chibiwallpaper/character/SlimeController.kt)

- `idleTimer` cộng dồn mỗi frame lúc **thức** (không cộng lúc đang ngủ — đã rảnh đủ lâu rồi).
  Đạt `SLEEP_IDLE_THRESHOLD` (25s) → set `headingToSleep = true`, chọn đích là 1 trong 2 góc
  (`SLEEP_MARGIN_X` = ±0.50, gần sát mép vùng roam bình thường) rồi **đi bằng đúng cơ chế
  `moveTowardTarget()` có sẵn** (không dịch chuyển tức thời) — tới nơi mới chuyển `isSleeping = true`.
- Lúc `isSleeping`: đứng yên tại `targetX/targetY`, chỉ còn 1 nhịp "thở" rất nhẹ theo trục Y
  (`SLEEP_BOB_AMPLITUDE`, nhỏ hơn nhịp bob bình thường nhiều) cho đỡ "chết cứng".
- `wake()` (hàm mới, public): reset `idleTimer`, huỷ `headingToSleep`, và nếu đang ngủ thì đánh
  thức — set `pauseTimer` ngắn để roam lại tự nhiên (không bật dậy đột ngột).
- `nudge()` (tap trực tiếp vào Slime) gọi `wake()` trước tiên nên tap khi đang ngủ vừa đánh thức
  vừa giật nhẹ luôn, không cần 2 lần chạm.

## 3. Vẽ "Z z z" — [`SleepIndicatorOverlay`](app/src/main/java/com/example/chibiwallpaper/render/SleepIndicatorOverlay.kt) (file mới)

Theo đúng pattern `ChatBubbleOverlay`/`OverlayTextureRenderer` đã có từ Phần 5: build 1 Bitmap
(Canvas) → 1 texture GL **duy nhất lúc khởi tạo**, không vẽ lại Canvas mỗi frame. Animation "trôi
lên + mờ dần rồi lặp lại" (chu kỳ 2.4s) chỉ đổi `centerY`/`alpha` truyền vào `drawTexture()` mỗi
lần vẽ — gần như miễn phí, đúng tinh thần tiết kiệm pin của cả tính năng.

`MultiModelScene.onDrawFrame()` chỉ vẽ overlay này khi: `displayModel == SLIME`,
`slimeController.isSleeping`, và không có bong bóng chat/bảng clipboard/cổng dịch chuyển nào đang
chiếm màn hình (dùng lại đúng khối `if` overlay có sẵn từ Phần 5, thêm 1 nhánh `else if`).

## 4. Chạm để đánh thức — 2 đường

- **Single-tap** (khi Slime đang hiện): đi qua `MultiModelScene.onTouch()` như cũ — thêm 1 dòng
  `slimeController.wake()` ngay đầu hàm (trước cả các early-return của cổng dịch chuyển), nên chạm
  vào lúc nào cũng tính, kể cả đang giữa animation cổng.
- **Double-tap**: xử lý ở `ChibiWallpaperService.onDoubleTap()`, KHÔNG đi qua `onTouch()` —
  thêm hàm public mới `MultiModelScene.wakeSlime()` và gọi ngay đầu `onDoubleTap()`.

## 5. Các file đổi/thêm trong Phần 8

- **Mới**: `render/SleepIndicatorOverlay.kt`
- **Sửa**: `character/SlimeController.kt` (thêm `isSleeping`/`wake()`/logic đi ngủ),
  `render/GLScene.kt` (thêm `wantsLowFrameRate()` mặc định `false`),
  `render/GLRenderer.kt` (thêm cơ chế hạ/khôi phục FPS động),
  `render/MultiModelScene.kt` (nối `SleepIndicatorOverlay`, override `wantsLowFrameRate()`,
  gọi `wake()` trong `onTouch()`, thêm `wakeSlime()`),
  `ChibiWallpaperService.kt` (gọi `scene.wakeSlime()` đầu `onDoubleTap()`),
  `app/build.gradle.kts` (versionCode/versionName → 8 / "0.8-phan8_sleep")

## 6. Cách kiểm tra

1. Chạy app, đặt làm hình nền, để Slime roam tự do (không chạm gì).
2. Đợi ~25s không chạm — phải thấy Slime tự đi thẳng về góc trái hoặc phải (random mỗi lần), tới
   nơi thì đứng yên, chữ "Z z z" bắt đầu trôi lên mờ dần lặp lại trên đầu.
3. Chạm vào màn hình (single tap bất kỳ chỗ nào) — Slime phải "giật" nhẹ và roam lại bình thường
   ngay, "Z z z" biến mất.
4. Thử double-tap lúc Slime đang ngủ — phải đánh thức trước khi vào LISTENING (không bị "ngủ luôn"
   trong lúc ghi âm).
5. Có thể kiểm tra FPS hạ thật qua Logcat filter `ChibiGL` — sẽ thấy dòng
   `Low-power mode: true (fps=6)` lúc Slime vừa ngủ, và `false (fps=30)` lúc thức dậy.
6. Lặp lại vài lần, để ý góc ngủ đổi random (không luôn 1 bên).

## Còn thiếu / có thể tinh chỉnh thêm

- Ngưỡng 25s và FPS lúc ngủ (hiện 6fps) là số tạm — nên test trên máy thật để cân lại nếu thấy
  Slime "đi ngủ" quá sớm/muộn, hoặc animation "Z z z" giật do FPS thấp.
- Chưa có hiệu ứng "nhắm mắt" trên chính model Slime (Cubism) lúc ngủ — hiện chỉ đứng yên + text
  overlay; có thể để dành thêm 1 phần sau nếu Slime có sẵn expression/motion "ngủ" riêng.

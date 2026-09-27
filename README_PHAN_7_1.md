# Phần 7.1 — Hiệu ứng cổng dịch chuyển (Slime ⟷ Chibi)

Tính năng MỚI, nằm ngoài 9 giai đoạn gốc trong `plan_app_wallpaper_live.txt` — theo yêu cầu bổ
sung: khi đổi model, thay vì "cắt cứng" (instant swap) như từ Phần 4 tới giờ, dùng hiệu ứng
"cổng không gian": 1 dải tối kéo từ đỉnh màn hình xuống nuốt model cũ, model đổi khi cổng phủ kín
màn hình, rồi cổng rút dần từ mép dưới lên để lộ model mới.

**Chia làm 2 phần** (theo đúng yêu cầu — làm Phần 7.1 trước):
- **Phần 7.1 (phần này)**: dựng cơ chế cổng dùng chung + áp dụng cho cặp model NHỎ
  (Slime ⟷ Chibi) — theo đúng ý "Slime và Chibi thì nhỏ nên cứ chuyển qua lại thoải mái".
- **Phần 7.2 (chưa làm)**: hiệu ứng RIÊNG cho Full — cổng to kéo tới chân làm Full biến mất, sau
  đó 1 cổng nhỏ ở góc trái/phải/giữa (dưới màn hình) hiện model nhỏ (Chibi/Slime) chui ra. Sẽ cần
  quyết định thêm: cổng nhỏ xuất hiện ở góc nào (cố định 1 góc, hay random, hay theo action Gemini
  chỉ định?), và chiều ngược lại (từ Slime/Chibi → Full) dùng hiệu ứng gì (cổng nhỏ thu nhỏ model
  hiện tại trước, rồi cổng to mở ra từ trên xuống lộ Full?).

## 1. Cơ chế chung — tách "muốn hiện gì" khỏi "đang vẽ gì"

Trước Phần 7.1, `MultiModelScene.applyState()` đổi `activeModel` (model đang vẽ) ngay lập tức mỗi
khi `CharacterStateMachine` đổi state — không có chỗ để chèn animation ở giữa.

Giờ tách làm 2 field:
- `desiredModel` — model mà state machine MUỐN hiện, set bởi `applyState()` (main thread), y hệt
  logic switch cũ (Roaming/Listening→Slime, Thinking→Chibi, Talking→Chibi hoặc Slime nếu text
  dài, Expressing→Full, PlayingVideo→Video). Các side-effect đi kèm (set expression, `videoLayer
  .play()`) vẫn chạy ngay lập tức như cũ — chỉ việc "hiện model nào" là bị hoãn.
- `displayModel` — model THỰC SỰ đang được vẽ frame đó, chỉ được đổi bởi `reconcileModel()` (chạy
  mỗi frame trên GL thread, trong `onDrawFrame`).

`reconcileModel()`:
- Nếu cổng đang chạy dở → không đụng, để nó chạy hết (nó đã "chốt" target lúc `start()`).
- Nếu `desiredModel != displayModel` và **cả 2 đều là model nhỏ** (Slime/Chibi) →
  `PortalTransitionOverlay.start(desiredModel)` (chạy cổng thay vì đổi ngay).
- Nếu có dính FULL hoặc VIDEO ở 1 trong 2 bên → đổi ngay lập tức, **giữ nguyên hành vi cũ** (Phần
  7.2 sẽ thay bằng hiệu ứng riêng cho Full).

## 2. `PortalTransitionOverlay` (file mới)

- Dùng lại `OverlayTextureRenderer` sẵn có từ Phần 5 (vẽ quad có texture, toạ độ pixel) — không
  cần thêm shader mới.
- Texture "dải cổng" chỉ dựng **1 lần** lúc `onContextCreated` (không build lại mỗi frame, tránh
  tốn CPU): 1 bitmap 720×320 vẽ bằng `Canvas` — nền gradient tím-đen đặc mô phỏng không gian, vài
  chấm sao rải rác, và 1 đường viền zigzag phát sáng (glow bằng `BlurMaskFilter` + lõi trắng sáng
  đè lên) đặt gần đáy bitmap (90% chiều cao).
- 2 pha animation, mỗi lần đổi model chạy đúng 1 lần theo thứ tự:
  1. **DESCEND** (0.28s) — quad phủ từ y=0 xuống `coverHeight` tăng dần 0→chiều cao màn hình,
     texture bị stretch theo nên mép sáng luôn nằm sát đúng đường biên `coverHeight` đang tiến
     xuống → cảm giác "cổng kéo từ trên xuống nuốt model".
  2. Đúng lúc `coverHeight` đạt full màn hình (chuyển DESCEND→ASCEND) → gọi callback `onSwap` ĐÚNG
     1 LẦN để `MultiModelScene` đổi `displayModel` — lúc này cổng che kín 100% nên user không thấy
     giật hình.
  3. **ASCEND** (0.32s) — quad co lại từ đáy màn hình lên đỉnh (`coverHeight` giảm dần từ full về
     0), lộ dần model MỚI từ dưới lên → khớp đúng yêu cầu "từ dưới lên hiện lên model khác".
- Vì bitmap gốc tô đặc (opaque) toàn bộ chiều cao (không để hở vùng trong suốt), quá trình stretch
  không bao giờ lộ model bên dưới qua khe hở.

## 3. Chặn thao tác trong lúc cổng chạy

- `MultiModelScene.onTouch()` — nuốt hết chạm khi `portalTransition.isActive` (tránh nudge Slime /
  trigger motion Chibi giữa lúc hình đang biến mất hoặc chưa kịp xuất hiện hẳn).
- `ChibiWallpaperService.onDoubleTap()` — thêm điều kiện bỏ qua double-tap khi
  `scene.isTransitioning()`, y hệt cách đã làm với `isBoardShowing()` ở Phần 5 (double-tap khi
  bảng clipboard hiện).
- Bong bóng chat/bảng clipboard (`ChatBubbleOverlay`) tạm **ẩn** trong lúc cổng đang chạy (`!
  portalTransition.isActive`) — tránh 2 hiệu ứng chồng lên nhau nhìn rối; bong bóng tự hiện lại
  ngay khung hình sau khi cổng xong (state/bubbleText không đổi gì trong lúc chờ).

## 4. Các file đổi/thêm trong Phần 7.1

- **Mới**: `render/PortalTransitionOverlay.kt`
- **Sửa**: `render/MultiModelScene.kt` (tách `desiredModel`/`displayModel`, thêm
  `reconcileModel()`, nối vòng đời `PortalTransitionOverlay`, chặn touch lúc cổng chạy, thêm
  `isTransitioning()`), `ChibiWallpaperService.kt` (chặn double-tap lúc cổng chạy),
  `app/build.gradle.kts` (versionCode/versionName → 6 / "0.6-phan7_1")

## 5. Cách kiểm tra

1. Chạy app, đặt làm hình nền như Phần 6.
2. Ở màn hình chính (Slime đang roam), double-tap vào Slime → nói gì đó ngắn gọn → chờ Gemini trả
   lời. Lúc chuyển Slime→Chibi (vào THINKING) và Chibi→Slime (nếu trả lời dài, quay Slime hiện
   bảng clipboard) phải thấy dải tối kéo từ trên xuống che kín rồi rút từ dưới lên, KHÔNG thấy
   model cũ/mới bị "chớp" lộ ra giữa chừng.
3. Double-tap liên tục trong lúc dải cổng đang chạy (khoảng 0.6s) → không có gì xảy ra (bị chặn),
   thử lại sau khi cổng xong thì bình thường.
4. Chạm vào màn hình (single tap) đúng lúc cổng đang chạy → Slime/Chibi không bị nudge/trigger
   motion gì cả.
5. Trường hợp Gemini trả về `show_expression` hoặc `play_video` (chuyển sang Full/Video) — vẫn đổi
   NGAY LẬP TỨC như trước giờ (chưa có hiệu ứng cổng riêng, để dành Phần 7.2), không bị lỗi hay
   đứng hình.

## Còn thiếu / để dành Phần 7.2

- Hiệu ứng cổng riêng cho Full (cổng to kéo tới chân + cổng nhỏ ở góc/giữa hiện model nhỏ), và
  chiều ngược lại (Slime/Chibi → Full).
- Vị trí cổng nhỏ (góc trái/phải/giữa) hiện đang để ngỏ — cần chốt là cố định, random, hay do
  Gemini/action chỉ định.
- Test trên máy thật để canh lại thời lượng DESCEND/ASCEND (hiện 0.28s/0.32s, số tạm) — có thể cần
  chỉnh nếu thấy quá nhanh/chậm so với cảm giác mong muốn.

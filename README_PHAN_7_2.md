# Phần 7.2 — Hiệu ứng cổng riêng cho Full

Tiếp nối Phần 7.1 (`README_PHAN_7_1.md`): hiệu ứng cổng cho các lần đổi model có dính **Full**, vốn
để tạm "đổi tức thì" ở Phần 7.1. Theo đúng phần "Còn thiếu / để dành Phần 7.2" đã ghi ở đó, cộng
với 1 quyết định chốt lại với user: **vị trí cổng nhỏ = RANDOM mỗi lần** (không cố định 1 góc, và
không để Gemini/action chỉ định).

## 1. Hai chiều hiệu ứng

**FULL → model nhỏ (Slime/Chibi)** — `FullPortalOverlay.startFullToSmall(target)`:
1. **DESCEND_BAND** (0.30s) — dải cổng TO (dùng lại đúng texture/mesh của
   `PortalTransitionOverlay` qua hàm mới `drawBandAt()`) kéo từ đỉnh màn hình xuống, luôn đi hết
   100% chiều cao ("tới chân") — nuốt trọn Full. Lúc phủ kín → swap `displayModel` sang model nhỏ
   đích (bị che hoàn toàn nên không giật hình), rồi chốt random 1 vị trí cổng nhỏ.
2. **EMERGE_HOLE** (0.36s) — 1 lỗ tròn viền glow tím (cùng tông màu #B388FF của Phần 7.1) mở rộng
   dần từ bán kính 0 tại vị trí vừa chốt (trái/giữa/phải, sát đáy màn hình) tới phủ hết đường chéo
   màn hình — model nhỏ lộ dần ra đúng qua cái lỗ đó, như "chui ra từ một điểm".

**model nhỏ → FULL** (chiều ngược lại) — `FullPortalOverlay.startSmallToFull()`:
1. **CONSUME_HOLE** (0.36s) — chốt random 1 vị trí khác, lỗ tròn bắt đầu ở bán kính phủ hết màn
   hình (tức nhìn bình thường) rồi co dần về 0 — như "cổng nhỏ hút" nuốt model nhỏ hiện tại, phần
   ngoài lỗ luôn bị che kín đặc. Co hết (bán kính 0 = phủ kín 100%) → swap `displayModel` sang FULL.
2. **ASCEND_BAND** (0.30s) — dải cổng to (lại `drawBandAt()`) rút từ đáy màn hình lên đỉnh, lộ dần
   Full ra từ dưới lên — giống hệt cơ chế ASCEND của Phần 7.1.

## 2. Vì sao cần 1 GL program riêng cho lỗ tròn

`OverlayTextureRenderer` (Phần 5) chỉ vẽ được 1 quad chữ nhật lấy nguyên texture — không mask được
theo hình tròn. `FullPortalOverlay` tự dựng 1 program riêng: fragment shader tô đặc (voidColor gần
đen-tím) toàn màn hình TRỪ 1 lỗ tròn tâm `uCenter` bán kính `uRadius`, mép lỗ là dải glow tím rộng
`uEdge` (tính theo % đường chéo màn hình để nhất quán giữa các máy khác độ phân giải). Dùng
`smoothstep` trên khoảng cách tới tâm để có alpha mềm mại ở mép, tránh răng cưa.

`gl_FragCoord` gốc DƯỚI-TRÁI (chuẩn GL) trong khi `uCenter` truyền vào theo pixel gốc TRÊN-TRÁI
(giống toạ độ touch/`OverlayTextureRenderer`) nên shader tự lật trục Y trước khi so khoảng cách.

2 pha dải cổng to (DESCEND_BAND/ASCEND_BAND) thì KHÔNG dựng lại texture — gọi thẳng
`PortalTransitionOverlay.drawBandAt(coverHeight, ...)` (hàm mới, tách ra từ `draw()` cũ của Phần
7.1) để dùng chung đúng 1 texture "dải cổng" cho cả 2 phần 7.1 và 7.2, tránh trùng lặp asset.

## 3. Vị trí cổng nhỏ — RANDOM (đã chốt với user)

`pickRandomHolePosition()` chọn ngẫu nhiên 1 trong 3 vị trí cố định mỗi lần bắt đầu `EMERGE_HOLE`
hoặc `CONSUME_HOLE`: trái (x=18%), giữa (x=50%), phải (x=82%) — luôn sát đáy màn hình (y=86%). Vị
trí được chốt lại (random mới) ở CẢ HAI chiều, độc lập nhau — không nhất thiết đối xứng (lúc "chui
ra" ở góc trái, xong THINKING → EXPRESSING quay lại Full có thể "hút" ở góc khác).

## 4. `MultiModelScene.reconcileModel()` — mở rộng cho FULL

- `desired`/`display` đều là model nhỏ và khác nhau → như cũ, `portalTransition.start()` (Phần
  7.1).
- `desired=FULL`, `display`=model nhỏ → `fullPortal.startSmallToFull()`.
- `desired`=model nhỏ, `display=FULL` → `fullPortal.startFullToSmall(desired)`.
- Còn lại (dính VIDEO) → đổi ngay lập tức như trước — PLAYING_VIDEO vẫn CHƯA có hiệu ứng cổng
  riêng.

`onDrawFrame()` update/draw CẢ HAI overlay (`portalTransition` và `fullPortal`) mỗi frame, nhưng
`reconcileModel()` đảm bảo chỉ 1 trong 2 đang active tại 1 thời điểm (không bao giờ chạy chồng).
Bong bóng chat/bảng clipboard tạm ẩn khi `portalTransition.isActive || fullPortal.isActive`
(giống Phần 7.1, mở rộng thêm điều kiện). `onTouch()` và `MultiModelScene.isTransitioning()`
(dùng bởi `ChibiWallpaperService.onDoubleTap()`) cũng gộp cả 2 cờ — không cần sửa gì thêm ở
`ChibiWallpaperService.kt` vì nó đã gọi qua `scene.isTransitioning()` sẵn từ Phần 7.1.

## 5. Các file đổi/thêm trong Phần 7.2

- **Mới**: `render/FullPortalOverlay.kt`
- **Sửa**: `render/PortalTransitionOverlay.kt` (tách `drawBandAt()` public từ `draw()` để dùng
  chung), `render/MultiModelScene.kt` (thêm field `fullPortal`, mở rộng `reconcileModel()`,
  `onDrawFrame()`, `onTouch()`, `onContextDestroyed()`, `isTransitioning()`),
  `app/build.gradle.kts` (versionCode/versionName → 7 / "0.7-phan7_2")

## 6. Cách kiểm tra

1. Chạy app, đặt làm hình nền như Phần 6/7.1.
2. Double-tap Slime, nói 1 câu để Gemini trả về action chuyển **Expressing** (Full) — phải thấy dải
   tối kéo từ trên xuống nuốt Slime/Chibi trước, rồi 1 lỗ tròn viền tím mở dần ở 1 trong 3 vị trí
   (trái/giữa/phải, sát đáy) lộ dần Full ra.
3. Khi Full quay lại Slime/Chibi (ví dụ do timeout hoặc action tiếp theo) — phải thấy chiều ngược
   lại: lỗ tròn co dần lại (nuốt Full) rồi dải cổng rút từ dưới lên lộ model nhỏ.
4. Lặp lại vài lần, để ý vị trí lỗ tròn đổi chỗ ngẫu nhiên giữa các lần (không luôn ở 1 góc).
5. Double-tap / chạm liên tục trong lúc cổng Full đang chạy → không có gì xảy ra (bị chặn, giống
   Phần 7.1); thử lại sau khi cổng xong thì bình thường.
6. Trường hợp Gemini trả về `play_video` (PLAYING_VIDEO) — vẫn đổi NGAY LẬP TỨC như trước (chưa có
   hiệu ứng cổng riêng cho VIDEO), không lỗi hay đứng hình dù đang ở Full hay model nhỏ.

## Còn thiếu / có thể tinh chỉnh thêm

- Thời lượng DESCEND_BAND/ASCEND_BAND/EMERGE_HOLE/CONSUME_HOLE (hiện 0.30s/0.30s/0.36s/0.36s, số
  tạm) — nên test trên máy thật để canh lại nếu thấy quá nhanh/chậm.
- `EDGE_RATIO` (độ rộng dải glow quanh lỗ, hiện 5% đường chéo màn hình) có thể cần chỉnh nếu viền
  glow trông quá dày/mỏng trên máy thật.
- PLAYING_VIDEO vẫn chưa có hiệu ứng cổng riêng (đổi tức thì) — để dành 1 phần sau nếu cần.

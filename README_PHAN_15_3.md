# Phần 15.3 — Chuyển model theo state (cổng dịch chuyển thật) cho Floating Pet

## Đã làm

1. **`render/ActiveModel.kt` (mới)** — tách `enum class ActiveModel { SLIME, CHIBI, FULL, VIDEO }`
   ra khỏi `MultiModelScene` thành file top-level riêng, cùng package `render`.
   - `MultiModelScene` không đổi hành vi: mọi chỗ dùng `ActiveModel.XXX` bên trong vẫn hợp lệ
     (cùng package, không cần import).
   - `PortalTransitionOverlay` và `FullPortalOverlay` đổi `MultiModelScene.ActiveModel` →
     `ActiveModel` — hết phụ thuộc `MultiModelScene`.

2. **`floating/FloatingPetRenderer.kt`** — model đổi qua **cổng dịch chuyển thật** (dùng lại
   nguyên `PortalTransitionOverlay`/`FullPortalOverlay`), giống hệt `MultiModelScene`:
   - `desiredModel` (set trong `updateDesiredModel()`, gọi ở cuối `showThinking()` /
     `showReply()` / `showExpression()` / `clearBubbleAndRevert()`) — theo `renderMode`:
     `BASE` → model gốc theo `modelType`, `CHIBI_REPLY` → `CHIBI`, `FULL_EXPR` → `FULL`.
   - `displayModel` — model **thật sự đang vẽ**, chỉ đổi qua `reconcileModel()` (mỗi frame,
     logic y hệt `MultiModelScene.reconcileModel`) hoặc qua callback `onSwap` của cổng.
   - `onSurfaceCreated`: thêm `portalTransition.ensureInitialized(or)` +
     `fullPortal.ensureInitialized()` ngay sau khi tạo `overlayRenderer`.
   - `onDrawFrame`: `portalTransition.update` → `fullPortal.update` → `reconcileModel()` →
     vẽ theo `displayModel` (qua `wrapperFor()`) → `portalTransition.draw` → `fullPortal.draw`
     → bubble (ẩn khi `isTransitioning`).
   - `release()`: thêm `portalTransition.onContextDestroyed(or)` + `fullPortal.onContextDestroyed()`.
   - `wrapperFor(ActiveModel)`: map model → đúng wrapper GL, có tính tới việc `baseWrapper` có
     thể CHÍNH LÀ Chibi hoặc Full tuỳ `modelType` (không có wrapper phụ trùng lặp).
   - `isTransitioning` (public): `portalTransition.isActive || fullPortal.isActive` — dành cho
     Phần 15.5 (double-tap) dùng để chặn thao tác lúc cổng đang chạy.
   - **Không đụng** `FloatingPetService.attachDragAndTap` / `updateViewLayout` — cổng vẽ theo
     toạ độ local của surface 130dp, không liên quan vị trí window trên màn hình.

## Không đổi
- `FloatingPetService.kt` — API gọi renderer y hệt cũ (`showThinking/showReply/showExpression/
  clearBubbleAndRevert/onTap/isListening/isBusy`), không cần sửa gì.
- Hành vi Live Wallpaper (`MultiModelScene`) — không đổi, chỉ đổi chỗ khai báo enum.

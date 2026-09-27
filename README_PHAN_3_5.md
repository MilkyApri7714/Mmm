# Phần 3.5 — Motion idle, Physics, Slime roaming ✅

## Đã làm

### 1. `character/SlimeController.kt` (mới)

Logic di chuyển thuần Kotlin, không dependency Android/Cubism — dễ test riêng.

**Thuật toán:**
- Chọn điểm đích ngẫu nhiên trong vùng an toàn `X ∈ [-0.55, +0.55]`, `Y ∈ [-0.6, +0.1]`.
- Trượt đều đến đích tốc độ `0.25 đơn vị/giây`.
- Đến nơi → dừng ngẫu nhiên 1–3.5 giây → chọn đích mới.
- Bob (nảy nhẹ): cộng `sin(t × 2.2Hz) × 0.018` vào posY mỗi frame — biên độ nhỏ, không gây chóng mặt.

`MultiModelScene.onDrawFrame()` đọc `slimeController.posX/posY` rồi gán vào `slimeWrapper` trước mỗi `draw()`.

---

### 2. `character/CubismModelWrapper.kt` — motion + physics + breath + eye blink

Thêm vào `loadFromAssets()`:

| Thứ | Tính năng | Cách load |
|-----|-----------|-----------|
| Motion idle | `CubismMotionManager` + vòng lặp round-robin qua nhóm `"Idle"` trong model3.json | `CubismMotion.create(bytes)` |
| Physics | `CubismPhysics` — tóc/phụ kiện lắc lư theo chuyển động | `CubismPhysics.create(bytes)` |
| Breath | `CubismBreath` — `ParamBreath` dao động chu kỳ 3.2s | `CubismBreath.create()` |
| Eye blink | `CubismEyeBlink` — từ `Groups[EyeBlink]` trong model3.json; fallback param chuẩn | `CubismEyeBlink.create(setting)` |

Thứ tự cập nhật trong `draw(dt)` mỗi frame:
```
1. motionManager.updateMotion(model, dt)   // đặt tham số motion
2. breath.updateParameters(model, dt)      // đè thêm breath
3. eyeBlink.updateParameters(model, dt)    // đè thêm blink
4. physics.evaluate(model, dt)             // tính vật lý
5. model.update()                          // đông cứng → vertex
6. renderer.drawModel()                    // vẽ lên GL
```

Signature `draw()` đổi thành `draw(screenWidth, screenHeight, dt: Float = 0f)` — backward compatible (dt mặc định 0 nếu không truyền).

**Model nào không có nhóm Idle** (Slime, Tingyun) sẽ chỉ có breath + physics — vẫn sống động nhờ physics lắc + thở.

**Koharu (Chibi)** có 11 motion file trong nhóm `Idle` → load tất cả, phát round-robin liên tục.

---

### 3. `render/MultiModelScene.kt` — truyền dt + gắn SlimeController

- `onDrawFrame(dt)` clamp dt ở 0.1s (tránh giật lớn sau khi app resume).
- Slot SLIME: gọi `slimeController.update(dt)` rồi gán `posX/posY` vào wrapper.
- Slot CHIBI/FULL: gọi `draw(width, height, dt)` thẳng.

---

## Cách kiểm tra

1. Build + cài APK, đặt lại hình nền động.
2. Về Home: **Slime** di chuyển tự do trên màn hình, dừng lại, đổi hướng, nảy nhẹ.
3. Logcat `ChibiCubism`: physics và (nếu có) số idle motion được log khi load.
4. Tap 1 lần → **Chibi (Koharu)**: đứng giữa màn hình, thở, chớp mắt, chạy animation idle.
5. Tap lần 2 → **Full (Tingyun)**: phủ gần toàn màn hình, physics tóc/phụ kiện lắc.
6. Tap lần 3 → quay lại Slime roaming.
7. Khoá máy 30 giây, mở lại: không giật vì dt clamp 0.1s — Slime tiếp tục đi từ vị trí cũ.

---

## Việc CHƯA làm (Phần 4 trở đi)

- Double-tap → STT: `onTouch()` vẫn là single-tap switcher tạm thời.
- `CharacterStateMachine`: chưa có — state machine thật là Giai đoạn 4.
- Biểu cảm (expression) cho Tingyun: 4 file `.exp3.json` đã có trong `assets/full/` nhưng
  `CubismExpressionMotionManager` chưa được wire vào — để dành cho `ActionRouter` (Phần 5).

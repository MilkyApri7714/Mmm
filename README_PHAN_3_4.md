# Phần 3.4 — Load 3 model thật vào project ✅

## Mục tiêu
Thay thế model demo `Mark` (đi kèm Cubism SDK) bằng 3 asset thật của dự án, đồng thời
chuẩn bị nền tảng vị trí/kích thước (posX/posY/scaleFactor) để Slime roaming (Phần 3.5) chỉ
cần ghi 2 giá trị mỗi frame mà không phải đụng vào projection hay renderer.

---

## Thay đổi so với Phần 3.3

### 1. Assets mới trong `app/src/main/assets/`

```
assets/
├── slime/                         ← SLIME (dạng roaming, model mặc định)
│   ├── SLIME.model3.json
│   ├── SLIME.moc3
│   ├── SLIME.physics3.json
│   └── SLIME.4096/
│       └── texture_00.png
├── chibi/                         ← Tingyun / 停云 (dạng trò chuyện + tạm dùng cho Full)
│   ├── 停云.model3.json
│   ├── 停云.moc3
│   ├── 停云.physics3.json
│   ├── 停云.4096/
│   │   └── texture_00.png
│   └── (expression: 尾巴, 心心眼, 脸红, 脸黑 — dùng ở Phần 5)
└── test_model/Mark/               ← giữ lại để fallback nếu cần debug
```

> **Full placeholder**: asset "full" chưa có → `MultiModelScene` load Koharu lần 2 với
> `scaleFactor = 1.1f` để phân biệt với Chibi khi test bằng mắt. Khi có asset thật chỉ
> cần đổi 2 hằng `FULL_DIR`/`FULL_JSON` trong `MultiModelScene.kt`.

---

### 2. `character/CubismModelWrapper.kt` — thêm posX / posY / scaleFactor

```kotlin
var posX: Float = 0f       // X trong [-1,1]: -1=trái, 0=giữa, +1=phải
var posY: Float = 0f       // Y trong [-1,1]: -1=dưới, 0=giữa, +1=trên
var scaleFactor: Float = 1f // 1.0 = kích thước layout mặc định của model
```

Trong `draw()`, sau khi dựng projection co theo tỉ lệ màn hình và nhân model matrix như Phần
3.3, thêm một bước `projection.translateRelative(posX, posY)` — áp vào cuối nên posX/posY là
đơn vị màn hình chuẩn hoá thực sự, không bị méo theo tỉ lệ.

**Phần 3.5** chỉ cần:
```kotlin
slimeWrapper.posX = newX   // tính từ SlimeController
slimeWrapper.posY = newY
// draw() tự áp, không phải đổi gì thêm
```

---

### 3. `render/MultiModelScene.kt` (mới, thay `CubismModelDrawScene`)

Load cả 3 wrapper ngay trong `onContextCreated()`:

| Slot    | Asset           | scaleFactor | posY  | Ghi chú                      |
|---------|-----------------|-------------|-------|------------------------------|
| SLIME   | slime/          | 0.5         | −0.3  | nhỏ, thấp — cảm giác "đứng trên sàn" |
| CHIBI   | chibi/ (Koharu) | 0.75        | 0.0   | giữa màn hình                |
| FULL    | chibi/ (Koharu) | 1.1         | +0.1  | scale lớn, tạm dùng Koharu  |

`onDrawFrame()` chỉ gọi `draw()` trên wrapper của `activeModel`:
```kotlin
when (activeModel) {
    SLIME -> slimeWrapper?.draw(width, height)
    CHIBI -> chibiWrapper?.draw(width, height)
    FULL  -> fullWrapper?.draw(width, height)
}
```

**Test switcher tạm thời** trong `onTouch()`:
```
Tap → SLIME → CHIBI → FULL → SLIME → ...
```
Sẽ bị xoá/thay khi `CharacterStateMachine` + double-tap (Phần 3.5/Giai đoạn 4) vào.

---

### 4. `ChibiWallpaperService.kt`

Chỉ thay `CubismModelDrawScene` → `MultiModelScene`. Không đổi gì khác.

---

## Cách kiểm tra

1. Build + cài APK, đặt lại hình nền động **Chibi Wallpaper**.
2. Về Home: thấy **Slime** xuất hiện giữa-dưới màn hình (nhỏ hơn model Mark trước đây).
3. **Tap nhẹ 1 lần** → chuyển sang **Chibi** (Koharu, kích thước vừa).
4. Tap lần 2 → chuyển sang **Full** (Koharu to hơn, cao hơn giữa màn hình).
5. Tap lần 3 → quay lại **Slime**.
6. Logcat, filter `ChibiCubism`: thấy 3 dòng "Load OK" tương ứng:
   ```
   Load OK 'SLIME.model3.json': parts=... params=... drawables=... textures=1/1
   Load OK 'koharu.model3.json': parts=... params=... drawables=... textures=1/1
   Load OK 'koharu.model3.json': parts=... params=... drawables=... textures=1/1
   ```
7. Khoá/mở máy nhiều lần, xoay dọc/ngang: không crash, model vẫn đúng vị trí.
8. Mở app khác rồi quay lại: vẫn hiển thị đúng model đang chọn (activeModel được giữ qua
   vòng đời surface, vì scene không bị tạo lại — chỉ EGLSurface bị tạo lại).

---

## Việc CHƯA làm (để dành Phần 3.5 → Giai đoạn 4)

- Motion idle / physics: 3 model vẫn đứng yên hoàn toàn (chưa có `CubismMotionManager` /
  `CubismPhysics.evaluate()`). Phần 3.5 sẽ thêm vào `CubismModelWrapper.draw()` trước
  `model.update()`.
- Slime roaming: `posX`/`posY` vẫn cố định. `SlimeController` (Phần 3.5) sẽ cập nhật mỗi
  frame.
- Double-tap: `onTouch()` trong `MultiModelScene` hiện chỉ xử lý single-tap để test. Phần
  3.5/Giai đoạn 4 đưa `GestureDetector` vào `GLRenderer.onTouch()` / `ChibiEngine`.
- `CharacterStateMachine`: chưa tồn tại — sẽ điều khiển `activeModel` thay cho test
  switcher hiện tại.

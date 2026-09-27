# Phần 3.3 — Vẽ model Cubism thật lên màn hình ✅

## Đã làm
- **`character/CubismModelWrapper.kt`**: mở rộng thêm so với Phần 3.2 —
  - Sau khi load `moc3` + texture, gọi `setupRenderer(CubismRendererAndroid.create(1, 1))`
    rồi `bindTexture()` từng texture theo đúng chỉ số trong `model3.json` (đổi từ `List` sang
    `Map<textureIndex, glId>` để không bị lệch chỉ số nếu sau này có model texture rời rạc).
  - `isPremultipliedAlpha(true)` — bắt buộc vì texture được decode với `inPremultiplied = true`
    (thiếu dòng này thường thấy viền đen/màu tối quanh model).
  - Dựng `CubismModelMatrix` từ `canvasWidth/canvasHeight` của model + layout trong
    `model3.json` (nếu có) qua `setupFromLayout()`.
  - Gọi `model.update()` một lần lúc load (dựng vertex ban đầu từ tham số mặc định).
  - Hàm mới `draw(screenWidth, screenHeight)`: dựng ma trận **projection** co theo tỉ lệ khung
    hình màn hình (không méo dù dọc/ngang), nhân với model matrix, `setMvpMatrix()` rồi
    `drawModel()`. Gọi lại `model.update()` mỗi frame — chưa có gì đổi tham số (chưa
    motion/physics) nhưng để sẵn chỗ cho Phần 3.5 cắm vào mà không phải sửa `draw()`.
  - `release()`: giải phóng GL texture theo đúng map mới; `delete()` (kế thừa) tự lo giải
    phóng renderer.
- **`render/CubismModelDrawScene.kt`** (mới, thay hẳn `CubismModelLoadCheckScene.kt` của Phần
  3.2 — file đó đã bị xoá): load model ở `onContextCreated()`, gọi `modelWrapper.draw()` mỗi
  frame ở `onDrawFrame()`. Không còn tam giác demo nào cả.
- **`render/DemoTriangleScene.kt`** (Phần 2): đã xoá — không còn nơi nào dùng.
- **`ChibiWallpaperService.kt`**: `GLRenderer` giờ dùng thẳng `CubismModelDrawScene`, không
  bọc gì thêm.

## Cách kiểm tra
1. Chạy lại `app`, đặt lại hình nền động (chọn lại **Chibi Wallpaper** dù đang đặt sẵn).
2. Về Home: thấy model **Mark** (mẫu đi kèm Cubism SDK) đứng yên giữa màn hình, nền tím than
   như cũ — **không còn tam giác** nữa.
3. Logcat, filter `ChibiCubism`: phải thấy dòng `Load + setup renderer OK 'Mark.model3.json': ...`
   với số `parts/parameters/drawables/textures` hợp lý (giống hệt số ở Phần 3.2, chỉ thêm chữ
   "setup renderer OK").
4. Xoay máy dọc/ngang: model co giãn theo tỉ lệ khung hình, không bị méo, không bị cắt cạnh.
5. Khoá máy / mở app khác rồi quay lại nhiều lần: không crash, model vẫn hiện đúng vị trí
   (chứng minh chuỗi load lại model sau khi mất context vẫn chạy đúng).

## Việc CHƯA làm (để dành Phần 3.4 → 3.5)
- Chỉ có 1 model (Mark) — chưa tải Slime/Chibi/Full theo cấu trúc `assets/slime`, `assets/chibi`,
  `assets/full` (Phần 3.4).
- Chưa có cách chọn model nào đang vẽ (chưa phải state machine thật — đó là Giai đoạn 4).
- Chưa có motion idle / physics — model hoàn toàn tĩnh, không thở không chớp mắt (Phần 3.5).

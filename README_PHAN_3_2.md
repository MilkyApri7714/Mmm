# Phần 3.2 — Load 1 model Cubism thật vào bộ nhớ (chưa vẽ)

(Xem `README_PHAN_3_1.md` cho bản chia 5 phần của Giai đoạn 3.)

## Đã làm

- **`character/CubismModelWrapper.kt`** (mới): class Kotlin kế thừa `CubismUserModel` của
  Framework. `loadFromAssets(modelDir, modelJsonFileName)`:
  1. Đọc `.model3.json` từ assets, parse bằng `CubismModelSettingJson`.
  2. Đọc `FileReferences.Moc` -> gọi `loadModel()` (hàm có sẵn của `CubismUserModel`) ->
     dựng `CubismModel` trong bộ nhớ.
  3. Với mỗi file trong `FileReferences.Textures`: decode PNG bằng `BitmapFactory`, tạo GL
     texture (`glGenTextures` + `GLUtils.texImage2D` + mipmap) — **chưa** `bindTexture()` vào
     renderer vì Phần 3.2 chưa có renderer.
  4. Log ra Logcat (tag `ChibiCubism`) số `parts`/`parameters`/`drawables`/`textures`.
  - Có `release()`: xoá GL texture + gọi `delete()` (giải phóng moc/model phía JNI) — dùng khi
    context sắp mất hoặc đổi model.
  - **Chỉ đọc** `Moc` + `Textures` trong `model3.json`, cố tình bỏ qua `Physics`/`Pose`/
    `UserData`/`Motions` — những phần đó thuộc Phần 3.5 (idle motion/physics), giữ 3.2 gọn.
- **`render/CubismModelLoadCheckScene.kt`** (mới, **tạm thời** — thay `CubismBootCheckScene`
  của Phần 3.1): bọc quanh `DemoTriangleScene` như cũ (tam giác demo Phần 2 vẫn còn nguyên
  trên màn hình), cộng thêm: sau `CubismBoot.initializeOnGlThread()`, tạo
  `CubismModelWrapper` và `loadFromAssets()` model test, log kết quả. `onContextDestroyed()`
  gọi `release()` trước khi hủy CubismFramework.
- **`ChibiWallpaperService.kt`**: đổi sang dùng `CubismModelLoadCheckScene`.
- **Asset test tạm**: `app/src/main/assets/test_model/Mark/` — model mẫu **Mark** đi kèm
  Cubism SDK (nhẹ nhất trong các model mẫu, ~600KB, chỉ 1 texture). Chỉ copy đúng 3 file cần
  (`Mark.model3.json`, `Mark.moc3`, `Mark.2048/texture_00.png`) — không copy
  physics/userdata/motion vì code chưa đụng tới. Dùng model mẫu vì asset Slime/Chibi/Full của
  bạn chưa xong hoạt hoạ; khi sẵn sàng chỉ cần đổi 2 hằng số `MODEL_DIR`/`MODEL_JSON` trong
  `CubismModelLoadCheckScene`, không phải sửa `CubismModelWrapper`.

Tam giác demo Phần 2 **vẫn còn nguyên**, chưa có model Cubism nào hiện trên màn hình — đúng
mục tiêu 3.2 (chỉ chứng minh load, chưa vẽ).

## Cách kiểm tra

1. Mở lại project, đợi Gradle sync.
2. Chạy `app`, đặt lại hình nền động (chọn lại **Chibi Wallpaper** dù đang đặt sẵn).
3. Về Home: vẫn thấy tam giác xoay như trước — đúng như kỳ vọng.
4. Logcat, filter tag `ChibiCubism`: sau các dòng boot của Phần 3.1, phải thấy thêm
   `Load OK 'Mark.model3.json': parts=... parameters=... drawables=... textures=1/1`.
5. Nếu thấy `Load model test 'Mark.model3.json' thất bại` hoặc `ERROR` — copy nguyên log lỗi
   gửi lại, đừng đoán mò nguyên nhân.
6. Khoá máy / mở app khác rồi quay lại, xoay máy nhiều lần: không crash, log `Load OK` xuất
   hiện lại mỗi lần context được dựng lại (vì model bị `release()` khi mất context).

## Việc CHƯA làm (để dành Phần 3.3 → 3.5)

- Vẽ model lên màn hình (`CubismRendererAndroid`, `bindTexture`, `drawModel()`).
- Load cả 3 model Slime/Chibi/Full + cơ chế chọn model đang vẽ.
- Motion/expression/physics, dọn vòng đời hoàn chỉnh.
- Asset thật của bạn (Slime/Chibi/Full) — model `Mark` chỉ là placeholder test.

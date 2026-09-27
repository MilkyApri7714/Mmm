# Giai đoạn 3 được chia làm 5 phần nhỏ (3.1 → 3.5)

Giai đoạn 3 gốc ("Tích hợp Cubism SDK for Java + cả 3 model") khá nặng nên chia nhỏ.
Bản đầu chia 4 phần nhưng Phần 3.2 (load model + vẽ lên màn hình) nặng gấp đôi các phần
còn lại, nên tách lại thành 5 phần cho đều tay hơn — tách đúng chỗ nặng nhất (load vs vẽ)
làm hai bước riêng:

1. **Phần 3.1 (xong ở đây, giữ nguyên không đổi)** — Kéo Cubism SDK for Java vào project
   (module Gradle + Core AAR), viết lớp boot CubismFramework, chứng minh SDK khởi động/giải
   phóng sạch qua đúng vòng đời EGL context đã có từ Phần 2. **Chưa load model nào cả.**
2. **Phần 3.2** — `CubismModelWrapper`: đọc `.model3.json`, load `moc3` + texture của 1 model
   thật (đề xuất `chibi/` — Haruto — vì plan đã có sẵn 10 motion) vào bộ nhớ/GL texture, dựng
   xong `CubismModel`. **Chưa vẽ lên màn hình** — chỉ log số parts/drawables ra Logcat để xác
   nhận load đúng, tam giác demo Phần 2 vẫn còn nguyên trên màn hình.
3. **Phần 3.3** — Dùng `CubismRenderer_Android` vẽ model đã load ở 3.2 lên màn hình, thay hẳn
   `DemoTriangleScene`. Model đứng yên (chưa animation/physics).
4. **Phần 3.4** — Mở rộng tải cả 3 model (Slime/Chibi/Full) theo đúng cấu trúc
   `assets/slime`, `assets/chibi`, `assets/full`; thêm cơ chế chọn model nào đang được vẽ
   (chưa phải state machine thật — đó là Giai đoạn 4 theo README_PHAN_1, chỉ để test bằng tay).
5. **Phần 3.5** — Phát motion idle mặc định cho model đang hiển thị + physics cơ bản, dọn
   vòng đời (giải phóng đúng khi mất context/model đổi), cập nhật README tổng kết Giai đoạn 3.

## Đã làm ở Phần 3.1

- **`Core/android/Live2DCubismCore.aar`**: copy nguyên bản từ SDK gốc (thư viện native
  lõi Live2D, có sẵn `.so` cho `armeabi-v7a` + `arm64-v8a`).
- **`framework/`**: copy module `Framework/framework` từ SDK gốc (mã nguồn Java thuần của
  Cubism Framework — id manager, motion, physics, renderer Android...). Chỉ sửa đúng 1 chỗ:
  đường dẫn tới Core AAR trong `build.gradle` (`../../Core/android` → `../Core/android`) vì
  đã phẳng hoá cấu trúc thư mục so với SDK gốc.
- **`settings.gradle.kts`**: thêm `include(":framework")`.
- **`app/build.gradle.kts`**: thêm dependency `project(":framework")` + Core AAR, thêm
  `ndk { abiFilters += ["armeabi-v7a", "arm64-v8a"] }` (Core chỉ có 2 ABI này, khai báo rõ
  để tránh lint/packaging cảnh báo ABI thiếu).
- **`app/.../cubism/CubismBoot.kt`** (mới): quản lý `CubismFramework.startUp()` /
  `initialize()` / `dispose()`. Có `LogFn` (in log Cubism ra Logcat tag `ChibiCubism`) và
  `FileFn` (đọc asset qua `Context.assets`, dùng cho shader nội bộ mà Framework tự load).
- **`app/.../render/CubismBootCheckScene.kt`** (mới, **tạm thời**): bọc quanh
  `DemoTriangleScene` hiện có, gọi `CubismBoot.initializeOnGlThread()` trong
  `onContextCreated()` và `CubismBoot.disposeOnGlThread()` trong `onContextDestroyed()` —
  đúng 2 thời điểm GL thread có/mất context (xem `GLRenderer.kt`, Phần 2). File này sẽ bị
  xoá ở Phần 3.2 khi có scene Cubism thật đảm nhiệm luôn việc boot.
- **`ChibiWallpaperService.kt`**: đổi 1 dòng — bọc `DemoTriangleScene()` bằng
  `CubismBootCheckScene(applicationContext, ...)`.

Tam giác demo Phần 2 **vẫn còn nguyên**, chưa có gì mới hiện trên màn hình — mục tiêu
Phần 3.1 chỉ là chứng minh SDK link đúng và boot/shutdown sạch, không crash.

## Cách kiểm tra

1. Mở lại project bằng Android Studio, đợi Gradle sync (lần đầu sẽ tải thêm plugin
   `com.android.library` cho module `framework` nếu chưa có sẵn — vẫn cần mạng).
2. Chạy `app`, đặt lại hình nền động (chọn lại **Chibi Wallpaper** dù đang đặt sẵn, để
   nạp bản mới).
3. Về Home: vẫn thấy tam giác xoay như Phần 2 — **đúng như kỳ vọng**.
4. Logcat, filter tag `ChibiCubism`: phải thấy đúng thứ tự
   `CubismFramework.startUp() -> true` rồi `CubismFramework.initialize() xong...`.
5. Khoá máy / mở app khác rồi quay lại nhiều lần, xoay máy: không crash, tam giác vẫn xoay,
   Logcat không có dòng lỗi nào từ tag `ChibiCubism`.
6. (Tuỳ chọn, test context bị mất thật) Bật **Developer options > Không lưu hoạt động**
   hoặc chuyển máy vẽ GPU khác nếu có — nếu tam giác vẫn phục hồi bình thường và
   `ChibiCubism` log lại đúng chuỗi start→init một lần nữa, tức là đường dây context-lost
   cũng ổn.

## Việc CHƯA làm (để dành Phần 3.2 → 3.5)

- Load bất kỳ model `.moc3` nào (Slime/Chibi/Full).
- Vẽ Cubism model thật lên màn hình (renderer, texture, mesh).
- Motion/expression/physics.
- Chọn/chuyển đổi giữa 3 model.

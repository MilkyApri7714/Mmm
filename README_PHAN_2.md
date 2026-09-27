# Phần 2 — Giai đoạn 2 (EGL/OpenGL) ✅ | Giai đoạn 3 (Cubism + 3 model) ⏳

## Đã làm (Giai đoạn 2)
- `render/GLRenderer.kt`: tự dựng EGL context (OpenGL ES 2.0) trên một GL thread riêng ("ChibiGL"),
  vòng lặp vẽ bằng Choreographer, giới hạn 30 FPS, chỉ chạy khi wallpaper hiển thị + có surface.
  EGLContext được giữ sống khi surface bị huỷ/tạo lại; tự dựng lại nếu mất context.
- `render/GLScene.kt`: interface cho "cảnh" được vẽ (Giai đoạn 3 sẽ implement bằng scene Cubism).
- `render/DemoTriangleScene.kt`: tam giác màu xoay để xác nhận pipeline; chạm màn hình -> tam giác trượt tới điểm chạm.
- `ChibiWallpaperService.kt`: Engine chỉ chuyển tiếp vòng đời sang GLRenderer (không còn dùng Canvas).
- `AndroidManifest.xml`: thêm `glEsVersion 0x00020000`.

## Cách kiểm tra
1. Chạy lại `app`, đặt lại hình nền động **Chibi Wallpaper** (nếu đang đặt bản cũ, chọn lại để nạp bản mới).
2. Thấy nền tím than + tam giác màu xoay giữa màn hình. Chạm đâu, tam giác trượt tới đó.
3. Logcat, filter `ChibiGL` và `ChibiWallpaper`: có dòng `GL_RENDERER/GL_VERSION`, `shader OK`.
4. Khoá màn hình / mở app khác: log `onVisibilityChanged: false` và tam giác ngừng được vẽ (không tốn pin).
5. Bấm Home rồi vào lại nhiều lần, hoặc xoay máy: không crash, tam giác vẫn xoay.

## Giai đoạn 3 cần
- Cubism SDK for Java (phiên bản + cách đặt vào project).
- Asset: `slime/`, `chibi/` (model3.json, moc3, texture, physics3, motion...). Model `full/` làm sau.

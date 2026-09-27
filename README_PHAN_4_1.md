# PHẦN 4.1 — Bản vá: khôi phục tài nguyên bị mất khi gộp phần 4

## Vấn đề phát hiện
Khi gộp code Phần 4 (AI/Gemini, ActionRouter, CharacterStateMachine...) vào nhánh
chính, quá trình merge chỉ copy các file mã nguồn (.kt) mà **không copy các file
nhị phân lớn**, khiến bản zip "phan4" chỉ nặng ~78KB thay vì ~9MB như phần 3.5,
và thiếu cả hai module mà Gradle vẫn khai báo phụ thuộc (`:framework` và
`Core/android/Live2DCubismCore.aar`) → build sẽ lỗi ngay từ bước sync Gradle.

## Danh sách đã khôi phục (lấy từ phần 3.5)
- `framework/` — toàn bộ module Cubism SDK for Java (bị thiếu hoàn toàn)
- `Core/android/Live2DCubismCore.aar` — thư viện lõi Live2D (bị thiếu hoàn toàn)
- `gradle/` và `gradle.properties` — (bị thiếu hoàn toàn)
- `app/src/main/assets/full/tingyun.moc3` (3.6MB, bị thiếu)
- `app/src/main/assets/full/tingyun.4096/texture_00.png` (4MB, rỗng 0 byte)
- `app/src/main/assets/test_model/Mark/Mark.moc3` (bị thiếu)
- `app/src/main/assets/test_model/Mark/Mark.2048/texture_00.png` (rỗng 0 byte)
- `app/src/main/assets/slime/SLIME.moc3` (bị thiếu)
- `app/src/main/assets/slime/SLIME.4096/texture_00.png` (rỗng 0 byte)
- `app/src/main/assets/chibi/koharu.moc3` (bị thiếu)
- `app/src/main/assets/chibi/koharu.2048/texture_00.png` (rỗng 0 byte)
- `README_PHAN_3_2.md`, `README_PHAN_3_3.md`, `README_PHAN_3_5.md`,
  `README_PHAN_6.md` — bị thiếu hoàn toàn
- `README_PHAN_3_4.md` — còn tên file nhưng nội dung rỗng, đã nạp lại

## Giữ nguyên từ phần 4
Toàn bộ code mới của Phần 4 không bị đụng tới: `ai/GeminiClient.kt`,
`ai/ActionRouter.kt`, `ai/SpeechToTextManager.kt`,
`character/CharacterStateMachine.kt`, `render/MultiModelScene.kt` (bản cập nhật),
`character/CubismModelWrapper.kt` (bản cập nhật), `ChibiWallpaperService.kt`
(bản cập nhật), `app/build.gradle.kts` (thêm coroutines), `AndroidManifest.xml`
(quyền mới cho AI/notification).

## Kết quả
Dự án giờ build được đầy đủ — có cả module Cubism framework, thư viện .aar,
và tất cả model/texture Live2D cần thiết để 4 nhân vật (tingyun, Mark, slime,
koharu) hiển thị đúng.

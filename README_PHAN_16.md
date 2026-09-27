# PHẦN 16 — Hội thoại song phương (Bilingual Mode)

## Cách dùng
Nói với Milky: "Này Milky, hãy bật chế độ hội thoại song phương" (có thể kèm ngôn ngữ đích, ví dụ
"...dịch sang tiếng Anh"). Gemini gọi function `start_bilingual_mode(target_language?)`, Milky
chuyển hẳn về dạng **Slime** và giữ nguyên dạng đó suốt phiên, liên tục ghi âm — nói xong 1 câu,
Milky tự dịch (Gemini) + đọc to bằng TTS có sẵn của Android + hiện phụ đề (câu gốc + câu dịch)
+ tự mở mic nghe câu tiếp theo, lặp lại liên tục.

**Cách tắt** (1 trong 3 cách đều được):
- Nói: "tắt chế độ dịch" / "dừng hội thoại song phương" / "ngừng dịch"... (nhận diện local, không
  tốn API) — xem `BILINGUAL_STOP_PHRASES` trong `ChibiWallpaperService.kt`.
- Nhấn-giữ trúng Slime (giống cử chỉ ghi âm bình thường, nhưng lúc đang dịch thì nghĩa là "tắt").
- Bấm nút X nếu đang hiện bảng phụ đề dài (board).

## Giới hạn của bản đầu (v1)
- Chỉ hoạt động ở **live wallpaper** (`ChibiWallpaperService`), CHƯA nối vào `FloatingPetService`
  (nhân vật nổi) — service đó chưa dùng `CharacterStateMachine`.
- TTS dùng đúng giọng mặc định của engine theo từng ngôn ngữ (`TtsHelper` map mã ISO 639-1 → Locale),
  KHÔNG cho chọn giọng cụ thể. Nếu máy chưa cài gói giọng đọc ngôn ngữ đó → chỉ hiện chữ, không đọc.
- STT vẫn dùng chung `vi-VN` làm ngôn ngữ ưu tiên (cấu hình sẵn có từ Phần 4) — dựa vào
  `EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE=false` để đôi khi vẫn nhận đúng câu nói bằng ngôn ngữ khác.
  Gemini (`GeminiClient.translate()`) mới là bên tự nhận diện ngôn ngữ nguồn của mỗi câu.

## File đã sửa/thêm
- **Mới:** `ai/TtsHelper.kt` — bọc `android.speech.tts.TextToSpeech`.
- `ai/GeminiClient.kt` — thêm 2 function declarations, `DEFAULT_TRANSLATION_SYSTEM_PROMPT`,
  `translate()`, `TranslationResult`.
- `ai/ActionRouter.kt` — case `start_bilingual_mode`/`stop_bilingual_mode`,
  `RoutedAction.StartBilingualMode`/`StopBilingualMode`.
- `character/CharacterStateMachine.kt` — state `TRANSLATING`, `startBilingualMode()`,
  `updateTranslationBubble()`.
- `render/MultiModelScene.kt` — map `TRANSLATING` → Slime, `suppressSleep`, `forceBoardBubble`
  (ép phụ đề neo theo Slime thay vì đầu Chibi).
- `render/ChatBubbleOverlay.kt` — thêm tham số `forceBoard` cho `setText()`.
- `ChibiWallpaperService.kt` — toàn bộ vòng lặp ghi âm liên tục + gọi dịch + TTS + các đường tắt.
- `ui/MainActivity.kt` — ô "System Prompt dịch thuật" riêng (`KEY_TRANSLATION_SYSTEM_PROMPT`).

## Việc cần làm tiếp (chưa làm trong bản này)
- Nối tính năng vào `FloatingPetService` (nhân vật nổi) nếu muốn dùng được cả khi không đặt live
  wallpaper.
- Cho chọn Voice cụ thể trong TTS (hiện chỉ dùng giọng mặc định theo ngôn ngữ).
- Test thực tế trên máy thật — bản này viết trực tiếp bằng tay theo kiến trúc có sẵn của project,
  CHƯA build/compile thử qua Gradle (môi trường soạn không có Android SDK).

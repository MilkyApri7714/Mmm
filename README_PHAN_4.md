# Phần 4 — SpeechRecognizer · Gemini · State Machine đầy đủ

## Những gì thay đổi so với Phần 3.5

| File | Trạng thái | Mô tả |
|------|-----------|-------|
| `ai/SpeechToTextManager.kt` | **MỚI** | STT + bộ đếm 3s im lặng tự ngắt |
| `ai/GeminiClient.kt` | **MỚI** | Gọi Gemini 2.0 Flash, chat + function calling |
| `ai/ActionRouter.kt` | **MỚI** | Map function call → RoutedAction + ReminderReceiver |
| `character/CharacterStateMachine.kt` | **MỚI** | 6 trạng thái: Roaming/Listening/Thinking/Talking/Expressing/PlayingVideo |
| `ChibiWallpaperService.kt` | **CẬP NHẬT** | Double-tap, AI brain, auto-dismiss 8s |
| `render/MultiModelScene.kt` | **CẬP NHẬT** | `applyState()`, set expression, wiggle tap |
| `character/CubismModelWrapper.kt` | **CẬP NHẬT** | `setExpression()`, `triggerRandomMotion()` |
| `character/SlimeController.kt` | **CẬP NHẬT** | `nudge()` khi single-tap |
| `AndroidManifest.xml` | **CẬP NHẬT** | ~20 quyền: hiện tại + tương lai |
| `app/build.gradle.kts` | **CẬP NHẬT** | coroutines, androidx.core |

---

## Luồng hoạt động đầy đủ

```
[Home Screen]
  └─ Slime roam tự do (ROAMING)
       │
       ▼ double-tap
  [STT ghi âm] (LISTENING)
       │  3s không có âm thanh / có kết quả
       ▼
  [Chibi "Đang suy nghĩ..."] (THINKING)
       │  gọi Gemini 2.0 Flash
       ├─── text thường → [Chibi + bong bóng chat] (TALKING)
       │                        │ 8s không tương tác
       │                        └──────────────────── ROAMING
       │
       ├─── show_expression → [Full model + expression] (EXPRESSING)
       │                        │ 3s
       │                        └──────────────────── ROAMING
       │
       ├─── play_video → [video layer] (PLAYING_VIDEO)
       │                        │ video kết thúc (Phần 5)
       │                        └──────────────────── ROAMING
       │
       ├─── start_roaming ──────────────────────────── ROAMING
       │
       ├─── tell_time → [Chibi + "Bây giờ là HH:mm"] (TALKING)
       │
       └─── set_reminder → [Chibi + xác nhận] (TALKING) + AlarmManager
```

---

## Cách merge vào project

1. **Copy** các file mới/cập nhật vào đúng đường dẫn trong project Phần 3.5.
2. **Đừng xoá** các file không có trong danh sách (GLRenderer, GLScene, CubismBoot, GLScene, v.v.).
3. `app/build.gradle.kts` — thay hoàn toàn file cũ.
4. `AndroidManifest.xml` — thay hoàn toàn file cũ.
5. **Sync Gradle** → Build → Install.

---

## Cấu trúc file sau merge

```
ai/
├── SpeechToTextManager.kt   ← MỚI
├── GeminiClient.kt           ← MỚI
└── ActionRouter.kt           ← MỚI (chứa cả ReminderReceiver)

character/
├── CharacterStateMachine.kt  ← MỚI
├── CubismModelWrapper.kt     ← cập nhật (thêm setExpression, triggerRandomMotion)
└── SlimeController.kt        ← cập nhật (thêm nudge)

render/
└── MultiModelScene.kt        ← cập nhật (applyState, expression, wiggle)

ChibiWallpaperService.kt      ← cập nhật (double-tap, AI brain)
AndroidManifest.xml           ← cập nhật (~20 quyền)
app/build.gradle.kts          ← cập nhật (coroutines, notification)
```

---

## Setup trước khi chạy

1. Mở app → **MainActivity**.
2. Cấp quyền **Micro** (nút "Cấp quyền micro").
3. Dán **Gemini API key** (lấy từ [aistudio.google.com](https://aistudio.google.com)) → **Lưu**.
4. Bấm **Đặt làm hình nền động**.
5. Ra ngoài home screen → **double-tap** vào Slime → bắt đầu nói chuyện!

---

## Gemini Functions hiện có

| Function | Trigger | Kết quả |
|---------|---------|---------|
| `show_expression` | "Bạn có vui không?", "Ngạc nhiên đi~" | Full model + expression (hearteyes/blush/darkface/tail) |
| `play_video` | "Phát video mèo đi" | PLAYING_VIDEO (Phần 5 render thật) |
| `start_roaming` | "Thôi đi chơi đi", "Bye bye" | Slime roam tự do |
| `tell_time` | "Mấy giờ rồi?", "Bây giờ là mấy giờ?" | Text + giờ thật |
| `set_reminder` | "Nhắc mình uống nước sau 30 phút" | AlarmManager + Notification |

---

## Quyền khai báo trong Manifest

### Phần 4 — Đang dùng
- `RECORD_AUDIO` — STT
- `INTERNET` — Gemini API
- `POST_NOTIFICATIONS` — nhắc nhở
- `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` — AlarmManager
- `VIBRATE` — haptic double-tap (Phần 5)
- `WAKE_LOCK` — giữ CPU khi Gemini xử lý

### Khai báo sẵn cho tương lai
- `READ/WRITE_CALENDAR` — nhắc từ lịch
- `READ_CONTACTS`, `CALL_PHONE`, `SEND_SMS` — "gọi cho Mẹ"
- `CAMERA` — nhận diện ảnh
- `ACCESS_FINE_LOCATION` — thời tiết, địa điểm
- `READ_MEDIA_*` — đọc ảnh/video user
- `FOREGROUND_SERVICE_MICROPHONE` — STT khi màn hình tắt

---

## Lưu ý kỹ thuật

### SpeechToTextManager
- Tạo và gọi **phải trên main thread** (Android yêu cầu).
- Bộ đếm 3 giây reset mỗi khi `onRmsChanged > -1.5 dB` hoặc `onPartialResults` cập nhật.
- Nếu `onError(ERROR_NO_MATCH)` mà đã có `partialText` → coi như thành công (hay xảy ra).

### GeminiClient
- Dùng `java.net.HttpURLConnection` (built-in, không cần OkHttp).
- Giữ history 8 lượt (= 16 messages) để Gemini nhớ ngữ cảnh.
- Function calling mode: `AUTO` → Gemini tự quyết định khi nào gọi function.

### CharacterStateMachine
- `@Volatile` fields + transition check — thread-safe đủ dùng cho 2 thread (main + GL).
- `backToRoaming()` chấp nhận từ **bất kỳ** state nào (trừ ROAMING).

### Double-tap detection
- Window 300ms giữa 2 ACTION_UP.
- Single tap → `renderer.onTouch()` → `scene.onTouch()` → wiggle/random motion.
- Double tap → state machine + STT (không forward vào scene).

---

## TODO Phần 5
- [ ] Render bong bóng chat thật lên GL surface (Canvas overlay hoặc SurfaceView trên cùng)
- [ ] Mic indicator khi LISTENING (icon hoặc hiệu ứng trên Slime)
- [ ] VideoLayer: `MediaPlayer` + `SurfaceTexture` phát video khi PLAYING_VIDEO
- [ ] Haptic feedback khi double-tap
- [ ] TTS (TextToSpeech) đọc câu trả lời ra tiếng
- [ ] Tối ưu pin: suspend coroutine khi wallpaper không visible

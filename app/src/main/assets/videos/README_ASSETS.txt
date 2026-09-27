PHẦN 5 — Thư mục này cần các file .mp4 thật (chưa có, đây chỉ là placeholder ghi chú).

VideoLayer (render/VideoLayer.kt) mở file bằng appContext.assets.openFd("videos/<tên>"),
tên khớp với map VIDEO_ASSETS trong ai/ActionRouter.kt:

  dance.mp4
  wave.mp4
  sleep.mp4
  surprised.mp4
  default.mp4

Nếu file chưa tồn tại, VideoLayer.play() sẽ log lỗi và trả về false — scene tạm hiển thị
Slime, và ChibiWallpaperService sẽ tự quay về ROAMING sau VIDEO_FALLBACK_MS (15s) nhờ job
an toàn, không bị treo ở màn hình đen.

Khi có clip thật: bỏ trực tiếp file .mp4 vào thư mục này (không cần đổi code), khuyến nghị
độ phân giải vừa phải (≤720p) và thời lượng ngắn (vài giây) để không tốn pin/bộ nhớ khi
Gradle đóng gói vào APK.

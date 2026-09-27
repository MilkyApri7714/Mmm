package com.example.chibiwallpaper.ai

import android.app.Notification
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.chibiwallpaper.ChibiWallpaperService

/**
 * PHẦN 12 — Đọc thông báo quan trọng: khi có tin nhắn/thông báo mới từ app khác, Milky tóm tắt
 * ngắn gọn qua bong bóng chat ("Bạn có tin nhắn mới từ X") thay vì chủ nhân phải mở khoá xem.
 *
 * KHÁC với các quyền dangerous khác trong app: đây là "Special access" — user phải tự bật ở
 * Settings > Apps > Special app access > Notification access (nút mở màn hình đó ở MainActivity,
 * Section "Tính năng mở rộng"), không có runtime permission dialog.
 *
 * Tôn trọng "Không làm phiền" ([DoNotDisturbHelper]) giống mọi lời chủ động khác của Milky —
 * việc gọi qua [ChibiWallpaperService.tryShowProactiveMessage] → showProactiveBubble() đã tự
 * kiểm tra DND rồi, nhưng ta vẫn chặn sớm ở đây để không tốn công dựng câu tóm tắt khi chắc chắn
 * sẽ bị bỏ qua.
 */
class MilkyNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "ChibiNotifListener"

        /** Kiểm tra user đã bật quyền đọc thông báo cho app này chưa (để hiện StatusPill ở UI). */
        fun hasAccess(context: Context): Boolean {
            val enabledListeners = Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            return enabledListeners.contains(context.packageName)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        try {
            handleNotification(sbn)
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi xử lý notification: ${e.message}", e)
        }
    }

    private fun handleNotification(sbn: StatusBarNotification) {
        // Bỏ qua thông báo của chính Milky (nhắc nhở, proactive...) — không tự tóm tắt chính mình.
        if (sbn.packageName == packageName) return

        val notification = sbn.notification
        // Bỏ qua thông báo hệ thống dai dẳng (ongoing — ví dụ nhạc đang phát, tải file...) vì
        // đây không phải "tin nhắn mới" cần chủ nhân biết ngay.
        if (sbn.isOngoing) return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        // Bỏ qua khi đang bật Không làm phiền — tránh dựng câu tóm tắt vô ích.
        if (DoNotDisturbHelper.isActive(applicationContext)) {
            Log.d(TAG, "Đang Không làm phiền — bỏ qua thông báo từ ${sbn.packageName}")
            return
        }

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        val appName = appLabelFor(sbn.packageName)
        val summary = when {
            title.isNotEmpty() && text.isNotEmpty() -> "Bạn có tin nhắn mới từ $appName: \"$title\" — $text"
            title.isNotEmpty() -> "Bạn có thông báo mới từ $appName: $title"
            else -> "Bạn có thông báo mới từ $appName: $text"
        }

        Log.d(TAG, "Tóm tắt: $summary")
        // Chỉ hiện nếu wallpaper engine đang active và ở trạng thái ROAMING (xem showProactiveBubble
        // trong ChibiWallpaperService) — nếu không, coi như bỏ qua (thông báo gốc vẫn còn trên thanh
        // trạng thái, không cần Milky bắn thêm 1 notification nữa gây trùng lặp).
        ChibiWallpaperService.tryShowProactiveMessage(summary)
    }

    private fun appLabelFor(packageName: String): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: Exception) {
        packageName
    }
}

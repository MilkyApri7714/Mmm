package com.example.chibiwallpaper.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.chibiwallpaper.ChibiWallpaperService
import com.example.chibiwallpaper.R
import com.example.chibiwallpaper.floating.FloatingPetService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * PHẦN 11 — Xử lý các alarm chủ động do [ProactiveManager] đặt.
 *
 * Mỗi lần fire xong đều tự đặt lại lịch cho lần kế tiếp (morning → ngày mai cùng giờ,
 * weather/inactivity → lặp lại theo chu kỳ) — vì AlarmManager exact chỉ bắn 1 lần.
 */
class ProactiveReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ChibiProactiveRx"
        private const val CHANNEL_ID = "chibi_reminder" // dùng chung channel với ReminderReceiver
    }

    override fun onReceive(context: Context, intent: Intent) {
        // PHẦN 11 — handleWeatherCheck gọi mạng (Open-Meteo) nên KHÔNG được chạy trực tiếp trên
        // main thread (sẽ crash NetworkOnMainThreadException) → dùng goAsync() + coroutine IO,
        // giữ tiến trình sống đủ lâu để hoàn tất trước khi hệ thống có thể thu hồi.
        val action = intent.action
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    ProactiveManager.ACTION_MORNING -> handleMorning(context)
                    ProactiveManager.ACTION_WEATHER_CHECK -> handleWeatherCheck(context)
                    ProactiveManager.ACTION_INACTIVITY_CHECK -> handleInactivityCheck(context)
                    else -> Log.w(TAG, "Action lạ: $action")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi xử lý proactive action=$action: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleMorning(context: Context) {
        ProactiveManager.scheduleMorning(context) // đặt luôn cho sáng mai trước khi nói chuyện
        val scheduleLine = if (CalendarHelper.hasPermission(context)) {
            val events = CalendarHelper.getTodaySchedule(context)
            if (events.isEmpty()) "Hôm nay bạn rảnh cả ngày, thảnh thơi nhé~"
            else CalendarHelper.formatScheduleForSpeech(events)
        } else {
            "Chúc bạn một ngày mới tốt lành nhé~"
        }
        deliver(context, "Chào buổi sáng! $scheduleLine")
    }

    private fun handleWeatherCheck(context: Context) {
        ProactiveManager.scheduleWeatherCheck(context) // đặt lại cho lần check kế tiếp
        val prefs = context.getSharedPreferences("chibi_wallpaper_prefs", Context.MODE_PRIVATE)
        val lat = prefs.getFloat(ProactiveManager.KEY_WEATHER_LAT, ProactiveManager.DEFAULT_LAT.toFloat()).toDouble()
        val lon = prefs.getFloat(ProactiveManager.KEY_WEATHER_LON, ProactiveManager.DEFAULT_LON.toFloat()).toDouble()

        val now = WeatherHelper.fetchCurrent(lat, lon) ?: return
        val lastCode = prefs.getInt(ProactiveManager.KEY_LAST_WEATHER_CODE, Int.MIN_VALUE)
        prefs.edit().putInt(ProactiveManager.KEY_LAST_WEATHER_CODE, now.code).apply()

        // Chỉ báo khi ĐÃ có mã lần trước (không phải lần check đầu tiên) và mã thật sự đổi.
        if (lastCode != Int.MIN_VALUE && lastCode != now.code) {
            val desc = WeatherHelper.describeCode(now.code)
            deliver(context, "Thời tiết bên ngoài đổi rồi nè, giờ đang $desc, khoảng ${now.tempC.toInt()}°C~")
        }
    }

    private fun handleInactivityCheck(context: Context) {
        val prefs = context.getSharedPreferences("chibi_wallpaper_prefs", Context.MODE_PRIVATE)
        val lastInteraction = prefs.getLong(ProactiveManager.KEY_LAST_INTERACTION_TS, System.currentTimeMillis())
        val hours = prefs.getInt(ProactiveManager.KEY_INACTIVITY_HOURS, ProactiveManager.DEFAULT_INACTIVITY_HOURS)
        val elapsedMs = System.currentTimeMillis() - lastInteraction

        if (elapsedMs >= hours * 60 * 60 * 1000L) {
            deliver(context, "Lâu rồi không thấy bạn ghé qua, Milky nhớ bạn ghê~ Dạo này ổn không?")
            // Coi như vừa "tương tác" để không nhắc dồn dập — vẫn tiếp tục chu kỳ nhắc định kỳ.
            prefs.edit().putLong(ProactiveManager.KEY_LAST_INTERACTION_TS, System.currentTimeMillis()).apply()
        }
        ProactiveManager.scheduleInactivityCheck(context)
    }

    /** Ưu tiên hiện bong bóng chat ngay trên wallpaper nếu đang active; không thì bắn notification. */
    private fun deliver(context: Context, text: String) {
        // PHẦN 12 — "Không làm phiền": chặn cả đường fallback notification (không chỉ bong bóng
        // chat, việc đó đã được showProactiveBubble() tự chặn khi wallpaper đang active).
        if (DoNotDisturbHelper.isActive(context)) {
            Log.d(TAG, "Đang bật Không làm phiền — bỏ qua: $text")
            return
        }
        // Thử floating trước (đang nổi trên app khác), nếu không có thì thử live wallpaper
        if (FloatingPetService.tryShowFloatingProactiveMessage(text)) return
        if (ChibiWallpaperService.tryShowProactiveMessage(text)) return

        try {
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Milky")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "Không có quyền POST_NOTIFICATIONS: ${e.message}")
        }
    }
}

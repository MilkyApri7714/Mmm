package com.example.chibiwallpaper.ai

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import java.util.Calendar

/**
 * PHẦN 11 — Proactive behavior: Milky tự chủ động lên tiếng thay vì chỉ chờ user hỏi.
 *
 * 3 loại trigger, đều dùng [AlarmManager] có sẵn (như [ActionRouter.scheduleReminder]):
 *   1. Chào buổi sáng — alarm lặp lại mỗi ngày vào 1 giờ cố định (mặc định 7:00).
 *   2. Thời tiết thay đổi — alarm lặp lại mỗi vài giờ, so sánh mã thời tiết với lần check trước.
 *   3. Lâu không tương tác — reset mỗi khi user thật sự tương tác (double-tap/hỏi Gemini);
 *      nếu không có tương tác nào trong X giờ, [ProactiveReceiver] sẽ nhắc nhẹ.
 *
 * Vì AlarmManager không giữ lịch qua reboot, [BootReceiver] gọi [scheduleAll] lại khi máy khởi động.
 */
object ProactiveManager {
    private const val TAG = "ChibiProactive"
    private const val PREFS_NAME = "chibi_wallpaper_prefs"

    // ── Cờ bật/tắt + cấu hình (đọc/ghi từ MainActivity, Section "Trợ lý chủ động") ──────────
    const val KEY_MORNING_ENABLED = "proactive_morning_enabled"
    const val KEY_MORNING_HOUR = "proactive_morning_hour"           // 0-23, mặc định 7
    const val KEY_WEATHER_ENABLED = "proactive_weather_enabled"
    const val KEY_WEATHER_LAT = "proactive_weather_lat"
    const val KEY_WEATHER_LON = "proactive_weather_lon"
    const val KEY_INACTIVITY_ENABLED = "proactive_inactivity_enabled"
    const val KEY_INACTIVITY_HOURS = "proactive_inactivity_hours"   // mặc định 6

    // ── State nội bộ ─────────────────────────────────────────────────────────────────────
    const val KEY_LAST_INTERACTION_TS = "proactive_last_interaction_ts"
    const val KEY_LAST_WEATHER_CODE = "proactive_last_weather_code"

    const val DEFAULT_MORNING_HOUR = 7
    const val DEFAULT_INACTIVITY_HOURS = 6
    const val DEFAULT_LAT = 21.0285   // Hà Nội — chỉ là giá trị mặc định, user nên chỉnh lại
    const val DEFAULT_LON = 105.8542
    const val WEATHER_CHECK_INTERVAL_MS = 3 * 60 * 60 * 1000L // 3 tiếng/lần

    const val ACTION_MORNING = "com.example.chibiwallpaper.action.PROACTIVE_MORNING"
    const val ACTION_WEATHER_CHECK = "com.example.chibiwallpaper.action.PROACTIVE_WEATHER_CHECK"
    const val ACTION_INACTIVITY_CHECK = "com.example.chibiwallpaper.action.PROACTIVE_INACTIVITY_CHECK"

    private const val REQ_MORNING = 9101
    private const val REQ_WEATHER = 9102
    private const val REQ_INACTIVITY = 9103

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Gọi ở [ChibiWallpaperService.onCreate] và ở [BootReceiver] — (re)đặt cả 3 loại alarm. */
    fun scheduleAll(context: Context) {
        scheduleMorning(context)
        scheduleWeatherCheck(context)
        // Inactivity không cần đặt ngay ở đây — notifyInteraction() sẽ tự đặt lần đầu.
        // Nhưng nếu app chưa từng tương tác lần nào sau khi cài (chưa có timestamp) → đặt luôn
        // để tính năng hoạt động ngay cả khi user chưa mở STT lần nào.
        if (!prefs(context).contains(KEY_LAST_INTERACTION_TS)) {
            prefs(context).edit().putLong(KEY_LAST_INTERACTION_TS, System.currentTimeMillis()).apply()
        }
        scheduleInactivityCheck(context)
    }

    /** Gọi mỗi khi user THẬT SỰ tương tác (double-tap hỏi Gemini) — reset đồng hồ "lâu không dùng". */
    fun notifyInteraction(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_INTERACTION_TS, System.currentTimeMillis()).apply()
        scheduleInactivityCheck(context)
    }

    // ── 1. Chào buổi sáng ────────────────────────────────────────────────────────────────

    fun scheduleMorning(context: Context) {
        if (!prefs(context).getBoolean(KEY_MORNING_ENABLED, true)) {
            cancel(context, ACTION_MORNING, REQ_MORNING)
            return
        }
        val hour = prefs(context).getInt(KEY_MORNING_HOUR, DEFAULT_MORNING_HOUR)
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        scheduleExact(context, ACTION_MORNING, REQ_MORNING, next.timeInMillis)
        Log.d(TAG, "Đặt lịch chào buổi sáng lúc ${next.time}")
    }

    // ── 2. Thời tiết thay đổi ────────────────────────────────────────────────────────────

    fun scheduleWeatherCheck(context: Context) {
        if (!prefs(context).getBoolean(KEY_WEATHER_ENABLED, true)) {
            cancel(context, ACTION_WEATHER_CHECK, REQ_WEATHER)
            return
        }
        scheduleExact(
            context, ACTION_WEATHER_CHECK, REQ_WEATHER,
            System.currentTimeMillis() + WEATHER_CHECK_INTERVAL_MS
        )
    }

    // ── 3. Lâu không tương tác ───────────────────────────────────────────────────────────

    fun scheduleInactivityCheck(context: Context) {
        if (!prefs(context).getBoolean(KEY_INACTIVITY_ENABLED, true)) {
            cancel(context, ACTION_INACTIVITY_CHECK, REQ_INACTIVITY)
            return
        }
        val hours = prefs(context).getInt(KEY_INACTIVITY_HOURS, DEFAULT_INACTIVITY_HOURS)
        scheduleExact(
            context, ACTION_INACTIVITY_CHECK, REQ_INACTIVITY,
            System.currentTimeMillis() + hours * 60 * 60 * 1000L
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────────────

    private fun scheduleExact(context: Context, action: String, requestCode: Int, triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ProactiveReceiver::class.java).apply { this.action = action }
        val pi = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Không đặt được alarm $action: ${e.message}")
        }
    }

    private fun cancel(context: Context, action: String, requestCode: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ProactiveReceiver::class.java).apply { this.action = action }
        val pi = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        alarmManager.cancel(pi)
    }
}

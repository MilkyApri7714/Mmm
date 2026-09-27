package com.example.chibiwallpaper.ai

import android.content.Context

/**
 * PHẦN 12 — "Không làm phiền": khi đang bật, mọi lời CHỦ ĐỘNG của Milky (chào buổi sáng,
 * báo thời tiết đổi, nhắc lâu không dùng, tóm tắt thông báo mới từ [MilkyNotificationListenerService])
 * đều bị bỏ qua cho tới khi hết giờ.
 *
 * KHÔNG ảnh hưởng tới việc trả lời khi chủ nhân chủ động hỏi/nói chuyện với Milky (double-tap → STT
 * → Gemini vẫn hoạt động bình thường) — DND chỉ chặn hành vi tự lên tiếng, không chặn hội thoại.
 *
 * Bật bằng lời nói qua function "set_do_not_disturb" (xem [ActionRouter]) hoặc bằng tay ở
 * MainActivity (Section "Tính năng mở rộng").
 */
object DoNotDisturbHelper {
    private const val PREFS_NAME = "chibi_wallpaper_prefs"
    const val KEY_DND_UNTIL = "dnd_until_millis"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** @return true nếu Không làm phiền đang bật (mốc hết hạn còn ở tương lai). */
    fun isActive(context: Context): Boolean =
        prefs(context).getLong(KEY_DND_UNTIL, 0L) > System.currentTimeMillis()

    /** @return mốc thời gian (millis) hết hạn nếu đang bật, 0 nếu đang tắt. */
    fun activeUntilMillis(context: Context): Long {
        val until = prefs(context).getLong(KEY_DND_UNTIL, 0L)
        return if (until > System.currentTimeMillis()) until else 0L
    }

    /** Bật Không làm phiền trong [minutes] phút kể từ bây giờ (ghi đè thời hạn cũ nếu có). */
    fun enableForMinutes(context: Context, minutes: Long) {
        val safeMinutes = minutes.coerceIn(1, 24 * 60)
        val until = System.currentTimeMillis() + safeMinutes * 60_000L
        prefs(context).edit().putLong(KEY_DND_UNTIL, until).apply()
    }

    /** Tắt Không làm phiền ngay lập tức. */
    fun disable(context: Context) {
        prefs(context).edit().remove(KEY_DND_UNTIL).apply()
    }
}

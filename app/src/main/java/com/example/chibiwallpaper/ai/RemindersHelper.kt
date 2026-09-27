package com.example.chibiwallpaper.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PHẦN 18 — Lưu trữ nhắc nhở BỀN (sống sót qua khởi động lại máy) + hỗ trợ xem/huỷ.
 *
 * Trước PHẦN 18: [ActionRouter.scheduleReminder] (cũ) chỉ đặt 1 AlarmManager dùng
 * ELAPSED_REALTIME_WAKEUP + SystemClock.elapsedRealtime() — giá trị này RESET về 0 mỗi khi máy
 * khởi động lại, và không hề được lưu ở đâu để khôi phục → tắt máy trước giờ nhắc là MẤT LUÔN,
 * Milky im re không báo gì. PHẦN 18 sửa tận gốc:
 *   - [ActionRouter] chuyển sang AlarmManager.RTC_WAKEUP (giờ thực — sống sót qua reboot).
 *   - Ở ĐÂY lưu mỗi reminder (id, message, triggerAtMillis) vào SharedPreferences dạng JSON —
 *     đây là "nguồn sự thật" để [BootReceiver] đặt lại alarm sau khi máy khởi động lại (bản thân
 *     alarm bị OS xoá sạch lúc reboot, nhưng dữ liệu ở đây thì còn nguyên).
 *   - Thêm [findMatching] để hỗ trợ "huỷ nhắc nhở uống thuốc" bằng giọng nói, [formatForSpeech]
 *     để hỗ trợ "xem nhắc nhở đang chờ".
 */
object RemindersHelper {
    private const val PREFS_NAME = "chibi_wallpaper_prefs"
    private const val KEY_REMINDERS = "pending_reminders_json"
    private const val KEY_ID_SEQ = "reminder_id_seq"
    private const val MAX_REMINDERS = 100 // an toàn, không nên chạm tới trong thực tế

    data class Reminder(val id: Long, val message: String, val triggerAtMillis: Long)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Id tăng dần, không đụng nhau kể cả khi đặt 2 reminder trong cùng 1 millisecond. */
    private fun nextId(context: Context): Long {
        val p = prefs(context)
        val next = p.getLong(KEY_ID_SEQ, 0L) + 1L
        p.edit().putLong(KEY_ID_SEQ, next).apply()
        return next
    }

    /** Thêm 1 reminder mới vào danh sách chờ, trả về chính nó (đã có id) để caller đặt alarm. */
    fun add(context: Context, message: String, triggerAtMillis: Long): Reminder {
        val reminder = Reminder(nextId(context), message, triggerAtMillis)
        val all = loadAll(context).toMutableList()
        all.add(reminder)
        while (all.size > MAX_REMINDERS) all.removeAt(0)
        persist(context, all)
        return reminder
    }

    fun loadAll(context: Context): List<Reminder> {
        val json = prefs(context).getString(KEY_REMINDERS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Reminder(o.getLong("id"), o.getString("message"), o.getLong("at"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Xoá 1 reminder theo id — gọi khi alarm đã bắn xong (dọn dẹp) hoặc khi chủ nhân chủ động huỷ. */
    fun remove(context: Context, id: Long) {
        val remaining = loadAll(context).filterNot { it.id == id }
        persist(context, remaining)
    }

    private fun persist(context: Context, reminders: List<Reminder>) {
        val arr = JSONArray()
        reminders.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("message", r.message)
                put("at", r.triggerAtMillis)
            })
        }
        prefs(context).edit().putString(KEY_REMINDERS, arr.toString()).apply()
    }

    /**
     * Câu thoại liệt kê nhắc nhở đang chờ, gần giờ nhất trước. Chỉ đọc tối đa 5 cái cho gọn qua
     * giọng nói, còn lại gộp thành "và N cái khác".
     */
    fun formatForSpeech(context: Context): String {
        val pending = loadAll(context).sortedBy { it.triggerAtMillis }
        if (pending.isEmpty()) return "Bạn chưa có nhắc nhở nào đang chờ cả~"

        val fmt = SimpleDateFormat("HH:mm dd/MM", Locale.getDefault())
        val shown = pending.take(5)
        val parts = shown.map { "\"${it.message}\" lúc ${fmt.format(Date(it.triggerAtMillis))}" }
        val more = if (pending.size > shown.size) " và ${pending.size - shown.size} cái khác nữa" else ""
        return "Bạn có ${pending.size} nhắc nhở đang chờ: " + parts.joinToString("; ") + more
    }

    /**
     * Tìm 1 reminder khớp [query] (không phân biệt hoa/thường, chỉ cần chứa từ khoá) để huỷ theo
     * giọng nói. [query] rỗng → coi như "huỷ giúp mình" chung chung, trả về cái GẦN TỚI GIỜ NHẤT
     * (thường là cái chủ nhân đang nhớ tới). Khớp nhiều query cùng lúc → cũng ưu tiên cái gần nhất.
     */
    fun findMatching(context: Context, query: String): Reminder? {
        val all = loadAll(context)
        if (query.isBlank()) return all.minByOrNull { it.triggerAtMillis }
        val q = query.trim().lowercase()
        return all.filter { it.message.lowercase().contains(q) }
            .minByOrNull { it.triggerAtMillis }
    }
}

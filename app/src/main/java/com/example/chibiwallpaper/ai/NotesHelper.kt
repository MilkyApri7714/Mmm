package com.example.chibiwallpaper.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * PHẦN 12 — Ghi chú giọng nói nhanh: tận dụng STT có sẵn, Gemini gọi function "save_note" khi
 * chủ nhân nói "ghi chú giúp mình...", và "get_notes" khi hỏi lại "hôm qua mình ghi chú gì".
 *
 * Lưu dạng JSON array trong cùng SharedPreferences với các phần khác của app (đơn giản, không
 * cần thêm Room/DB cho một danh sách nhỏ như thế này).
 */
object NotesHelper {
    private const val PREFS_NAME = "chibi_wallpaper_prefs"
    private const val KEY_NOTES = "quick_notes_json"
    private const val MAX_NOTES = 200 // tránh SharedPreferences phình to vô hạn

    data class Note(val content: String, val timestampMillis: Long)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveNote(context: Context, content: String) {
        if (content.isBlank()) return
        val notes = loadAll(context).toMutableList()
        notes.add(Note(content.trim(), System.currentTimeMillis()))
        while (notes.size > MAX_NOTES) notes.removeAt(0)
        persist(context, notes)
    }

    fun loadAll(context: Context): List<Note> {
        val json = prefs(context).getString(KEY_NOTES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Note(o.getString("content"), o.getLong("ts"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clearAll(context: Context) {
        prefs(context).edit().remove(KEY_NOTES).apply()
    }

    private fun persist(context: Context, notes: List<Note>) {
        val arr = JSONArray()
        notes.forEach { n ->
            arr.put(JSONObject().apply {
                put("content", n.content)
                put("ts", n.timestampMillis)
            })
        }
        prefs(context).edit().putString(KEY_NOTES, arr.toString()).apply()
    }

    /**
     * Câu thoại Milky sẽ nói khi được hỏi lại ghi chú.
     * @param period "today" | "yesterday" | bất kỳ giá trị khác (kể cả null) → 10 ghi chú gần nhất.
     */
    fun formatForSpeech(context: Context, period: String?): String {
        val all = loadAll(context)
        if (all.isEmpty()) return "Bạn chưa ghi chú gì với mình cả~"

        fun startOfDay(offsetDays: Int): Long {
            val c = Calendar.getInstance()
            c.add(Calendar.DAY_OF_YEAR, offsetDays)
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }

        val normalizedPeriod = period?.lowercase()?.trim()
        val filtered = when (normalizedPeriod) {
            "today" -> all.filter { it.timestampMillis >= startOfDay(0) }
            "yesterday" -> all.filter { it.timestampMillis in startOfDay(-1) until startOfDay(0) }
            else -> all.takeLast(10)
        }

        if (filtered.isEmpty()) {
            return when (normalizedPeriod) {
                "today" -> "Hôm nay bạn chưa ghi chú gì với mình cả~"
                "yesterday" -> "Hôm qua bạn không ghi chú gì cả~"
                else -> "Bạn chưa ghi chú gì với mình cả~"
            }
        }

        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val shown = filtered.takeLast(5)
        val parts = shown.map { "\"${it.content}\" lúc ${fmt.format(Date(it.timestampMillis))}" }
        val more = if (filtered.size > shown.size) " và ${filtered.size - shown.size} ghi chú khác nữa" else ""
        return "Bạn có ${filtered.size} ghi chú nè: " + parts.joinToString("; ") + more
    }
}

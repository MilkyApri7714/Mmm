package com.example.chibiwallpaper.ai

import android.content.Context

/**
 * PHẦN 20 — "Hồ sơ nhỏ" lưu local về chủ nhân (dị ứng, sinh nhật người thân, sở thích, thói quen...)
 * để Milky nhớ được QUA NHIỀU NGÀY, thay vì mỗi câu hỏi gửi Gemini gần như độc lập như trước.
 *
 * Lưu dạng free-text nhiều dòng (không phải JSON có cấu trúc) vì 2 lý do:
 *   - Gemini ghi vào qua function [ActionRouter] "remember_fact(content)" → chỉ cần append 1 dòng,
 *     không cần parse/serialize field cố định.
 *   - Chủ nhân tự xem/sửa trực tiếp qua ô textarea ở MainActivity (Section 8) — free-text sửa tự
 *     do, không bị gò theo field cứng.
 *
 * [ContextSnapshotProvider] đọc [summaryForContext] và nối vào system prompt ở MỌI lượt gọi API,
 * để Gemini luôn "biết" hồ sơ này mà không cần chủ nhân nhắc lại.
 */
object MemoryProfileHelper {
    private const val PREFS_NAME = "chibi_wallpaper_prefs"
    private const val KEY_PROFILE = "user_memory_profile"

    // Giới hạn độ dài nhét vào system prompt mỗi lượt gọi — hồ sơ phình quá to vừa tốn token oan
    // uổng vừa khiến Gemini dễ bỏ sót ý quan trọng giữa một đống chữ. takeLast giữ lại các dòng
    // MỚI GHI GẦN ĐÂY khi vượt giới hạn (thà quên fact cũ hiếm dùng còn hơn cắt lung tung).
    private const val MAX_CHARS = 2_000

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Đọc toàn bộ hồ sơ hiện có (rỗng nếu chưa ghi gì). */
    fun get(context: Context): String =
        prefs(context).getString(KEY_PROFILE, "") ?: ""

    /** Ghi đè toàn bộ hồ sơ — dùng khi chủ nhân tự sửa qua ô textarea rồi bấm Lưu. */
    fun set(context: Context, text: String) {
        val trimmed = text.trim()
        val capped = if (trimmed.length > MAX_CHARS) trimmed.takeLast(MAX_CHARS) else trimmed
        prefs(context).edit().putString(KEY_PROFILE, capped).apply()
    }

    /**
     * Thêm 1 dòng fact mới — dùng khi Gemini gọi function "remember_fact". Dòng trùng lặp y hệt
     * (không phân biệt hoa/thường) thì bỏ qua, tránh hồ sơ phình vô ích vì Gemini nhắc lại chuyện
     * đã biết. Trả về false nếu nội dung rỗng hoặc đã có sẵn.
     */
    fun appendFact(context: Context, fact: String): Boolean {
        val trimmed = fact.trim()
        if (trimmed.isEmpty()) return false
        val lines = get(context).lines().filter { it.isNotBlank() }
        if (lines.any { it.equals(trimmed, ignoreCase = true) }) return false
        set(context, (lines + trimmed).joinToString("\n"))
        return true
    }

    /** Khối text nhét vào [ContextSnapshotProvider] mỗi lượt gọi — có câu thay thế khi còn rỗng. */
    fun summaryForContext(context: Context): String =
        get(context).ifBlank { "(chưa có gì)" }
}

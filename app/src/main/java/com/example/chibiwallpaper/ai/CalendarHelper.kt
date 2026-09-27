package com.example.chibiwallpaper.ai

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * PHẦN 11 — Đọc lịch (Calendar) của chủ nhân trong ngày hôm nay.
 * PHẦN 19 — Thêm cả khả năng TẠO sự kiện thật vào Calendar app (xem [createEvent]).
 *
 * Quyền READ_CALENDAR/WRITE_CALENDAR đã khai báo sẵn trong AndroidManifest — chỉ cần user cấp
 * runtime (xem nút "Cấp quyền Lịch" ở MainActivity, Section "Trợ lý chủ động").
 * Nếu chưa được cấp, mọi hàm ở đây trả về rỗng/null/thông báo phù hợp thay vì crash.
 */
object CalendarHelper {

    data class CalendarEvent(
        val title: String,
        val beginMillis: Long,
        val endMillis: Long,
        val allDay: Boolean
    )

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /** PHẦN 19 — Quyền THÊM sự kiện (khác với quyền chỉ đọc ở trên). */
    fun hasWritePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * PHẦN 19 — Tạo 1 sự kiện mới vào Calendar app thật (không phải reminder nội bộ như
     * [RemindersHelper] — cái này chủ nhân mở app Lịch/Google Calendar lên là thấy luôn).
     *
     * Trả về id sự kiện nếu thành công, null nếu thiếu quyền / không tìm được calendar nào
     * ghi được / có lỗi khi insert.
     */
    fun createEvent(context: Context, title: String, beginMillis: Long, endMillis: Long): Long? {
        if (!hasWritePermission(context)) return null
        val calendarId = getDefaultWritableCalendarId(context) ?: return null

        val values = android.content.ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, beginMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
        }

        return try {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            uri?.lastPathSegment?.toLongOrNull()
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Tìm 1 calendar mà app có thể GHI vào — ưu tiên calendar chính chủ (owner account trùng
     * account đăng nhập máy), nếu không có thì lấy đại calendar ghi được đầu tiên tìm thấy.
     */
    private fun getDefaultWritableCalendarId(context: Context): Long? {
        if (!hasWritePermission(context)) return null
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )
        return try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, projection,
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
                arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
                null
            )?.use { cursor ->
                var fallback: Long? = null
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val isPrimary = cursor.getInt(1) != 0
                    if (isPrimary) return id
                    if (fallback == null) fallback = id
                }
                fallback
            }
        } catch (e: SecurityException) {
            null
        }
    }

    /** Lấy toàn bộ sự kiện (kể cả lặp lại — dùng Instances để "giãn" recurring events) hôm nay. */
    fun getTodaySchedule(context: Context): List<CalendarEvent> {
        if (!hasPermission(context)) return emptyList()

        val dayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val dayEnd = dayStart + 24L * 60 * 60 * 1000

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, dayStart)
            ContentUris.appendId(this, dayEnd)
        }.build()

        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY
        )

        val events = mutableListOf<CalendarEvent>()
        try {
            context.contentResolver.query(
                uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    events.add(
                        CalendarEvent(
                            title = cursor.getString(0) ?: "(Không có tiêu đề)",
                            beginMillis = cursor.getLong(1),
                            endMillis = cursor.getLong(2),
                            allDay = cursor.getInt(3) != 0
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            // Quyền có thể bị thu hồi giữa lúc check và query — bỏ qua, trả về rỗng.
        }
        return events
    }

    /** Tóm tắt ngắn cho khối context (1 dòng), dùng để nhét vào system prompt mỗi lượt gọi. */
    fun summaryOneLine(context: Context): String {
        if (!hasPermission(context)) return "chưa cấp quyền xem lịch"
        val events = getTodaySchedule(context)
        if (events.isEmpty()) return "không có sự kiện nào"
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val next = events.first()
        val time = if (next.allDay) "cả ngày" else fmt.format(next.beginMillis)
        return "${events.size} sự kiện, gần nhất \"${next.title}\" lúc $time"
    }

    /** Câu thoại đầy đủ Milky sẽ nói khi được hỏi lịch hôm nay hoặc vào buổi sáng. */
    fun formatScheduleForSpeech(events: List<CalendarEvent>): String {
        if (events.isEmpty()) return "Hôm nay bạn không có lịch gì cả, thảnh thơi ghê~"
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val parts = events.take(4).map { e ->
            val time = if (e.allDay) "cả ngày" else fmt.format(e.beginMillis)
            "${e.title} lúc $time"
        }
        val more = if (events.size > 4) " và ${events.size - 4} việc khác nữa" else ""
        return "Hôm nay bạn có ${events.size} lịch nè: " + parts.joinToString(", ") + more
    }
}

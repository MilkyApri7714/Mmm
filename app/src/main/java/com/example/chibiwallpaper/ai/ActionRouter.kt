package com.example.chibiwallpaper.ai

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.chibiwallpaper.R
import com.example.chibiwallpaper.ui.MainActivity
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PHẦN 4 — Router nhận [GeminiResponse.FunctionCall] và chuyển thành [RoutedAction].
 *
 * Đây là tầng trung gian giữa AI và state machine:
 *   GeminiClient → ActionRouter → CharacterStateMachine
 *
 * Mỗi function Gemini có thể gọi tương ứng với 1 [RoutedAction].
 * Một số action cũng thực thi side-effect ngay tại đây (đặt alarm nhắc nhở, v.v.).
 */
class ActionRouter(private val appContext: Context) {

    companion object {
        private const val TAG = "ChibiRouter"
        private const val REMINDER_CHANNEL_ID = "chibi_reminder"
        private const val REBOOT_CATCHUP_DELAY_MS = 8_000L // PHẦN 18 (reminder bền)

        /**
         * PHẦN 18 (mở rộng video UI) — Video DÀI giờ do user tự chọn ở MainActivity (SAF,
         * content:// URI) thay vì asset cố định trong assets/videos/ — xem [pickLongVideoUri].
         * Danh sách asset cũ (dance/wave/sleep/surprised/default) không còn dùng nữa vì
         * assets/videos/ thực tế chưa từng có file .mp4 thật (chỉ có README_ASSETS.txt placeholder).
         */
    }

    init {
        createNotificationChannel()
    }

    /**
     * Phân tích [GeminiResponse.FunctionCall] và trả về [RoutedAction] tương ứng.
     * Cũng thực thi side-effect (alarm, v.v.) nếu cần.
     */
    fun route(call: GeminiResponse.FunctionCall): RoutedAction {
        Log.d(TAG, "Routing: ${call.name}(${call.args})")
        return when (call.name) {

            "show_expression" -> {
                val expressionName = call.args.optString("name", "blush")
                RoutedAction.ShowExpression(expressionName)
            }

            "play_video" -> {
                // PHẦN 18 (mở rộng video UI) — "slot" (1/2/3) nếu Gemini chỉ định rõ chủ nhân muốn
                // xem video nào; không chỉ định hoặc slot đó chưa có video → chọn ngẫu nhiên 1
                // trong các slot ĐÃ được cấu hình. Không slot nào có video → PlayVideo(null),
                // service sẽ nói cho chủ nhân biết thay vì phát video.
                val slot = call.args.optInt("slot", 0)
                RoutedAction.PlayVideo(pickLongVideoUri(slot))
            }

            "start_roaming" -> {
                RoutedAction.StartRoaming
            }

            "tell_time" -> {
                val now = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                // Route thành text reply với giờ thực tế
                RoutedAction.SpeakText("Bây giờ là $now nè~")
            }

            "set_reminder" -> {
                val message = call.args.optString("message", "Nhắc nhở từ Milky~")
                val minutes = call.args.optDouble("minutes", 1.0).toLong().coerceAtLeast(1)
                // PHẦN 18 (reminder bền) — giờ THẬT (epoch millis) thay vì elapsedRealtime, để
                // reminder sống sót qua reboot (BootReceiver đặt lại alarm dựa trên đúng giờ này).
                val triggerAt = System.currentTimeMillis() + minutes * 60_000L
                val reminder = RemindersHelper.add(appContext, message, triggerAt)
                scheduleReminderAlarm(reminder.id, message, triggerAt)
                RoutedAction.SpeakText("Okay, mình sẽ nhắc bạn sau $minutes phút nha!")
            }

            // ── PHẦN 18 (reminder bền) — Xem / huỷ nhắc nhở đang chờ ─────────────
            "get_reminders" -> {
                RoutedAction.SpeakText(RemindersHelper.formatForSpeech(appContext))
            }

            "cancel_reminder" -> {
                val query = call.args.optString("query", "").trim()
                val match = RemindersHelper.findMatching(appContext, query)
                if (match == null) {
                    RoutedAction.SpeakText(
                        if (query.isBlank()) "Bạn chưa có nhắc nhở nào để huỷ cả~"
                        else "Mình không tìm thấy nhắc nhở nào khớp với \"$query\" cả~"
                    )
                } else {
                    cancelReminderAlarm(match.id)
                    RemindersHelper.remove(appContext, match.id)
                    RoutedAction.SpeakText("Huỷ nhắc nhở \"${match.message}\" rồi nha~")
                }
            }

            "get_today_schedule" -> {
                RoutedAction.SpeakText(readTodaySchedule())
            }

            // ── PHẦN 19 — Tạo sự kiện Lịch thật ──────────────────────────────────
            "create_calendar_event" -> {
                val title = call.args.optString("title", "").trim()
                val date = call.args.optString("date", "").trim()   // "yyyy-MM-dd"
                val time = call.args.optString("time", "").trim()   // "HH:mm"
                val durationMinutes = call.args.optDouble("duration_minutes", 60.0).toLong().coerceAtLeast(1)
                RoutedAction.SpeakText(handleCreateCalendarEvent(title, date, time, durationMinutes))
            }

            // ── PHẦN 12 — Ghi chú giọng nói nhanh ────────────────────────────────
            "save_note" -> {
                val content = call.args.optString("content", "").trim()
                if (content.isEmpty()) {
                    RoutedAction.SpeakText("Bạn muốn ghi chú gì vậy, nói lại giúp mình nha~")
                } else {
                    NotesHelper.saveNote(appContext, content)
                    RoutedAction.SpeakText("Ghi rồi nè: \"$content\"~")
                }
            }

            "get_notes" -> {
                val period = call.args.optString("period", "all")
                RoutedAction.SpeakText(NotesHelper.formatForSpeech(appContext, period))
            }

            // ── PHẦN 20 — Hồ sơ nhớ lâu dài về chủ nhân ──────────────────────────
            "remember_fact" -> {
                val content = call.args.optString("content", "").trim()
                if (content.isEmpty()) {
                    RoutedAction.SpeakText("Bạn muốn mình nhớ điều gì vậy?")
                } else {
                    val added = MemoryProfileHelper.appendFact(appContext, content)
                    RoutedAction.SpeakText(
                        if (added) "Ghi nhớ rồi nè: \"$content\"~ Lần sau mình sẽ nhớ đó!"
                        else "Cái này mình nhớ rồi mà~"
                    )
                }
            }

            // ── PHẦN 12 — Gọi/nhắn nhanh qua danh bạ ─────────────────────────────
            "call_contact" -> {
                val name = call.args.optString("name", "").trim()
                RoutedAction.SpeakText(handleCallContact(name))
            }

            "send_sms" -> {
                val name = call.args.optString("name", "").trim()
                val message = call.args.optString("message", "").trim()
                RoutedAction.SpeakText(handleSendSms(name, message))
            }

            // ── PHẦN 12 — Đèn pin ────────────────────────────────────────────────
            "turn_on_flashlight" -> {
                val ok = FlashlightHelper.setTorch(appContext, true)
                RoutedAction.SpeakText(
                    if (ok) "Bật đèn pin rồi nè~"
                    else "Máy này không có đèn pin hoặc mình không bật được~"
                )
            }

            "turn_off_flashlight" -> {
                val ok = FlashlightHelper.setTorch(appContext, false)
                RoutedAction.SpeakText(
                    if (ok) "Tắt đèn pin rồi~"
                    else "Mình tắt đèn pin không được, thử lại nhé~"
                )
            }

            // ── PHẦN 12 — Không làm phiền ─────────────────────────────────────────
            "set_do_not_disturb" -> {
                val minutes = call.args.optDouble("minutes", 30.0).toLong()
                DoNotDisturbHelper.enableForMinutes(appContext, minutes)
                RoutedAction.SpeakText(
                    "Ok, mình im lặng trong $minutes phút nha, cần gì cứ gọi mình vẫn nghe được~"
                )
            }

            "cancel_do_not_disturb" -> {
                DoNotDisturbHelper.disable(appContext)
                RoutedAction.SpeakText("Mình huỷ Không làm phiền rồi, quay lại bình thường nè~")
            }

            // ── PHẦN 16 — Hội thoại song phương ──────────────────────────────────
            "start_bilingual_mode" -> {
                val targetLang = call.args.optString("target_language", "").trim()
                RoutedAction.StartBilingualMode(targetLang)
            }

            "stop_bilingual_mode" -> {
                RoutedAction.StopBilingualMode
            }

            // ── PHẦN 26 — "Hỏi bằng ảnh" qua function call thay vì quad-tap ──────
            "open_photo_ask" -> {
                RoutedAction.OpenPhotoAsk
            }

            else -> {
                Log.w(TAG, "Function chưa được xử lý: ${call.name}")
                RoutedAction.SpeakText("Mình biết bạn muốn làm gì rồi nhưng tính năng đó đang được phát triển~")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * PHẦN 18 (mở rộng video UI) — Đọc URI của 3 "video dài" đã lưu ở MainActivity (nếu có).
     * [slot] hợp lệ (1..3) VÀ đã cấu hình → dùng đúng slot đó; ngoài ra → chọn ngẫu nhiên 1 trong
     * các slot đã cấu hình. Không slot nào có video → null.
     */
    private fun pickLongVideoUri(slot: Int): String? {
        val prefs = appContext.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val configured = listOf(
            1 to prefs.getString(MainActivity.KEY_LONG_VIDEO_1_URI, null),
            2 to prefs.getString(MainActivity.KEY_LONG_VIDEO_2_URI, null),
            3 to prefs.getString(MainActivity.KEY_LONG_VIDEO_3_URI, null)
        ).mapNotNull { (idx, uri) -> if (uri != null) idx to uri else null }

        if (configured.isEmpty()) return null
        return configured.firstOrNull { it.first == slot }?.second ?: configured.random().second
    }

    /** PHẦN 11 — Đọc lịch hôm nay qua CalendarHelper, trả về câu thoại phù hợp. */
    private fun readTodaySchedule(): String {
        if (!CalendarHelper.hasPermission(appContext)) {
            return "Mình chưa được cấp quyền xem Lịch của bạn~ Vào app Chibi Wallpaper > Trợ lý chủ động để cấp quyền nhé!"
        }
        return CalendarHelper.formatScheduleForSpeech(CalendarHelper.getTodaySchedule(appContext))
    }

    /**
     * PHẦN 19 — Tạo 1 sự kiện thật vào Calendar app từ [title]/[date]/[time] do Gemini tự tính ra
     * (Gemini đã có "giờ hiện tại" trong context mỗi lượt gọi — xem [ContextSnapshotProvider] —
     * nên tự quy đổi được "3h chiều mai" thành date/time tuyệt đối trước khi gọi function này).
     * [date] dạng "yyyy-MM-dd", [time] dạng "HH:mm" (24h). Sai định dạng → báo lỗi cho chủ nhân
     * thay vì tạo lịch sai giờ.
     */
    private fun handleCreateCalendarEvent(title: String, date: String, time: String, durationMinutes: Long): String {
        if (title.isEmpty()) return "Bạn muốn đặt lịch gì vậy?"
        if (!CalendarHelper.hasWritePermission(appContext)) {
            return "Mình chưa được cấp quyền thêm Lịch~ Vào app Chibi Wallpaper > Trợ lý chủ động để cấp quyền nhé!"
        }

        val beginMillis = try {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .parse("$date $time")?.time
        } catch (e: Exception) {
            null
        } ?: return "Mình không hiểu ngày giờ bạn muốn đặt lịch, nói lại giúp mình rõ hơn nha~"

        val endMillis = beginMillis + durationMinutes * 60_000L
        val eventId = CalendarHelper.createEvent(appContext, title, beginMillis, endMillis)

        val timeLabel = SimpleDateFormat("HH:mm dd/MM", Locale.getDefault()).format(Date(beginMillis))
        return if (eventId != null) {
            "Đặt lịch \"$title\" lúc $timeLabel vào Lịch rồi nè~"
        } else {
            "Mình tạo lịch không được, thử lại sau nhé~"
        }
    }

    /** PHẦN 12 — Gọi điện cho người trong danh bạ theo tên. */
    private fun handleCallContact(name: String): String {
        if (name.isEmpty()) return "Bạn muốn gọi cho ai vậy?"
        if (!ContactsHelper.hasPermission(appContext)) {
            return "Mình chưa được cấp quyền xem Danh bạ~ Vào app Chibi Wallpaper cấp quyền giúp mình nhé!"
        }
        val contact = ContactsHelper.findByName(appContext, name)
            ?: return "Mình không tìm thấy ai tên \"$name\" trong danh bạ cả~"

        return try {
            val hasCallPermission = ContextCompat.checkSelfPermission(appContext, Manifest.permission.CALL_PHONE) ==
                PackageManager.PERMISSION_GRANTED
            // Có quyền CALL_PHONE → gọi thẳng. Không có → mở sẵn màn hình quay số (ACTION_DIAL
            // không cần quyền dangerous) để chủ nhân tự bấm gọi.
            val action = if (hasCallPermission) Intent.ACTION_CALL else Intent.ACTION_DIAL
            val intent = Intent(action, Uri.parse("tel:${contact.phoneNumber}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            if (hasCallPermission) "Đang gọi cho ${contact.displayName}~"
            else "Mình mở sẵn màn hình gọi cho ${contact.displayName} rồi, bấm gọi giúp mình nhé!"
        } catch (e: Exception) {
            Log.e(TAG, "Không gọi được: ${e.message}")
            "Mình gọi không được, thử lại sau nhé~"
        }
    }

    /** PHẦN 12 — Gửi SMS nhanh cho người trong danh bạ theo tên. */
    private fun handleSendSms(name: String, message: String): String {
        if (name.isEmpty()) return "Bạn muốn nhắn cho ai vậy?"
        if (message.isEmpty()) return "Bạn muốn nhắn nội dung gì vậy?"
        if (!ContactsHelper.hasPermission(appContext)) {
            return "Mình chưa được cấp quyền xem Danh bạ~ Vào app Chibi Wallpaper cấp quyền giúp mình nhé!"
        }
        val contact = ContactsHelper.findByName(appContext, name)
            ?: return "Mình không tìm thấy ai tên \"$name\" trong danh bạ cả~"

        val hasSmsPermission = ContextCompat.checkSelfPermission(appContext, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

        return try {
            if (hasSmsPermission) {
                val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    appContext.getSystemService(android.telephony.SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    android.telephony.SmsManager.getDefault()
                }
                smsManager.sendTextMessage(contact.phoneNumber, null, message, null, null)
                "Nhắn cho ${contact.displayName} rồi nè: \"$message\"~"
            } else {
                // Không có quyền SEND_SMS → mở sẵn app nhắn tin với nội dung điền sẵn.
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${contact.phoneNumber}")).apply {
                    putExtra("sms_body", message)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(intent)
                "Mình mở sẵn tin nhắn cho ${contact.displayName} rồi, bấm gửi giúp mình nhé!"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Không gửi SMS được: ${e.message}")
            "Mình gửi tin không được, thử lại sau nhé~"
        }
    }

    /**
     * PHẦN 18 (reminder bền) — [id] phải TRÙNG với id đã lưu trong [RemindersHelper] (dùng làm
     * requestCode) để [cancelReminderAlarm] và [rescheduleAllPendingReminders] sau này khớp đúng
     * alarm này (PendingIntent chỉ khớp nhau qua component + requestCode, extras không tính).
     */
    private fun scheduleReminderAlarm(id: Long, message: String, triggerAtMillis: Long) {
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = reminderPendingIntent(id, message)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                // Không có quyền SCHEDULE_EXACT_ALARM → dùng inexact
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                Log.d(TAG, "Reminder #$id set (inexact) lúc ${Date(triggerAtMillis)}: $message")
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                Log.d(TAG, "Reminder #$id set (exact) lúc ${Date(triggerAtMillis)}: $message")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Không đặt được reminder #$id: ${e.message}")
        }
    }

    /** PHẦN 18 (reminder bền) — Huỷ alarm thật của 1 reminder (gọi kèm [RemindersHelper.remove]). */
    private fun cancelReminderAlarm(id: Long) {
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(reminderPendingIntent(id, ""))
    }

    private fun reminderPendingIntent(id: Long, message: String): PendingIntent {
        val intent = Intent(appContext, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_MESSAGE, message)
            putExtra(ReminderReceiver.EXTRA_ID, id)
        }
        // requestCode = id.toInt(): ổn với quy mô 1 người dùng cá nhân, và PHẢI ổn định để
        // cancel/reschedule sau này khớp đúng alarm.
        return PendingIntent.getBroadcast(
            appContext, id.toInt(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * PHẦN 18 (reminder bền) — Gọi từ [BootReceiver] sau khi máy khởi động lại: AlarmManager bị
     * OS xoá sạch mọi alarm cũ lúc reboot, nhưng dữ liệu reminder tự lưu ở [RemindersHelper] thì
     * còn nguyên → đặt lại alarm cho từng reminder chưa tới giờ. Reminder nào LỠ quá giờ trong lúc
     * máy tắt (vd tắt qua đêm) thì bắn CATCH-UP sau vài giây thay vì lặng lẽ bỏ qua.
     */
    fun rescheduleAllPendingReminders() {
        val now = System.currentTimeMillis()
        val pending = RemindersHelper.loadAll(appContext)
        pending.forEach { r ->
            val triggerAt = if (r.triggerAtMillis <= now) now + REBOOT_CATCHUP_DELAY_MS else r.triggerAtMillis
            scheduleReminderAlarm(r.id, r.message, triggerAt)
        }
        Log.d(TAG, "Đặt lại ${pending.size} reminder sau khi máy khởi động lại")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            REMINDER_CHANNEL_ID,
            "Nhắc nhở từ Milky",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Các nhắc nhở được đặt thông qua trợ lý AI Milky"
        }
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/** Các hành động đã được router phân tích, sẵn sàng để state machine xử lý. */
sealed class RoutedAction {
    /** Chuyển sang Full model và kích hoạt expression. */
    data class ShowExpression(val expressionName: String) : RoutedAction()

    /** Phát video clip. null nếu chưa có video dài nào được cấu hình ở MainActivity. */
    data class PlayVideo(val videoUri: String?) : RoutedAction()

    /** Chuyển về Slime roaming. */
    object StartRoaming : RoutedAction()

    /** Hiển thị text trong bong bóng chat (Chibi mode). */
    data class SpeakText(val text: String) : RoutedAction()

    /** PHẦN 16 — Bật chế độ hội thoại song phương. [targetLanguage] có thể rỗng (chưa xác định). */
    data class StartBilingualMode(val targetLanguage: String) : RoutedAction()

    /** PHẦN 16 — Tắt chế độ hội thoại song phương. */
    object StopBilingualMode : RoutedAction()

    /** PHẦN 26 — Mở PhotoAskActivity ("Hỏi bằng ảnh") — trước đây chỉ mở được qua quad-tap. */
    object OpenPhotoAsk : RoutedAction()
}

// ─────────────────────────────────────────────────────────────────────────────

/** BroadcastReceiver nhận alarm nhắc nhở và bắn notification. */
class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_MESSAGE = "reminder_message"
        const val EXTRA_ID = "reminder_id" // PHẦN 18 (reminder bền)
        private const val CHANNEL_ID = "chibi_reminder"
        private const val TAG = "ChibiReminder"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "Nhắc nhở từ Milky~"
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        Log.d(TAG, "Reminder fired (#$id): $message")

        // Đã bắn xong thì dọn khỏi danh sách "đang chờ", không thì get_reminders sẽ báo nhầm.
        if (id >= 0) RemindersHelper.remove(context, id)

        try {
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Milky nhắc bạn~")
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            NotificationManagerCompat.from(context)
                .notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "Không có quyền POST_NOTIFICATIONS: ${e.message}")
        }
    }
}

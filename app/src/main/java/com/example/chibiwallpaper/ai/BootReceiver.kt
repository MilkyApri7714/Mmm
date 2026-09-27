package com.example.chibiwallpaper.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * PHẦN 11 — Đặt lại lịch chủ động (morning/weather/inactivity) sau khi thiết bị khởi động lại.
 * PHẦN 18 — Đặt lại luôn các reminder (set_reminder) đang chờ mà chưa tới giờ — trước PHẦN 18,
 * reminder bị MẤT TRẮNG mỗi khi khởi động lại máy vì AlarmManager bị OS xoá sạch và app không
 * lưu lại thông tin gì để khôi phục. Xem [ActionRouter.rescheduleAllPendingReminders].
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ProactiveManager.scheduleAll(context)
            ActionRouter(context.applicationContext).rescheduleAllPendingReminders()
        }
    }
}

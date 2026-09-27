package com.example.chibiwallpaper.ai

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PHẦN 11 — Context nhận thức: gom giờ hiện tại, % pin, tình trạng mạng và tóm tắt lịch hôm nay
 * thành một khối text, được [GeminiClient] nối vào cuối system prompt ở MỌI lượt gọi API — để
 * Milky "biết" ngữ cảnh thật thay vì chỉ dựa vào câu chat.
 */
object ContextSnapshotProvider {

    fun buildSnapshot(context: Context): String {
        val time = SimpleDateFormat("HH:mm, EEEE dd/MM/yyyy", Locale("vi")).format(Date())
        val battery = batteryPercent(context)
        val network = networkStatus(context)
        val schedule = CalendarHelper.summaryOneLine(context)
        val memoryProfile = MemoryProfileHelper.summaryForContext(context)

        return buildString {
            append("Giờ hiện tại: $time\n")
            append("Pin điện thoại: ${if (battery >= 0) "$battery%" else "không rõ"}\n")
            append("Kết nối mạng: $network\n")
            append("Lịch hôm nay: $schedule\n")
            append("Hồ sơ về chủ nhân (Milky tự ghi nhớ qua thời gian): $memoryProfile")
        }
    }

    private fun batteryPercent(context: Context): Int {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: fallbackBatteryPercent(context)
        } catch (e: Exception) {
            fallbackBatteryPercent(context)
        }
    }

    /** Fallback cho vài thiết bị/OEM không trả BATTERY_PROPERTY_CAPACITY đúng — đọc sticky intent. */
    private fun fallbackBatteryPercent(context: Context): Int {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        } catch (e: Exception) {
            -1
        }
    }

    private fun networkStatus(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return "không có mạng"
            val caps = cm.getNetworkCapabilities(network) ?: return "không có mạng"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "dữ liệu di động"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "đã kết nối"
            }
        } catch (e: Exception) {
            "không rõ"
        }
    }
}

package com.example.chibiwallpaper.floating

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.Process

/**
 * PHẦN 16 — Tự ẩn nhân vật nổi khi ở Home/màn hình khoá, tự hiện lại khi vào [MainActivity] hoặc
 * bất kỳ app nào khác.
 *
 * Không có API "onForegroundAppChanged" native mà không cần AccessibilityService/root, nên dùng
 * cách đơn giản: poll [UsageStatsManager.queryEvents] mỗi [POLL_INTERVAL_MS], lấy sự kiện
 * `MOVE_TO_FOREGROUND` cuối cùng trong cửa sổ [QUERY_WINDOW_MS] gần nhất để suy ra app đang mở.
 *
 * Giới hạn (chấp nhận được — xem plan Phần 16): polling không phân biệt được recents/1 số overlay
 * hệ thống khác với "app khác" — mọi trường hợp không phải launcher và không khoá máy đều coi là
 * "app khác" nên hiện nhân vật.
 *
 * [onVisibilityShouldChange] chỉ được gọi khi giá trị THỰC SỰ đổi (không gọi lặp lại mỗi lần poll
 * nếu trạng thái không đổi) — luôn từ main thread (Handler dùng Looper.getMainLooper()).
 */
class ForegroundAppWatcher(
    private val context: Context,
    private val onVisibilityShouldChange: (shouldShow: Boolean) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val keyguardManager =
        context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

    private val launcherPackageName: String? by lazy {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager
            .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    }

    @Volatile private var lastShouldShow: Boolean? = null
    private var running = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            poll()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(pollRunnable)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(pollRunnable)
        lastShouldShow = null
    }

    private fun poll() {
        val locked = try { keyguardManager.isKeyguardLocked } catch (e: Exception) { false }
        val foregroundPackage = currentForegroundPackage()

        // Không xác định được app đang mở (query rỗng/lỗi) và máy không khoá → giữ nguyên trạng
        // thái trước đó thay vì đoán bừa, tránh nhấp nháy ẩn/hiện liên tục.
        if (foregroundPackage == null && !locked) return

        val shouldShow = !locked && foregroundPackage != null && foregroundPackage != launcherPackageName
        if (shouldShow != lastShouldShow) {
            lastShouldShow = shouldShow
            onVisibilityShouldChange(shouldShow)
        }
    }

    private fun currentForegroundPackage(): String? {
        val now = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(now - QUERY_WINDOW_MS, now)
        var lastPackage: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastPackage = event.packageName
            }
        }
        return lastPackage
    }

    companion object {
        private const val POLL_INTERVAL_MS = 600L
        private const val QUERY_WINDOW_MS = 2_000L

        /** MainActivity dùng để chặn bật switch cho tới khi có đủ quyền (xem FloatingPetSection). */
        fun hasUsageAccess(context: Context): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            @Suppress("DEPRECATION")
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}

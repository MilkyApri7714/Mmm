package com.example.chibiwallpaper.floating

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * PHẦN 15.4 — "Bảng clipboard" cho floating pet, hiện khi reply/text quá dài
 * (xem [com.example.chibiwallpaper.render.ChatBubbleOverlay.isLongText], ngưỡng 60 ký tự —
 * dùng LẠI đúng ngưỡng của live wallpaper, không định nghĩa lại).
 *
 * Khác với [FloatingPetRenderer] (vẽ bubble bằng GL texture, đè lên đúng cửa sổ pet 130dp),
 * bảng này dùng **View THẬT** (TextView trong FrameLayout) thêm thẳng vào [WindowManager], hiện
 * giữa màn hình — đơn giản hơn nhiều so với việc port [com.example.chibiwallpaper.render.
 * ChatBubbleOverlay] (Canvas → GL texture) sang overlay nổi, vì ở đây không cần đồng bộ animation
 * với model bên dưới như live wallpaper.
 *
 * Chỉ được gọi từ main thread (giống mọi thao tác [WindowManager] khác trong
 * [FloatingPetService]).
 */
class TextBoardOverlay(
    private val context: Context,
    private val windowManager: WindowManager
) {

    private var boardView: FrameLayout? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null

    val isShowing: Boolean get() = boardView != null

    /**
     * Hiện bảng với [text]. Nếu đang hiện bảng khác (chưa kịp đóng) → đóng bảng cũ trước
     * (KHÔNG gọi [onClosed] của lần trước — chỉ lần đóng THẬT SỰ, do user bấm X hoặc hết giờ
     * auto-dismiss [AUTO_DISMISS_MS], mới gọi [onClosed]).
     */
    fun show(text: String, onClosed: () -> Unit) {
        dismissInternal()

        val density = context.resources.displayMetrics.density
        val maxWidthPx = (300 * density).toInt()

        val textView = TextView(context).apply {
            this.text = text
            setTextColor(Color.parseColor("#2B2540"))
            textSize = 15f
            setLineSpacing(4f, 1f)
        }

        val closeButton = TextView(context).apply {
            this.text = "\u2715" // ✕
            setTextColor(Color.parseColor("#6B5B95"))
            textSize = 16f
            val pad = (10 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val boardBackground = GradientDrawable().apply {
            setColor(Color.parseColor("#F5F0FF"))
            cornerRadius = 24f * density
            setStroke((2.5f * density).toInt().coerceAtLeast(1), Color.parseColor("#B9A6E8"))
        }

        val container = FrameLayout(context).apply {
            background = boardBackground
            val pad = (20 * density).toInt()
            setPadding(pad, (pad * 1.6f).toInt(), pad, pad)
            addView(
                textView,
                FrameLayout.LayoutParams(maxWidthPx, FrameLayout.LayoutParams.WRAP_CONTENT)
            )
            addView(
                closeButton,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.TOP or Gravity.END }
            )
        }

        closeButton.setOnClickListener {
            dismissInternal()
            onClosed()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        try {
            windowManager.addView(container, params)
            boardView = container
        } catch (e: Exception) {
            Log.e(TAG, "addView board lỗi: ${e.message}")
            return
        }

        val runnable = Runnable {
            dismissInternal()
            onClosed()
        }
        dismissRunnable = runnable
        mainHandler.postDelayed(runnable, AUTO_DISMISS_MS)
    }

    /** Đóng bảng chủ động từ code (ví dụ service bị huỷ) — KHÔNG gọi [onClosed] đã truyền ở [show]. */
    fun dismiss() = dismissInternal()

    private fun dismissInternal() {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        dismissRunnable = null
        boardView?.let { v ->
            try { windowManager.removeView(v) } catch (e: Exception) {
                Log.w(TAG, "removeView board: ${e.message}")
            }
        }
        boardView = null
    }

    companion object {
        private const val TAG = "TextBoardOverlay"

        /** Giống ChibiWallpaperService.BOARD_AUTO_DISMISS_MS — thời gian đọc bảng dài hơn bubble thường. */
        const val AUTO_DISMISS_MS = 45_000L
    }
}

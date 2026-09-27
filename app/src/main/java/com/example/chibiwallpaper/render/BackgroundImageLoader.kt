package com.example.chibiwallpaper.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log

/**
 * PHẦN 18 — Decode ảnh nền từ URI đã chọn (SAF, quyền đọc bền qua `takePersistableUriPermission`
 * ở MainActivity) + center-crop về đúng tỉ lệ màn hình thật ([targetW]x[targetH]), tránh OOM ảnh
 * gốc lớn bằng `inSampleSize` tính theo kích thước màn hình.
 *
 * Dùng chung cho cả 2 nơi gọi:
 *  - [MultiModelScene.reloadBackground] — load lần đầu khi wallpaper khởi động (onContextCreated)
 *    VÀ hot-reload ngay khi user vừa chọn ảnh xong (qua GLRenderer.runOnGlThread, xem
 *    ChibiWallpaperService).
 *
 * Trả về bitmap ĐÃ crop đúng tỉ lệ targetW:targetH (kích thước thật có thể nhỏ hơn do
 * inSampleSize, nhưng tỉ lệ khung luôn khớp) — [BackgroundImageLayer] chỉ việc upload thẳng lên
 * texture rồi vẽ full quad (dùng lại drawTexture() có sẵn), không cần crop/scale gì thêm.
 */
object BackgroundImageLoader {
    private const val TAG = "ChibiBgImage"

    fun decodeCenterCropped(context: Context, uri: Uri, targetW: Int, targetH: Int): Bitmap? {
        if (targetW <= 0 || targetH <= 0) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val srcW = bounds.outWidth
            val srcH = bounds.outHeight
            if (srcW <= 0 || srcH <= 0) return null

            val opts = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(srcW, srcH, targetW, targetH)
            }
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            centerCrop(decoded, targetW, targetH)
        } catch (e: Exception) {
            Log.e(TAG, "decodeCenterCropped lỗi: ${e.message}")
            null
        }
    }

    /** Chỉ downsample tới mức vẫn còn ĐỦ LỚN hơn kích thước màn hình thật (không giảm quá đó). */
    private fun calculateInSampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        var inSampleSize = 1
        while (srcW / (inSampleSize * 2) >= reqW && srcH / (inSampleSize * 2) >= reqH) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    /** Crop [src] về đúng tỉ lệ targetW:targetH (center-crop), giữ nguyên độ phân giải của src. */
    private fun centerCrop(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val targetRatio = targetW.toFloat() / targetH.toFloat()
        val srcRatio = src.width.toFloat() / src.height.toFloat()

        val cropW: Int
        val cropH: Int
        if (srcRatio > targetRatio) {
            // Ảnh gốc "rộng" hơn tỉ lệ đích → cắt bớt 2 bên trái/phải.
            cropH = src.height
            cropW = (cropH * targetRatio).toInt().coerceIn(1, src.width)
        } else {
            // Ảnh gốc "cao" hơn tỉ lệ đích → cắt bớt trên/dưới.
            cropW = src.width
            cropH = (cropW / targetRatio).toInt().coerceIn(1, src.height)
        }
        val x = (src.width - cropW) / 2
        val y = (src.height - cropH) / 2
        val cropped = Bitmap.createBitmap(src, x, y, cropW, cropH)
        if (cropped !== src) src.recycle()
        return cropped
    }
}

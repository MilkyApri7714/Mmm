package com.example.chibiwallpaper.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.sin

/**
 * PHẦN 8 — "Z z z" hiện phía trên đầu Slime khi đang ngủ (xem
 * [com.example.chibiwallpaper.character.SlimeController.isSleeping]), báo hiệu đang ở chế độ
 * tiết kiệm pin (khung hình/giây bị hạ — xem [GLScene.wantsLowFrameRate] / [GLRenderer]).
 *
 * Chỉ build 1 Bitmap/texture DUY NHẤT lúc [ensureInitialized] — nội dung không đổi nên không cần
 * vẽ lại Canvas mỗi frame (tốn CPU). Animation "trôi lên + mờ dần, lặp lại" chỉ đổi tham số
 * vị trí/alpha truyền vào [OverlayTextureRenderer.drawTexture] mỗi lần [draw], gần như miễn phí.
 *
 * update()/draw() đều chạy trên GL thread ("ChibiGL"), giống [ChatBubbleOverlay].
 */
class SleepIndicatorOverlay {

    private var textureId = 0
    private var texWidthPx = 0f
    private var texHeightPx = 0f
    private var loopTime = 0f

    fun ensureInitialized(renderer: OverlayTextureRenderer) {
        if (textureId != 0) return
        val (bmp, w, h) = buildBitmap()
        textureId = renderer.createTextureFromBitmap(bmp)
        texWidthPx = w
        texHeightPx = h
    }

    /** Context GL bị huỷ — texture tự mất theo context, chỉ cần reset handle để build lại lần sau. */
    fun onContextDestroyed() {
        textureId = 0
    }

    fun update(dt: Float) {
        loopTime += dt
        if (loopTime > LOOP_DURATION) loopTime -= LOOP_DURATION
    }

    /**
     * [anchorXPx]/[anchorYPx]: vị trí đầu Slime hiện tại trên màn hình (dùng lại
     * [MultiModelScene.slimeAnchorScreenPx], giống chỗ neo bảng clipboard ở Phần 5).
     */
    fun draw(r: OverlayTextureRenderer, screenW: Int, screenH: Int, anchorXPx: Float, anchorYPx: Float) {
        if (textureId == 0) return
        val t = (loopTime / LOOP_DURATION).coerceIn(0f, 1f)
        val rise = t * RISE_PX
        val alpha = when {
            t < 0.15f -> t / 0.15f
            t > 0.75f -> (1f - t) / 0.25f
            else -> 1f
        }.coerceIn(0f, 1f)
        val wobble = sin(t * Math.PI.toFloat() * 3f) * 6f
        r.drawTexture(
            textureId, screenW, screenH,
            centerXPx = anchorXPx + wobble,
            centerYPx = anchorYPx - BASE_OFFSET_PX - rise,
            widthPx = texWidthPx,
            heightPx = texHeightPx,
            alpha = alpha
        )
    }

    private fun buildBitmap(): Triple<Bitmap, Float, Float> {
        val text = "Z z z"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F5F0FF")
            textSize = 44f
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            setShadowLayer(6f, 0f, 2f, Color.parseColor("#661A1430"))
        }
        val bmpW = 150
        val bmpH = 64
        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val fm = textPaint.fontMetrics
        val baseline = bmpH / 2f - (fm.ascent + fm.descent) / 2f
        c.drawText(text, bmpW / 2f - textPaint.measureText(text) / 2f, baseline, textPaint)
        return Triple(bmp, bmpW.toFloat(), bmpH.toFloat())
    }

    companion object {
        private const val LOOP_DURATION = 2.4f // giây / vòng lặp trôi lên + mờ dần
        private const val RISE_PX = 60f
        private const val BASE_OFFSET_PX = 70f // khoảng cách gốc phía trên đầu Slime
    }
}

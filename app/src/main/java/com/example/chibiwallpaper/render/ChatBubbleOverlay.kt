package com.example.chibiwallpaper.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.min
import kotlin.math.sin

/**
 * PHẦN 5 — Quản lý hiển thị "bong bóng chat" (Chibi, text ngắn) và "bảng clipboard"
 * (Slime, text dài) đè lên scene, vẽ qua [OverlayTextureRenderer].
 *
 * Quy tắc: text ngắn hơn/bằng [LONG_TEXT_THRESHOLD] ký tự → bong bóng chat thường, đứng cạnh
 * đầu Chibi. Text dài hơn → MultiModelScene chuyển hẳn nhân vật về Slime (xem [isLongText]) và
 * bảng chạy qua 3 pha animation:
 *
 *   ICON   (0.3s)  — bảng nhỏ (icon clipboard) hiện ngay trên đầu Slime
 *   FLYING (0.45s) — phóng to dần + bay theo 1 cung nhẹ lên giữa màn hình ("quăng lên")
 *   SHOWN          — đứng yên giữa màn hình, hiện full nội dung, có nút X để đóng
 *
 * Chỉ vẽ lại Bitmap (Canvas, tốn CPU) khi nội dung text thực sự thay đổi — texture GL được cache
 * lại giữa các frame, đúng tinh thần "tối ưu pin" của giai đoạn 8.
 *
 * setText()/update()/draw()/onTouch-hitTest đều được gọi từ GL thread ("ChibiGL") — vì
 * createTextureFromBitmap() bên trong đụng GLES20.*, KHÔNG được gọi từ thread khác.
 */
class ChatBubbleOverlay {

    enum class Mode { NONE, BUBBLE, BOARD }
    private enum class BoardPhase { ICON, FLYING, SHOWN }

    @Volatile var mode: Mode = Mode.NONE
        private set
    @Volatile private var boardPhase = BoardPhase.ICON

    // ── Bubble (Chibi, text ngắn) ────────────────────────────────────────────
    private var bubbleTextureId = 0
    private var bubbleWidthPx = 0f
    private var bubbleHeightPx = 0f
    private var lastBubbleText: String? = null

    // ── Board (Slime, text dài) ──────────────────────────────────────────────
    private var iconTextureId = 0
    private var boardTextureId = 0
    private var boardWidthPx = 0f
    private var boardHeightPx = 0f
    private var lastBoardText: String? = null

    private var phaseElapsed = 0f
    private var startXPx = 0f
    private var startYPx = 0f
    private var chibiHeadX = 0f
    private var chibiHeadY = 0f

    // Vùng nút X trên board, tính theo tỉ lệ (0..1) so với board width/height — dùng lại được
    // dù board được vẽ với kích thước khác lúc build bitmap.
    private val closeRectFraction = RectF(0.87f, 0.03f, 0.97f, 0.15f)

    private var renderer: OverlayTextureRenderer? = null

    fun attach(renderer: OverlayTextureRenderer) {
        this.renderer = renderer
    }

    fun release() {
        renderer?.let {
            it.deleteTexture(bubbleTextureId)
            it.deleteTexture(iconTextureId)
            it.deleteTexture(boardTextureId)
        }
        bubbleTextureId = 0; iconTextureId = 0; boardTextureId = 0
        lastBubbleText = null; lastBoardText = null
        mode = Mode.NONE
    }

    /**
     * Cập nhật nội dung cần hiển thị, gọi mỗi frame từ onDrawFrame (rẻ nhờ cache theo text).
     * [anchorXPx]/[anchorYPx]: vị trí đầu Slime hiện tại trên màn hình — điểm xuất phát animation
     * bảng. [headXPx]/[headYPx]: vị trí đầu Chibi — nơi neo bong bóng chat thường.
     * [forceBoard]: PHẦN 16 — ép luôn dùng BOARD (neo theo Slime) bất kể độ dài text. Dùng cho
     * TRANSLATING, vì lúc đó Chibi không hiện (model luôn là Slime) nên neo BUBBLE theo đầu Chibi
     * (toạ độ cũ/không cập nhật) sẽ sai vị trí.
     */
    fun setText(text: String, anchorXPx: Float, anchorYPx: Float, headXPx: Float, headYPx: Float, forceBoard: Boolean = false) {
        val r = renderer ?: return
        chibiHeadX = headXPx
        chibiHeadY = headYPx

        if (text.isEmpty()) {
            mode = Mode.NONE
            return
        }

        if (isLongText(text) || forceBoard) {
            if (lastBoardText != text) {
                r.deleteTexture(boardTextureId)
                if (iconTextureId == 0) iconTextureId = r.createTextureFromBitmap(buildIconBitmap())
                val (bmp, w, h) = buildBoardBitmap(text)
                boardTextureId = r.createTextureFromBitmap(bmp)
                boardWidthPx = w; boardHeightPx = h
                lastBoardText = text

                // Text mới → luôn phát lại animation từ đầu, xuất phát từ vị trí Slime hiện tại.
                boardPhase = BoardPhase.ICON
                phaseElapsed = 0f
                startXPx = anchorXPx
                startYPx = anchorYPx
            }
            mode = Mode.BOARD
        } else {
            if (lastBubbleText != text) {
                r.deleteTexture(bubbleTextureId)
                val (bmp, w, h) = buildBubbleBitmap(text)
                bubbleTextureId = r.createTextureFromBitmap(bmp)
                bubbleWidthPx = w; bubbleHeightPx = h
                lastBubbleText = text
            }
            mode = Mode.BUBBLE
        }
    }

    fun clear() {
        mode = Mode.NONE
        lastBubbleText = null
        lastBoardText = null
    }

    fun update(dt: Float) {
        if (mode != Mode.BOARD) return
        phaseElapsed += dt
        when (boardPhase) {
            BoardPhase.ICON -> if (phaseElapsed >= ICON_DURATION) { boardPhase = BoardPhase.FLYING; phaseElapsed = 0f }
            BoardPhase.FLYING -> if (phaseElapsed >= FLY_DURATION) { boardPhase = BoardPhase.SHOWN; phaseElapsed = 0f }
            BoardPhase.SHOWN -> { /* đứng yên, chờ user bấm X hoặc timeout ở service */ }
        }
    }

    fun draw(r: OverlayTextureRenderer, screenW: Int, screenH: Int) {
        when (mode) {
            Mode.BUBBLE -> {
                if (bubbleTextureId == 0) return
                r.drawTexture(
                    bubbleTextureId, screenW, screenH,
                    centerXPx = chibiHeadX,
                    centerYPx = chibiHeadY - bubbleHeightPx / 2f - 24f,
                    widthPx = bubbleWidthPx, heightPx = bubbleHeightPx
                )
            }
            Mode.BOARD -> {
                val centerXPx = screenW / 2f
                val centerYPx = screenH / 2f
                when (boardPhase) {
                    BoardPhase.ICON -> {
                        if (iconTextureId == 0) return
                        val t = (phaseElapsed / ICON_DURATION).coerceIn(0f, 1f)
                        val pop = 1f + 0.15f * sin(t * Math.PI).toFloat() // nảy nhẹ lúc xuất hiện
                        r.drawTexture(
                            iconTextureId, screenW, screenH,
                            centerXPx = startXPx, centerYPx = startYPx - ICON_SIZE_PX / 2f,
                            widthPx = ICON_SIZE_PX * pop, heightPx = ICON_SIZE_PX * pop
                        )
                    }
                    BoardPhase.FLYING -> {
                        if (iconTextureId == 0 || boardTextureId == 0) return
                        val t = easeOutCubic((phaseElapsed / FLY_DURATION).coerceIn(0f, 1f))
                        val arc = sin(t * Math.PI).toFloat() * ARC_HEIGHT_PX // cung "quăng lên"
                        val x = lerp(startXPx, centerXPx, t)
                        val y = lerp(startYPx - ICON_SIZE_PX / 2f, centerYPx, t) - arc
                        val w = lerp(ICON_SIZE_PX, boardWidthPx, t)
                        val h = lerp(ICON_SIZE_PX, boardHeightPx, t)
                        val rot = lerp(0f, 55f, t) // xoay nhẹ khi bay, không xoay hẳn 1 vòng
                        if (t < 0.5f) {
                            r.drawTexture(iconTextureId, screenW, screenH, x, y, w, h, rotationDeg = rot)
                        } else {
                            val alpha = ((t - 0.5f) / 0.5f).coerceIn(0f, 1f)
                            r.drawTexture(
                                boardTextureId, screenW, screenH, x, y, w, h,
                                alpha = alpha, rotationDeg = rot * (1f - alpha)
                            )
                        }
                    }
                    BoardPhase.SHOWN -> {
                        if (boardTextureId == 0) return
                        r.drawTexture(boardTextureId, screenW, screenH, centerXPx, centerYPx, boardWidthPx, boardHeightPx)
                    }
                }
            }
            Mode.NONE -> { /* không vẽ gì */ }
        }
    }

    /**
     * Có trúng nút X trên board không (chỉ tính khi board đã đứng yên giữa màn hình — SHOWN).
     * Toạ độ [xPx]/[yPx] là toạ độ pixel gốc trên-trái, giống GLScene.onTouch.
     */
    fun hitTestClose(xPx: Float, yPx: Float, screenW: Int, screenH: Int): Boolean {
        if (mode != Mode.BOARD || boardPhase != BoardPhase.SHOWN) return false
        val left = screenW / 2f - boardWidthPx / 2f + closeRectFraction.left * boardWidthPx
        val right = screenW / 2f - boardWidthPx / 2f + closeRectFraction.right * boardWidthPx
        val top = screenH / 2f - boardHeightPx / 2f + closeRectFraction.top * boardHeightPx
        val bottom = screenH / 2f - boardHeightPx / 2f + closeRectFraction.bottom * boardHeightPx
        return xPx in left..right && yPx in top..bottom
    }

    /** true khi board đang chiếm màn hình (bất kỳ pha nào) — dùng để chặn tương tác với model bên dưới. */
    fun isBoardBlocking(): Boolean = mode == Mode.BOARD

    // ─────────────────────────────────────────────────────────────────────────
    // Vẽ Bitmap bằng Canvas — chỉ chạy khi text đổi (xem setText ở trên)
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildBubbleBitmap(text: String): Triple<Bitmap, Float, Float> {
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2B2540")
            textSize = 30f
        }
        val maxWidth = 520
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, textPaint, maxWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(4f, 1f)
            .build()

        val padding = 28
        val tailH = 18
        val bmpW = min(maxWidth, layout.width) + padding * 2
        val bmpH = layout.height + padding * 2 + tailH

        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F5F0FF") }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#B9A6E8"); style = Paint.Style.STROKE; strokeWidth = 3f
        }
        val bubbleRect = RectF(2f, 2f, bmpW - 2f, (bmpH - tailH).toFloat())
        canvas.drawRoundRect(bubbleRect, 26f, 26f, bg)
        canvas.drawRoundRect(bubbleRect, 26f, 26f, border)

        // Đuôi bong bóng trỏ xuống đầu Chibi.
        val tail = Path().apply {
            moveTo(bmpW / 2f - 14f, bubbleRect.bottom - 2f)
            lineTo(bmpW / 2f + 14f, bubbleRect.bottom - 2f)
            lineTo(bmpW / 2f, bubbleRect.bottom + tailH - 2f)
            close()
        }
        canvas.drawPath(tail, bg)

        canvas.save()
        canvas.translate(padding.toFloat(), padding.toFloat())
        layout.draw(canvas)
        canvas.restore()

        return Triple(bmp, bmpW.toFloat(), bmpH.toFloat())
    }

    /** Icon clipboard nhỏ hiện trên đầu Slime trước khi "quăng lên". */
    private fun buildIconBitmap(): Bitmap {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val board = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E8DFFB") }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8A6FD1"); style = Paint.Style.STROKE; strokeWidth = 4f
        }
        val boardRect = RectF(10f, 16f, size - 10f, size - 6f)
        c.drawRoundRect(boardRect, 10f, 10f, board)
        c.drawRoundRect(boardRect, 10f, 10f, border)

        // Kẹp trên cùng kiểu clipboard.
        val clip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8A6FD1") }
        c.drawRoundRect(RectF(size / 2f - 16f, 2f, size / 2f + 16f, 20f), 6f, 6f, clip)

        // Vài dòng "chữ" giả trên bảng.
        val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8A6FD1"); strokeWidth = 4f }
        for (i in 0..2) {
            val y = 34f + i * 16f
            c.drawLine(20f, y, size - 20f, y, lineP)
        }
        return bmp
    }

    /** Bảng lớn giữa màn hình: thanh tiêu đề + nút X + nội dung wrap đầy đủ. */
    private fun buildBoardBitmap(text: String): Triple<Bitmap, Float, Float> {
        val targetWidth = 620
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2B2540")
            textSize = 32f
        }
        val padding = 36
        val topBarH = 64
        val contentWidth = targetWidth - padding * 2
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, textPaint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(6f, 1f)
            .build()

        val bmpW = targetWidth
        val bmpH = (topBarH + layout.height + padding * 2).coerceAtMost(MAX_BOARD_HEIGHT_PX)

        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FBF8FF") }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8A6FD1"); style = Paint.Style.STROKE; strokeWidth = 4f
        }
        val rect = RectF(3f, 3f, bmpW - 3f, bmpH - 3f)
        c.drawRoundRect(rect, 28f, 28f, bg)

        // Thanh tiêu đề (clip theo bo góc của cả bảng để không lòi ra ngoài).
        val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8A6FD1") }
        c.save()
        c.clipRect(3f, 3f, bmpW - 3f, topBarH.toFloat())
        c.drawRoundRect(rect, 28f, 28f, bar)
        c.restore()

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 30f; isFakeBoldText = true
        }
        c.drawText("📋 Milky nhắn dài~", 24f, topBarH / 2f + 10f, titlePaint)

        // Nút X — vùng hit-test phải khớp closeRectFraction ở trên.
        val closeLeft = closeRectFraction.left * bmpW
        val closeTop = closeRectFraction.top * bmpH
        val closeRight = closeRectFraction.right * bmpW
        val closeBottom = closeRectFraction.bottom * bmpH
        val closeCenterX = (closeLeft + closeRight) / 2f
        val closeCenterY = (closeTop + closeBottom) / 2f
        val closeRadius = min(closeRight - closeLeft, closeBottom - closeTop) / 2f

        val closeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#5D4A9C") }
        c.drawCircle(closeCenterX, closeCenterY, closeRadius, closeBg)
        val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; strokeWidth = 5f; strokeCap = Paint.Cap.ROUND
        }
        val d = closeRadius * 0.5f
        c.drawLine(closeCenterX - d, closeCenterY - d, closeCenterX + d, closeCenterY + d, xPaint)
        c.drawLine(closeCenterX - d, closeCenterY + d, closeCenterX + d, closeCenterY - d, xPaint)

        c.drawRoundRect(rect, 28f, 28f, border)

        c.save()
        c.translate(padding.toFloat(), topBarH + padding / 2f)
        layout.draw(c)
        c.restore()

        return Triple(bmp, bmpW.toFloat(), bmpH.toFloat())
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun easeOutCubic(t: Float): Float { val f = t - 1f; return f * f * f + 1f }

    companion object {
        /** Ký tự — text dài hơn ngưỡng này thì chuyển sang bảng clipboard thay vì bong bóng chat. */
        const val LONG_TEXT_THRESHOLD = 60

        fun isLongText(text: String) = text.length > LONG_TEXT_THRESHOLD

        private const val ICON_SIZE_PX = 96f
        private const val ARC_HEIGHT_PX = 260f
        private const val ICON_DURATION = 0.3f
        private const val FLY_DURATION = 0.45f
        private const val MAX_BOARD_HEIGHT_PX = 900
    }
}

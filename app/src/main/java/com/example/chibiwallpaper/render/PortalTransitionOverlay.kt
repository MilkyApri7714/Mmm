package com.example.chibiwallpaper.render

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import kotlin.random.Random

/**
 * PHẦN 7.1 — Hiệu ứng "cổng không gian" khi đổi model GIỮA HAI MODEL NHỎ (Slime ⟷ Chibi).
 *
 * Ý tưởng (theo yêu cầu): một dải "cổng không gian" (nền tối + mép sáng zigzag) kéo từ đỉnh
 * màn hình xuống, nuốt chửng model đang hiện (DESCEND). Khi cổng phủ kín toàn màn hình, model
 * bên dưới được đổi (swap, hoàn toàn bị che nên user không thấy giật hình). Sau đó cổng tiếp tục
 * "rút" từ dưới lên (ASCEND) — vùng cổng co lại dần từ mép dưới màn hình lên đỉnh, để lộ dần model
 * mới ra từ phía dưới lên.
 *
 * Model FULL (và PLAYING_VIDEO) KHÔNG dùng overlay này — Full có hiệu ứng riêng (cổng to kéo tới
 * chân + cổng nhỏ random góc/giữa) ở [FullPortalOverlay] (Phần 7.2), vốn dùng lại đúng texture/
 * mesh dải cổng của lớp này qua [drawBandAt] thay vì tự dựng lại. Lớp này chỉ áp dụng cho các cặp
 * chuyển đổi SLIME↔CHIBI (MultiModelScene tự quyết định khi nào gọi [start]). PLAYING_VIDEO vẫn
 * chưa có hiệu ứng cổng riêng (đổi tức thì như trước).
 *
 * Chỉ được gọi trên GL thread ("ChibiGL"), giống toàn bộ [OverlayTextureRenderer].
 */
class PortalTransitionOverlay {

    enum class Phase { IDLE, DESCEND, ASCEND }

    var phase: Phase = Phase.IDLE
        private set

    val isActive: Boolean get() = phase != Phase.IDLE

    private var timer = 0f
    private var pendingTarget: ActiveModel? = null

    private var stripTextureId = 0
    private var initialized = false

    // ─────────────────────────────────────────────────────────────────────────
    // Vòng đời GL
    // ─────────────────────────────────────────────────────────────────────────

    /** Gọi 1 lần trong onContextCreated (sau khi [OverlayTextureRenderer.ensureInitialized]). */
    fun ensureInitialized(overlayRenderer: OverlayTextureRenderer) {
        if (initialized) return
        stripTextureId = overlayRenderer.createTextureFromBitmap(buildStripBitmap())
        initialized = true
    }

    /** Context GL bị huỷ — xoá texture, reset toàn bộ trạng thái animation đang dang dở. */
    fun onContextDestroyed(overlayRenderer: OverlayTextureRenderer) {
        if (stripTextureId != 0) overlayRenderer.deleteTexture(stripTextureId)
        stripTextureId = 0
        initialized = false
        phase = Phase.IDLE
        timer = 0f
        pendingTarget = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Điều khiển animation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Bắt đầu hiệu ứng cổng chuyển sang [target]. Nếu đang chạy dở dang tới đúng [target] này rồi
     * thì bỏ qua (không restart animation giữa chừng). Nếu đang chạy tới target KHÁC, animation cũ
     * vẫn được cho chạy hết tới lúc gọi [update] tiếp theo mới xử lý — [MultiModelScene] chịu trách
     * nhiệm không gọi start() chồng lấn (xem `reconcileModel()`).
     */
    fun start(target: ActiveModel) {
        if (phase != Phase.IDLE && pendingTarget == target) return
        pendingTarget = target
        phase = Phase.DESCEND
        timer = 0f
    }

    /**
     * Cập nhật animation theo [dt] (giây). Gọi [onSwap] đúng 1 lần — đúng thời điểm cổng phủ kín
     * màn hình — để [MultiModelScene] đổi model đang thực sự được vẽ (lúc này bị cổng che hoàn
     * toàn nên đổi mà user không thấy "giật hình").
     */
    fun update(dt: Float, onSwap: (ActiveModel) -> Unit) {
        when (phase) {
            Phase.IDLE -> return
            Phase.DESCEND -> {
                timer += dt
                if (timer >= DESCEND_SECONDS) {
                    timer = 0f
                    phase = Phase.ASCEND
                    pendingTarget?.let(onSwap)
                }
            }
            Phase.ASCEND -> {
                timer += dt
                if (timer >= ASCEND_SECONDS) {
                    timer = 0f
                    phase = Phase.IDLE
                    pendingTarget = null
                }
            }
        }
    }

    /** Vẽ cổng đè lên model (gọi SAU KHI model hiện tại đã được vẽ, TRƯỚC bong bóng chat). */
    fun draw(overlayRenderer: OverlayTextureRenderer, screenW: Int, screenH: Int) {
        if (!isActive) return

        // coverHeight = chiều cao vùng cổng đang che, tính từ ĐỈNH màn hình xuống.
        val coverHeight = when (phase) {
            Phase.DESCEND -> (timer / DESCEND_SECONDS).coerceIn(0f, 1f) * screenH
            Phase.ASCEND  -> (1f - (timer / ASCEND_SECONDS).coerceIn(0f, 1f)) * screenH
            Phase.IDLE    -> 0f
        }
        drawBandAt(coverHeight, overlayRenderer, screenW, screenH)
    }

    /**
     * PHẦN 7.2 — Tách riêng từ [draw] để [FullPortalOverlay] dùng lại ĐÚNG texture/mesh dải cổng
     * to này cho 2 pha DESCEND_BAND/ASCEND_BAND của chiều FULL ⟷ model nhỏ, thay vì phải dựng lại
     * 1 texture "dải cổng" y hệt từ đầu. [coverHeight] tính từ ĐỈNH màn hình xuống, đơn vị pixel.
     */
    fun drawBandAt(coverHeight: Float, overlayRenderer: OverlayTextureRenderer, screenW: Int, screenH: Int) {
        if (!initialized || screenH <= 0 || screenW <= 0 || coverHeight <= 1f) return

        // Quad neo từ y=0 xuống y=coverHeight — mép sáng zigzag của texture luôn nằm sát đáy quad
        // (xem buildStripBitmap: mép ở gần cuối bitmap), tức đúng ngay "đường cổng" đang di chuyển.
        overlayRenderer.drawTexture(
            textureId = stripTextureId,
            screenW = screenW,
            screenH = screenH,
            centerXPx = screenW / 2f,
            centerYPx = coverHeight / 2f,
            widthPx = screenW.toFloat(),
            heightPx = coverHeight
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Dựng texture cổng (1 lần duy nhất, cache lại — KHÔNG build lại mỗi frame)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Bitmap "dải cổng": nền gradient tím-đen đặc (giả không gian), rắc vài chấm sao, và 1 đường
     * viền zigzag phát sáng (glow) nằm gần đáy bitmap. Vì quad luôn stretch bitmap này theo đúng
     * [coverHeight] hiện tại, đáy bitmap luôn khớp với "đường biên cổng" đang tiến/lùi.
     */
    private fun buildStripBitmap(): Bitmap {
        val w = STRIP_W
        val h = STRIP_H
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Nền: gradient dọc đặc từ đen-tím rất tối (đỉnh) sang tím đậm hơn (đáy) — phủ TOÀN BỘ
        // chiều cao bitmap (không để hở vùng trong suốt) để khi stretch không lộ model bên dưới.
        val voidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(Color.parseColor("#05010A"), Color.parseColor("#20103D")),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), voidPaint)

        // Vài chấm sao nhỏ rải trong vùng "không gian" cho có chiều sâu.
        val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val starRnd = Random(42)
        repeat(32) {
            val sx = starRnd.nextFloat() * w
            val sy = starRnd.nextFloat() * h * 0.85f
            starPaint.alpha = 50 + starRnd.nextInt(140)
            canvas.drawCircle(sx, sy, 0.6f + starRnd.nextFloat() * 1.6f, starPaint)
        }

        // Đường mép cổng: zigzag ngang, đặt gần đáy bitmap (EDGE_RATIO) — đây chính là "đường biên"
        // luôn khớp với coverHeight sau khi stretch.
        val edgeY = h * EDGE_RATIO
        val amp = h * 0.045f
        val segments = 16
        val edgeRnd = Random(7)
        val glowPath = Path().apply {
            moveTo(0f, edgeY)
            for (i in 1..segments) {
                val x = w * i / segments.toFloat()
                val y = edgeY + (if (i % 2 == 0) -amp else amp) * (0.55f + edgeRnd.nextFloat() * 0.45f)
                lineTo(x, y)
            }
        }

        // Lớp glow mờ (blur) rồi lớp lõi sáng mảnh đè lên trên — tạo cảm giác "nứt không gian".
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = h * 0.05f
            color = Color.parseColor("#B388FF")
            maskFilter = BlurMaskFilter(h * 0.06f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawPath(glowPath, glowPaint)

        val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = h * 0.014f
            color = Color.parseColor("#F5EEFF")
        }
        canvas.drawPath(glowPath, corePaint)

        return bmp
    }

    companion object {
        // Kích thước bitmap gốc (độc lập với kích thước màn hình thật — quad sẽ stretch theo).
        private const val STRIP_W = 720
        private const val STRIP_H = 320

        // Mép cổng đặt ở 90% chiều cao bitmap (gần đáy) — phần 10% còn lại vẫn tô đặc (không hở)
        // để tránh lộ model khi biên độ zigzag đẩy điểm cao nhất lên trên.
        private const val EDGE_RATIO = 0.90f

        private const val DESCEND_SECONDS = 0.28f
        private const val ASCEND_SECONDS = 0.32f
    }
}

package com.example.chibiwallpaper.floating

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import com.example.chibiwallpaper.character.CubismModelWrapper
import com.example.chibiwallpaper.cubism.CubismBoot
import com.example.chibiwallpaper.render.ActiveModel
import com.example.chibiwallpaper.render.FullPortalOverlay
import com.example.chibiwallpaper.render.OverlayTextureRenderer
import com.example.chibiwallpaper.render.PortalTransitionOverlay
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * PHẦN 14 — Renderer cho floating overlay, tích hợp:
 *   - 3 model: slime / chibi / full (chọn qua [modelType])
 *   - Chat bubble vẽ bằng Canvas → OpenGL texture
 *   - State machine: IDLE → LISTENING → THINKING → REPLYING/EXPRESSING → IDLE
 *
 * PHẦN 15.3 — Chuyển model theo state giờ dùng CỔNG DỊCH CHUYỂN THẬT, y hệt cơ chế của
 * [com.example.chibiwallpaper.render.MultiModelScene] (Phần 7.1/7.2), thay vì đổi tức thời:
 *   - [renderMode] KHÔNG còn quyết định trực tiếp model đang vẽ — nó chỉ quyết định [desiredModel]
 *     ("model mà state hiện tại của floating pet muốn hiện"): BASE → model gốc theo [modelType];
 *     CHIBI_REPLY → CHIBI; FULL_EXPR → FULL.
 *   - Model THỰC SỰ đang vẽ là [displayModel], chỉ đổi bởi [reconcileModel] (mỗi frame) hoặc bởi
 *     callback `onSwap` của cổng ([PortalTransitionOverlay]/[FullPortalOverlay]) — không đổi tức
 *     thời như trước.
 *   - [PortalTransitionOverlay]/[FullPortalOverlay] tự vẽ theo đúng `screenW`/`screenH` truyền vào
 *     mỗi frame — ở overlay nổi này đó chính là kích thước thật của cửa sổ 130dp, nên gắn thẳng 2
 *     class gốc (dùng lại nguyên `ActiveModel` ở `render/ActiveModel.kt`, không phụ thuộc
 *     `MultiModelScene`) là cổng tự co vừa khít, không cần build lại texture/shader riêng.
 *
 * Tất cả GLES20.* và CubismModelWrapper chỉ được gọi từ GL thread.
 * State flag (isListening, isBusy) đọc từ main thread → @Volatile.
 */
class FloatingPetRenderer(
    private val appContext: Context,
    private val modelType: String = FloatingPetService.MODEL_SLIME
) : GLSurfaceView.Renderer {

    // ── State flags (đọc từ main thread) ────────────────────────────────────
    @Volatile var isListening: Boolean = false
        private set
    @Volatile var isBusy: Boolean = false
        private set

    // ── GL resources ─────────────────────────────────────────────────────────
    private var baseWrapper: CubismModelWrapper? = null   // model gốc
    private var chibiWrapper: CubismModelWrapper? = null  // chibi dùng khi nói
    private var fullWrapper: CubismModelWrapper? = null   // full dùng khi show_expression
    private var overlayRenderer: OverlayTextureRenderer? = null

    private var width = 1
    private var height = 1
    private var lastFrameNanos = 0L

    // ── Bubble text ──────────────────────────────────────────────────────────
    @Volatile private var pendingBubbleText: String? = null
    @Volatile private var clearBubble: Boolean = false

    private var bubbleTextureId = 0
    private var bubbleWidthPx = 0f
    private var bubbleHeightPx = 0f
    private var lastBuiltText: String? = null

    // ── Mode ─────────────────────────────────────────────────────────────────
    private enum class RenderMode { BASE, CHIBI_REPLY, FULL_EXPR }
    @Volatile private var renderMode = RenderMode.BASE

    // ── Expression (set từ main thread, apply trên GL thread) ────────────────
    @Volatile private var pendingExpression: String? = null

    // ── PHẦN 15.3 — Cổng dịch chuyển thật (nhỏ + Full), y hệt MultiModelScene ─
    private val portalTransition = PortalTransitionOverlay()
    private val fullPortal = FullPortalOverlay()

    /** Model gốc theo [modelType] — chính là model mà RenderMode.BASE muốn hiện. */
    private val baseActiveModel: ActiveModel = when (modelType) {
        FloatingPetService.MODEL_CHIBI -> ActiveModel.CHIBI
        FloatingPetService.MODEL_FULL  -> ActiveModel.FULL
        else                           -> ActiveModel.SLIME
    }

    /** Model THỰC SỰ đang được vẽ frame này — chỉ [reconcileModel] mới được đổi giá trị này. */
    @Volatile private var displayModel: ActiveModel = baseActiveModel
    /** Model mà [renderMode] hiện tại MUỐN hiện — set lại mỗi khi renderMode đổi. */
    @Volatile private var desiredModel: ActiveModel = baseActiveModel

    /**
     * true khi 1 trong 2 hiệu ứng cổng (nhỏ hoặc Full) đang chạy — tạm ẩn bubble/expression lúc
     * cổng chạy để không chồng chéo hiệu ứng (giống MultiModelScene.isTransitioning()).
     */
    val isTransitioning: Boolean get() = portalTransition.isActive || fullPortal.isActive

    /**
     * PHẦN 15.5 — true khi Chibi đang trong trạng thái "nói" (renderMode CHIBI_REPLY — bao gồm cả
     * lúc hiện "..." chờ Gemini lẫn lúc hiện text trả lời thật). FloatingPetService dùng cờ này để
     * quyết định single-tap là nudge (bình thường) hay skip/dừng bubble ngay (khi đang nói).
     */
    val isReplying: Boolean get() = renderMode == RenderMode.CHIBI_REPLY

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    fun setListeningState(listening: Boolean) {
        isListening = listening
        if (listening) {
            isBusy = true
            pendingBubbleText = "🎤 Milky đang nghe~"
        }
    }

    fun showThinking() {
        isBusy = true
        renderMode = RenderMode.CHIBI_REPLY
        updateDesiredModel()
        pendingBubbleText = "..."
    }

    fun showReply(text: String) {
        renderMode = RenderMode.CHIBI_REPLY
        updateDesiredModel()
        pendingBubbleText = text
    }

    fun showBubble(text: String) {
        pendingBubbleText = text
    }

    /**
     * Chuyển sang Full model + kích hoạt expression.
     * FloatingPetService gọi clearBubbleAndRevert() sau EXPRESSION_DURATION_MS để quay về.
     */
    fun showExpression(expressionName: String) {
        isBusy = true
        renderMode = RenderMode.FULL_EXPR
        updateDesiredModel()
        pendingExpression = expressionName
        pendingBubbleText = "✨"   // bubble ngắn, không che model
    }

    fun clearBubbleAndRevert() {
        clearBubble = true
        renderMode = RenderMode.BASE
        updateDesiredModel()
        isListening = false
        isBusy = false
    }

    fun onTap() {
        baseWrapper?.triggerRandomMotion()
        chibiWrapper?.triggerRandomMotion()
    }

    // PHẦN 15.6 — Hiệu ứng đang bật do vuốt lên ("AD" hoặc "Straw"), null nếu chưa bật gì.
    // Y hệt cơ chế trong MultiModelScene.onSwipeUp (Phần 13), port sang floating pet.
    @Volatile private var swipeUpEffectActive: String? = null

    /**
     * PHẦN 15.6 — Vuốt lên trên Slime: bật/tắt luân phiên 1 trong 2 hiệu ứng
     * (AD.exp3.json / STRAW.motion3.json), chọn ngẫu nhiên khi bật, hoạt động kể cả khi Slime
     * đang ngủ. Chỉ áp dụng khi model đang hiển thị thật sự là SLIME (không phải Chibi/Full) —
     * đúng ngữ nghĩa như MultiModelScene.
     */
    fun onSwipeUp() {
        if (displayModel != ActiveModel.SLIME) return
        val wrapper = wrapperFor(ActiveModel.SLIME) ?: return

        val active = swipeUpEffectActive
        if (active == null) {
            val choice = if (kotlin.random.Random.nextBoolean()) "AD" else "Straw"
            val turnedOn = if (choice == "AD") wrapper.toggleExpression("AD") else wrapper.toggleMotion("Straw")
            if (turnedOn) {
                swipeUpEffectActive = choice
                Log.d(TAG, "onSwipeUp: bật $choice")
            }
        } else {
            if (active == "AD") wrapper.toggleExpression("AD") else wrapper.toggleMotion("Straw")
            Log.d(TAG, "onSwipeUp: tắt $active")
            swipeUpEffectActive = null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GLSurfaceView.Renderer
    // ─────────────────────────────────────────────────────────────────────────

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        CubismBoot.initializeOnGlThread(appContext)

        // Base model
        val (baseDir, baseJson, baseScale) = modelAssets(modelType)
        baseWrapper = CubismModelWrapper(appContext).also { w ->
            if (!w.loadFromAssets(baseDir, baseJson))
                Log.e(TAG, "Load base model lỗi: $baseDir$baseJson")
            w.scaleFactor = baseScale
        }

        // Chibi — dùng khi nói (chỉ load nếu base không phải chibi)
        if (modelType != FloatingPetService.MODEL_CHIBI) {
            chibiWrapper = CubismModelWrapper(appContext).also { w ->
                if (!w.loadFromAssets("chibi/", "koharu.model3.json"))
                    Log.e(TAG, "Load chibi wrapper lỗi")
                w.scaleFactor = 0.45f
            }
        }

        // Full — dùng khi show_expression (chỉ load nếu base không phải full)
        if (modelType != FloatingPetService.MODEL_FULL) {
            fullWrapper = CubismModelWrapper(appContext).also { w ->
                if (!w.loadFromAssets("full/", "tingyun.model3.json"))
                    Log.e(TAG, "Load full wrapper lỗi")
                w.scaleFactor = 0.35f
            }
        }

        overlayRenderer = OverlayTextureRenderer().also { or ->
            or.ensureInitialized()
            portalTransition.ensureInitialized(or)
            fullPortal.ensureInitialized()
        }
        lastFrameNanos = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        GLES20.glViewport(0, 0, w, h)
        baseWrapper?.setRenderTargetSize(w, h)
        chibiWrapper?.setRenderTargetSize(w, h)
        fullWrapper?.setRenderTargetSize(w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
        lastFrameNanos = now

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // Apply pending expression trên GL thread — áp lên đúng wrapper đang đại diện cho FULL
        // (fullWrapper phụ, hoặc baseWrapper nếu modelType chính là FULL).
        val expr = pendingExpression
        if (expr != null) {
            pendingExpression = null
            wrapperFor(ActiveModel.FULL)?.setExpression(expr)
        }

        // Bubble text
        val newText = pendingBubbleText
        if (newText != null) {
            pendingBubbleText = null
            rebuildBubbleIfNeeded(newText)
        }
        if (clearBubble) {
            clearBubble = false
            deleteBubbleTexture()
            lastBuiltText = null
        }

        // PHẦN 15.3 — Cập nhật animation cổng (nhỏ và Full) trước, rồi mới đồng bộ model thật sự
        // cần vẽ. onSwap chạy ĐÚNG lúc cổng phủ kín cửa sổ 130dp → đổi displayModel mà user không
        // thấy giật hình. Chỉ 1 trong 2 overlay active tại 1 thời điểm (reconcileModel() đảm bảo).
        portalTransition.update(dt) { swappedTo -> displayModel = swappedTo }
        fullPortal.update(dt) { swappedTo -> displayModel = swappedTo }
        reconcileModel()

        // Vẽ model theo displayModel (KHÔNG phải renderMode nữa — có thể đang giữa chừng cổng).
        wrapperFor(displayModel)?.draw(width, height, dt)

        // PHẦN 15.3 — Cổng dịch chuyển (nhỏ và Full) vẽ đè lên model (dưới bong bóng chat).
        val or = overlayRenderer
        if (or != null) {
            portalTransition.draw(or, width, height)
            fullPortal.draw(portalTransition, or, width, height)
        }

        // Vẽ bubble — tạm ẩn lúc cổng đang chạy (isTransitioning) để không chồng chéo hiệu ứng.
        if (bubbleTextureId != 0 && !isTransitioning) {
            or?.drawTexture(
                textureId  = bubbleTextureId,
                screenW    = width, screenH = height,
                centerXPx  = width / 2f,
                centerYPx  = height * 0.15f,
                widthPx    = bubbleWidthPx,
                heightPx   = bubbleHeightPx
            )
        }
    }

    fun release() {
        baseWrapper?.release();  baseWrapper  = null
        chibiWrapper?.release(); chibiWrapper = null
        fullWrapper?.release();  fullWrapper  = null
        deleteBubbleTexture()
        overlayRenderer?.let { or -> portalTransition.onContextDestroyed(or) }
        fullPortal.onContextDestroyed()
        overlayRenderer = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers (GL thread)
    // ─────────────────────────────────────────────────────────────────────────

    /** [renderMode] → [desiredModel]. Gọi lại mỗi khi renderMode đổi (showThinking/showReply/
     *  showExpression/clearBubbleAndRevert). Model thật sự vẽ ra vẫn phải chờ [reconcileModel]. */
    private fun updateDesiredModel() {
        desiredModel = when (renderMode) {
            RenderMode.BASE        -> baseActiveModel
            RenderMode.CHIBI_REPLY -> ActiveModel.CHIBI
            RenderMode.FULL_EXPR   -> ActiveModel.FULL
        }
    }

    private fun isSmallModel(m: ActiveModel): Boolean = m == ActiveModel.SLIME || m == ActiveModel.CHIBI

    /**
     * Đối chiếu [desiredModel] với [displayModel] — y hệt logic của
     * `MultiModelScene.reconcileModel`:
     *  - Cổng đang chạy dở → không đụng vào.
     *  - Cả 2 bên đều là model nhỏ (Slime/Chibi) → cổng nhỏ [PortalTransitionOverlay].
     *  - 1 bên FULL, bên kia model nhỏ → cổng riêng [FullPortalOverlay], đúng chiều tương ứng.
     *  - Còn lại (không xảy ra ở floating pet vì không có VIDEO) → đổi ngay lập tức, phòng hờ.
     */
    private fun reconcileModel() {
        if (portalTransition.isActive || fullPortal.isActive) return
        val desired = desiredModel
        if (desired == displayModel) return
        when {
            isSmallModel(desired) && isSmallModel(displayModel) ->
                portalTransition.start(desired)
            desired == ActiveModel.FULL && isSmallModel(displayModel) ->
                fullPortal.startSmallToFull()
            isSmallModel(desired) && displayModel == ActiveModel.FULL ->
                fullPortal.startFullToSmall(desired)
            else ->
                displayModel = desired
        }
    }

    /**
     * [active] → wrapper GL thực sự cần vẽ, có tính tới [modelType]: model gốc của pet (baseWrapper)
     * có thể CHÍNH LÀ Chibi hoặc Full (không load wrapper phụ trùng lặp — xem [onSurfaceCreated]).
     */
    private fun wrapperFor(active: ActiveModel): CubismModelWrapper? = when (active) {
        ActiveModel.SLIME -> baseWrapper.takeIf { modelType == FloatingPetService.MODEL_SLIME }
        ActiveModel.CHIBI -> if (modelType == FloatingPetService.MODEL_CHIBI) baseWrapper else chibiWrapper
        ActiveModel.FULL  -> if (modelType == FloatingPetService.MODEL_FULL) baseWrapper else fullWrapper
        ActiveModel.VIDEO -> null // floating pet không có VIDEO
    }

    private fun rebuildBubbleIfNeeded(text: String) {
        if (text == lastBuiltText) return
        deleteBubbleTexture()
        val or = overlayRenderer ?: return
        val (bmp, w, h) = buildBubbleBitmap(text)
        bubbleTextureId = or.createTextureFromBitmap(bmp)
        bubbleWidthPx = w; bubbleHeightPx = h
        lastBuiltText = text
    }

    private fun deleteBubbleTexture() {
        if (bubbleTextureId != 0) {
            overlayRenderer?.deleteTexture(bubbleTextureId)
            bubbleTextureId = 0
        }
    }

    private fun buildBubbleBitmap(text: String): Triple<Bitmap, Float, Float> {
        val dp = appContext.resources.displayMetrics.density
        val textSizePx = 28f
        val maxWidthPx = (260 * dp).toInt().coerceAtLeast(200)

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2B2540")
            textSize = textSizePx
        }
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, textPaint, maxWidthPx)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(3f, 1f)
            .build()

        val pad = 20
        val tailH = 14
        val bmpW = (layout.width + pad * 2).coerceAtMost(maxWidthPx + pad * 2)
        val bmpH = layout.height + pad * 2 + tailH

        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val bgPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F5F0FF") }
        val bordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#B9A6E8"); style = Paint.Style.STROKE; strokeWidth = 2.5f
        }
        val bubbleRect = RectF(2f, 2f, bmpW - 2f, (bmpH - tailH).toFloat())
        canvas.drawRoundRect(bubbleRect, 22f, 22f, bgPaint)
        canvas.drawRoundRect(bubbleRect, 22f, 22f, bordPaint)

        val tailPath = android.graphics.Path().apply {
            moveTo(bmpW / 2f - 10f, bubbleRect.bottom - 1f)
            lineTo(bmpW / 2f + 10f, bubbleRect.bottom - 1f)
            lineTo(bmpW / 2f, bubbleRect.bottom + tailH - 1f)
            close()
        }
        canvas.drawPath(tailPath, bgPaint)

        canvas.save()
        canvas.translate(pad.toFloat(), pad.toFloat())
        layout.draw(canvas)
        canvas.restore()

        return Triple(bmp, bmpW.toFloat(), bmpH.toFloat())
    }

    companion object {
        private const val TAG = "FloatingPetRenderer"

        fun modelAssets(type: String): Triple<String, String, Float> = when (type) {
            FloatingPetService.MODEL_CHIBI -> Triple("chibi/", "koharu.model3.json",  0.45f)
            FloatingPetService.MODEL_FULL  -> Triple("full/",  "tingyun.model3.json", 0.35f)
            else                           -> Triple("slime/", "SLIME.model3.json",   0.5f)
        }
    }
}

package com.example.chibiwallpaper.render

import android.opengl.GLES20
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * PHẦN 7.2 — Hiệu ứng cổng RIÊNG cho các lần đổi model có dính FULL (khác Phần 7.1, chỉ lo
 * Slime ⟷ Chibi). Theo README_PHAN_7_1.md mục "Còn thiếu / để dành Phần 7.2", và theo lựa chọn
 * của user cho vị trí cổng nhỏ: RANDOM mỗi lần trong 3 vị trí (trái / giữa / phải), luôn sát đáy
 * màn hình.
 *
 * FULL → model nhỏ (Slime/Chibi):
 *   1. [Phase.DESCEND_BAND] — dải cổng TO (dùng lại đúng texture/mesh của [PortalTransitionOverlay]
 *      qua [PortalTransitionOverlay.drawBandAt]) kéo từ đỉnh màn hình xuống "tới chân" — luôn đi
 *      hết 100% chiều cao (khác Phần 7.1 vốn cũng đi hết, nhưng ở đây tách state riêng vì bước kế
 *      tiếp không phải ASCEND mà là mở lỗ tròn) — nuốt trọn Full. Khi phủ kín → swap displayModel
 *      sang model nhỏ đích (bị che hoàn toàn nên user không thấy giật hình).
 *   2. [Phase.EMERGE_HOLE] — chốt 1 vị trí RANDOM (trái/giữa/phải, sát đáy) rồi mở 1 "cổng nhỏ":
 *      lỗ tròn viền phát sáng, trong suốt, bán kính lớn dần từ 0 tới phủ hết đường chéo màn hình —
 *      model nhỏ lộ ra dần đúng qua cái lỗ đó, kiểu "chui ra từ một điểm".
 *
 * model nhỏ → FULL (chiều ngược lại):
 *   1. [Phase.CONSUME_HOLE] — chốt 1 vị trí RANDOM khác, lỗ tròn bắt đầu ở bán kính phủ hết màn
 *      hình (tức màn hình bình thường, chưa thấy gì) rồi CO LẠI dần về 0 — cảm giác "cổng nhỏ hút
 *      nhỏ dần" nuốt model nhỏ hiện tại, phần ngoài lỗ luôn bị che kín đặc. Khi co về 0 (phủ kín
 *      100%) → swap displayModel sang FULL (bị che hoàn toàn).
 *   2. [Phase.ASCEND_BAND] — dải cổng to (lại dùng [PortalTransitionOverlay.drawBandAt]) rút dần
 *      từ đáy màn hình lên đỉnh, lộ dần Full ra từ dưới lên — giống hệt cơ chế ASCEND của Phần 7.1.
 *
 * Lỗ tròn KHÔNG vẽ được bằng [OverlayTextureRenderer] (chỉ vẽ quad chữ nhật lấy nguyên texture) nên
 * lớp này tự dựng 1 GL program riêng: fragment shader tô đặc toàn màn hình TRỪ 1 lỗ tròn ở tâm
 * [holeCenterX]/[holeCenterY] bán kính [uRadius], mép có dải glow tím giống màu cổng của Phần 7.1
 * (#B388FF) cho đồng bộ hình ảnh.
 *
 * Chỉ được gọi trên GL thread ("ChibiGL"), giống [PortalTransitionOverlay]/[OverlayTextureRenderer].
 */
class FullPortalOverlay {

    enum class Phase { IDLE, DESCEND_BAND, EMERGE_HOLE, CONSUME_HOLE, ASCEND_BAND }

    var phase: Phase = Phase.IDLE
        private set

    val isActive: Boolean get() = phase != Phase.IDLE

    private var timer = 0f
    private var pendingTarget: ActiveModel? = null

    // Vị trí lỗ tròn hiện tại, tỉ lệ 0..1 theo width/height — chốt lại (random) mỗi lần bắt đầu
    // 1 trong 2 phase liên quan tới lỗ (EMERGE_HOLE / CONSUME_HOLE), không đổi giữa chừng.
    private var holeCenterX = 0.5f
    private var holeCenterY = HOLE_Y_RATIO

    private var program = 0
    private var aPositionLoc = 0
    private var uResolutionLoc = 0
    private var uCenterLoc = 0
    private var uRadiusLoc = 0
    private var uEdgeLoc = 0
    private var initialized = false

    private val vertexBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(4 * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            // Quad full-screen ở NDC luôn (-1..1) — không cần theo pixel như OverlayTextureRenderer,
            // vì mask hình tròn được tính thẳng trong fragment shader theo uResolution/uCenter.
            put(floatArrayOf(-1f, -1f, -1f, 1f, 1f, -1f, 1f, 1f))
            position(0)
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Vòng đời GL
    // ─────────────────────────────────────────────────────────────────────────

    /** Gọi 1 lần trong onContextCreated, độc lập với [OverlayTextureRenderer] (program riêng). */
    fun ensureInitialized() {
        if (initialized) return
        program = buildProgram()
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        uResolutionLoc = GLES20.glGetUniformLocation(program, "uResolution")
        uCenterLoc = GLES20.glGetUniformLocation(program, "uCenter")
        uRadiusLoc = GLES20.glGetUniformLocation(program, "uRadius")
        uEdgeLoc = GLES20.glGetUniformLocation(program, "uEdge")
        initialized = true
    }

    fun onContextDestroyed() {
        initialized = false
        program = 0
        phase = Phase.IDLE
        timer = 0f
        pendingTarget = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Điều khiển animation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Bắt đầu chiều FULL → model nhỏ [target] ([ActiveModel.SLIME] hoặc
     * [ActiveModel.CHIBI]). Không làm gì nếu đang chạy dở 1 animation khác — gọi
     * nơi duy nhất là [MultiModelScene.reconcileModel], nơi đã đảm bảo không gọi chồng lấn.
     */
    fun startFullToSmall(target: ActiveModel) {
        if (isActive) return
        pendingTarget = target
        phase = Phase.DESCEND_BAND
        timer = 0f
    }

    /** Bắt đầu chiều model nhỏ → FULL. */
    fun startSmallToFull() {
        if (isActive) return
        pendingTarget = ActiveModel.FULL
        pickRandomHolePosition()
        phase = Phase.CONSUME_HOLE
        timer = 0f
    }

    /** PHẦN 7.2 — vị trí cổng nhỏ: RANDOM mỗi lần trong 3 vị trí cố định, sát đáy màn hình. */
    private fun pickRandomHolePosition() {
        holeCenterX = when (Random.nextInt(3)) {
            0 -> HOLE_X_LEFT
            1 -> HOLE_X_CENTER
            else -> HOLE_X_RIGHT
        }
        holeCenterY = HOLE_Y_RATIO
    }

    /**
     * Cập nhật animation theo [dt] (giây). Gọi [onSwap] đúng 1 lần ở đúng thời điểm màn hình bị
     * che kín 100% (cuối DESCEND_BAND hoặc cuối CONSUME_HOLE) để [MultiModelScene] đổi model đang
     * thực sự vẽ mà user không thấy giật hình — giống hệt cơ chế của [PortalTransitionOverlay].
     */
    fun update(dt: Float, onSwap: (ActiveModel) -> Unit) {
        when (phase) {
            Phase.IDLE -> return
            Phase.DESCEND_BAND -> {
                timer += dt
                if (timer >= BAND_SECONDS) {
                    timer = 0f
                    pickRandomHolePosition()
                    phase = Phase.EMERGE_HOLE
                    pendingTarget?.let(onSwap)
                }
            }
            Phase.EMERGE_HOLE -> {
                timer += dt
                if (timer >= HOLE_SECONDS) {
                    timer = 0f
                    phase = Phase.IDLE
                    pendingTarget = null
                }
            }
            Phase.CONSUME_HOLE -> {
                timer += dt
                if (timer >= HOLE_SECONDS) {
                    timer = 0f
                    phase = Phase.ASCEND_BAND
                    pendingTarget?.let(onSwap)
                }
            }
            Phase.ASCEND_BAND -> {
                timer += dt
                if (timer >= BAND_SECONDS) {
                    timer = 0f
                    phase = Phase.IDLE
                    pendingTarget = null
                }
            }
        }
    }

    /**
     * Vẽ hiệu ứng đè lên model đã vẽ (gọi SAU khi model hiện tại vẽ xong, TRƯỚC bong bóng chat) —
     * đúng vị trí gọi giống [PortalTransitionOverlay.draw] trong [MultiModelScene.onDrawFrame].
     * [bandSource] là instance [PortalTransitionOverlay] có sẵn của scene — dùng lại texture/mesh
     * dải cổng to của nó cho 2 phase DESCEND_BAND/ASCEND_BAND thay vì dựng lại từ đầu.
     */
    fun draw(
        bandSource: PortalTransitionOverlay,
        overlayRenderer: OverlayTextureRenderer,
        screenW: Int,
        screenH: Int
    ) {
        if (!isActive || screenW <= 0 || screenH <= 0) return
        when (phase) {
            Phase.DESCEND_BAND -> {
                val cover = (timer / BAND_SECONDS).coerceIn(0f, 1f) * screenH
                bandSource.drawBandAt(cover, overlayRenderer, screenW, screenH)
            }
            Phase.ASCEND_BAND -> {
                val cover = (1f - (timer / BAND_SECONDS).coerceIn(0f, 1f)) * screenH
                bandSource.drawBandAt(cover, overlayRenderer, screenW, screenH)
            }
            Phase.EMERGE_HOLE -> drawHole(
                radiusProgress = (timer / HOLE_SECONDS).coerceIn(0f, 1f),
                screenW = screenW,
                screenH = screenH
            )
            Phase.CONSUME_HOLE -> drawHole(
                radiusProgress = 1f - (timer / HOLE_SECONDS).coerceIn(0f, 1f),
                screenW = screenW,
                screenH = screenH
            )
            Phase.IDLE -> {}
        }
    }

    /**
     * [radiusProgress] 0 → lỗ đóng hoàn toàn (che kín 100%, radius=0); 1 → lỗ mở hết cỡ (radius =
     * đường chéo màn hình, tức xem như trong suốt toàn màn hình — hiệu ứng coi như đã xong).
     */
    private fun drawHole(radiusProgress: Float, screenW: Int, screenH: Int) {
        if (!initialized) return
        val diag = sqrt((screenW.toFloat() * screenW + screenH.toFloat() * screenH))
        val radius = diag * radiusProgress.coerceIn(0f, 1f)
        val edge = diag * EDGE_RATIO
        val centerXPx = holeCenterX * screenW
        val centerYPx = holeCenterY * screenH

        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glUniform2f(uResolutionLoc, screenW.toFloat(), screenH.toFloat())
        GLES20.glUniform2f(uCenterLoc, centerXPx, centerYPx)
        GLES20.glUniform1f(uRadiusLoc, radius)
        GLES20.glUniform1f(uEdgeLoc, edge)

        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPositionLoc)

        GLES20.glDisable(GLES20.GL_BLEND)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shader "lỗ tròn"
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildProgram(): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SRC)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SRC)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Link full-portal program lỗi: ${GLES20.glGetProgramInfoLog(prog)}")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Compile full-portal shader lỗi: ${GLES20.glGetShaderInfoLog(shader)}")
        }
        return shader
    }

    companion object {
        private const val TAG = "ChibiFullPortal"

        private const val BAND_SECONDS = 0.30f
        private const val HOLE_SECONDS = 0.36f

        // 3 vị trí cố định cho cổng nhỏ (tỉ lệ theo width), luôn sát đáy màn hình (HOLE_Y_RATIO).
        // Mỗi lần EMERGE_HOLE/CONSUME_HOLE bắt đầu, pickRandomHolePosition() chọn random 1 trong 3.
        private const val HOLE_X_LEFT = 0.18f
        private const val HOLE_X_CENTER = 0.5f
        private const val HOLE_X_RIGHT = 0.82f
        private const val HOLE_Y_RATIO = 0.86f

        // Độ rộng dải glow ở mép lỗ, tính theo tỉ lệ đường chéo màn hình (để không đổi cảm giác to/
        // nhỏ giữa các máy có độ phân giải khác nhau).
        private const val EDGE_RATIO = 0.05f

        private const val VERTEX_SRC = """
            attribute vec2 aPosition;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """

        // Tô đặc (voidColor) toàn màn hình TRỪ 1 lỗ tròn tâm uCenter bán kính uRadius (trong suốt),
        // mép lỗ là dải "glow" màu tím sáng (#B388FF, khớp màu mép dải cổng ở Phần 7.1) rộng uEdge.
        // gl_FragCoord gốc DƯỚI-TRÁI (chuẩn GL) trong khi uCenter truyền vào theo pixel gốc TRÊN-
        // TRÁI (giống toạ độ touch/OverlayTextureRenderer) nên phải lật trục Y trước khi so khoảng
        // cách.
        private const val FRAGMENT_SRC = """
            precision mediump float;
            uniform vec2 uResolution;
            uniform vec2 uCenter;
            uniform float uRadius;
            uniform float uEdge;
            void main() {
                vec2 fragTopLeft = vec2(gl_FragCoord.x, uResolution.y - gl_FragCoord.y);
                float d = distance(fragTopLeft, uCenter);
                float innerR = max(uRadius - uEdge * 0.5, 0.0);
                float outerR = uRadius + uEdge * 0.5;
                float alpha = smoothstep(innerR, outerR, d);
                vec3 glowColor = vec3(0.702, 0.533, 1.0);
                vec3 voidColor = vec3(0.02, 0.004, 0.04);
                vec3 color = mix(glowColor, voidColor, smoothstep(innerR, outerR, d));
                gl_FragColor = vec4(color, alpha);
            }
        """
    }
}

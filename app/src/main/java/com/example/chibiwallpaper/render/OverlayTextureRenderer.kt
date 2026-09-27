package com.example.chibiwallpaper.render

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * PHẦN 5 — Renderer phụ trợ vẽ "overlay" 2D (bong bóng chat, bảng clipboard...) đè lên trên
 * scene Cubism, dưới dạng 1 quad có texture lấy từ Bitmap vẽ bằng android.graphics.Canvas.
 *
 * Độc lập hoàn toàn với hệ toạ độ/matrix riêng của Cubism (MultiModelScene dùng NDC riêng cho
 * model). Ở đây làm việc thẳng bằng toạ độ PIXEL màn hình (gốc trên-trái, giống MotionEvent và
 * giống tham số của GLScene.onTouch) cho dễ tính vị trí + hit-test nút đóng.
 *
 * CHỈ được gọi trên GL thread ("ChibiGL") — mọi hàm ở đây dùng GLES20.* trực tiếp.
 */
class OverlayTextureRenderer {

    private var program = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uTextureLoc = 0
    private var uAlphaLoc = 0
    private var initialized = false

    private val vertexBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(4 * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private val texCoordBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(4 * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            // top-left, bottom-left, top-right, bottom-right (khớp thứ tự TRIANGLE_STRIP bên dưới).
            // Nếu texture bị lộn ngược trên máy thật, đổi 0f<->1f ở cột thứ 2 (trục t) tại đây.
            put(floatArrayOf(
                0f, 0f,
                0f, 1f,
                1f, 0f,
                1f, 1f
            ))
            position(0)
        }

    fun ensureInitialized() {
        if (initialized) return
        program = buildProgram()
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")
        uAlphaLoc = GLES20.glGetUniformLocation(program, "uAlpha")
        initialized = true
    }

    /** Context GL bị huỷ — program/texture cũ tự mất theo context, chỉ cần reset cờ nội bộ. */
    fun onContextDestroyed() {
        initialized = false
        program = 0
    }

    fun createTextureFromBitmap(bitmap: Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        return ids[0]
    }

    fun deleteTexture(id: Int) {
        if (id != 0) GLES20.glDeleteTextures(1, intArrayOf(id), 0)
    }

    /**
     * Vẽ [textureId] thành 1 quad tâm ([centerXPx],[centerYPx]) kích thước [widthPx]x[heightPx],
     * toạ độ pixel gốc trên-trái. [rotationDeg] dùng cho hiệu ứng "quăng lên" (bảng clipboard bay).
     */
    fun drawTexture(
        textureId: Int,
        screenW: Int,
        screenH: Int,
        centerXPx: Float,
        centerYPx: Float,
        widthPx: Float,
        heightPx: Float,
        alpha: Float = 1f,
        rotationDeg: Float = 0f
    ) {
        if (!initialized || textureId == 0 || screenW <= 0 || screenH <= 0) return

        val hw = widthPx / 2f
        val hh = heightPx / 2f
        val rad = Math.toRadians(rotationDeg.toDouble())
        val cos = Math.cos(rad).toFloat()
        val sin = Math.sin(rad).toFloat()

        fun corner(dx: Float, dy: Float): FloatArray {
            val rx = dx * cos - dy * sin
            val ry = dx * sin + dy * cos
            val px = centerXPx + rx
            val py = centerYPx + ry
            // pixel (gốc trên-trái) -> NDC (-1..1, trục Y hướng lên)
            val ndcX = (px / screenW) * 2f - 1f
            val ndcY = 1f - (py / screenH) * 2f
            return floatArrayOf(ndcX, ndcY)
        }

        val tl = corner(-hw, -hh)
        val bl = corner(-hw, hh)
        val tr = corner(hw, -hh)
        val br = corner(hw, hh)

        vertexBuffer.clear()
        vertexBuffer.put(tl); vertexBuffer.put(bl); vertexBuffer.put(tr); vertexBuffer.put(br)
        vertexBuffer.position(0)

        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uTextureLoc, 0)
        GLES20.glUniform1f(uAlphaLoc, alpha.coerceIn(0f, 1f))

        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

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
            Log.e(TAG, "Link overlay program lỗi: ${GLES20.glGetProgramInfoLog(prog)}")
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
            Log.e(TAG, "Compile shader lỗi: ${GLES20.glGetShaderInfoLog(shader)}")
        }
        return shader
    }

    companion object {
        private const val TAG = "ChibiOverlay"

        private const val VERTEX_SRC = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """

        private const val FRAGMENT_SRC = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            uniform float uAlpha;
            void main() {
                vec4 c = texture2D(uTexture, vTexCoord);
                gl_FragColor = vec4(c.rgb, c.a * uAlpha);
            }
        """
    }
}

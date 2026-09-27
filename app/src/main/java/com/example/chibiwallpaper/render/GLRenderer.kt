package com.example.chibiwallpaper.render

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * GIAI ĐOẠN 2 — EGL context tự dựng + render loop.
 *
 * Thiết kế:
 *  - Một HandlerThread riêng ("ChibiGL") sở hữu toàn bộ EGL/GL. Main thread (Engine) chỉ gọi
 *    các hàm public bên dưới; chúng post việc sang GL thread.
 *  - EGLContext được giữ SỐNG khi surface bị huỷ/tạo lại (wallpaper hay bị vậy) để sau này
 *    model/texture Cubism không phải load lại. Chỉ EGLSurface được tạo/huỷ theo Android Surface.
 *  - Vòng lặp vẽ dùng Choreographer (đồng bộ vsync) và giới hạn [targetFps] để tiết kiệm pin.
 *    Chỉ chạy khi wallpaper đang hiển thị VÀ có surface.
 *  - Nếu mất context (EGL_CONTEXT_LOST) thì dựng lại context và báo scene qua onContextCreated().
 *
 * Nền tảng: OpenGL ES 2.0 (đủ cho Cubism; nâng lên 3.0 sau nếu cần).
 */
class GLRenderer(
    private val scene: GLScene,
    private val targetFps: Int = 30,
    private val clearColor: FloatArray = floatArrayOf(0.118f, 0.106f, 0.180f, 1f) // #1E1B2E
) {

    private companion object {
        const val TAG = "ChibiGL"
        const val LOW_POWER_FPS = 4 // PHẦN 8 — đủ mượt cho nhịp thở + "Z z z" trôi lên, tiết kiệm pin đáng kể
    }

    private val thread = HandlerThread("ChibiGL").also { it.start() }
    private val handler = Handler(thread.looper)

    // ---- Trạng thái, CHỈ được đụng tới trên GL thread ----
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null

    private var androidSurface: Surface? = null
    private var width = 0
    private var height = 0
    private var visible = false
    private var frameScheduled = false
    private var lastFrameNanos = 0L

    // PHẦN 8 — Khung hình/giây có thể hạ động lúc scene báo "muốn tiết kiệm pin" (Slime ngủ, xem
    // [GLScene.wantsLowFrameRate]). CHỈ đọc/ghi trên GL thread (bên trong onFrame, đã chạy sẵn ở
    // đó) nên không cần đồng bộ hoá gì thêm.
    private val normalFrameIntervalNanos = 1_000_000_000L / targetFps.coerceAtLeast(1)
    private val lowPowerFrameIntervalNanos = 1_000_000_000L / LOW_POWER_FPS
    private var frameIntervalNanos = normalFrameIntervalNanos
    private var lowPowerActive = false

    private val frameCallback = Choreographer.FrameCallback { onFrame(it) }

    // =====================================================================================
    // API public — gọi từ main thread (Engine)
    // =====================================================================================

    fun surfaceCreated(surface: Surface) {
        handler.post {
            androidSurface = surface
            attachSurface()
        }
    }

    fun surfaceChanged(w: Int, h: Int) {
        handler.post {
            width = w
            height = h
            applySize()
        }
    }

    fun setVisible(v: Boolean) {
        handler.post {
            visible = v
            if (v) {
                lastFrameNanos = 0L
                scheduleFrame()
            } else {
                cancelFrame()
            }
        }
    }

    fun onTouch(x: Float, y: Float) {
        handler.post { scene.onTouch(x, y) }
    }

    /** PHẦN 9 — Triple-tap: toggle STRAW motion. Chạy trên GL thread. */
    fun onTripleTap(x: Float, y: Float) {
        handler.post { scene.onTripleTap(x, y) }
    }

    /** PHẦN 13 — Vuốt lên trúng Slime: bật/tắt luân phiên AD/STRAW (ngẫu nhiên). Chạy trên GL thread. */
    fun onSwipeUp(x: Float, y: Float) {
        handler.post { scene.onSwipeUp(x, y) }
    }

    /**
     * PHẦN 9 — Double-tap: đánh thức Slime chỉ khi trúng đúng vị trí Slime.
     * Trả về true (qua callback) nếu được đánh thức — service dùng để quyết định có vào STT không.
     * Vì cần kết quả trả về từ GL thread → main thread, dùng callback thay vì return value.
     */
    fun wakeIfHit(x: Float, y: Float, onResult: (Boolean) -> Unit) {
        handler.post {
            val hit = scene.wakeIfHit(x, y)
            android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(hit) }
        }
    }

    /**
     * PHẦN 12 — Đánh thức Slime vô điều kiện (không cần tap trúng vị trí), dùng cho các trigger
     * bên ngoài không có toạ độ chạm thật (Quick Settings Tile). Chạy trên GL thread như mọi
     * thao tác khác lên [scene].
     */
    fun wakeSlime() {
        handler.post { scene.wakeSlime() }
    }

    /**
     * PHẦN 18 — Chạy 1 block bất kỳ trên GL thread, dùng cho hot-reload ảnh/video nền từ
     * MainActivity (đụng tới OverlayTextureRenderer/VideoLayer bên trong scene nên bắt buộc phải
     * chạy trên GL thread). Không cần thêm API riêng cho từng loại reload — scene tự biết phải
     * làm gì (xem MultiModelScene.reloadBackground/reloadBackgroundVideo).
     */
    fun runOnGlThread(block: () -> Unit) {
        handler.post(block)
    }

    /**
     * BLOCKING (tối đa ~2s): phải xong trước khi Android thu hồi Surface, nếu không
     * EGLSurface còn trỏ vào Surface đã chết -> lỗi/crash.
     */
    fun surfaceDestroyed() {
        runAndWait {
            cancelFrame()
            destroyEglSurface()
            androidSurface = null
            width = 0
            height = 0
        }
    }

    /** Giải phóng toàn bộ EGL và dừng GL thread. Gọi từ Engine.onDestroy(). */
    fun release() {
        runAndWait {
            cancelFrame()
            visible = false
            teardownEgl()
            androidSurface = null
        }
        thread.quitSafely()
    }

    // =====================================================================================
    // Nội bộ — chạy trên GL thread
    // =====================================================================================

    private fun runAndWait(block: () -> Unit) {
        if (Looper.myLooper() == thread.looper) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        val posted = handler.post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        if (posted && !latch.await(2, TimeUnit.SECONDS)) {
            Log.w(TAG, "runAndWait: quá 2s, GL thread có thể đang bị kẹt")
        }
    }

    private fun initEglIfNeeded(): Boolean {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglConfig != null) return true

        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "eglGetDisplay thất bại")
            return false
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            Log.e(TAG, "eglInitialize thất bại: 0x${Integer.toHexString(EGL14.eglGetError())}")
            return false
        }
        val config = chooseConfig(display)
        if (config == null) {
            Log.e(TAG, "Không tìm được EGLConfig phù hợp")
            EGL14.eglTerminate(display)
            return false
        }
        eglDisplay = display
        eglConfig = config
        Log.d(TAG, "EGL ${version[0]}.${version[1]} khởi tạo xong")
        return true
    }

    private fun chooseConfig(display: EGLDisplay): EGLConfig? {
        // Thử có depth+stencil trước (Cubism dùng stencil/FBO cho clipping mask ở vài chế độ);
        // máy nào không có thì lùi về config tối thiểu.
        for (withDepthStencil in listOf(true, false)) {
            val attribs = mutableListOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8
            )
            if (withDepthStencil) {
                attribs += listOf(EGL14.EGL_DEPTH_SIZE, 16, EGL14.EGL_STENCIL_SIZE, 8)
            }
            attribs += EGL14.EGL_NONE

            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            val ok = EGL14.eglChooseConfig(display, attribs.toIntArray(), 0, configs, 0, 1, num, 0)
            if (ok && num[0] > 0 && configs[0] != null) {
                Log.d(TAG, "EGLConfig chọn được (depth+stencil=$withDepthStencil)")
                return configs[0]
            }
        }
        return null
    }

    /** Tạo EGLSurface từ Android Surface hiện có (+ tạo context nếu chưa có) và make current. */
    private fun attachSurface() {
        val surface = androidSurface ?: return
        if (!surface.isValid) return
        if (!initEglIfNeeded()) return

        destroyEglSurface()

        val newSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0
        )
        if (newSurface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "eglCreateWindowSurface thất bại: 0x${Integer.toHexString(EGL14.eglGetError())}")
            return
        }
        eglSurface = newSurface

        var contextIsNew = false
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(
                eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0
            )
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                Log.e(TAG, "eglCreateContext thất bại: 0x${Integer.toHexString(EGL14.eglGetError())}")
                destroyEglSurface()
                return
            }
            contextIsNew = true
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            Log.e(TAG, "eglMakeCurrent thất bại: 0x${Integer.toHexString(EGL14.eglGetError())}")
            destroyEglSurface()
            return
        }
        EGL14.eglSwapInterval(eglDisplay, 1)
        Log.d(TAG, "GL_RENDERER = ${GLES20.glGetString(GLES20.GL_RENDERER)}, " +
                "GL_VERSION = ${GLES20.glGetString(GLES20.GL_VERSION)}")

        if (contextIsNew) scene.onContextCreated()
        applySize()
        scheduleFrame()
    }

    private fun applySize() {
        if (eglSurface == EGL14.EGL_NO_SURFACE || width <= 0 || height <= 0) return
        GLES20.glViewport(0, 0, width, height)
        scene.onSurfaceChanged(width, height)
    }

    private fun destroyEglSurface() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        // Phải nhả khỏi thread hiện tại trước khi huỷ surface.
        EGL14.eglMakeCurrent(
            eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
        )
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
        }
    }

    private fun destroyEglContext() {
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            scene.onContextDestroyed()
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            eglContext = EGL14.EGL_NO_CONTEXT
        }
    }

    private fun teardownEgl() {
        destroyEglSurface()
        destroyEglContext()
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
        eglConfig = null
    }

    private fun handleContextLost() {
        Log.w(TAG, "EGL context bị mất — dựng lại")
        destroyEglSurface()
        destroyEglContext()
        attachSurface() // tạo context mới + gọi scene.onContextCreated()
    }

    // ---- Vòng lặp vẽ ----

    private fun scheduleFrame() {
        if (!visible || eglSurface == EGL14.EGL_NO_SURFACE || frameScheduled) return
        Choreographer.getInstance().postFrameCallback(frameCallback)
        frameScheduled = true
    }

    private fun cancelFrame() {
        if (frameScheduled) {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            frameScheduled = false
        }
    }

    private fun onFrame(frameTimeNanos: Long) {
        frameScheduled = false
        if (!visible || eglSurface == EGL14.EGL_NO_SURFACE) return

        // Giới hạn FPS: bỏ qua các vsync đến quá sớm (chừa 2ms dung sai).
        if (lastFrameNanos != 0L &&
            frameTimeNanos - lastFrameNanos < frameIntervalNanos - 2_000_000L
        ) {
            scheduleFrame()
            return
        }
        val dt = if (lastFrameNanos == 0L) 0f
        else ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceAtMost(0.1f)
        lastFrameNanos = frameTimeNanos

        drawFrame(dt)

        // PHẦN 8 — Sau khi vẽ xong (scene đã cập nhật state của frame này), hỏi xem có nên
        // chuyển mức FPS không. Chỉ log/đổi khi giá trị thực sự thay đổi, tránh so sánh/ghi thừa
        // mỗi frame.
        val wantsLow = scene.wantsLowFrameRate()
        if (wantsLow != lowPowerActive) {
            lowPowerActive = wantsLow
            frameIntervalNanos = if (wantsLow) lowPowerFrameIntervalNanos else normalFrameIntervalNanos
            Log.d(TAG, "Low-power mode: $lowPowerActive (fps=${if (wantsLow) LOW_POWER_FPS else targetFps})")
        }

        scheduleFrame()
    }

    private fun drawFrame(deltaSeconds: Float) {
        GLES20.glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3])
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_STENCIL_BUFFER_BIT)
        scene.onDrawFrame(deltaSeconds)

        if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            when (val err = EGL14.eglGetError()) {
                EGL14.EGL_CONTEXT_LOST -> handleContextLost()
                EGL14.EGL_BAD_SURFACE, EGL14.EGL_BAD_NATIVE_WINDOW -> {
                    Log.w(TAG, "Surface không còn hợp lệ, chờ surface mới")
                    destroyEglSurface()
                }
                else -> Log.e(TAG, "eglSwapBuffers lỗi: 0x${Integer.toHexString(err)}")
            }
        }
    }
}

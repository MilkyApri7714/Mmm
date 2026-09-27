package com.example.chibiwallpaper.render

import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface

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
 * PHẦN 22 — Dùng API EGL CŨ (`javax.microedition.khronos.egl.*`, giống hệt API mà
 * `android.opengl.GLSurfaceView` dùng nội bộ) THAY VÌ `android.opengl.EGL14` như trước — để có
 * thể truyền THẲNG object [EGLContext]/[EGLConfig]/[EGLDisplay] của mình làm `share_context` cho
 * [com.example.chibiwallpaper.floating.FloatingPetService] (dùng GLSurfaceView + cùng API này qua
 * [CubismGlShare.SharedEglContextFactory]/[CubismGlShare.SharedEglConfigChooser]), không cần
 * chuyển đổi qua lại giữa 2 API EGL khác nhau. Xem [CubismGlShare] để biết lý do CẦN chia sẻ
 * context: Cubism SDK cache shader program trong 1 singleton dùng chung cho CẢ TIẾN TRÌNH, gắn
 * với EGLContext đang current lúc nó được tạo — 2 context KHÔNG chia sẻ namespace sẽ khiến bên
 * còn lại (wallpaper hoặc nhân vật nổi) hiện màn hình trắng/model vỡ vụn.
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
        const val LOW_POWER_FPS = 3 // PHẦN 26 (hạ từ 4→3) — đủ mượt cho nhịp thở + "Z z z" trôi lên, tiết kiệm pin hơn nữa
        const val EGL_CONTEXT_LOST = 0x300E // Không có trong EGL10 (chỉ EGL 1.4+/EGL14 mới định nghĩa)
    }

    private val thread = HandlerThread("ChibiGL").also { it.start() }
    private val handler = Handler(thread.looper)

    private val egl: EGL10 = EGLContext.getEGL() as EGL10

    // ---- Trạng thái, CHỈ được đụng tới trên GL thread ----
    private var eglDisplay: EGLDisplay = EGL10.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL10.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL10.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null
    // true nếu [eglDisplay] là display TỰ MÌNH eglInitialize() (owner đầu tiên của tiến trình) —
    // CHỈ owner này mới được eglTerminate() lúc dọn dẹp cuối cùng (xem [teardownEgl]).
    private var ownsDisplay = false

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
        if (eglDisplay != EGL10.EGL_NO_DISPLAY && eglConfig != null) return true

        // PHẦN 22 — Nếu Nhân vật nổi đã chạy trước và đăng ký sẵn display/config dùng chung, DÙNG
        // LẠI NGUYÊN nó (không tự eglInitialize/eglChooseConfig riêng) — bắt buộc, để context của
        // wallpaper chia sẻ được namespace shader với context floating đã tồn tại.
        val sharedDisplay = CubismGlShare.masterDisplay
        val sharedConfig = CubismGlShare.masterConfig
        if (sharedDisplay != null && sharedConfig != null) {
            eglDisplay = sharedDisplay
            eglConfig = sharedConfig
            ownsDisplay = false
            Log.d(TAG, "initEglIfNeeded: dùng lại display/config đã có (Nhân vật nổi khởi tạo trước)")
            return true
        }

        val display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
        if (display == EGL10.EGL_NO_DISPLAY) {
            Log.e(TAG, "eglGetDisplay thất bại")
            return false
        }
        val version = IntArray(2)
        if (!egl.eglInitialize(display, version)) {
            Log.e(TAG, "eglInitialize thất bại: 0x${Integer.toHexString(egl.eglGetError())}")
            return false
        }
        val config = CubismGlShare.chooseConfig(egl, display)
        if (config == null) {
            Log.e(TAG, "Không tìm được EGLConfig phù hợp")
            egl.eglTerminate(display)
            return false
        }
        eglDisplay = display
        eglConfig = config
        ownsDisplay = true
        CubismGlShare.registerDisplayConfig(display, config) // để Nhân vật nổi (nếu chạy sau) dùng lại
        Log.d(TAG, "EGL khởi tạo xong (owner đầu tiên của tiến trình), version=${version[0]}.${version[1]}")
        return true
    }

    /** Tạo EGLSurface từ Android Surface hiện có (+ tạo context nếu chưa có) và make current. */
    private fun attachSurface() {
        val surface = androidSurface ?: return
        if (!surface.isValid) return

        // PHẦN 27 (fix) — RACE CONDITION: nếu Wallpaper và Nhân vật nổi cùng gọi attachSurface()
        // gần như đồng thời (2 GL thread khác nhau), cả 2 có thể cùng đọc thấy "chưa ai làm chủ"
        // TRƯỚC KHI bên nào kịp đăng ký xong → cả 2 tự tạo context RIÊNG, không chia sẻ namespace
        // → vỡ hình/trắng màn hình NGẪU NHIÊN (chỉ xảy ra khi timing trùng nhau lúc khởi động).
        // Bọc toàn bộ đoạn "quyết định ai làm chủ + tạo/đăng ký context" trong 1 khoá DÙNG CHUNG
        // cho cả 2 luồng — luồng nào tới trước phải làm xong hẳn (đăng ký + tạo context share)
        // rồi luồng kia mới được đọc trạng thái, đảm bảo không bao giờ có 2 context không share.
        var contextIsNew = false
        synchronized(CubismGlShare) {
            if (!initEglIfNeeded()) return

            destroyEglSurface()

            val newSurface = egl.eglCreateWindowSurface(eglDisplay, eglConfig, surface, null)
            if (newSurface == EGL10.EGL_NO_SURFACE) {
                Log.e(TAG, "eglCreateWindowSurface thất bại: 0x${Integer.toHexString(egl.eglGetError())}")
                return
            }
            eglSurface = newSurface

            contextIsNew = false
            if (eglContext == EGL10.EGL_NO_CONTEXT) {
                // PHẦN 22 — Truyền context chia sẻ (của Nhân vật nổi, nếu nó đã chạy trước) làm
                // share_context — xem [CubismGlShare].
                val shareCtx = CubismGlShare.beforeCreateContext(egl)
                eglContext = egl.eglCreateContext(eglDisplay, eglConfig, shareCtx, CubismGlShare.contextAttribs())
                if (eglContext == EGL10.EGL_NO_CONTEXT) {
                    Log.e(TAG, "eglCreateContext thất bại: 0x${Integer.toHexString(egl.eglGetError())}")
                    destroyEglSurface()
                    return
                }
                CubismGlShare.afterContextCreated(eglContext)
                contextIsNew = true
            }
        }

        if (!egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            Log.e(TAG, "eglMakeCurrent thất bại: 0x${Integer.toHexString(egl.eglGetError())}")
            destroyEglSurface()
            return
        }
        egl.eglSwapInterval(eglDisplay, 1)
        Log.d(TAG, "GL_RENDERER = ${GLES20.glGetString(GLES20.GL_RENDERER)}, " +
                "GL_VERSION = ${GLES20.glGetString(GLES20.GL_VERSION)}")

        if (contextIsNew) scene.onContextCreated()
        applySize()
        scheduleFrame()
    }

    private fun applySize() {
        if (eglSurface == EGL10.EGL_NO_SURFACE || width <= 0 || height <= 0) return
        GLES20.glViewport(0, 0, width, height)
        scene.onSurfaceChanged(width, height)
    }

    private fun destroyEglSurface() {
        if (eglDisplay == EGL10.EGL_NO_DISPLAY) return
        // Phải nhả khỏi thread hiện tại trước khi huỷ surface.
        egl.eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
        if (eglSurface != EGL10.EGL_NO_SURFACE) {
            egl.eglDestroySurface(eglDisplay, eglSurface)
            eglSurface = EGL10.EGL_NO_SURFACE
        }
    }

    /** @return true nếu đây là owner GL CUỐI CÙNG của tiến trình (xem [CubismGlShare]). */
    private fun destroyEglContext(): Boolean {
        if (eglContext == EGL10.EGL_NO_CONTEXT) return false
        // PHẦN 22 — Hỏi trước xem mình có phải owner GL cuối cùng của tiến trình không; CHỈ
        // báo scene "được phép dispose thật" nếu đúng vậy (xem [CubismGlShare]).
        val isLastCubismOwner = CubismGlShare.beforeDestroyContext()
        scene.onContextDestroyed(isLastCubismOwner)
        egl.eglDestroyContext(eglDisplay, eglContext)
        eglContext = EGL10.EGL_NO_CONTEXT
        return isLastCubismOwner
    }

    private fun teardownEgl() {
        destroyEglSurface()
        val isLastCubismOwner = destroyEglContext()
        // PHẦN 22 (FIX) — Trước đây dùng [ownsDisplay] ("mình có phải bên tự eglInitialize()
        // display này không") để quyết định có eglTerminate() hay không. SAI: ownsDisplay chỉ nói
        // ai là bên TẠO display trước, không nói bên KIA có còn sống hay không. Nếu Wallpaper là
        // owner đầu tiên (ownsDisplay=true) và Nhân vật nổi khởi động SAU (dùng chung display này
        // qua CubismGlShare), thì lúc Wallpaper bị onDestroy() trong khi Nhân vật nổi VẪN ĐANG
        // CHẠY, code cũ vẫn eglTerminate() display dùng chung → Nhân vật nổi vỡ hình/trắng màn
        // hình/crash ngay khung kế tiếp dù bản thân nó không hề bị đụng tới.
        // Giờ dùng ĐÚNG [isLastCubismOwner] (đếm số owner GL còn sống trong CubismGlShare) —
        // CHỈ eglTerminate() khi chắc chắn không còn ai (Wallpaper lẫn Nhân vật nổi) dùng display
        // này nữa, bất kể ai là người gọi eglInitialize() đầu tiên.
        if (isLastCubismOwner && eglDisplay != EGL10.EGL_NO_DISPLAY) {
            egl.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL10.EGL_NO_DISPLAY
        eglConfig = null
        ownsDisplay = false
    }

    private fun handleContextLost() {
        Log.w(TAG, "EGL context bị mất — dựng lại")
        destroyEglSurface()
        destroyEglContext()
        attachSurface() // tạo context mới + gọi scene.onContextCreated()
    }

    // ---- Vòng lặp vẽ ----

    private fun scheduleFrame() {
        if (!visible || eglSurface == EGL10.EGL_NO_SURFACE || frameScheduled) return
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
        if (!visible || eglSurface == EGL10.EGL_NO_SURFACE) return

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

        if (!egl.eglSwapBuffers(eglDisplay, eglSurface)) {
            when (val err = egl.eglGetError()) {
                EGL_CONTEXT_LOST -> handleContextLost()
                EGL10.EGL_BAD_SURFACE, EGL10.EGL_BAD_NATIVE_WINDOW -> {
                    Log.w(TAG, "Surface không còn hợp lệ, chờ surface mới")
                    destroyEglSurface()
                }
                else -> Log.e(TAG, "eglSwapBuffers lỗi: 0x${Integer.toHexString(err)}")
            }
        }
    }
}

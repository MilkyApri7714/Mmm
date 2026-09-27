package com.example.chibiwallpaper.render

import android.util.Log
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay

/**
 * PHẦN 22 — Fix xung đột GL giữa Live Wallpaper ([com.example.chibiwallpaper.render.GLRenderer])
 * và Nhân vật nổi ([com.example.chibiwallpaper.floating.FloatingPetRenderer]).
 *
 * NGUYÊN NHÂN GỐC của cả 2 lỗi "app xung đột liên tục" và "chế độ nổi bị trắng/model vỡ vụn":
 * Cubism SDK for Java biên dịch & cache shader program trong 1 SINGLETON DÙNG CHUNG CHO CẢ TIẾN
 * TRÌNH (`CubismShaderAndroid.s_instance`, xem framework), gắn với BẤT KỲ EGLContext nào đang
 * "current" tại thời điểm nó được tạo lần đầu. Trước bản vá này, Wallpaper (tự dựng EGL thủ công
 * trong [GLRenderer]) và Nhân vật nổi (trước BUG 2, dùng `android.opengl.GLSurfaceView` mặc định)
 * MỖI BÊN tạo 1 EGLContext HOÀN TOÀN RIÊNG, không hề chia sẻ object namespace với nhau:
 *   - Program ID hợp lệ ở context A lại vô nghĩa khi context B đang current → bên vẽ SAU (tuỳ
 *     thời điểm, có thể đổi chiều mỗi lần bật/tắt) hiện màn hình trắng hoặc model vỡ vụn.
 *   - Khi 1 bên mất/huỷ context (vd. wallpaper mất surface lúc khoá màn hình) và gọi
 *     `CubismFramework.dispose()` → xoá theo cả `CubismShaderAndroid.s_instance` mà bên KIA vẫn
 *     đang cần → bên kia vỡ hình ngay khung kế tiếp dù bản thân nó không hề mất context.
 *
 * CÁCH SỬA: dùng CHUNG 1 "nhóm chia sẻ" EGLContext cho toàn tiến trình.
 *   - Bên nào tạo EGLContext TRƯỚC (không quan trọng là Wallpaper hay Floating) trở thành
 *     "chủ" — [masterDisplay]/[masterConfig] được đăng ký lại đây qua [registerDisplayConfig].
 *   - Bên tạo SAU bắt buộc phải:
 *      1. Dùng lại ĐÚNG [masterConfig] (không tự chọn EGLConfig riêng) — 2 context chỉ chia sẻ
 *         được object namespace khi dùng config tương thích; an toàn nhất là dùng NGUYÊN config
 *         của bên đầu tiên.
 *      2. Truyền context trả về bởi [beforeCreateContext] làm `share_context` khi gọi
 *         `eglCreateContext` — nhờ vậy `CubismShaderAndroid` compile 1 lần, DÙNG ĐƯỢC Ở CẢ HAI.
 *   - [beforeDestroyContext] đếm số "chủ" (owner) GL đang sống. CHỈ khi nó trả về true (owner
 *     CUỐI CÙNG rời đi) thì bên gọi mới được thực sự chạy `CubismFramework.dispose()` (xem
 *     [com.example.chibiwallpaper.cubism.CubismBoot.disposeOnGlThread]) — tránh xoá singleton
 *     dùng chung trong khi bên kia vẫn còn sống.
 *
 * BUG 2 (FIX) — Nhân vật nổi giờ cũng dùng [GLRenderer] (EGL tự quản lý, y hệt Wallpaper) thay vì
 * `GLSurfaceView`, nên gọi thẳng [registerDisplayConfig]/[beforeCreateContext]/[afterContextCreated]/
 * [beforeDestroyContext] qua [GLRenderer] — không còn cần `GLSurfaceView.EGLConfigChooser`/
 * `EGLContextFactory` riêng nữa (2 class đó đã bị xoá khỏi file này).
 *
 * GIẢ ĐỊNH ĐƠN GIẢN HOÁ: cả tiến trình chỉ có TỐI ĐA 2 "chủ" GL cùng lúc (Wallpaper + Floating),
 * mỗi bên gọi enter/leave đúng 1 cặp cân bằng trong vòng đời của nó — nên chỉ cần đếm số lượng,
 * không cần theo dõi định danh từng EGLContext.
 *
 * Mọi hàm đều [Synchronized] vì Wallpaper và Floating chạy trên 2 thread GL khác nhau, có thể
 * gọi vào đây gần như cùng lúc lúc khởi động/tắt.
 */
object CubismGlShare {
    private const val TAG = "ChibiGlShare"

    // Hằng số EGL không có sẵn trong javax.microedition.khronos.egl.EGL10 (interface này có từ
    // thời Java ME, trước OpenGL ES 2.0) — khai báo thủ công y hệt cách GLSurfaceView tự làm.
    private const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
    private const val EGL_OPENGL_ES2_BIT = 4
    private const val EGL_RENDERABLE_TYPE = 0x3040

    @Volatile var masterDisplay: EGLDisplay? = null
        private set
    @Volatile var masterConfig: EGLConfig? = null
        private set
    @Volatile private var shareContext: EGLContext? = null
    private var liveOwnerCount = 0

    /** Bên đầu tiên tạo context gọi hàm này để công bố display/config của mình cho bên sau dùng lại. */
    @Synchronized
    fun registerDisplayConfig(display: EGLDisplay, config: EGLConfig) {
        if (masterDisplay == null) {
            masterDisplay = display
            masterConfig = config
            Log.d(TAG, "registerDisplayConfig: đăng ký làm chủ (owner đầu tiên của tiến trình)")
        }
    }

    /** Gọi TRƯỚC eglCreateContext để lấy share_context (EGL_NO_CONTEXT nếu mình là owner đầu tiên). */
    @Synchronized
    fun beforeCreateContext(egl: EGL10): EGLContext = shareContext ?: EGL10.EGL_NO_CONTEXT

    /** Gọi NGAY SAU khi eglCreateContext thành công. */
    @Synchronized
    fun afterContextCreated(context: EGLContext) {
        liveOwnerCount++
        shareContext = context
        Log.d(TAG, "afterContextCreated: liveOwnerCount=$liveOwnerCount")
    }

    /**
     * Gọi TRƯỚC khi 1 bên huỷ context của chính mình (surface/service dừng hẳn, KHÔNG phải lúc
     * mất context tạm thời do xoay màn hình — lúc đó GLRenderer dựng lại ngay, không gọi hàm này).
     * @return true nếu đây là owner CUỐI CÙNG (không còn ai khác) — bên gọi lúc này MỚI được phép
     *         thực sự `CubismFramework.dispose()`; false → còn bên kia đang sống, TUYỆT ĐỐI không
     *         được đụng vào Cubism framework/shader dùng chung.
     */
    @Synchronized
    fun beforeDestroyContext(): Boolean {
        if (liveOwnerCount > 0) liveOwnerCount--
        val isLast = liveOwnerCount <= 0
        Log.d(TAG, "beforeDestroyContext: liveOwnerCount=$liveOwnerCount, isLast=$isLast")
        if (isLast) {
            liveOwnerCount = 0
            masterDisplay = null
            masterConfig = null
            shareContext = null
        }
        return isLast
    }

    /**
     * Thuật toán chọn EGLConfig DÙNG CHUNG cho cả 2 [GLRenderer] (wallpaper + floating, từ BUG 2 cả
     * hai đều tự dựng EGL thủ công) — CHỈ owner đầu tiên của tiến trình mới thực sự gọi hàm này;
     * owner thứ 2 luôn dùng lại [masterConfig] (xem trên).
     * Thử có depth+stencil trước (Cubism dùng stencil/FBO cho clipping mask ở vài chế độ), máy
     * nào không có thì lùi về config tối thiểu.
     */
    fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig? {
        for (withDepthStencil in listOf(true, false)) {
            val attribs = mutableListOf(
                EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL10.EGL_SURFACE_TYPE, EGL10.EGL_WINDOW_BIT,
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8
            )
            if (withDepthStencil) {
                attribs += listOf(EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_STENCIL_SIZE, 8)
            }
            attribs += EGL10.EGL_NONE

            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            val ok = egl.eglChooseConfig(display, attribs.toIntArray(), configs, 1, num)
            if (ok && num[0] > 0 && configs[0] != null) {
                Log.d(TAG, "chooseConfig: chọn được (depth+stencil=$withDepthStencil)")
                return configs[0]
            }
        }
        return null
    }

    /** Tạo attrib list chuẩn cho eglCreateContext (OpenGL ES 2.0). */
    fun contextAttribs(): IntArray = intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE)
}

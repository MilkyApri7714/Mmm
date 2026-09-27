package com.example.chibiwallpaper.cubism

import android.content.Context
import android.util.Log
import com.live2d.sdk.cubism.core.ICubismLogger
import com.live2d.sdk.cubism.framework.CubismFramework
import com.live2d.sdk.cubism.framework.CubismFrameworkConfig.LogLevel
import com.live2d.sdk.cubism.framework.ICubismLoadFileFunction
import java.io.IOException

/**
 * PHẦN 3.1 — Vòng đời khởi động của Cubism SDK for Java (chưa load model nào).
 *
 * CubismFramework có 2 tầng trạng thái tách biệt:
 *  - startUp(option): gắn log function + file-loading function cho cả tiến trình, KHÔNG đụng GL,
 *    chỉ cần làm 1 lần, có thể gọi trước khi có EGL context.
 *  - initialize()/dispose(): cấp phát/giải phóng tài nguyên nội bộ (id manager, shader cache...)
 *    gắn với EGL context hiện có -> BẮT BUỘC gọi trên GL thread ("ChibiGL"), khớp với đúng thời điểm
 *    GLRenderer gọi scene.onContextCreated() / scene.onContextDestroyed() (xem GLScene.kt, Phần 2).
 *
 * File này KHÔNG tự đứng làm GLScene — nó được một scene gọi tới ở đúng 2 thời điểm trên.
 * Phần 3.2 sẽ thay DemoTriangleScene bằng scene thật load model, lúc đó lệnh gọi
 * initializeOnGlThread()/disposeOnGlThread() sẽ chuyển vào scene đó.
 */
object CubismBoot {
    private const val TAG = "ChibiCubism"

    private class LogFn : ICubismLogger {
        override fun print(message: String) {
            Log.d(TAG, message)
        }
    }

    private class FileFn(private val appContext: Context) : ICubismLoadFileFunction {
        override fun load(filePath: String): ByteArray {
            return try {
                appContext.assets.open(filePath).use { it.readBytes() }
            } catch (e: IOException) {
                Log.e(TAG, "Không đọc được asset '$filePath': ${e.message}")
                ByteArray(0)
            }
        }
    }

    /**
     * Gọi TRÊN GL THREAD, bên trong onContextCreated() của scene.
     * An toàn khi gọi lại nhiều lần (ví dụ sau khi context bị mất và dựng lại) nhờ các cờ
     * isStarted()/isInitialized() nội bộ của CubismFramework.
     */
    fun initializeOnGlThread(context: Context) {
        if (!CubismFramework.isStarted()) {
            val option = CubismFramework.Option().apply {
                logFunction = LogFn()
                loggingLevel = LogLevel.VERBOSE
                loadFileFunction = FileFn(context.applicationContext)
            }
            val ok = CubismFramework.startUp(option)
            Log.d(TAG, "CubismFramework.startUp() -> $ok")
        }
        if (!CubismFramework.isInitialized()) {
            CubismFramework.initialize()
            Log.d(TAG, "CubismFramework.initialize() xong — SDK sẵn sàng nạp model (Phần 3.2)")
        }
    }

    /** Gọi TRÊN GL THREAD, bên trong onContextDestroyed() của scene, TRƯỚC khi context thật sự mất. */
    fun disposeOnGlThread() {
        if (CubismFramework.isInitialized()) {
            CubismFramework.dispose()
            Log.d(TAG, "CubismFramework.dispose() xong")
        }
    }
}

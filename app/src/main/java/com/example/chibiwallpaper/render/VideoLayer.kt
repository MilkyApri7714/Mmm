package com.example.chibiwallpaper.render

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * PHẦN 5 — Phát video clip (function calling "play_video") ngay trong context OpenGL dùng chung
 * với Cubism, qua SurfaceTexture (GL_TEXTURE_EXTERNAL_OES) + MediaPlayer — đúng như plan ban đầu
 * ("video phát on-demand qua MediaPlayer/SurfaceTexture chung 1 context OpenGL").
 *
 * Vòng đời:
 *  - [onContextCreated]: tạo OES texture + SurfaceTexture 1 lần (gắn với context hiện có).
 *  - [play]: tạo MediaPlayer MỚI cho mỗi clip, release ngay khi phát xong/lỗi để không giữ
 *    decoder chạy nền tốn pin (đúng tinh thần "tối ưu pin" của giai đoạn 8).
 *  - [draw]: gọi mỗi frame khi state == PLAYING_VIDEO; chỉ updateTexImage() khi có frame mới.
 *
 * [onContextCreated]/[onContextDestroyed]/[draw] BẮT BUỘC chạy trên GL thread ("ChibiGL") vì
 * đụng GLES20.*. [play]/[stop] có thể gọi từ thread khác (main thread, xem ChibiWallpaperService)
 * vì chỉ thao tác MediaPlayer/Surface — các field dùng chung giữa 2 thread được đánh dấu @Volatile.
 */
class VideoLayer(private val appContext: Context) {

    /** Gọi khi video phát xong tự nhiên hoặc gặp lỗi — luôn từ thread đã gọi [play] (thường main). */
    var onPlaybackFinished: (() -> Unit)? = null

    private var program = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uTextureLoc = 0
    private var uTexMatrixLoc = 0

    @Volatile private var oesTextureId = 0
    @Volatile private var surfaceTexture: SurfaceTexture? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var mediaPlayer: MediaPlayer? = null
    @Volatile private var frameAvailable = false

    private val texMatrix = FloatArray(16)

    private val vertexBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(4 * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, -1f, 1f, 1f, -1f, 1f, 1f)) // full-screen quad (NDC)
            position(0)
        }
    private val texCoordBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(4 * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f))
            position(0)
        }

    val isPlaying: Boolean get() = mediaPlayer != null

    /** GL thread. */
    fun onContextCreated() {
        program = buildProgram()
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")
        uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        oesTextureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // Listener chạy trên chính thread đã tạo SurfaceTexture (GL thread) — không cần đồng bộ thêm.
        surfaceTexture = SurfaceTexture(oesTextureId).apply {
            setOnFrameAvailableListener { frameAvailable = true }
        }
        surface = Surface(surfaceTexture)
    }

    /** GL thread. */
    fun onContextDestroyed() {
        stop()
        surface?.release(); surface = null
        surfaceTexture?.release(); surfaceTexture = null
        oesTextureId = 0
        program = 0
    }

    /**
     * Bắt đầu phát file [assetFileName] trong assets/videos/. Có thể gọi từ main thread.
     * Trả về false nếu file không mở được — caller (MultiModelScene/service) tự lo quay về ROAMING.
     */
    fun play(assetFileName: String): Boolean {
        stop() // đảm bảo player cũ (nếu có) được release trước khi phát cái mới
        val fd = try {
            appContext.assets.openFd("videos/$assetFileName")
        } catch (e: IOException) {
            Log.e(TAG, "Không mở được video 'videos/$assetFileName': ${e.message}")
            return false
        }
        val targetSurface = surface
        if (targetSurface == null) {
            Log.e(TAG, "Surface video chưa sẵn sàng (context chưa tạo?)")
            fd.close()
            return false
        }
        return try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                fd.close()
                setSurface(targetSurface)
                isLooping = false
                setOnPreparedListener { it.start() }
                setOnCompletionListener {
                    Log.d(TAG, "Video xong: $assetFileName")
                    stop()
                    onPlaybackFinished?.invoke()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer lỗi ($what, $extra) khi phát $assetFileName")
                    stop()
                    onPlaybackFinished?.invoke()
                    true
                }
                prepareAsync()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Không phát được video '$assetFileName': ${e.message}")
            mediaPlayer?.release(); mediaPlayer = null
            false
        }
    }

    /**
     * PHẦN 18 (mở rộng video UI) — Phát video từ URI do user tự chọn (SAF, `content://...`) thay
     * vì asset bundled trong app. [loop]=true dùng cho video ngắn làm NỀN LẶP LIÊN TỤC (vẽ ở
     * Layer 1, xem MultiModelScene.backgroundVideoLayer) — lúc đó KHÔNG bắn [onPlaybackFinished]
     * (MediaPlayer tự lặp lại, không có "kết thúc" thật). [loop]=false dùng cho video DÀI (function
     * calling "play_video") — y hệt hành vi của [play] (asset) trước đây.
     */
    fun play(uri: Uri, loop: Boolean = false): Boolean {
        stop()
        val targetSurface = surface
        if (targetSurface == null) {
            Log.e(TAG, "Surface video chưa sẵn sàng (context chưa tạo?)")
            return false
        }
        return try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(appContext, uri)
                setSurface(targetSurface)
                isLooping = loop
                setOnPreparedListener { it.start() }
                if (!loop) {
                    setOnCompletionListener {
                        Log.d(TAG, "Video xong: $uri")
                        stop()
                        onPlaybackFinished?.invoke()
                    }
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer lỗi ($what, $extra) khi phát '$uri'")
                    stop()
                    if (!loop) onPlaybackFinished?.invoke()
                    true
                }
                prepareAsync()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Không phát được video '$uri': ${e.message}")
            mediaPlayer?.release(); mediaPlayer = null
            false
        }
    }

    /** Dừng + release MediaPlayer hiện tại (nếu có). An toàn khi gọi lại nhiều lần. */
    fun stop() {
        mediaPlayer?.let {
            try {
                it.setOnCompletionListener(null)
                it.setOnErrorListener(null)
                it.reset()
                it.release()
            } catch (e: Exception) {
                Log.w(TAG, "stop(): ${e.message}")
            }
        }
        mediaPlayer = null
    }

    /** GL thread. Vẽ frame hiện tại phủ toàn màn hình — gọi trong onDrawFrame khi PLAYING_VIDEO. */
    fun draw(screenWidth: Int, screenHeight: Int) {
        val st = surfaceTexture ?: return
        if (mediaPlayer == null || program == 0) return

        if (frameAvailable) {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            frameAvailable = false
        }

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniform1i(uTextureLoc, 0)
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)

        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
    }

    private fun buildProgram(): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SRC)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SRC)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs); GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) Log.e(TAG, "Link video program lỗi: ${GLES20.glGetProgramInfoLog(p)}")
        GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) Log.e(TAG, "Compile video shader lỗi: ${GLES20.glGetShaderInfoLog(s)}")
        return s
    }

    companion object {
        private const val TAG = "ChibiVideo"

        private const val VERTEX_SRC = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        private const val FRAGMENT_SRC = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}

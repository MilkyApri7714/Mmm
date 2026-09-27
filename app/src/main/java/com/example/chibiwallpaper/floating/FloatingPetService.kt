package com.example.chibiwallpaper.floating

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.chibiwallpaper.R
import com.example.chibiwallpaper.ai.ActionRouter
import com.example.chibiwallpaper.ai.DoNotDisturbHelper
import com.example.chibiwallpaper.ai.GeminiClient
import com.example.chibiwallpaper.ai.GeminiResponse
import com.example.chibiwallpaper.ai.GeminiTtsHelper
import com.example.chibiwallpaper.ai.ProactiveManager
import com.example.chibiwallpaper.ai.RoutedAction
import com.example.chibiwallpaper.ai.SpeechToTextManager
import com.example.chibiwallpaper.ai.TtsHelper
import com.example.chibiwallpaper.render.ChatBubbleOverlay
import com.example.chibiwallpaper.render.GLRenderer
import com.example.chibiwallpaper.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * PHẦN 14 — Nhân vật nổi đè lên MỌI app khác.
 *
 * Luồng tương tác đầy đủ:
 *   Tap nhân vật → STT → Gemini → route qua ActionRouter → renderer phản ứng.
 *
 * --- Cập nhật: port đầy đủ tính năng từ ChibiWallpaperService ---
 *
 * 1. PHẦN 21 — Gemini TTS (chế độ trò chuyện): đọc to reply bằng GeminiTtsHelper sau mỗi câu
 *    trả lời text, giống hệt live wallpaper. Bật/tắt bằng KEY_CONVERSATION_MODE_ENABLED.
 *
 * 2. PHẦN 16 — Hội thoại song phương: TtsHelper + vòng lặp mic liên tục + dịch qua Gemini.
 *    Nhấn-giữ trong lúc TRANSLATING = tắt ngay. Câu lệnh thoại cũng tắt được.
 *    TextBoardOverlay dùng để hiện phụ đề dài; bấm X trên bảng cũng tắt hội thoại.
 *
 * 3. PHẦN 11 — ProactiveManager: đăng ký hook activeFloatingMessageHandler để ProactiveReceiver
 *    có thể "nói" trực tiếp qua bubble thay vì notification. scheduleAll() gọi khi onCreate.
 *    DND được kiểm tra trước khi hiện lời chủ động.
 *
 * 4. PHẦN 13 — Nhấn-giữ ghi âm: giữ yên LONG_PRESS_MS trúng nhân vật → STT bắt đầu;
 *    buông tay → ngắt ghi âm (thay double-tap cũ). Cũng xử lý stop bilingual khi nhấn-giữ
 *    trong lúc đang TRANSLATING.
 *
 * 5. Vuốt lên toggle AD/STRAW: vuốt lên đủ xa + ít lệch ngang → onSwipeUp() (renderer tự lo).
 *    Không xung đột với nhấn-giữ (swipeFired → hủy longPress).
 *
 * 6. PHẦN 12 — Quad-tap mở PhotoAskActivity: 4 tap liên tiếp trong TAP_WINDOW_MS → mở camera.
 *    Chỉ khi đang ROAMING (renderer không busy, không đang reply).
 *
 * 7. PHẦN 12 — activeFloatingVoiceTrigger: QuickListenTileService gọi tryTriggerFloatingVoice()
 *    để kích hoạt STT từ Quick Settings mà không cần chạm vào nhân vật.
 */
class FloatingPetService : Service() {

    private lateinit var windowManager: WindowManager
    private var petView: SurfaceView? = null
    private var renderer: FloatingPetRenderer? = null
    // BUG 2 (FIX) — Thay `GLSurfaceView` bằng [SurfaceView] thường + [GLRenderer] (EGL tự quản lý,
    // y hệt cách ChibiWallpaperService dùng cho Live Wallpaper — xem javadoc GLRenderer). Trước
    // đây mỗi lần tắt nhân vật nổi hoặc đổi model (removeOverlayView(); addOverlayView()) đều tạo
    // 1 GLSurfaceView MỚI, và nội bộ EglHelper.finish() của nó tự gọi eglTerminate(display) — KHÔNG
    // có API public nào chặn được — phá luôn context share chung với Wallpaper (đang dùng cùng
    // display qua CubismGlShare). GLRenderer tự quản lý EGL nên CHỈ eglTerminate() khi chắc chắn
    // không còn owner GL nào khác (Wallpaper lẫn Nhân vật nổi) — xem CubismGlShare.beforeDestroyContext().
    private var glRenderer: GLRenderer? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

    private var modelType: String = MODEL_SLIME

    // PHẦN 15.4 — Bảng clipboard cho reply dài
    private var textBoard: TextBoardOverlay? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var stt: SpeechToTextManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var gemini: GeminiClient
    private lateinit var actionRouter: ActionRouter

    // PHẦN 21 — Gemini TTS cho chế độ trò chuyện
    private lateinit var geminiTtsHelper: GeminiTtsHelper

    // PHẦN 16 — Hội thoại song phương
    private lateinit var ttsHelper: TtsHelper
    private var bilingualTargetLang: String = ""

    // PHẦN 15.5 — Revert timer (để skip bubble sớm khi single-tap)
    private var pendingRevertRunnable: Runnable? = null

    // PHẦN 22/25 — Nhớ lại vị trí + kích thước nổi (trước khi vào chế độ trả lời) để khôi phục
    // sau khi xong. Khi Milky chuyển sang Chibi để trả lời, cửa sổ nổi tự dời ra GIỮA MÀN HÌNH
    // (thay vì đứng yên ở góc mà user thả trước đó) VÀ PHÓNG TO (từ 130dp lên [REPLY_SIZE_DP]) —
    // cửa sổ nhỏ 130dp trước đây quá bé để thấy trọn nhân vật + bong bóng chat (bong bóng tự build
    // rộng tới 260dp — xem FloatingPetRenderer.buildBubbleBitmap — NHƯNG bị viewport 130dp cắt mất
    // phần lớn, chỉ thấy 1 góc chữ) — rồi tự dời + THU NHỎ VỀ ĐÚNG chỗ/kích thước cũ khi về
    // ROAMING (clearBubbleAndRevert).
    private var savedOverlayX = 0
    private var savedOverlayY = 0
    private var savedOverlayWidth = 0
    private var savedOverlayHeight = 0
    private var isCenteredForReply = false

    // PHẦN 16 — Theo dõi app foreground để ẩn/hiện
    private var foregroundWatcher: ForegroundAppWatcher? = null

    // PHẦN 13 — Trạng thái nhấn-giữ và vuốt
    private var downX = 0f
    private var downY = 0f
    private var movedBeyondSlop = false
    private var swipeUpFired = false
    private var isHoldRecording = false
    private var longPressRunnable: Runnable? = null

    // Đếm tap cho quad-tap
    private var tapCount = 0
    private var lastTapDownTime = 0L
    private var pendingTapRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        gemini = GeminiClient(applicationContext)
        actionRouter = ActionRouter(applicationContext)
        geminiTtsHelper = GeminiTtsHelper(applicationContext)
        ttsHelper = TtsHelper(applicationContext)
        mainHandler.post {
            ttsHelper.init()
            initStt()
        }
        ensureNotificationChannel()

        // PHẦN 11 — Đăng ký hook proactive + voice trigger
        activeFloatingMessageHandler = { text -> mainHandler.post { showProactiveBubble(text) } }
        activeFloatingVoiceTrigger   = { mainHandler.post { triggerVoiceFromExternal() } }
        ProactiveManager.scheduleAll(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }

        val newModelType = intent?.getStringExtra(EXTRA_MODEL_TYPE) ?: MODEL_SLIME
        if (petView == null || newModelType != modelType) {
            modelType = newModelType
            removeOverlayView()
            startForeground(NOTIFICATION_ID, buildNotification())
            addOverlayView()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        activeFloatingMessageHandler = null
        activeFloatingVoiceTrigger   = null
        scope.cancel()
        mainHandler.post {
            stt?.release()
            ttsHelper.release()
        }
        geminiTtsHelper.release()
        removeOverlayView()
        super.onDestroy()
    }

    // ── STT ─────────────────────────────────────────────────────────────────

    private fun initStt() {
        stt = SpeechToTextManager(
            context = applicationContext,
            onResult = { text ->
                Log.d(TAG, "STT result: $text")
                renderer?.setListeningState(false)

                // PHẦN 16 — Đang hội thoại song phương → rẽ sang luồng dịch
                if (bilingualTargetLang.isNotEmpty()) {
                    handleBilingualUtterance(text)
                    return@SpeechToTextManager
                }

                askGemini(text)
            },
            onError = { code ->
                Log.w(TAG, "STT error: $code")
                renderer?.setListeningState(false)

                // PHẦN 16 — Im lặng giữa lượt trong bilingual là bình thường → nghe lại ngay
                if (bilingualTargetLang.isNotEmpty()) {
                    mainHandler.post { stt?.startListening(SpeechToTextManager.BILINGUAL_SILENCE_TIMEOUT_MS) }
                    return@SpeechToTextManager
                }

                if (code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || code == SpeechRecognizer.ERROR_NO_MATCH) {
                    // PHẦN 26 (fix) — dời + phóng to cửa sổ trước khi hiện bubble, nếu không thoại
                    // này sẽ bị cắt mất vì cửa sổ vẫn còn bé xíu (130dp) lúc đang ROAMING.
                    centerOverlayForReply()
                    renderer?.showBubble("Milky không nghe rõ~ Thử lại nhé!")
                    scheduleRevert(3000L)
                }
            },
            onListeningStarted = { renderer?.setListeningState(true) },
            onListeningStopped  = { renderer?.setListeningState(false) }
        ).also { it.init() }
    }

    // ── Gemini ───────────────────────────────────────────────────────────────

    private fun askGemini(userText: String) {
        ProactiveManager.notifyInteraction(applicationContext) // PHẦN 11 — reset đồng hồ inactivity
        enterReplyPresentation()
        scope.launch {
            val response = gemini.sendMessage(userText)
            when (response) {
                is GeminiResponse.Text -> respondWithText(response.content)
                is GeminiResponse.FunctionCall -> handleFunctionCall(response)
                null -> {
                    renderer?.showBubble("Ủa mình bị mất mạng hay sao ấy~ Thử lại sau nhé!")
                    scheduleRevert(3500L)
                }
            }
        }
    }

    private fun handleFunctionCall(call: GeminiResponse.FunctionCall) {
        val action = actionRouter.route(call)
        when (action) {
            is RoutedAction.SpeakText -> respondWithText(action.text)

            is RoutedAction.ShowExpression -> {
                renderer?.showExpression(action.expressionName)
                scheduleRevert(EXPRESSION_DURATION_MS)
            }

            is RoutedAction.StartRoaming -> {
                exitReplyPresentation()
            }

            is RoutedAction.PlayVideo -> {
                // Video chỉ có ở live wallpaper — thông báo thay thế
                renderer?.showBubble("Video chỉ xem được ở hình nền động nha~")
                scheduleRevert(3500L)
            }

            // PHẦN 16 — Hội thoại song phương
            is RoutedAction.StartBilingualMode -> startBilingualMode(action.targetLanguage)
            is RoutedAction.StopBilingualMode  -> stopBilingualMode()

            // PHẦN 26 — "Hỏi bằng ảnh" qua giọng nói, không cần quad-tap nữa. Trả cửa sổ về đúng
            // chỗ/kích thước cũ trước khi mở activity (giống StartRoaming) rồi mới mở camera.
            is RoutedAction.OpenPhotoAsk -> {
                exitReplyPresentation()
                openPhotoAskActivity()
            }
        }
    }

    // ── Reply ────────────────────────────────────────────────────────────────

    private fun respondWithText(text: String) {
        cancelPendingRevert()
        if (ChatBubbleOverlay.isLongText(text)) {
            exitReplyPresentation()
            textBoard?.show(text) { /* đã về BASE từ đầu */ }
        } else {
            // centerOverlayForReply() tự bỏ qua nếu askGemini() đã dời cửa sổ ra giữa màn hình từ
            // bước showThinking() rồi — nhưng vẫn cần gọi ở đây cho đường đi KHÔNG qua askGemini
            // (lời chủ động — xem showProactiveBubble), nơi respondWithText() là bước đầu tiên.
            centerOverlayForReply()
            renderer?.showReply(text)
            scheduleRevert(revertDelay(text))
        }
        maybeSpeakConversation(text) // PHẦN 21
    }

    private fun revertDelay(text: String): Long = (text.length * 40L).coerceIn(3_000L, 12_000L)

    private fun scheduleRevert(delayMs: Long) {
        cancelPendingRevert()
        val r = Runnable { pendingRevertRunnable = null; exitReplyPresentation() }
        pendingRevertRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    private fun cancelPendingRevert() {
        pendingRevertRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingRevertRunnable = null
    }

    private fun skipReplyBubble() {
        cancelPendingRevert()
        exitReplyPresentation()
    }

    // ── PHẦN 21 — Chế độ trò chuyện (Gemini TTS) ────────────────────────────

    private fun maybeSpeakConversation(text: String) {
        val prefs = applicationContext.getSharedPreferences(
            MainActivity.PREFS_NAME, Context.MODE_PRIVATE
        )
        if (!prefs.getBoolean(MainActivity.KEY_CONVERSATION_MODE_ENABLED, false)) return
        geminiTtsHelper.speak(text) { /* không cần làm gì thêm */ }
    }

    // ── PHẦN 16 — Hội thoại song phương ──────────────────────────────────────

    private fun startBilingualMode(targetLanguage: String) {
        bilingualTargetLang = targetLanguage.ifBlank { DEFAULT_BILINGUAL_TARGET_LANG }
        Log.d(TAG, "Bật hội thoại song phương — target=$bilingualTargetLang")
        // PHẦN 26 (fix) — dời + phóng to cửa sổ TRƯỚC KHI hiện bubble, và giữ nguyên vậy suốt lúc
        // dịch (không revert) — mọi bubble phụ đề trong handleBilingualUtterance() dựa vào cửa sổ
        // đã ở trạng thái phóng to này. stopBilingualMode() sẽ trả cửa sổ về đúng chỗ/kích thước
        // cũ qua scheduleRevert() → exitReplyPresentation() → restoreOverlayPosition().
        centerOverlayForReply()
        renderer?.showBubble("Đang bật chế độ dịch~ Nói đi!")
        mainHandler.postDelayed({
            renderer?.clearBubbleAndRevert()
            mainHandler.post { stt?.startListening(SpeechToTextManager.BILINGUAL_SILENCE_TIMEOUT_MS) }
        }, 1500L)
    }

    private fun handleBilingualUtterance(text: String) {
        if (isBilingualStopPhrase(text)) {
            Log.d(TAG, "Nhận diện câu lệnh dừng bilingual: \"$text\"")
            stopBilingualMode()
            return
        }

        renderer?.showBubble("$text\n…")

        scope.launch {
            val result = gemini.translate(text, bilingualTargetLang)
            if (bilingualTargetLang.isEmpty()) return@launch // đã bị tắt giữa chừng

            if (result == null) {
                renderer?.showBubble("Dịch bị lỗi mạng, đang nghe tiếp~")
                mainHandler.post { stt?.startListening(SpeechToTextManager.BILINGUAL_SILENCE_TIMEOUT_MS) }
                return@launch
            }

            renderer?.showBubble("$text\n→ ${result.translatedText}")

            ttsHelper.speak(result.translatedText, result.targetLang) {
                mainHandler.post {
                    if (bilingualTargetLang.isNotEmpty()) {
                        stt?.startListening(SpeechToTextManager.BILINGUAL_SILENCE_TIMEOUT_MS)
                    }
                }
            }
        }
    }

    private fun isBilingualStopPhrase(text: String): Boolean {
        val n = text.trim().lowercase()
        return BILINGUAL_STOP_PHRASES.any { n.contains(it) }
    }

    private fun stopBilingualMode() {
        if (bilingualTargetLang.isEmpty()) return
        Log.d(TAG, "Tắt hội thoại song phương")
        mainHandler.post { stt?.stopListening() }
        ttsHelper.stop()
        geminiTtsHelper.stop()
        bilingualTargetLang = ""
        renderer?.showBubble("Đã tắt chế độ dịch~")
        scheduleRevert(2000L)
    }

    // ── PHẦN 11 — Proactive bubble ───────────────────────────────────────────

    private fun showProactiveBubble(text: String) {
        val r = renderer ?: return
        if (r.isBusy || r.isListening || r.isReplying) return // đang bận, bỏ qua
        if (bilingualTargetLang.isNotEmpty()) return          // đang dịch, bỏ qua
        if (DoNotDisturbHelper.isActive(applicationContext)) {
            Log.d(TAG, "DND đang bật — bỏ qua lời chủ động")
            return
        }
        respondWithText(text)
    }

    // ── PHẦN 12 — Trigger STT từ Quick Settings Tile ─────────────────────────

    private fun triggerVoiceFromExternal() {
        val r = renderer ?: return
        if (r.isTransitioning) return
        if (bilingualTargetLang.isNotEmpty()) return
        when {
            r.isListening -> mainHandler.post { stt?.stopListening() }
            r.isBusy      -> { /* Gemini đang xử lý, bỏ qua */ }
            else -> startVoiceFlow()
        }
    }

    // ── Overlay window ───────────────────────────────────────────────────────

    private fun addOverlayView() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (textBoard == null) textBoard = TextBoardOverlay(this, windowManager)
        val sizePx = (PET_SIZE_DP * resources.displayMetrics.density).toInt()

        layoutParams = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = resources.displayMetrics.heightPixels / 3
        }

        val newRenderer = FloatingPetRenderer(applicationContext, modelType)
        renderer = newRenderer

        // BUG 2 (FIX) — GLRenderer (EGL tự quản lý, dùng chung [CubismGlShare] với Wallpaper) thay
        // cho GLSurfaceView; nền trong suốt (clearColor alpha=0) để thấy app phía dưới, y hệt
        // GLES20.glClearColor(0f,0f,0f,0f) cũ trong FloatingPetRenderer.onSurfaceCreated().
        val newGlRenderer = GLRenderer(
            scene = newRenderer,
            targetFps = 30,
            clearColor = floatArrayOf(0f, 0f, 0f, 0f)
        )
        glRenderer = newGlRenderer

        val view = SurfaceView(this).apply {
            holder.setFormat(PixelFormat.TRANSLUCENT)
            setZOrderOnTop(true)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    newGlRenderer.surfaceCreated(holder.surface)
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                    newGlRenderer.surfaceChanged(w, h)
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    newGlRenderer.surfaceDestroyed()
                }
            })
        }
        attachTouchHandler(view)
        petView = view

        try {
            windowManager.addView(view, layoutParams)
            // Không có callback onVisibilityChanged() như WallpaperService.Engine ở đây — phải tự
            // báo GLRenderer "đang hiển thị" để nó bắt đầu vòng lặp vẽ (xem GLRenderer.setVisible).
            newGlRenderer.setVisible(true)
            startForegroundWatcherIfPermitted()
        } catch (e: Exception) { Log.e(TAG, "addView lỗi: ${e.message}"); stopSelf() }
    }

    private fun removeOverlayView() {
        foregroundWatcher?.stop()
        foregroundWatcher = null
        petView?.let { v ->
            try { windowManager.removeView(v) } catch (e: Exception) { Log.w(TAG, "removeView: ${e.message}") }
        }
        // BUG 2 (FIX) — Gọi tường minh thay vì dựa vào SurfaceHolder.Callback.surfaceDestroyed()
        // (removeView() không đảm bảo luôn kích hoạt nó kịp lúc): surfaceDestroyed() chỉ huỷ
        // EGLSurface (an toàn gọi lại nhiều lần); release() mới thật sự huỷ EGLContext — và CHỈ
        // eglTerminate() display dùng chung nếu mình là owner GL CUỐI CÙNG của tiến trình (xem
        // GLRenderer.teardownEgl() + CubismGlShare) — không còn phá context của Wallpaper nữa.
        glRenderer?.surfaceDestroyed()
        glRenderer?.release()
        glRenderer = null
        petView = null
        renderer = null
        textBoard?.dismiss()
    }

    private fun startForegroundWatcherIfPermitted() {
        if (!ForegroundAppWatcher.hasUsageAccess(applicationContext)) return
        foregroundWatcher = ForegroundAppWatcher(applicationContext) { shouldShow ->
            mainHandler.post { applyForegroundVisibility(shouldShow) }
        }.also { it.start() }
    }

    // PHẦN 22/25 — Dời + phóng to cửa sổ nổi ra giữa màn hình lúc bắt đầu trả lời (Chibi), và trả
    // lại đúng vị trí + kích thước cũ lúc về ROAMING. Bọc quanh renderer.showThinking()/
    // clearBubbleAndRevert() thay vì gọi thẳng, để KHÔNG có chỗ nào quên dời/khôi phục (xem các
    // call site đã đổi bên dưới).
    /** Chỉ phần dời + phóng to cửa sổ — KHÔNG đụng renderer. Gọi lại nhiều lần vô hại (tự bỏ qua nếu đã ở giữa). */
    private fun centerOverlayForReply() {
        if (isCenteredForReply) return
        val lp = layoutParams
        savedOverlayX = lp.x
        savedOverlayY = lp.y
        // PHẦN 25 — Lưu luôn kích thước GỐC (130dp) để khôi phục đúng — trước đây chỉ lưu x/y,
        // cửa sổ vẫn giữ nguyên 130dp bé xíu suốt lúc trả lời nên bong bóng chat (build rộng tới
        // 260dp — xem FloatingPetRenderer.buildBubbleBitmap) bị viewport cắt mất, chỉ thấy 1 góc.
        savedOverlayWidth = lp.width
        savedOverlayHeight = lp.height
        val dp = resources.displayMetrics.density
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val replyW = (REPLY_SIZE_W_DP * dp).toInt()
        val replyH = (REPLY_SIZE_H_DP * dp).toInt()
        lp.width = replyW
        lp.height = replyH
        lp.x = (screenW - replyW) / 2
        lp.y = (screenH - replyH) / 2
        isCenteredForReply = true
        petView?.let { v ->
            try { windowManager.updateViewLayout(v, lp) } catch (e: Exception) {
                Log.w(TAG, "updateViewLayout(center): ${e.message}")
            }
        }
    }

    /** Chỉ phần trả cửa sổ về vị trí + kích thước cũ — KHÔNG đụng renderer. Vô hại nếu chưa từng dời. */
    private fun restoreOverlayPosition() {
        if (!isCenteredForReply) return
        val lp = layoutParams
        lp.x = savedOverlayX
        lp.y = savedOverlayY
        lp.width = savedOverlayWidth
        lp.height = savedOverlayHeight
        isCenteredForReply = false
        petView?.let { v ->
            try { windowManager.updateViewLayout(v, lp) } catch (e: Exception) {
                Log.w(TAG, "updateViewLayout(restore): ${e.message}")
            }
        }
    }

    private fun enterReplyPresentation() {
        renderer?.showThinking()
        centerOverlayForReply()
    }

    private fun exitReplyPresentation() {
        renderer?.clearBubbleAndRevert()
        restoreOverlayPosition()
    }

    private fun applyForegroundVisibility(shouldShow: Boolean) {
        val view = petView ?: return
        // BUG 2 (FIX) — glRenderer.setVisible() thay cho view.onPause()/onResume() của
        // GLSurfaceView: chỉ dừng/khởi động lại vòng lặp vẽ (Choreographer), KHÔNG đụng tới
        // EGLContext — đúng tinh thần "preserveEGLContextOnPause=true" cũ, không tạo/huỷ context
        // lặp lại mỗi lần đổi app foreground.
        if (shouldShow) {
            if (view.visibility != View.VISIBLE) { view.visibility = View.VISIBLE; glRenderer?.setVisible(true) }
        } else {
            if (view.visibility == View.VISIBLE) { view.visibility = View.GONE; glRenderer?.setVisible(false) }
        }
    }

    // ── Touch: nhấn-giữ / vuốt lên / tap đơn / quad-tap ────────────────────

    /**
     * PHẦN 13 — Thay thế double-tap bằng bộ gesture đầy đủ:
     *   - Nhấn-giữ LONG_PRESS_MS trúng nhân vật → STT (buông = ngắt)
     *   - Vuốt lên đủ xa → toggle AD/STRAW
     *   - Single-tap xác nhận → nudge / skip bubble đang nói
     *   - Quad-tap (4 lần trong TAP_WINDOW_MS) → mở PhotoAskActivity (PHẦN 12)
     *
     * GestureDetector xử lý single-tap-confirmed + double-tap trên renderer nhưng chúng ta cần
     * long-press và swipe nên tự xử lý ACTION_DOWN/MOVE/UP, bỏ GestureDetector cũ.
     */
    private fun attachTouchHandler(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN  -> handleDown(event)
                MotionEvent.ACTION_MOVE  -> handleMove(event)
                MotionEvent.ACTION_UP    -> handleUp(event)
                MotionEvent.ACTION_CANCEL -> handleCancel()
            }
            true
        }
    }

    private var startWinX = 0
    private var startWinY = 0
    private var startTouchX = 0f
    private var startTouchY = 0f
    private var dragMoved = false

    private fun handleDown(event: MotionEvent) {
        // Drag state
        startWinX   = layoutParams.x; startWinY   = layoutParams.y
        startTouchX = event.rawX;     startTouchY = event.rawY
        dragMoved   = false

        // Long-press / swipe state
        downX = event.x; downY = event.y
        movedBeyondSlop = false; swipeUpFired = false; isHoldRecording = false

        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
        val cx = downX; val cy = downY
        val r = Runnable {
            longPressRunnable = null
            // FloatingPetRenderer không có wakeIfHit — nhấn-giữ bất kỳ chỗ nào cũng kích hoạt
            startHoldRecording()
        }
        longPressRunnable = r
        mainHandler.postDelayed(r, LONG_PRESS_MS)
    }

    private fun handleMove(event: MotionEvent) {
        // Drag
        val dx = (event.rawX - startTouchX).toInt()
        val dy = (event.rawY - startTouchY).toInt()
        if (!dragMoved && (abs(dx) > TAP_SLOP_PX || abs(dy) > TAP_SLOP_PX)) dragMoved = true
        if (dragMoved) {
            layoutParams.x = startWinX + dx; layoutParams.y = startWinY + dy
            runCatching { windowManager.updateViewLayout(petView ?: return, layoutParams) }
        }

        if (isHoldRecording) return

        val lx = event.x - downX; val ly = event.y - downY
        if (!movedBeyondSlop && (abs(lx) > TOUCH_SLOP_PX || abs(ly) > TOUCH_SLOP_PX)) {
            movedBeyondSlop = true
            longPressRunnable?.let { mainHandler.removeCallbacks(it) }
            longPressRunnable = null
        }

        // PHẦN 5 — Vuốt lên
        if (!swipeUpFired && ly <= -SWIPE_UP_MIN_PX && abs(lx) <= SWIPE_UP_MAX_DRIFT_PX) {
            swipeUpFired = true
            Log.d(TAG, "Vuốt lên tại (${downX.toInt()}, ${downY.toInt()})")
            renderer?.onSwipeUp() // toggle AD/STRAW trên Slime, y hệt MultiModelScene (Phần 13)
        }
    }

    private fun handleUp(event: MotionEvent) {
        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
        longPressRunnable = null

        if (isHoldRecording) { stopHoldRecording(); return }
        if (swipeUpFired || dragMoved) return

        registerShortTap()
    }

    private fun handleCancel() {
        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
        longPressRunnable = null
        if (isHoldRecording) stopHoldRecording()
        dragMoved = false
    }

    // ── Nhấn-giữ ghi âm ─────────────────────────────────────────────────────

    private fun startHoldRecording() {
        val r = renderer ?: return
        if (r.isTransitioning) return

        // PHẦN 16 — Nhấn-giữ trong lúc dịch = tắt bilingual
        if (bilingualTargetLang.isNotEmpty()) {
            Log.d(TAG, "Nhấn-giữ trong TRANSLATING — tắt hội thoại song phương")
            stopBilingualMode()
            return
        }

        if (r.isBusy) return // Gemini đang xử lý, bỏ qua

        isHoldRecording = true
        Log.d(TAG, "Nhấn-giữ — bắt đầu ghi âm")
        startVoiceFlow()
    }

    private fun stopHoldRecording() {
        isHoldRecording = false
        mainHandler.post { stt?.stopListening() }
        Log.d(TAG, "Buông tay — ngắt ghi âm")
    }

    // ── Tap đếm (single / quad) ──────────────────────────────────────────────

    private fun registerShortTap() {
        val now = System.currentTimeMillis()
        if (now - lastTapDownTime <= TAP_WINDOW_MS) tapCount++ else tapCount = 1
        lastTapDownTime = now

        pendingTapRunnable?.let { mainHandler.removeCallbacks(it) }
        val count = tapCount
        val action = Runnable {
            pendingTapRunnable = null
            tapCount = 0
            when {
                count == 1  -> onSingleTap()
                count >= 4  -> onQuadTap()
                // 2-3 tap: không dùng
            }
        }
        pendingTapRunnable = action
        mainHandler.postDelayed(action, TAP_WINDOW_MS)
    }

    private fun onSingleTap() {
        val r = renderer ?: return
        if (r.isReplying && pendingRevertRunnable != null) {
            skipReplyBubble()
        } else {
            r.onTap() // nudge random motion
        }
    }

    /** PHẦN 12 — 4 tap liên tiếp → mở PhotoAskActivity (multimodal ảnh) */
    private fun onQuadTap() {
        val r = renderer ?: return
        if (r.isBusy || r.isListening || r.isReplying || r.isTransitioning) return
        if (bilingualTargetLang.isNotEmpty()) return
        Log.d(TAG, "Quad-tap — mở PhotoAskActivity")
        openPhotoAskActivity()
    }

    /** PHẦN 26 — Tách riêng để dùng chung giữa quad-tap (PHẦN 12) và function call open_photo_ask. */
    private fun openPhotoAskActivity() {
        try {
            val intent = Intent(applicationContext,
                com.example.chibiwallpaper.ui.PhotoAskActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            applicationContext.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Không mở được PhotoAskActivity: ${e.message}")
        }
    }

    // ── Voice flow ───────────────────────────────────────────────────────────

    private fun startVoiceFlow() {
        val r = renderer ?: return
        if (r.isListening) { mainHandler.post { stt?.stopListening() }; return }

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            vibrateOnce()
            mainHandler.post { stt?.startListening() }
        } else {
            // PHẦN 26 (fix) — cùng lý do: dời + phóng to trước khi hiện bubble cảnh báo thiếu quyền.
            centerOverlayForReply()
            r.showBubble("Cần quyền micro~ Vào app cấp nhé!")
            scheduleRevert(3000L)
        }
    }

    private fun vibrateOnce() {
        val vibrator = getSystemService(Vibrator::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(25L, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION") vibrator.vibrate(25L)
        }
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ch = NotificationChannel(CHANNEL_ID, "Nhân vật nổi", NotificationManager.IMPORTANCE_MIN)
            .apply { description = "Giữ nhân vật nổi đè lên app khác"; setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): android.app.Notification {
        val label = when (modelType) {
            MODEL_CHIBI -> "Milky-chibi"; MODEL_FULL -> "Milky-full"; else -> "Milky-slime"
        }
        val openIntent = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(this, 0,
            Intent(this, FloatingPetService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("$label đang nổi")
            .setContentText("Giữ để nói, 4 tap để hỏi bằng ảnh~")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Tắt", stopIntent)
            .build()
    }

    companion object {
        private const val TAG             = "FloatingPetService"
        private const val CHANNEL_ID      = "floating_pet_channel"
        private const val NOTIFICATION_ID = 1042
        private const val PET_SIZE_DP     = 130
        // PHẦN 25 — Kích thước cửa sổ nổi lúc Chibi TRẢ LỜI (giữa màn hình) — đủ rộng để bong
        // bóng chat (build tới 260dp — xem FloatingPetRenderer.buildBubbleBitmap) không bị cắt,
        // và đủ cao để thấy trọn nhân vật bên dưới bong bóng.
        private const val REPLY_SIZE_W_DP = 300
        private const val REPLY_SIZE_H_DP = 280

        // Touch constants (PHẦN 13)
        private const val LONG_PRESS_MS        = 320L
        private const val TOUCH_SLOP_PX        = 24f
        private const val SWIPE_UP_MIN_PX      = 80f
        private const val SWIPE_UP_MAX_DRIFT_PX = 60f
        private const val TAP_SLOP_PX          = 12
        private const val TAP_WINDOW_MS        = 380L

        private const val EXPRESSION_DURATION_MS = 4000L

        // PHẦN 16
        private const val DEFAULT_BILINGUAL_TARGET_LANG = "en"
        private val BILINGUAL_STOP_PHRASES = listOf(
            "tắt chế độ dịch", "tắt hội thoại song phương", "tắt phiên dịch",
            "dừng hội thoại song phương", "dừng phiên dịch", "dừng dịch",
            "ngừng dịch", "ngừng phiên dịch", "thoát chế độ dịch"
        )

        // PHẦN 11 — Hook cho ProactiveReceiver và QuickSettingsTile
        @Volatile var activeFloatingMessageHandler: ((String) -> Unit)? = null
        @Volatile var activeFloatingVoiceTrigger: (() -> Unit)? = null

        /** Gọi từ ProactiveReceiver để hiện lời chủ động qua floating bubble. */
        fun tryShowFloatingProactiveMessage(text: String): Boolean {
            val h = activeFloatingMessageHandler ?: return false
            h(text); return true
        }

        /** PHẦN 12 — Gọi từ QuickListenTileService để kích hoạt STT qua floating. */
        fun tryTriggerFloatingVoice(): Boolean {
            val t = activeFloatingVoiceTrigger ?: return false
            t(); return true
        }

        fun hasOverlayPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun start(context: Context, modelType: String = MODEL_SLIME) {
            context.startForegroundService(
                Intent(context, FloatingPetService::class.java)
                    .putExtra(EXTRA_MODEL_TYPE, modelType)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, FloatingPetService::class.java).setAction(ACTION_STOP)
            )
        }

        const val ACTION_STOP      = "com.example.chibiwallpaper.floating.STOP"
        const val EXTRA_MODEL_TYPE = "floating_model_type"
        const val MODEL_SLIME      = "slime"
        const val MODEL_CHIBI      = "chibi"
        const val MODEL_FULL       = "full"
    }
}

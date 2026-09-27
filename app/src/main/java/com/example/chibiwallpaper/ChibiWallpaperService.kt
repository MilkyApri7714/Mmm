package com.example.chibiwallpaper

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.example.chibiwallpaper.ai.ActionRouter
import com.example.chibiwallpaper.ai.DoNotDisturbHelper
import com.example.chibiwallpaper.ai.GeminiClient
import com.example.chibiwallpaper.ai.GeminiResponse
import com.example.chibiwallpaper.ai.GeminiTtsHelper
import com.example.chibiwallpaper.ai.ProactiveManager
import com.example.chibiwallpaper.ai.RoutedAction
import com.example.chibiwallpaper.ai.SpeechToTextManager
import com.example.chibiwallpaper.ai.TtsHelper
import com.example.chibiwallpaper.character.CharacterStateMachine
import com.example.chibiwallpaper.render.ChatBubbleOverlay
import com.example.chibiwallpaper.render.GLRenderer
import com.example.chibiwallpaper.render.MultiModelScene
import com.example.chibiwallpaper.ui.PhotoAskActivity
import com.example.chibiwallpaper.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * PHẦN 4 — WallpaperService tích hợp: double-tap → STT → Gemini → route → scene.
 *
 * PHẦN 5 — Thêm:
 *   - Nối [MultiModelScene.onBoardClosedByUser]: user bấm X trên bảng clipboard → về ROAMING.
 *   - Nối [MultiModelScene.onVideoFinished]: video phát xong thật (MediaPlayer) → về ROAMING,
 *     thay cho timer giả 10s tạm thời ở Phần 4 (vẫn giữ 1 fallback timer để tự hồi phục nếu
 *     asset video lỗi/thiếu và không bao giờ bắn callback).
 *   - [scheduleAutoRoaming] dùng thời gian tự-đóng dài hơn khi đang hiện bảng clipboard (text dài),
 *     vì người dùng cần thời gian đọc — đóng sớm hơn nếu họ tự bấm X.
 *   - Double-tap bị bỏ qua trong lúc bảng clipboard đang hiện (tránh xung đột thao tác).
 *
 * PHẦN 13 — Đổi bộ cử chỉ: ghi âm giờ dùng nhấn-giữ trúng Slime (buông tay = ngắt ghi âm) thay
 * double-tap; đánh thức Slime chỉ cần 1 tap; AD.exp3.json/STRAW.motion3.json gộp lại thành 1
 * cử chỉ vuốt lên trúng Slime, chọn ngẫu nhiên 1 trong 2, vuốt lần 1 bật/lần 2 tắt.
 * Xem [handleActionDown]/[handleActionMove]/[handleActionUp] trong [ChibiEngine].
 *
 * Mọi lệnh chuyển state đều phát ra từ main thread (nhấn-giữ/vuốt/tap, STT callback, Gemini coroutine
 * trên Dispatchers.Main, và các callback từ scene bên dưới đều được post qua [mainHandler] để
 * tránh gọi lại CharacterStateMachine.transition() một cách "chồng" lên lệnh gọi đang xử lý).
 */
class ChibiWallpaperService : WallpaperService() {

    // ── PHẦN 11 — Hook cho ProactiveReceiver "nói chuyện" trực tiếp qua bong bóng chat khi
    // wallpaper đang hiển thị, thay vì luôn phải bắn notification. ────────────────────────
    companion object {
        private const val TAG = "ChibiWallpaper"
        private const val TAP_WINDOW_MS = 350L               // cửa sổ để đếm tap liên tiếp (single/quad)
        private const val TALKING_AUTO_DISMISS_MS = 8_000L
        private const val BOARD_AUTO_DISMISS_MS = 45_000L
        private const val VIDEO_FALLBACK_MS = 15_000L

        // PHẦN 13 — Nhấn-giữ để ghi âm + vuốt lên để bật/tắt AD/STRAW.
        private const val LONG_PRESS_MS = 320L                // giữ yên bao lâu thì tính là "nhấn giữ" bắt đầu ghi âm
        private const val TOUCH_SLOP_PX = 24f                 // di chuyển quá ngưỡng này → huỷ chờ nhấn-giữ
        private const val SWIPE_UP_MIN_DISTANCE_PX = 80f      // đi lên tối thiểu bấy nhiêu px thì tính là vuốt lên
        private const val SWIPE_UP_MAX_DRIFT_PX = 60f         // lệch ngang tối đa cho phép khi vuốt lên

        // PHẦN 16 — Hội thoại song phương.
        private const val DEFAULT_BILINGUAL_TARGET_LANG = "en"
        private val BILINGUAL_STOP_PHRASES = listOf(
            "tắt chế độ dịch", "tắt hội thoại song phương", "tắt phiên dịch",
            "dừng hội thoại song phương", "dừng phiên dịch", "dừng dịch",
            "ngừng dịch", "ngừng phiên dịch", "thoát chế độ dịch"
        )

        @Volatile private var activeMessageHandler: ((String) -> Unit)? = null

        // PHẦN 12 — Hook cho QuickListenTileService kích hoạt STT từ Quick Settings, không cần
        // double-tap đúng vào Slime trên màn hình chính. ─────────────────────────────────────
        @Volatile private var activeVoiceTrigger: (() -> Unit)? = null

        // PHẦN 18 — Hot-reload ảnh/video nền ngay khi user vừa chọn xong ở MainActivity, không
        // cần restart wallpaper. null nếu chưa có engine nào active.
        @Volatile private var activeBackgroundImageReloader: (() -> Unit)? = null
        @Volatile private var activeBackgroundVideoReloader: (() -> Unit)? = null

        /** @return true nếu có engine đang active và đã hot-reload; false → không có wallpaper active. */
        fun tryReloadBackground(): Boolean {
            val reload = activeBackgroundImageReloader ?: return false
            reload(); return true
        }

        /** Tương tự [tryReloadBackground] nhưng cho video ngắn dùng làm nền lặp. */
        fun tryReloadBackgroundVideo(): Boolean {
            val reload = activeBackgroundVideoReloader ?: return false
            reload(); return true
        }

        /** @return true nếu có engine đang active và đã nhận xử lý; false → caller nên fallback notification. */
        fun tryShowProactiveMessage(text: String): Boolean {
            val handler = activeMessageHandler ?: return false
            handler(text)
            return true
        }

        /** @return true nếu có engine đang active và đã kích hoạt STT; false → caller nên báo user đặt wallpaper trước. */
        fun tryTriggerVoiceInput(): Boolean {
            val trigger = activeVoiceTrigger ?: return false
            trigger()
            return true
        }
    }

    override fun onCreateEngine(): Engine = ChibiEngine()

    inner class ChibiEngine : Engine() {

        // ── GL Renderer ───────────────────────────────────────────────────────
        private val scene = MultiModelScene(applicationContext)
        private val renderer = GLRenderer(scene = scene, targetFps = 30)

        // ── AI Stack ──────────────────────────────────────────────────────────
        private val stateMachine = CharacterStateMachine()
        private val geminiClient = GeminiClient(applicationContext)
        private val actionRouter = ActionRouter(applicationContext)
        private lateinit var sttManager: SpeechToTextManager
        private lateinit var ttsHelper: TtsHelper // PHẦN 16
        private lateinit var geminiTtsHelper: GeminiTtsHelper // PHẦN 21 — chế độ trò chuyện

        // PHẦN 16 — Ngôn ngữ đích hiện tại của phiên hội thoại song phương (rỗng khi chưa bật).
        private var bilingualTargetLang: String = ""

        // ── Coroutines ────────────────────────────────────────────────────────
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private var talkingDismissJob: Job? = null
        private var videoFallbackJob: Job? = null

        // ── Tap detection (1 / 4 taps — 2/3 taps không còn dùng, xem PHẦN 13) ───
        private val mainHandler = Handler(Looper.getMainLooper())
        private var tapCount = 0
        private var lastTapDownTime = 0L
        private var tapX = 0f
        private var tapY = 0f
        private var pendingTapRunnable: Runnable? = null

        // ── PHẦN 13 — Nhấn-giữ để ghi âm + vuốt lên để bật/tắt AD/STRAW ─────────
        private var downX = 0f
        private var downY = 0f
        private var movedBeyondSlop = false
        private var swipeUpFired = false
        private var isHoldRecording = false
        private var longPressRunnable: Runnable? = null

        // ── Vòng đời ──────────────────────────────────────────────────────────

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)

            // STT phải khởi tạo trên main thread
            sttManager = SpeechToTextManager(
                context = applicationContext,
                onResult = ::onSpeechResult,
                onError = ::onSpeechError,
                onListeningStarted = { log("STT bắt đầu nghe") },
                onListeningStopped = { log("STT dừng nghe") }
            )
            sttManager.init()

            // PHẦN 16 — TTS phải khởi tạo trên main thread, giống STT.
            ttsHelper = TtsHelper(applicationContext)
            ttsHelper.init()

            // PHẦN 21 — Gemini TTS cho "chế độ trò chuyện" (không cần init() vì mỗi lần speak()
            // tự mở network call riêng, xem GeminiTtsHelper).
            geminiTtsHelper = GeminiTtsHelper(applicationContext)

            // State machine listener — cập nhật scene mỗi khi state đổi
            stateMachine.onStateChanged = { _, to ->
                scene.applyState(stateMachine)
            }

            // PHẦN 5 — User bấm X trên bảng clipboard → post qua mainHandler để tránh gọi
            // transition() ngay trong callback đến từ GL thread.
            scene.onBoardClosedByUser = {
                mainHandler.post {
                    talkingDismissJob?.cancel()
                    if (stateMachine.state == CharacterStateMachine.State.TALKING) {
                        stateMachine.backToRoaming()
                        scene.applyState(stateMachine)
                    } else if (stateMachine.state == CharacterStateMachine.State.TRANSLATING) {
                        // PHẦN 16 — Bấm X trên bảng phụ đề dài cũng tắt hội thoại song phương,
                        // giống hệt nhấn-giữ trúng Slime.
                        stopBilingualMode()
                    }
                }
            }

            // PHẦN 5 — Video phát xong thật → quay về ROAMING (thay timer giả của Phần 4).
            scene.onVideoFinished = {
                mainHandler.post {
                    videoFallbackJob?.cancel()
                    if (stateMachine.state == CharacterStateMachine.State.PLAYING_VIDEO) {
                        stateMachine.backToRoaming()
                        scene.applyState(stateMachine)
                    }
                }
            }

            // PHẦN 11 — Đăng ký hook để ProactiveReceiver có thể "nói" trực tiếp qua bong bóng
            // chat của engine đang active, và (re)đặt lịch chủ động (morning/weather/inactivity).
            activeMessageHandler = { text -> mainHandler.post { showProactiveBubble(text) } }
            // PHẦN 12 — Hook cho QuickListenTileService.
            activeVoiceTrigger = { mainHandler.post { triggerVoiceFromExternal() } }
            // PHẦN 18 — Hot-reload ảnh/video nền: đụng OverlayTextureRenderer/VideoLayer nên phải
            // chạy trên GL thread (renderer.runOnGlThread), scene tự đọc lại prefs.
            activeBackgroundImageReloader = {
                renderer.runOnGlThread {
                    scene.reloadBackground(applicationContext.getSharedPreferences(MainActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE))
                }
            }
            activeBackgroundVideoReloader = {
                renderer.runOnGlThread {
                    scene.reloadBackgroundVideo(applicationContext.getSharedPreferences(MainActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE))
                }
            }
            ProactiveManager.scheduleAll(applicationContext)

            log("Engine onCreate")
        }

        /** PHẦN 11 — Hiện lời chủ động của Milky y như một câu TALKING bình thường. */
        private fun showProactiveBubble(text: String) {
            if (stateMachine.state != CharacterStateMachine.State.ROAMING) return // đang bận, bỏ qua
            // PHẦN 12 — "Không làm phiền": chặn TẤT CẢ lời chủ động (proactive alarm, tóm tắt
            // thông báo mới...) cho tới khi hết giờ DND, dù caller nào gọi tới đây.
            if (DoNotDisturbHelper.isActive(applicationContext)) {
                log("Đang bật Không làm phiền — bỏ qua lời chủ động: \"$text\"")
                return
            }
            stateMachine.startTalking(text)
            scene.applyState(stateMachine)
            scheduleAutoRoaming()
        }

        /**
         * PHẦN 12 — Kích hoạt STT từ bên ngoài (Quick Settings Tile), không có toạ độ chạm thật
         * nên không hit-test Slime, chỉ đánh thức vô điều kiện rồi vào STT như double-tap thường.
         */
        private fun triggerVoiceFromExternal() {
            if (scene.isTransitioning()) return
            when (stateMachine.state) {
                CharacterStateMachine.State.ROAMING -> {
                    renderer.wakeSlime()
                    startVoiceInput()
                }
                CharacterStateMachine.State.TALKING -> {
                    if (!scene.isBoardShowing()) {
                        talkingDismissJob?.cancel()
                        stateMachine.continueTalking()
                        startVoiceInput()
                    }
                }
                else -> { /* đang bận (LISTENING/THINKING/EXPRESSING/PLAYING_VIDEO) → bỏ qua */ }
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            renderer.surfaceCreated(holder.surface)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            renderer.surfaceChanged(width, height)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            renderer.setVisible(visible)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            renderer.surfaceDestroyed()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            log("Engine onDestroy")
            activeMessageHandler = null
            activeVoiceTrigger = null
            activeBackgroundImageReloader = null
            activeBackgroundVideoReloader = null
            scope.cancel()
            mainHandler.post { sttManager.release() }
            mainHandler.post { ttsHelper.release() } // PHẦN 16
            geminiTtsHelper.release() // PHẦN 21
            renderer.release()
            super.onDestroy()
        }

        // ── Touch: nhấn-giữ ghi âm / vuốt lên toggle AD-STRAW / tap đơn-tứ ──────

        override fun onTouchEvent(event: MotionEvent) {
            super.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> handleActionDown(event)
                MotionEvent.ACTION_MOVE -> handleActionMove(event)
                MotionEvent.ACTION_UP   -> handleActionUp(event)
                MotionEvent.ACTION_CANCEL -> handleActionCancel()
            }
        }

        /**
         * PHẦN 13 — Bắt đầu 1 lượt chạm: hẹn giờ [LONG_PRESS_MS]; nếu ngón tay còn ở yên trên
         * Slime tới lúc đó → bắt đầu ghi âm (nhấn-giữ). [handleActionMove]/[handleActionUp] sẽ
         * huỷ hẹn giờ này nếu ngón tay di chuyển nhiều (vuốt) hoặc nhấc lên sớm (tap ngắn).
         */
        private fun handleActionDown(event: MotionEvent) {
            downX = event.x
            downY = event.y
            movedBeyondSlop = false
            swipeUpFired = false
            isHoldRecording = false

            longPressRunnable?.let { mainHandler.removeCallbacks(it) }
            val capturedX = downX
            val capturedY = downY
            val runnable = Runnable {
                longPressRunnable = null
                renderer.wakeIfHit(capturedX, capturedY) { hitSlime ->
                    if (hitSlime) startHoldRecording()
                }
            }
            longPressRunnable = runnable
            mainHandler.postDelayed(runnable, LONG_PRESS_MS)
        }

        /**
         * PHẦN 13 — Trong lúc ngón tay còn chạm: nếu đã đi lên đủ xa (ít lệch ngang) → coi là
         * vuốt lên, kích hoạt toggle AD/STRAW ngay (không đợi nhấc tay) và huỷ hẹn nhấn-giữ.
         * Nếu chỉ di chuyển nhẹ thì huỷ hẹn nhấn-giữ (không còn là "giữ yên") nhưng chưa tính là vuốt.
         */
        private fun handleActionMove(event: MotionEvent) {
            if (isHoldRecording) return // đang ghi âm rồi thì bỏ qua move, chỉ chờ nhấc tay
            val dx = event.x - downX
            val dy = event.y - downY

            if (!movedBeyondSlop && (kotlin.math.abs(dx) > TOUCH_SLOP_PX || kotlin.math.abs(dy) > TOUCH_SLOP_PX)) {
                movedBeyondSlop = true
                longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                longPressRunnable = null
            }

            if (!swipeUpFired && dy <= -SWIPE_UP_MIN_DISTANCE_PX && kotlin.math.abs(dx) <= SWIPE_UP_MAX_DRIFT_PX) {
                swipeUpFired = true
                log("Vuốt lên tại (${downX.toInt()}, ${downY.toInt()})")
                renderer.onSwipeUp(downX, downY)
            }
        }

        /**
         * PHẦN 13 — Nhấc tay: nếu đang ghi âm (nhấn-giữ) → dừng ghi âm (buông ra = ngắt ghi âm).
         * Nếu vừa vuốt lên → không làm gì thêm (đã xử lý ở move). Ngược lại → tap ngắn bình thường,
         * đếm tap để phân biệt 1 tap (đánh thức) và 4 tap (Hỏi bằng ảnh — PHẦN 12).
         */
        private fun handleActionUp(event: MotionEvent) {
            longPressRunnable?.let { mainHandler.removeCallbacks(it) }
            longPressRunnable = null

            if (isHoldRecording) {
                stopHoldRecording()
                return
            }
            if (swipeUpFired) return

            registerShortTap(event.x, event.y)
        }

        /** PHẦN 13 — Chạm bị huỷ giữa chừng (ví dụ hệ thống cướp gesture) → coi như nhấc tay, dọn dẹp. */
        private fun handleActionCancel() {
            longPressRunnable?.let { mainHandler.removeCallbacks(it) }
            longPressRunnable = null
            if (isHoldRecording) stopHoldRecording()
        }

        /**
         * PHẦN 13 — Đếm tap ngắn liên tiếp trong cửa sổ [TAP_WINDOW_MS] để phân biệt:
         * 1 tap → đánh thức Slime (PHẦN 13, thay double-tap cũ); 4 tap → Hỏi bằng ảnh (PHẦN 12).
         * 2-3 tap liên tiếp không còn gán chức năng (ghi âm đã chuyển sang nhấn-giữ,
         * AD/STRAW đã chuyển sang vuốt lên).
         */
        private fun registerShortTap(x: Float, y: Float) {
            val now = System.currentTimeMillis()
            if (now - lastTapDownTime <= TAP_WINDOW_MS) tapCount++ else tapCount = 1
            lastTapDownTime = now
            tapX = x
            tapY = y

            pendingTapRunnable?.let { mainHandler.removeCallbacks(it) }
            val capturedCount = tapCount
            val capturedX = tapX
            val capturedY = tapY
            val action = Runnable {
                pendingTapRunnable = null
                when {
                    capturedCount == 1 -> onSingleTap(capturedX, capturedY)
                    capturedCount >= 4 -> onQuadTap(capturedX, capturedY)
                    else -> { /* 2-3 tap: không còn chức năng */ }
                }
                tapCount = 0
            }
            pendingTapRunnable = action
            mainHandler.postDelayed(action, TAP_WINDOW_MS)
        }

        /**
         * PHẦN 13 — Single tap: đánh thức + nudge nhẹ khi trúng Slime (scene tự hit-test).
         * Cũng xử lý nút X trên bảng clipboard (scene tự lo qua onTouch).
         */
        private fun onSingleTap(x: Float, y: Float) {
            renderer.onTouch(x, y)
        }

        /**
         * PHẦN 13 — Bắt đầu ghi âm khi nhấn-giữ trúng Slime đủ [LONG_PRESS_MS].
         * Chỉ vào STT khi đang ROAMING hoặc TALKING (bong bóng ngắn, không phải bảng clipboard) —
         * giống điều kiện double-tap cũ. Nếu state không phù hợp thì huỷ, không coi là đang ghi âm.
         */
        private fun startHoldRecording() {
            if (scene.isTransitioning()) return
            if (stateMachine.state == CharacterStateMachine.State.TALKING && scene.isBoardShowing()) return

            when (stateMachine.state) {
                CharacterStateMachine.State.ROAMING -> {
                    isHoldRecording = true
                    log("Nhấn-giữ trúng Slime — bắt đầu ghi âm")
                    startVoiceInput()
                }
                CharacterStateMachine.State.TALKING -> {
                    isHoldRecording = true
                    log("Nhấn-giữ trúng Slime (đang TALKING) — hỏi tiếp")
                    talkingDismissJob?.cancel()
                    stateMachine.continueTalking()
                    startVoiceInput()
                }
                // PHẦN 16 — Nhấn-giữ trong lúc đang hội thoại song phương = tắt ngay (không cần
                // nói đúng câu lệnh thoại). Không set isHoldRecording vì đây không phải 1 lượt ghi âm mới.
                CharacterStateMachine.State.TRANSLATING -> {
                    log("Nhấn-giữ trúng Slime (đang TRANSLATING) — tắt hội thoại song phương")
                    stopBilingualMode()
                }
                else -> { /* LISTENING/THINKING/EXPRESSING/PLAYING_VIDEO → bỏ qua */ }
            }
        }

        /** PHẦN 13 — Buông tay: dừng ghi âm, để SpeechRecognizer tự chốt kết quả cuối cùng đã nghe được. */
        private fun stopHoldRecording() {
            isHoldRecording = false
            if (stateMachine.state == CharacterStateMachine.State.LISTENING) {
                log("Buông tay — ngắt ghi âm, chờ kết quả STT")
                mainHandler.post { sttManager.stopListening() }
            }
        }

        /**
         * PHẦN 12 — "Hỏi bằng ảnh": 4 tap liên tiếp mở [PhotoAskActivity] (chụp ảnh → hỏi bằng
         * giọng nói → Gemini multimodal). Chỉ mở khi đang ROAMING và không có transition/board
         * nào đang chạy, tránh chồng lấn với các luồng khác.
         */
        private fun onQuadTap(x: Float, y: Float) {
            log("Quad-tap tại (${x.toInt()}, ${y.toInt()}) — mở Hỏi bằng ảnh")
            if (stateMachine.state != CharacterStateMachine.State.ROAMING) return
            if (scene.isTransitioning()) return
            openPhotoAskActivity()
        }

        /** PHẦN 26 — Tách riêng để dùng chung giữa quad-tap (PHẦN 12) và function call open_photo_ask. */
        private fun openPhotoAskActivity() {
            try {
                val intent = Intent(applicationContext, PhotoAskActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                applicationContext.startActivity(intent)
            } catch (e: Exception) {
                log("Không mở được PhotoAskActivity: ${e.message}")
            }
        }

        // ── Voice input ───────────────────────────────────────────────────────

        private fun startVoiceInput() {
            if (!stateMachine.startListening()) return
            scene.applyState(stateMachine)
            mainHandler.post { sttManager.startListening() }
        }

        private fun onSpeechResult(text: String) {
            log("STT result: \"$text\"")
            ProactiveManager.notifyInteraction(applicationContext) // PHẦN 11 — reset đồng hồ "lâu không dùng"

            // PHẦN 16 — Đang hội thoại song phương → rẽ hẳn sang luồng dịch liên tục,
            // KHÔNG đi qua THINKING/sendToGemini bình thường (khác pipeline, khác system prompt).
            if (stateMachine.state == CharacterStateMachine.State.TRANSLATING) {
                handleBilingualUtterance(text)
                return
            }

            stateMachine.startThinking(text)
            scene.applyState(stateMachine)
            sendToGemini(text)
        }

        private fun onSpeechError(errorCode: Int) {
            log("STT error: $errorCode")

            // PHẦN 16 — Trong lúc hội thoại song phương, im lặng giữa các lượt nói (timeout/no-match)
            // là chuyện bình thường (đang chờ người kia nói) — nghe lại ngay, KHÔNG coi là lỗi,
            // không hiện bong bóng lỗi/khóc như luồng chat thường.
            if (stateMachine.state == CharacterStateMachine.State.TRANSLATING) {
                mainHandler.post { sttManager.startListening(SpeechToTextManager.BILINGUAL_SILENCE_TIMEOUT_MS) }
                return
            }

            if (stateMachine.state == CharacterStateMachine.State.LISTENING) {
                stateMachine.showError("Mình không nghe rõ lắm~ Bạn nói lại được không?")
                scene.applyState(stateMachine)
                scene.playChibiCryMotion() // STT lỗi → biểu cảm khóc (giống lúc Gemini báo lỗi)
                scheduleAutoRoaming()
            }
        }

        // ── Gemini ────────────────────────────────────────────────────────────

        private fun sendToGemini(userText: String) {
            scope.launch {
                val response = geminiClient.sendMessage(userText)
                if (response == null) {
                    stateMachine.showError("Mình bị mất kết nối rồi~ Thử lại sau nhé!")
                    scene.applyState(stateMachine)
                    scene.playChibiCryMotion() // Gemini báo lỗi/mất kết nối → biểu cảm khóc
                    scheduleAutoRoaming()
                    return@launch
                }

                when (response) {
                    is GeminiResponse.Text -> {
                        stateMachine.startTalking(response.content)
                        scene.applyState(stateMachine)
                        scheduleAutoRoaming()
                        maybeSpeakConversation(response.content) // PHẦN 21
                    }
                    is GeminiResponse.FunctionCall -> {
                        val action = actionRouter.route(response)
                        handleRoutedAction(action)
                    }
                }
            }
        }

        private fun handleRoutedAction(action: RoutedAction) {
            when (action) {
                is RoutedAction.ShowExpression -> {
                    stateMachine.startExpressing(action.expressionName)
                    scene.applyState(stateMachine)
                    // Về roaming sau 3 giây (expression animation xong)
                    scope.launch {
                        delay(3_000)
                        if (stateMachine.state == CharacterStateMachine.State.EXPRESSING) {
                            stateMachine.backToRoaming()
                            scene.applyState(stateMachine)
                        }
                    }
                }
                is RoutedAction.PlayVideo -> {
                    val uri = action.videoUri
                    if (uri == null) {
                        // PHẦN 18 (mở rộng video UI) — Chưa cấu hình video dài nào ở MainActivity.
                        stateMachine.startTalking("Bạn chưa thêm video dài nào cho mình cả~ Vào app thêm giúp mình nhé!")
                        scene.applyState(stateMachine)
                        scheduleAutoRoaming()
                    } else {
                        stateMachine.startVideo(uri)
                        scene.applyState(stateMachine)
                        // PHẦN 5 — VideoLayer.onPlaybackFinished (nối ở onCreate) sẽ tự về ROAMING khi
                        // video kết thúc thật; job dưới đây chỉ là lưới an toàn nếu URI lỗi/mất quyền
                        // đọc và không bao giờ bắn callback đó.
                        videoFallbackJob?.cancel()
                        videoFallbackJob = scope.launch {
                            delay(VIDEO_FALLBACK_MS)
                            if (stateMachine.state == CharacterStateMachine.State.PLAYING_VIDEO) {
                                log("Video fallback timeout — quay về ROAMING")
                                stateMachine.backToRoaming()
                                scene.applyState(stateMachine)
                            }
                        }
                    }
                }
                is RoutedAction.StartRoaming -> {
                    stateMachine.backToRoaming()
                    scene.applyState(stateMachine)
                }
                is RoutedAction.SpeakText -> {
                    stateMachine.startTalking(action.text)
                    scene.applyState(stateMachine)
                    scheduleAutoRoaming()
                    maybeSpeakConversation(action.text) // PHẦN 21
                }
                // PHẦN 16 — Hội thoại song phương.
                is RoutedAction.StartBilingualMode -> startBilingualMode(action.targetLanguage)
                is RoutedAction.StopBilingualMode -> stopBilingualMode()
                // PHẦN 26 — "Hỏi bằng ảnh" qua giọng nói, không cần quad-tap nữa. State lúc này
                // đang THINKING (đã startThinking() trước khi gọi Gemini), không phải ROAMING, nên
                // KHÔNG kiểm tra state như quad-tap cũ — chỉ tránh mở đè lên lúc cổng đang chạy.
                is RoutedAction.OpenPhotoAsk -> {
                    if (!scene.isTransitioning()) {
                        openPhotoAskActivity()
                        stateMachine.backToRoaming()
                        scene.applyState(stateMachine)
                    }
                }
            }
        }

        // ── PHẦN 21 — Chế độ trò chuyện (đọc to câu trả lời chat thường bằng Gemini TTS) ────────

        /**
         * Chỉ đọc to nếu chủ nhân đã bật "Chế độ trò chuyện" ở MainActivity. KHÔNG liên quan tới
         * Hội thoại song phương (PHẦN 16, dùng TtsHelper + vòng lặp mic riêng) — hai chế độ độc
         * lập, dùng 2 helper khác nhau, không tranh chấp state machine.
         */
        private fun maybeSpeakConversation(text: String) {
            val prefs = applicationContext.getSharedPreferences(
                MainActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE
            )
            if (!prefs.getBoolean(MainActivity.KEY_CONVERSATION_MODE_ENABLED, false)) return
            geminiTtsHelper.speak(text) { /* không cần làm gì thêm khi đọc xong ở chat thường */ }
        }

        // ── PHẦN 16 — Hội thoại song phương ──────────────────────────────────────

        /**
         * THINKING → TRANSLATING: ép về Slime (qua applyState), rồi mở vòng lặp ghi âm liên tục
         * đầu tiên. Các lượt tiếp theo tự lặp trong [handleBilingualUtterance] sau khi TTS đọc xong.
         */
        private fun startBilingualMode(targetLanguage: String) {
            if (!stateMachine.startBilingualMode()) return
            bilingualTargetLang = targetLanguage.ifBlank { DEFAULT_BILINGUAL_TARGET_LANG }
            log("Bật hội thoại song phương — target=$bilingualTargetLang")
            scene.applyState(stateMachine)
            mainHandler.post { sttManager.startListening(SpeechToTextManager.BILINGUAL_SILENCE_TIMEOUT_MS) }
        }

        /**
         * 1 lượt trong vòng lặp: kiểm tra lệnh dừng bằng giọng nói trước (rẻ, không tốn API) →
         * nếu không phải thì gọi [GeminiClient.translate], hiện phụ đề, đọc to bằng TTS, rồi mới
         * mở mic lại (xem [TtsHelper.speak] — PHẢI đợi đọc xong để tránh Milky tự nghe giọng mình).
         */
        private fun handleBilingualUtterance(text: String) {
            if (isBilingualStopPhrase(text)) {
                log("Nhận diện câu lệnh dừng hội thoại song phương: \"$text\"")
                stopBilingualMode()
                return
            }

            stateMachine.updateTranslationBubble("$text\n…")
            scene.applyState(stateMachine)

            scope.launch {
                val result = geminiClient.translate(text, bilingualTargetLang)

                // Có thể đã bị tắt (nhấn-giữ / lệnh dừng khác) trong lúc đang chờ Gemini trả lời.
                if (stateMachine.state != CharacterStateMachine.State.TRANSLATING) return@launch

                if (result == null) {
                    stateMachine.updateTranslationBubble("Mình dịch bị lỗi mạng, đang nghe tiếp câu khác~")
                    scene.applyState(stateMachine)
                    mainHandler.post { sttManager.startListening(SpeechToTextManager.BILINGUAL_SILENCE_TIMEOUT_MS) }
                    return@launch
                }

                stateMachine.updateTranslationBubble("$text\n→ ${result.translatedText}")
                scene.applyState(stateMachine)

                ttsHelper.speak(result.translatedText, result.targetLang) {
                    mainHandler.post {
                        if (stateMachine.state == CharacterStateMachine.State.TRANSLATING) {
                            sttManager.startListening(SpeechToTextManager.BILINGUAL_SILENCE_TIMEOUT_MS)
                        }
                    }
                }
            }
        }

        private fun isBilingualStopPhrase(text: String): Boolean {
            val normalized = text.trim().lowercase()
            return BILINGUAL_STOP_PHRASES.any { normalized.contains(it) }
        }

        /** TRANSLATING → ROAMING (dừng vòng lặp + dừng TTS + tắt mic đang mở nếu có). */
        private fun stopBilingualMode() {
            if (stateMachine.state != CharacterStateMachine.State.TRANSLATING) return
            log("Tắt hội thoại song phương")
            mainHandler.post { sttManager.stopListening() }
            ttsHelper.stop()
            bilingualTargetLang = ""
            stateMachine.backToRoaming()
            scene.applyState(stateMachine)
        }

        /**
         * Tự động về ROAMING sau 1 khoảng thời gian không tương tác.
         * PHẦN 5 — dùng [BOARD_AUTO_DISMISS_MS] (dài hơn) khi đang hiện bảng clipboard (text dài,
         * cần thời gian đọc), còn lại dùng [TALKING_AUTO_DISMISS_MS] như bong bóng chat thường.
         */
        private fun scheduleAutoRoaming() {
            talkingDismissJob?.cancel()
            val delayMs = if (ChatBubbleOverlay.isLongText(stateMachine.bubbleText))
                BOARD_AUTO_DISMISS_MS else TALKING_AUTO_DISMISS_MS
            talkingDismissJob = scope.launch {
                delay(delayMs)
                if (stateMachine.state == CharacterStateMachine.State.TALKING) {
                    stateMachine.backToRoaming()
                    scene.applyState(stateMachine)
                }
            }
        }

        private fun log(msg: String) = Log.d(TAG, msg)
    }
}

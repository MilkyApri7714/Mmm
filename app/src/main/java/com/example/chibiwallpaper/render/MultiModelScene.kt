package com.example.chibiwallpaper.render

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import com.example.chibiwallpaper.character.CharacterStateMachine
import com.example.chibiwallpaper.character.CubismModelWrapper
import com.example.chibiwallpaper.character.SlimeController
import com.example.chibiwallpaper.cubism.CubismBoot
import com.example.chibiwallpaper.ui.MainActivity
import kotlin.random.Random

/**
 * PHẦN 4 — Chọn model Active (Slime/Chibi/Full) theo [CharacterStateMachine] + expression.
 *
 * PHẦN 5 — Mở rộng:
 *   - Bong bóng chat (Chibi, text ngắn) / bảng clipboard bay lên giữa màn hình (Slime, text dài)
 *     qua [ChatBubbleOverlay] + [OverlayTextureRenderer].
 *   - Phát video nền cho state PLAYING_VIDEO qua [VideoLayer] (SurfaceTexture + MediaPlayer,
 *     chung context GL với Cubism).
 *   - Chặn thao tác (nudge/motion) vào model bên dưới trong lúc bảng clipboard đang hiện.
 *
 * PHẦN 7.1 — Hiệu ứng cổng dịch chuyển (Slime ⟷ Chibi) qua [PortalTransitionOverlay]:
 *   - [applyState] KHÔNG còn đổi model đang vẽ ngay lập tức — nó chỉ set [desiredModel]
 *     ("model mà state machine muốn hiện"). Model THỰC SỰ đang vẽ là [displayModel], chỉ đổi bởi
 *     [reconcileModel] (mỗi frame) hoặc bởi callback swap giữa animation cổng.
 *   - Nếu [desiredModel] và [displayModel] đều là SLIME/CHIBI ("model nhỏ") và khác nhau →
 *     chạy hiệu ứng cổng [PortalTransitionOverlay] trước khi đổi.
 *
 * PHẦN 7.2 — Hiệu ứng cổng RIÊNG cho FULL qua [FullPortalOverlay] (vị trí cổng nhỏ: random mỗi
 * lần trong 3 vị trí trái/giữa/phải, theo lựa chọn của user):
 *   - [desiredModel]=FULL, [displayModel]=model nhỏ → [FullPortalOverlay.startSmallToFull]
 *     (cổng nhỏ co lại nuốt model nhỏ, rồi dải cổng to rút lên lộ Full).
 *   - [desiredModel]=model nhỏ, [displayModel]=FULL → [FullPortalOverlay.startFullToSmall]
 *     (dải cổng to kéo xuống nuốt Full, rồi cổng nhỏ mở ra lộ model nhỏ).
 *   - Các trường hợp còn lại dính VIDEO vẫn đổi ngay lập tức như cũ — PLAYING_VIDEO chưa có hiệu
 *     ứng cổng riêng.
 */
class MultiModelScene(private val appContext: Context) : GLScene {

    // ── Model wrappers ─────────────────────────────────────────────────────
    private var slimeWrapper: CubismModelWrapper? = null
    private var chibiWrapper: CubismModelWrapper? = null
    private var fullWrapper: CubismModelWrapper? = null

    // ── Controllers ────────────────────────────────────────────────────────
    private val slimeController = SlimeController()

    // ── PHẦN 5 — Overlay (bong bóng/bảng) + video ───────────────────────────
    private val overlayRenderer = OverlayTextureRenderer()
    private val bubbleOverlay = ChatBubbleOverlay()
    private val videoLayer = VideoLayer(appContext)

    // ── PHẦN 18 — Layer 1 (nền dưới cùng, vẽ TRƯỚC model): ảnh tĩnh HOẶC video ngắn lặp liên tục.
    // TÁCH BIỆT với [videoLayer] ở trên (dùng cho video DÀI function-call "play_video", không lặp,
    // thay THẾ model qua ActiveModel.VIDEO + cổng dịch chuyển) — 2 nhu cầu khác nhau, không dùng
    // chung 1 MediaPlayer/SurfaceTexture để tránh xung đột lúc cả 2 cùng cần phát.
    private val backgroundLayer = BackgroundImageLayer()
    private val backgroundVideoLayer = VideoLayer(appContext)

    // ── PHẦN 8 — "Z z z" khi Slime ngủ (tiết kiệm pin) ───────────────────────
    private val sleepIndicator = SleepIndicatorOverlay()

    /** Video phát xong (tự nhiên hoặc lỗi) — service lắng nghe để quay về ROAMING. */
    var onVideoFinished: (() -> Unit)? = null

    /** User bấm nút X trên bảng clipboard — service lắng nghe để gọi stateMachine.backToRoaming(). */
    var onBoardClosedByUser: (() -> Unit)? = null

    // ── PHẦN 7.1 — Hiệu ứng cổng dịch chuyển (Slime ⟷ Chibi) ─────────────────
    private val portalTransition = PortalTransitionOverlay()

    // ── PHẦN 7.2 — Hiệu ứng cổng riêng cho FULL ⟷ model nhỏ ──────────────────
    private val fullPortal = FullPortalOverlay()

    // ── Trạng thái hiển thị (phản chiếu từ CharacterStateMachine) ─────────
    /** Model THỰC SỰ đang được vẽ frame này — chỉ [reconcileModel] mới được đổi giá trị này. */
    @Volatile private var displayModel: ActiveModel = ActiveModel.SLIME
    /** Model mà [CharacterStateMachine] MUỐN hiện — set bởi [applyState], có thể chưa hiện ngay. */
    @Volatile private var desiredModel: ActiveModel = ActiveModel.SLIME
    @Volatile var bubbleText: String = ""
    @Volatile private var pendingExpression: String = ""
    // PHẦN 16 — true khi đang TRANSLATING: ép bubbleOverlay dùng BOARD (neo theo Slime) thay vì
    // BUBBLE (neo theo đầu Chibi, sai vị trí vì Chibi không hiện lúc này). Xem [applyState].
    @Volatile private var forceBoardBubble: Boolean = false

    // ── Surface size ───────────────────────────────────────────────────────
    private var width = 0
    private var height = 0

    // PHẦN 15.3 — ActiveModel giờ nằm ở file riêng render/ActiveModel.kt (xem file đó để biết lý
    // do tách) — cùng package `render` nên dùng thẳng không cần import.

    // ─────────────────────────────────────────────────────────────────────────
    // GLScene callbacks
    // ─────────────────────────────────────────────────────────────────────────

    override fun onContextCreated() {
        CubismBoot.initializeOnGlThread(appContext)
        loadAllModels()
        val prefs = appContext.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        reloadScales(prefs)
        slimeController.reset()

        overlayRenderer.ensureInitialized()
        bubbleOverlay.attach(overlayRenderer)
        sleepIndicator.ensureInitialized(overlayRenderer)
        portalTransition.ensureInitialized(overlayRenderer)
        fullPortal.ensureInitialized()
        videoLayer.onContextCreated()
        videoLayer.onPlaybackFinished = { onVideoFinished?.invoke() }

        // PHẦN 18 — Layer 1 (ảnh/video nền): load ngay lúc khởi động từ URI đã lưu (nếu có).
        backgroundVideoLayer.onContextCreated()
        reloadBackground(prefs)
        reloadBackgroundVideo(prefs)
    }

    override fun onSurfaceChanged(width: Int, height: Int) {
        this.width = width
        this.height = height
        slimeWrapper?.setRenderTargetSize(width, height)
        chibiWrapper?.setRenderTargetSize(width, height)
        fullWrapper?.setRenderTargetSize(width, height)
    }

    override fun onDrawFrame(deltaSeconds: Float) {
        val dt = deltaSeconds.coerceAtMost(0.1f)
        bubbleOverlay.update(dt)
        sleepIndicator.update(dt)

        // PHẦN 18 — Layer 1 (nền dưới cùng), vẽ NGAY ĐẦU, trước model (Layer 2). Video nền (nếu
        // đang phát) ưu tiên hơn ảnh nền tĩnh; không có cái nào thì giữ nguyên glClearColor màu
        // đặc hiện có (GLRenderer đã clear trước khi gọi onDrawFrame).
        if (backgroundVideoLayer.isPlaying) {
            backgroundVideoLayer.draw(width, height)
        } else {
            backgroundLayer.draw(overlayRenderer, width, height)
        }

        // PHẦN 7.1/7.2 — Cập nhật animation cổng (nhỏ và Full) trước, rồi mới đồng bộ model thật
        // sự cần vẽ. onSwap chạy ĐÚNG lúc cổng phủ kín màn hình → đổi displayModel mà user không
        // thấy giật. Chỉ 1 trong 2 overlay active tại 1 thời điểm (reconcileModel() đảm bảo).
        portalTransition.update(dt) { swappedTo -> displayModel = swappedTo }
        fullPortal.update(dt) { swappedTo -> displayModel = swappedTo }
        reconcileModel()

        when (displayModel) {
            ActiveModel.SLIME -> {
                slimeController.update(dt)
                slimeWrapper?.also { w ->
                    w.posX = slimeController.posX
                    w.posY = slimeController.posY
                    w.draw(width, height, dt)
                }
            }
            ActiveModel.CHIBI -> {
                chibiWrapper?.draw(width, height, dt)
            }
            ActiveModel.FULL -> {
                fullWrapper?.also { w ->
                    if (pendingExpression.isNotEmpty()) {
                        w.setExpression(pendingExpression)
                        pendingExpression = ""
                    }
                    w.draw(width, height, dt)
                }
            }
            ActiveModel.VIDEO -> {
                videoLayer.draw(width, height)
            }
        }

        // PHẦN 7.1/7.2 — Cổng dịch chuyển (nhỏ và Full) vẽ đè lên model (dưới bong bóng chat).
        portalTransition.draw(overlayRenderer, width, height)
        fullPortal.draw(portalTransition, overlayRenderer, width, height)

        // PHẦN 5 — Overlay bong bóng/bảng vẽ đè lên trên (không áp dụng khi đang phát video
        // full-screen, và tạm ẩn trong lúc bất kỳ cổng nào đang chạy để tránh chồng chéo hiệu ứng).
        if (displayModel != ActiveModel.VIDEO && !portalTransition.isActive && !fullPortal.isActive) {
            val (slimeX, slimeY) = slimeAnchorScreenPx()
            val (headX, headY) = chibiHeadAnchorScreenPx()
            bubbleOverlay.setText(bubbleText, slimeX, slimeY, headX, headY, forceBoard = forceBoardBubble)
            if (bubbleOverlay.mode != ChatBubbleOverlay.Mode.NONE) {
                bubbleOverlay.draw(overlayRenderer, width, height)
            } else if (displayModel == ActiveModel.SLIME && slimeController.isSleeping) {
                // PHẦN 8 — Không có bong bóng/bảng nào đang chiếm chỗ → an toàn để hiện "Z z z".
                sleepIndicator.draw(overlayRenderer, width, height, slimeX, slimeY)
            }
        }
    }

    /**
     * PHẦN 8 — GLRenderer hỏi mỗi frame để biết có nên hạ khung hình/giây tiết kiệm pin không.
     * Slime đang ngủ (đứng yên, chỉ còn nhịp thở rất nhẹ) là lúc an toàn nhất để hạ FPS — không
     * có animation nào cần mượt lúc đó.
     */
    override fun wantsLowFrameRate(): Boolean = slimeController.isSleeping

    /**
     * PHẦN 7.1/7.2 — Đối chiếu [desiredModel] (state machine muốn) với [displayModel] (đang vẽ):
     *  - Nếu đang có 1 trong 2 animation cổng chạy dở → không đụng vào, để nó tự chạy hết (đã
     *    "chốt" target lúc [PortalTransitionOverlay.start]/[FullPortalOverlay.startFullToSmall]/
     *    [FullPortalOverlay.startSmallToFull] được gọi).
     *  - 2 bên khác nhau, CẢ HAI đều là model nhỏ (Slime/Chibi) → cổng nhỏ [PortalTransitionOverlay]
     *    (Phần 7.1).
     *  - 2 bên khác nhau, 1 bên FULL và bên kia là model nhỏ → cổng riêng [FullPortalOverlay]
     *    (Phần 7.2), đúng chiều tương ứng.
     *  - Còn lại (dính VIDEO) → đổi ngay lập tức (chưa có hiệu ứng cổng riêng cho VIDEO).
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

    private fun isSmallModel(m: ActiveModel): Boolean = m == ActiveModel.SLIME || m == ActiveModel.CHIBI

    /**
     * Single-tap: model hiện tại phản ứng nhỏ, TRỪ KHI bảng clipboard đang hiện (PHẦN 5) —
     * lúc đó chỉ kiểm tra có trúng nút X không, mọi chạm khác vào bảng đều bị "nuốt".
     * (Double-tap xử lý ở ChibiWallpaperService, không đến đây.)
     */
    override fun onTouch(x: Float, y: Float) {
        // PHẦN 7.1/7.2 — Cổng (nhỏ hoặc Full) đang chạy: nuốt hết chạm.
        if (portalTransition.isActive || fullPortal.isActive) return
        if (bubbleOverlay.mode == ChatBubbleOverlay.Mode.BOARD) {
            if (bubbleOverlay.hitTestClose(x, y, width, height)) {
                onBoardClosedByUser?.invoke()
            }
            return
        }
        when (displayModel) {
            ActiveModel.SLIME -> {
                // PHẦN 13 — Single-tap: chỉ còn tác dụng đánh thức + nudge khi trúng Slime
                // (AD/STRAW không còn gán vào single-tap nữa — xem [onSwipeUp]).
                if (hitTestSlime(x, y)) {
                    slimeController.nudge()
                }
            }
            ActiveModel.CHIBI -> chibiWrapper?.triggerRandomMotion()
            ActiveModel.FULL  -> fullWrapper?.triggerRandomMotion()
            ActiveModel.VIDEO -> { /* không tương tác trong lúc phát video */ }
        }
    }

    /**
     * PHẦN 9 — Triple-tap: toggle STRAW motion trên Slime (hoạt động kể cả khi ngủ).
     * Không cần hit-test — triple-tap ở đâu cũng toggle STRAW.
     */
    override fun onTripleTap(x: Float, y: Float) {
        slimeWrapper?.toggleMotion("Straw")
    }

    // PHẦN 13 — Hiệu ứng đang bật do vuốt lên ("AD" hoặc "Straw"), null nếu chưa bật gì.
    // Vuốt lần 2 phải tắt ĐÚNG hiệu ứng đã bật ở lần 1, nên cần nhớ lại lựa chọn ngẫu nhiên đó.
    private var swipeUpEffectActive: String? = null

    /**
     * PHẦN 13 — Vuốt lên trúng Slime: bật/tắt luân phiên 1 trong 2 hiệu ứng
     * (AD.exp3.json / STRAW.motion3.json), chọn ngẫu nhiên khi bật, hoạt động kể cả khi Slime
     * đang ngủ (giống hành vi toggle cũ của single/triple-tap trước đây).
     */
    override fun onSwipeUp(x: Float, y: Float) {
        if (displayModel != ActiveModel.SLIME) return
        if (!hitTestSlime(x, y)) return
        val wrapper = slimeWrapper ?: return

        val active = swipeUpEffectActive
        if (active == null) {
            val choice = if (Random.nextBoolean()) "AD" else "Straw"
            val turnedOn = if (choice == "AD") wrapper.toggleExpression("AD") else wrapper.toggleMotion("Straw")
            if (turnedOn) {
                swipeUpEffectActive = choice
                android.util.Log.d("ChibiScene", "onSwipeUp: bật $choice")
            }
        } else {
            if (active == "AD") wrapper.toggleExpression("AD") else wrapper.toggleMotion("Straw")
            android.util.Log.d("ChibiScene", "onSwipeUp: tắt $active")
            swipeUpEffectActive = null
        }
    }

    /** PHẦN 13 — Hit-test dùng chung: [x],[y] có nằm trong bán kính Slime (~11% cạnh ngắn) không. */
    private fun hitTestSlime(x: Float, y: Float): Boolean {
        val (slimePx, slimePy) = slimeAnchorScreenPx()
        val hitRadius = minOf(width, height) * 0.11f
        val dx = x - slimePx
        val dy = y - slimePy
        return (dx * dx + dy * dy) <= hitRadius * hitRadius
    }

    /**
     * PHẦN 9 — Chỉ đánh thức Slime nếu tap/giữ TRÚNG VÀO Slime (bán kính ~14% cạnh ngắn).
     * Nếu Slime không phải model đang hiện (đang TALKING/THINKING...) thì luôn cho qua (trả về true)
     * để STT vẫn hoạt động bình thường.
     * Nếu Slime đang hiện và không trúng → trả về false (không vào STT).
     */
    override fun wakeIfHit(x: Float, y: Float): Boolean {
        if (displayModel != ActiveModel.SLIME) {
            // Không phải Slime đang hiện → cho qua STT
            return true
        }
        val hit = hitTestSlime(x, y)
        if (hit) {
            slimeController.wake()
            android.util.Log.d("ChibiScene", "wakeIfHit: trúng Slime tại ($x, $y)")
        } else {
            android.util.Log.d("ChibiScene", "wakeIfHit: không trúng Slime tại ($x, $y)")
        }
        return hit
    }

    override fun onContextDestroyed() {
        slimeWrapper?.release(); slimeWrapper = null
        chibiWrapper?.release(); chibiWrapper = null
        fullWrapper?.release();  fullWrapper = null
        bubbleOverlay.release()
        sleepIndicator.onContextDestroyed()
        overlayRenderer.onContextDestroyed()
        portalTransition.onContextDestroyed(overlayRenderer)
        fullPortal.onContextDestroyed()
        videoLayer.onContextDestroyed()
        backgroundVideoLayer.onContextDestroyed()
        backgroundLayer.onContextDestroyed()
        CubismBoot.disposeOnGlThread()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // API cho ChibiWallpaperService
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Đồng bộ scene với [CharacterStateMachine]. Gọi từ service (main thread) mỗi khi state đổi.
     * Thread-safe: chỉ set các field @Volatile / gọi [VideoLayer.play] (không đụng GLES trực tiếp);
     * GL thread đọc/ tiêu thụ chúng trong onDrawFrame.
     */
    fun applyState(sm: CharacterStateMachine) {
        bubbleText = sm.bubbleText
        forceBoardBubble = (sm.state == CharacterStateMachine.State.TRANSLATING) // PHẦN 16

        // Đang LISTENING (ghi âm nhấn-giữ, model hiện vẫn là Slime) → không cho Slime ngủ,
        // vì đó là 1 "hành động" đang diễn ra, không phải đang rảnh. Mọi state khác thì cho
        // ngủ bình thường như cũ (lúc đó Slime cũng không phải model đang hiện, trừ ROAMING).
        // PHẦN 16 — TRANSLATING cũng không cho Slime ngủ, y như LISTENING: đang trong 1 phiên
        // hội thoại song phương liên tục, không phải đang rảnh dù giữa các lượt có thể im lặng vài giây.
        slimeController.suppressSleep = (sm.state == CharacterStateMachine.State.LISTENING ||
            sm.state == CharacterStateMachine.State.TRANSLATING)

        // PHẦN 7.1 — Đây chỉ set MONG MUỐN (desiredModel); model thật sự đang vẽ (displayModel)
        // được reconcileModel() đổi dần trên GL thread, có thể qua hiệu ứng cổng trước khi đổi.
        // Các side-effect (play video, set expression) vẫn chạy NGAY ở đây như cũ — chỉ việc
        // "hiện model nào lên màn hình" là được hoãn lại qua cổng.
        desiredModel = when (sm.state) {
            CharacterStateMachine.State.ROAMING   -> ActiveModel.SLIME
            CharacterStateMachine.State.LISTENING -> ActiveModel.SLIME  // Slime vẫn roam, thêm mic icon (giai đoạn sau)
            CharacterStateMachine.State.THINKING  -> ActiveModel.CHIBI
            CharacterStateMachine.State.TALKING   ->
                // PHẦN 5 — text quá dài thì tự chuyển về Slime + hiện bảng clipboard thay vì Chibi + bong bóng.
                if (ChatBubbleOverlay.isLongText(sm.bubbleText)) ActiveModel.SLIME else ActiveModel.CHIBI
            CharacterStateMachine.State.EXPRESSING -> {
                pendingExpression = sm.activeExpression
                ActiveModel.FULL
            }
            CharacterStateMachine.State.PLAYING_VIDEO -> {
                // PHẦN 18 mở rộng — activeVideo giờ là content:// URI do user tự chọn ở
                // MainActivity (video dài, xem ActionRouter) thay vì tên file asset cố định.
                // Vẫn giữ nhánh asset cũ để tương thích ngược (asset trong assets/videos/ nếu có).
                val target = sm.activeVideo
                val played = if (target.startsWith("content://")) {
                    videoLayer.play(Uri.parse(target))
                } else {
                    videoLayer.play(target)
                }
                if (!played) {
                    Log.w(TAG, "Video '$target' không phát được — service sẽ tự fallback về ROAMING")
                }
                ActiveModel.VIDEO
            }
            // PHẦN 16 — Hội thoại song phương: luôn giữ Slime ("giữ ở dạng đó"), không bao giờ
            // chuyển sang Chibi/Full trong lúc đang dịch. Phụ đề (câu gốc + câu dịch) hiển thị
            // qua đúng cơ chế bubbleOverlay có sẵn (bubbleText 2 dòng, xem service).
            CharacterStateMachine.State.TRANSLATING -> ActiveModel.SLIME
        }

        Log.d(TAG, "Scene state → desired=$desiredModel (display=$displayModel) bubble=\"$bubbleText\" expr=\"${sm.activeExpression}\" video=\"${sm.activeVideo}\"")
    }

    /**
     * Kích hoạt biểu cảm "khóc" trên Chibi — gọi từ service ngay sau [applyState] khi có
     * lỗi (STT lỗi / Gemini báo lỗi / mất kết nối...). Không làm gì nếu Chibi chưa load được.
     */
    fun playChibiCryMotion() {
        chibiWrapper?.playCryMotion()
    }

    /** true khi bảng clipboard (text dài) đang hiện — service dùng để chặn double-tap lúc này. */
    fun isBoardShowing(): Boolean = bubbleOverlay.isBoardBlocking()

    /**
     * PHẦN 7.1/7.2 — true khi 1 trong 2 hiệu ứng cổng (nhỏ hoặc Full) đang chạy — service dùng để
     * chặn double-tap lúc này.
     */
    fun isTransitioning(): Boolean = portalTransition.isActive || fullPortal.isActive

    /**
     * PHẦN 12 — Đánh thức Slime vô điều kiện, dùng cho trigger bên ngoài không có toạ độ chạm
     * thật (Quick Settings Tile — xem [com.example.chibiwallpaper.ChibiWallpaperService.triggerVoiceFromExternal]).
     */
    override fun wakeSlime() = slimeController.wake()

    /**
     * PHẦN 10-B — Đọc scale factor 3 model từ SharedPreferences và áp dụng ngay.
     * Gọi trong onSurfaceCreated (qua onContextCreated) để load khi wallpaper khởi động.
     * Hot-reload khi đang chạy chưa hỗ trợ — cần restart wallpaper để thấy hiệu lực.
     */
    fun reloadScales(prefs: SharedPreferences) {
        val scaleSlime = prefs.getFloat("scale_slime", 1.0f).coerceIn(0.2f, 2.0f)
        val scaleChibi = prefs.getFloat("scale_chibi", 1.0f).coerceIn(0.2f, 2.0f)
        val scaleFull  = prefs.getFloat("scale_full",  1.0f).coerceIn(0.2f, 2.0f)
        slimeWrapper?.scaleFactor = scaleSlime * 0.5f   // baseline Slime = 0.5
        chibiWrapper?.scaleFactor = scaleChibi * 0.75f  // baseline Chibi = 0.75
        fullWrapper?.scaleFactor  = scaleFull  * 1.0f   // baseline Full  = 1.0
        Log.d(TAG, "reloadScales: slime=$scaleSlime chibi=$scaleChibi full=$scaleFull")
    }

    /**
     * PHẦN 18 — Đọc URI ảnh nền đã lưu (nếu có) + decode/crop theo kích thước màn hình thật, upload
     * lên [backgroundLayer]. Gọi lúc khởi động ([onContextCreated]) VÀ khi hot-reload từ
     * MainActivity (qua GLRenderer.runOnGlThread — xem ChibiWallpaperService). Không có ảnh đã
     * lưu / lỗi decode → backgroundLayer về rỗng → fallback màu đặc hiện có, không breaking change.
     */
    fun reloadBackground(prefs: SharedPreferences) {
        val uriStr = prefs.getString(MainActivity.KEY_BACKGROUND_IMAGE_URI, null)
        if (uriStr == null) {
            backgroundLayer.clearImage(overlayRenderer)
            return
        }
        val metrics = appContext.resources.displayMetrics
        val bmp = BackgroundImageLoader.decodeCenterCropped(
            appContext, Uri.parse(uriStr), metrics.widthPixels, metrics.heightPixels
        )
        if (bmp != null) backgroundLayer.setImage(overlayRenderer, bmp)
        else Log.w(TAG, "reloadBackground: không decode được ảnh nền đã lưu")
    }

    /**
     * PHẦN 18 (mở rộng video UI) — Đọc URI video ngắn đã lưu (nếu có), phát LẶP LIÊN TỤC làm nền
     * (Layer 1, ưu tiên hơn ảnh tĩnh — xem [onDrawFrame]). Không có URI đã lưu → dừng hẳn, quay về
     * ảnh nền/màu đặc.
     */
    fun reloadBackgroundVideo(prefs: SharedPreferences) {
        val uriStr = prefs.getString(MainActivity.KEY_BG_VIDEO_URI, null)
        if (uriStr == null) {
            backgroundVideoLayer.stop()
            return
        }
        if (!backgroundVideoLayer.play(Uri.parse(uriStr), loop = true)) {
            Log.w(TAG, "reloadBackgroundVideo: không phát được video nền đã lưu")
        }
    }


    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * PHẦN 5 — Quy đổi XẤP XỈ vị trí Slime (hệ toạ độ nội bộ Cubism, xem SlimeController) sang
     * pixel màn hình, để đặt icon bảng ngay phía trên đầu Slime lúc bắt đầu animation.
     * Không cần chính xác tuyệt đối — chỉ cần đủ gần để hiệu ứng "quăng lên" trông tự nhiên.
     */
    private fun slimeAnchorScreenPx(): Pair<Float, Float> {
        val nx = (slimeController.posX / 0.6f).coerceIn(-1f, 1f)
        val ny = (slimeController.posY / 0.6f).coerceIn(-1f, 1f)
        val px = width / 2f + nx * (width * 0.42f)
        val py = height / 2f - ny * (height * 0.42f) // Cubism: dương = lên; pixel: dương = xuống
        return px to py
    }

    /** Chibi đứng cố định gần giữa màn hình — ước lượng vị trí đầu để neo bong bóng chat. */
    private fun chibiHeadAnchorScreenPx(): Pair<Float, Float> =
        (width / 2f) to (height * 0.30f)

    private fun loadAllModels() {
        slimeWrapper = loadModel(SLIME_DIR, SLIME_JSON)?.also { w ->
            w.scaleFactor = 0.5f
        }
        chibiWrapper = loadModel(CHIBI_DIR, CHIBI_JSON)?.also { w ->
            w.scaleFactor = 0.75f
            w.posX = 0f
            w.posY = 0f
            // Motion 05 (nhóm Tap) = biểu cảm "khóc" — chỉ chạy lúc lỗi/Gemini báo lỗi
            // (xem [playChibiCryMotion]), không còn nằm trong pool ngẫu nhiên khi tap.
            w.cryMotionFileName = "05.motion3.json"
        }
        fullWrapper = loadModel(FULL_DIR, FULL_JSON)?.also { w ->
            w.scaleFactor = 1.0f
            w.posX = 0f
            w.posY = 0.05f
        }
    }

    private fun loadModel(dir: String, jsonFile: String): CubismModelWrapper? {
        val wrapper = CubismModelWrapper(appContext)
        return if (wrapper.loadFromAssets(dir, jsonFile)) wrapper
        else { Log.e(TAG, "Load thất bại: $dir$jsonFile"); wrapper.release(); null }
    }

    companion object {
        private const val TAG = "ChibiScene"
        private const val SLIME_DIR  = "slime/"
        private const val SLIME_JSON = "SLIME.model3.json"
        private const val CHIBI_DIR  = "chibi/"
        private const val CHIBI_JSON = "koharu.model3.json"
        private const val FULL_DIR   = "full/"
        private const val FULL_JSON  = "tingyun.model3.json"
    }
}

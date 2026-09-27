package com.example.chibiwallpaper.character

import android.util.Log

/**
 * PHẦN 4 — State machine 6 trạng thái cho nhân vật.
 *
 * Sơ đồ chuyển trạng thái:
 *
 *   ROAMING (Slime di chuyển tự do)
 *     └─ [double-tap] ──────────────→ LISTENING (ghi âm STT)
 *
 *   LISTENING
 *     ├─ [có kết quả / hết 3s] ────→ THINKING (chờ Gemini, hiện Chibi)
 *     └─ [lỗi STT / tap cancel] ───→ ROAMING
 *
 *   THINKING
 *     ├─ [Gemini trả về text] ─────→ TALKING (Chibi + bong bóng chat)
 *     ├─ [function: expression] ───→ EXPRESSING (Full model)
 *     ├─ [function: video] ────────→ PLAYING_VIDEO
 *     ├─ [function: roaming] ──────→ ROAMING
 *     └─ [lỗi Gemini] ─────────────→ TALKING (thông báo lỗi)
 *
 *   TALKING (Chibi + bong bóng chat)
 *     ├─ [double-tap] ──────────────→ LISTENING (hỏi tiếp)
 *     └─ [tự động sau X giây] ─────→ ROAMING
 *
 *   EXPRESSING (Full model + expression)
 *     └─ [animation xong / timeout] → ROAMING
 *
 *   PLAYING_VIDEO
 *     └─ [video kết thúc] ──────────→ ROAMING
 *
 * Thread-safety: [transition] và các getter đều an toàn để gọi từ bất kỳ thread nào
 * (dùng @Volatile + synchronized block nhỏ).
 */
class CharacterStateMachine {

    enum class State {
        ROAMING,        // Slime di chuyển ngẫu nhiên
        LISTENING,      // Đang ghi âm STT
        THINKING,       // Đã gửi lên Gemini, đang chờ
        TALKING,        // Chibi + bong bóng chat hiển thị câu trả lời
        EXPRESSING,     // Full model + expression animation
        PLAYING_VIDEO,  // Đang phát video nền
        TRANSLATING     // PHẦN 16 — Hội thoại song phương: Slime đứng yên form Slime, ghi âm liên tục
    }

    @Volatile
    private var _state: State = State.ROAMING

    val state: State get() = _state

    /** Text hiển thị trong bong bóng chat (THINKING / TALKING). */
    @Volatile
    var bubbleText: String = ""
        private set

    /** Tên expression đang active (EXPRESSING). */
    @Volatile
    var activeExpression: String = ""
        private set

    /** Tên video đang phát (PLAYING_VIDEO). */
    @Volatile
    var activeVideo: String = ""
        private set

    /** Listener nhận thông báo khi trạng thái thay đổi (gọi trên thread gọi transition). */
    var onStateChanged: ((from: State, to: State) -> Unit)? = null

    companion object {
        private const val TAG = "ChibiState"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Các hàm chuyển trạng thái
    // ─────────────────────────────────────────────────────────────────────────

    /** ROAMING → LISTENING (double-tap). */
    fun startListening(): Boolean = transition(
        from = State.ROAMING,
        to = State.LISTENING
    ) {
        bubbleText = ""
        activeExpression = ""
    }

    /** LISTENING → THINKING (có kết quả STT). */
    fun startThinking(query: String): Boolean = transition(
        from = State.LISTENING,
        to = State.THINKING
    ) {
        bubbleText = "Đang suy nghĩ..."
    }

    /** THINKING → TALKING (Gemini trả về text). */
    fun startTalking(responseText: String): Boolean = transition(
        from = State.THINKING,
        to = State.TALKING
    ) {
        bubbleText = responseText
    }

    /** THINKING → EXPRESSING (Gemini gọi show_expression). */
    fun startExpressing(expressionName: String): Boolean = transition(
        from = State.THINKING,
        to = State.EXPRESSING
    ) {
        activeExpression = expressionName
        bubbleText = ""
    }

    /** THINKING → PLAYING_VIDEO (Gemini gọi play_video). */
    fun startVideo(videoName: String): Boolean = transition(
        from = State.THINKING,
        to = State.PLAYING_VIDEO
    ) {
        activeVideo = videoName
        bubbleText = ""
    }

    /**
     * PHẦN 16 — THINKING → TRANSLATING (Gemini gọi start_bilingual_mode).
     * Model được ép về Slime ở tầng scene (xem MultiModelScene.applyState); state machine ở đây
     * chỉ lo chuyển trạng thái + set bubble ban đầu, vòng lặp ghi âm liên tục do service điều khiển.
     */
    fun startBilingualMode(): Boolean = transition(
        from = State.THINKING,
        to = State.TRANSLATING
    ) {
        bubbleText = "Đang bật chế độ hội thoại song phương..."
        activeExpression = ""
        activeVideo = ""
    }

    /**
     * PHẦN 16 — Cập nhật nội dung bong bóng phụ đề trong lúc đang TRANSLATING, KHÔNG đổi state
     * (khác các hàm transition() khác — đây là update tại chỗ, gọi mỗi khi có 1 lượt dịch mới).
     * @return false nếu hiện không ở TRANSLATING (ví dụ đã bị tắt trong lúc đang chờ Gemini dịch).
     */
    fun updateTranslationBubble(text: String): Boolean {
        if (_state != State.TRANSLATING) return false
        bubbleText = text
        Log.d(TAG, "TRANSLATING bubble update: \"$text\"")
        return true
    }

    /** TALKING → LISTENING (user hỏi tiếp). */
    fun continueTalking(): Boolean = transition(
        from = State.TALKING,
        to = State.LISTENING
    ) {
        bubbleText = ""
    }

    /**
     * Bất kỳ trạng thái nào → ROAMING.
     * Dùng khi: hết session, lỗi, function "start_roaming", animation xong.
     */
    fun backToRoaming(): Boolean {
        val from = _state
        if (from == State.ROAMING) return false
        return doTransition(from, State.ROAMING) {
            bubbleText = ""
            activeExpression = ""
            activeVideo = ""
        }
    }

    /**
     * LISTENING → ROAMING khi tap/gesture bị huỷ giữa chừng (không phải lỗi thật, không cần
     * hiện thông báo gì). [showError] mới là hàm dùng khi có lỗi thật (STT lỗi / Gemini lỗi).
     */
    fun cancelListening(): Boolean = transition(
        from = State.LISTENING,
        to = State.ROAMING
    ) {
        bubbleText = ""
    }

    /**
     * (LISTENING hoặc THINKING) → TALKING với error message — dùng cho CẢ 2 trường hợp lỗi:
     * STT lỗi (đang LISTENING) và Gemini báo lỗi/mất kết nối (đang THINKING). Tái dùng
     * startTalking nhưng set bubbleText là thông báo lỗi.
     */
    fun showError(errorText: String): Boolean = transitionAny(
        from = setOf(State.LISTENING, State.THINKING),
        to = State.TALKING
    ) {
        bubbleText = errorText
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Chuyển trạng thái từ [from] → [to].
     * @return true nếu chuyển thành công, false nếu trạng thái hiện tại không phải [from].
     */
    private fun transition(from: State, to: State, sideEffect: () -> Unit = {}): Boolean {
        if (_state != from) {
            Log.w(TAG, "Không thể chuyển $from→$to: trạng thái hiện tại là $_state")
            return false
        }
        return doTransition(from, to, sideEffect)
    }

    /**
     * Giống [transition] nhưng chấp nhận chuyển từ NHIỀU trạng thái nguồn khác nhau
     * (ví dụ [showError] dùng được từ cả LISTENING và THINKING).
     */
    private fun transitionAny(from: Set<State>, to: State, sideEffect: () -> Unit = {}): Boolean {
        val current = _state
        if (current !in from) {
            Log.w(TAG, "Không thể chuyển $current→$to: yêu cầu từ 1 trong $from")
            return false
        }
        return doTransition(current, to, sideEffect)
    }

    private fun doTransition(from: State, to: State, sideEffect: () -> Unit): Boolean {
        sideEffect()
        _state = to
        Log.d(TAG, "State: $from → $to (bubble=\"$bubbleText\" expr=\"$activeExpression\")")
        onStateChanged?.invoke(from, to)
        return true
    }
}

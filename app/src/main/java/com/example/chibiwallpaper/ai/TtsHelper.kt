package com.example.chibiwallpaper.ai

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

/**
 * PHẦN 16 — Đọc to bằng TextToSpeech CÓ SẴN của Android (không dùng giọng riêng của Milky, không
 * cho chọn giọng — dùng đúng giọng mặc định mà engine TTS (thường là Google Text-to-speech) đã
 * cài cho từng ngôn ngữ). Bản đầu (v1), chưa hỗ trợ chọn Voice cụ thể.
 *
 * Vòng đời giống [SpeechToTextManager]: [init] trên main thread, [release] khi service destroy.
 *
 * Quan trọng: caller (service) PHẢI đợi [speak]'s onDone rồi mới gọi lại STT.startListening(),
 * để tránh Milky tự ghi âm lại chính giọng đọc của mình (vòng lặp vô nghĩa) — xem service.
 */
class TtsHelper(private val context: Context) {

    private var tts: TextToSpeech? = null
    @Volatile private var isReady = false

    companion object {
        private const val TAG = "ChibiTTS"

        /** Map mã ngôn ngữ ngắn (Gemini trả về) sang Locale — mở rộng thêm khi cần. */
        private val LANG_TO_LOCALE = mapOf(
            "vi" to Locale("vi", "VN"),
            "en" to Locale.US,
            "ja" to Locale.JAPAN,
            "ko" to Locale.KOREA,
            "zh" to Locale.SIMPLIFIED_CHINESE,
            "fr" to Locale.FRANCE,
            "de" to Locale.GERMANY,
            "es" to Locale("es", "ES"),
            "th" to Locale("th", "TH"),
            "id" to Locale("id", "ID")
        )
    }

    /** Khởi tạo engine TTS. Phải gọi trên main thread. */
    fun init() {
        tts = TextToSpeech(context) { status ->
            isReady = (status == TextToSpeech.SUCCESS)
            if (!isReady) Log.e(TAG, "TextToSpeech init thất bại (status=$status)")
            else Log.d(TAG, "TextToSpeech sẵn sàng")
        }
    }

    /**
     * Đọc to [text] bằng giọng mặc định của [langCode] (vd "en", "ja"). Nếu máy chưa cài gói
     * ngôn ngữ đó ([TextToSpeech.LANG_MISSING_DATA]/[TextToSpeech.LANG_NOT_SUPPORTED]) hoặc engine
     * chưa sẵn sàng → không đọc, gọi [onDone] ngay để caller không bị treo vòng lặp chờ.
     */
    fun speak(text: String, langCode: String, onDone: () -> Unit) {
        val engine = tts
        if (engine == null || !isReady || text.isBlank()) {
            onDone()
            return
        }

        val locale = LANG_TO_LOCALE[langCode.lowercase()] ?: run {
            Log.w(TAG, "Không map được langCode='$langCode' sang Locale — bỏ qua đọc, chỉ hiện chữ")
            onDone()
            return
        }

        val availability = engine.isLanguageAvailable(locale)
        if (availability == TextToSpeech.LANG_MISSING_DATA || availability == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Thiếu gói giọng đọc cho $locale (availability=$availability) — bỏ qua đọc")
            onDone()
            return
        }
        engine.language = locale

        val utteranceId = UUID.randomUUID().toString()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { onDone() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { onDone() }
            override fun onError(utteranceId: String?, errorCode: Int) { onDone() }
        })

        val params = Bundle()
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "speak() trả lỗi ($result)")
            onDone()
        }
    }

    /** Dừng đọc ngay lập tức (dùng khi user huỷ giữa chừng). */
    fun stop() {
        tts?.stop()
    }

    /** Giải phóng tài nguyên. Phải gọi trên main thread khi service bị destroy. */
    fun release() {
        tts?.shutdown()
        tts = null
        isReady = false
        Log.d(TAG, "TextToSpeech released")
    }
}

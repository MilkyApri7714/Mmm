package com.example.chibiwallpaper.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * PHẦN 4 — Quản lý STT bằng SpeechRecognizer có sẵn của Android.
 *
 * Luồng chính:
 *   [startListening] → SpeechRecognizer chạy → user nói.
 *   Sau [SILENCE_TIMEOUT_MS] không nhận thêm âm thanh → tự gọi [stopListening].
 *   Kết quả cuối cùng trả về qua [onResult]; lỗi/huỷ qua [onError].
 *
 * - Phải tạo trên MAIN thread (SpeechRecognizer yêu cầu).
 * - Chỉ gọi [startListening] khi đã có quyền RECORD_AUDIO.
 * - [release] phải được gọi khi WallpaperService bị destroy.
 *
 * Bộ đếm 3 giây im lặng chạy trên main-thread Handler:
 *   - Reset mỗi khi [onRmsChanged] phát hiện âm thanh đủ lớn (> [RMS_THRESHOLD]).
 *   - Hết giờ → stopListening() → [onResult] sẽ nhận kết quả partial cuối cùng.
 */
class SpeechToTextManager(
    private val context: Context,
    private val onResult: (text: String) -> Unit,
    private val onError: (errorCode: Int) -> Unit,
    private val onListeningStarted: () -> Unit = {},
    private val onListeningStopped: () -> Unit = {}
) {

    private var recognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var partialText = ""
    private var isListening = false

    // Runnable đếm ngược 3 giây im lặng
    private val silenceTimeoutRunnable = Runnable {
        Log.d(TAG, "Hết 3s im lặng → tự ngắt ghi âm")
        stopListening()
        val result = partialText.trim()
        if (result.isNotEmpty()) {
            onResult(result)
        } else {
            onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        }
    }

    companion object {
        private const val TAG = "ChibiSTT"
        private const val SILENCE_TIMEOUT_MS = 3_000L
        private const val RMS_THRESHOLD = -1.5f  // dB, âm thanh đủ để coi là đang nói
    }

    /** Tạo SpeechRecognizer. Phải gọi trên main thread. */
    fun init() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Máy không hỗ trợ SpeechRecognizer")
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(recognitionListener)
        }
        Log.d(TAG, "SpeechRecognizer khởi tạo xong")
    }

    /**
     * Bắt đầu nghe. Phải gọi trên main thread.
     * Nếu đang nghe rồi thì không làm gì.
     */
    fun startListening() {
        if (isListening) return
        val r = recognizer ?: run {
            Log.w(TAG, "startListening() nhưng chưa init() — thử init lại")
            init()
            recognizer ?: return
        }

        partialText = ""
        isListening = true

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // Cho phép nhận nhiều ngôn ngữ — ưu tiên tiếng Việt nhưng fallback English
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            // Tắt bộ phát hiện im lặng tích hợp (ta tự dùng bộ đếm 3s)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        r.startListening(intent)
        resetSilenceTimer()
        onListeningStarted()
        Log.d(TAG, "Bắt đầu nghe…")
    }

    /**
     * Dừng nghe sớm (ví dụ user tap lại, hoặc do silence timeout).
     * Phải gọi trên main thread.
     */
    fun stopListening() {
        if (!isListening) return
        cancelSilenceTimer()
        recognizer?.stopListening()
        isListening = false
        onListeningStopped()
        Log.d(TAG, "Dừng nghe")
    }

    /** Giải phóng tài nguyên. Phải gọi trên main thread khi service bị destroy. */
    fun release() {
        cancelSilenceTimer()
        recognizer?.destroy()
        recognizer = null
        isListening = false
        Log.d(TAG, "SpeechRecognizer released")
    }

    val currentlyListening: Boolean get() = isListening

    // ─────────────────────────────────────────────────────────────────────────

    private fun resetSilenceTimer() {
        mainHandler.removeCallbacks(silenceTimeoutRunnable)
        mainHandler.postDelayed(silenceTimeoutRunnable, SILENCE_TIMEOUT_MS)
    }

    private fun cancelSilenceTimer() {
        mainHandler.removeCallbacks(silenceTimeoutRunnable)
    }

    private val recognitionListener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "onReadyForSpeech")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "onBeginningOfSpeech")
            resetSilenceTimer()
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Có âm thanh đủ lớn → reset bộ đếm im lặng
            if (rmsdB > RMS_THRESHOLD) resetSilenceTimer()
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "onEndOfSpeech")
        }

        override fun onError(error: Int) {
            Log.w(TAG, "onError: $error (${sttErrorName(error)})")
            cancelSilenceTimer()
            isListening = false
            onListeningStopped()
            // ERROR_NO_MATCH thường xảy ra khi dùng partialResults; kết quả thực sự đến qua
            // onPartialResults / onResults rồi → đây ta dùng partialText đã tích luỹ.
            if (error == SpeechRecognizer.ERROR_NO_MATCH && partialText.isNotEmpty()) {
                onResult(partialText.trim())
            } else {
                onError(error)
            }
        }

        override fun onResults(results: Bundle?) {
            cancelSilenceTimer()
            isListening = false
            onListeningStopped()
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim() ?: partialText.trim()
            Log.d(TAG, "onResults: \"$text\"")
            if (text.isNotEmpty()) onResult(text) else onError(SpeechRecognizer.ERROR_NO_MATCH)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partial = matches?.firstOrNull() ?: return
            partialText = partial
            Log.v(TAG, "Partial: \"$partial\"")
            // Reset bộ đếm im lặng mỗi khi nhận thêm partial (người dùng vẫn đang nói)
            resetSilenceTimer()
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun sttErrorName(code: Int) = when (code) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
        else -> "UNKNOWN($code)"
    }
}

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
 * PHẦN 27 (fix) — SpeechRecognizer trên nhiều máy (Samsung/Xiaomi...) CRASH nếu stopListening()
 * được gọi TRƯỚC KHI onReadyForSpeech() báo về (vd. nhấn-giữ rồi buông tay ngay, không nói gì —
 * recognizer chưa kịp khởi động xong nội bộ). cancel() an toàn hơn để gọi bất kỳ lúc nào, kể cả
 * trước khi sẵn sàng — stopListening() chỉ dùng khi ĐÃ chắc chắn recognizer đang thực sự lắng
 * nghe (readyForSpeech = true). Mọi lệnh gọi recognizer đều bọc try/catch làm lớp bảo vệ cuối.
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
    private var readyForSpeech = false

    private var activeSilenceTimeoutMs = SILENCE_TIMEOUT_MS

    private val silenceTimeoutRunnable = Runnable {
        Log.d(TAG, "Hết ${activeSilenceTimeoutMs}ms im lặng → tự ngắt ghi âm")
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
        const val BILINGUAL_SILENCE_TIMEOUT_MS = 1_500L
        private const val RMS_THRESHOLD = -1.5f
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

    /** Bắt đầu nghe. Phải gọi trên main thread. */
    fun startListening(silenceTimeoutMs: Long = SILENCE_TIMEOUT_MS) {
        if (isListening) return
        val r = recognizer ?: run {
            Log.w(TAG, "startListening() nhưng chưa init() — thử init lại")
            init()
            recognizer ?: return
        }

        partialText = ""
        isListening = true
        readyForSpeech = false
        activeSilenceTimeoutMs = silenceTimeoutMs

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        try {
            r.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening lỗi: ${e.message}")
            isListening = false
            return
        }
        resetSilenceTimer()
        onListeningStarted()
        Log.d(TAG, "Bắt đầu nghe…")
    }

    /**
     * Dừng nghe sớm (ví dụ user tap lại, hoặc do silence timeout).
     * Phải gọi trên main thread. PHẦN 27 (fix) — xem doc ở đầu file.
     */
    fun stopListening() {
        if (!isListening) return
        cancelSilenceTimer()
        try {
            if (readyForSpeech) {
                recognizer?.stopListening()
            } else {
                Log.w(TAG, "stopListening() gọi trước onReadyForSpeech — dùng cancel() an toàn hơn")
                recognizer?.cancel()
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopListening/cancel lỗi: ${e.message}")
        }
        isListening = false
        readyForSpeech = false
        onListeningStopped()
        Log.d(TAG, "Dừng nghe")
    }

    /** Giải phóng tài nguyên. Phải gọi trên main thread khi service bị destroy. */
    fun release() {
        cancelSilenceTimer()
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "release lỗi: ${e.message}")
        }
        recognizer = null
        isListening = false
        readyForSpeech = false
        Log.d(TAG, "SpeechRecognizer released")
    }

    val currentlyListening: Boolean get() = isListening

    // ─────────────────────────────────────────────────────────────────────────

    private fun resetSilenceTimer() {
        mainHandler.removeCallbacks(silenceTimeoutRunnable)
        mainHandler.postDelayed(silenceTimeoutRunnable, activeSilenceTimeoutMs)
    }

    private fun cancelSilenceTimer() {
        mainHandler.removeCallbacks(silenceTimeoutRunnable)
    }

    private val recognitionListener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "onReadyForSpeech")
            readyForSpeech = true
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "onBeginningOfSpeech")
            resetSilenceTimer()
        }

        override fun onRmsChanged(rmsdB: Float) {
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
            readyForSpeech = false
            onListeningStopped()
            if (error == SpeechRecognizer.ERROR_NO_MATCH && partialText.isNotEmpty()) {
                onResult(partialText.trim())
            } else {
                onError(error)
            }
        }

        override fun onResults(results: Bundle?) {
            cancelSilenceTimer()
            isListening = false
            readyForSpeech = false
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

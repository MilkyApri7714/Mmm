package com.example.chibiwallpaper.ai

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.example.chibiwallpaper.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * PHẦN 21 — "Chế độ trò chuyện" (voice-out): đọc to câu trả lời CHAT THƯỜNG của Milky bằng
 * Gemini TTS (giọng + style prompt tuỳ chỉnh do chủ nhân tự sửa ở MainActivity), KHÁC với
 * [TtsHelper] (Android TextToSpeech hệ thống) chỉ dùng riêng cho Hội thoại song phương (PHẦN 16).
 *
 * PHẦN 21-C (fallback đa model) — Gemini có 2 "thế hệ" API TTS đang cùng tồn tại:
 *   - Thế hệ MỚI (Interactions API, endpoint /v1beta/interactions): gemini-3.8-flash-tts,
 *     gemini-3.8-flash-lite-tts.
 *   - Thế hệ CŨ (generateContent, endpoint /v1beta/models/{model}:generateContent):
 *     gemini-3.1-flash-tts-preview, gemini-2.5-flash-preview-tts.
 * Vì tài khoản/khu vực khác nhau có thể chưa bật hết các model mới, [MODEL_FALLBACK_CHAIN] thử
 * lần lượt từng model theo thứ tự ưu tiên — hễ 1 model trả lỗi HTTP (404 model không tồn tại,
 * 400 tài khoản chưa có quyền, mất mạng...) thì tự động rớt xuống model kế tiếp trong danh sách,
 * KHÔNG báo lỗi ngay cho user. Chỉ khi TẤT CẢ đều lỗi thì mới bỏ qua đọc to (xem [speak]).
 *
 * Không cần init() như TtsHelper vì đây là network call theo yêu cầu — chỉ cần [release]/[stop]
 * để huỷ MediaPlayer đang phát nếu service destroy hoặc user huỷ giữa chừng.
 *
 * Lưu ý: [speak] tự chạy network + decode trên thread riêng, rồi post [onDone] về main thread —
 * caller (service) KHÔNG cần tự wrap trong coroutine/mainHandler.
 */
class GeminiTtsHelper(private val appContext: Context) {

    /** 2 shape request/response khác nhau giữa 2 thế hệ API TTS của Gemini. */
    private enum class ApiShape { INTERACTIONS, GENERATE_CONTENT }

    private data class ModelConfig(val modelId: String, val shape: ApiShape)

    companion object {
        private const val TAG = "ChibiGeminiTts"

        private const val INTERACTIONS_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/interactions"

        private const val SAMPLE_RATE = 24_000 // dùng để tự bọc WAV header cho 2 model generateContent cũ

        private const val DEFAULT_VOICE = "Zephyr" // "Bright" theo mô tả chính thức của Google

        // PHẦN 21-C — Thứ tự ưu tiên thử model: MỚI nhất → CŨ nhất. Muốn đổi thứ tự / bớt model
        // (ví dụ chỉ muốn dùng đúng 1 model, không fallback) thì sửa list này.
        private val MODEL_FALLBACK_CHAIN = listOf(
            ModelConfig("gemini-3.8-flash-lite-tts",      ApiShape.INTERACTIONS),      // rẻ, nhanh, khuyến nghị cho chat
            ModelConfig("gemini-3.8-flash-tts",            ApiShape.INTERACTIONS),      // fallback 1: chất lượng cao hơn
            ModelConfig("gemini-3.1-flash-tts-preview",    ApiShape.GENERATE_CONTENT),  // fallback 2: thế hệ cũ
            ModelConfig("gemini-2.5-flash-preview-tts",    ApiShape.GENERATE_CONTENT),  // fallback 3: cũ nhất, ổn định nhất
        )

        // PHẦN 21-B — Lưu ý từ guide migration của Google: đoạn "Audio Profile" + "Director's Notes"
        // nhiều đoạn văn (kiểu bạn crafted ở AI Studio) là nguyên nhân phổ biến nhất gây "trôi giọng"
        // (voice drift) ở model 3.8 — Google khuyên tạo persona 1 lần bằng Voice Design rồi chỉ
        // truyền voice_... id, để "style" mỗi lượt ngắn gọn. Mình vẫn giữ style dài này làm mặc định
        // vì bạn đã ưng giọng ra — nếu sau này thấy giọng Milky "trôi" qua nhiều câu, rút gọn lại.
        val DEFAULT_STYLE_PROMPT = """
Milky là một linh vật chibi nhỏ nhắn, chất giọng rất giống nhân vật anime tíu tít, hay nũng nịu,
luôn vui vẻ và tràn đầy năng lượng như một đứa trẻ. Nói theo phong cách Promo/Hype, nhịp điệu
staccato (nhanh, ngắt nhịp rõ ràng), giọng trung lập không mang accent vùng miền cụ thể.
        """.trimIndent()
    }

    @Volatile private var currentPlayer: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Index xoay vòng riêng cho TTS — độc lập với GeminiClient để không làm lệch nhau. */
    @Volatile private var ttsKeyIndex = 0

    /**
     * Lấy key tiếp theo từ pool 6 key (1 chính + 5 Alt).
     * Key nào để trống thì bỏ qua — pool co lại tự động.
     */
    private fun getNextTtsApiKey(): String? {
        val p = appContext.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val keys = listOfNotNull(
            p.getString(MainActivity.KEY_GEMINI_API_KEY,     null)?.takeIf { it.isNotBlank() },
            p.getString(MainActivity.KEY_ROBINROUND_API_KEY, null)?.takeIf { it.isNotBlank() },
            p.getString(MainActivity.KEY_ALT_API_KEY_2,      null)?.takeIf { it.isNotBlank() },
            p.getString(MainActivity.KEY_ALT_API_KEY_3,      null)?.takeIf { it.isNotBlank() },
            p.getString(MainActivity.KEY_ALT_API_KEY_4,      null)?.takeIf { it.isNotBlank() },
            p.getString(MainActivity.KEY_ALT_API_KEY_5,      null)?.takeIf { it.isNotBlank() },
        )
        if (keys.isEmpty()) return null
        val key = keys[ttsKeyIndex % keys.size]
        Log.d(TAG, "Round-robin TTS: dùng key #${ttsKeyIndex % keys.size + 1}/${keys.size}")
        ttsKeyIndex++
        return key
    }

    /** Gửi [text] lên Gemini TTS (tự fallback qua các model) rồi phát ra loa. [onDone] luôn được gọi. */
    fun speak(text: String, onDone: () -> Unit) {
        if (text.isBlank()) { onDone(); return }

        val prefs   = appContext.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey  = getNextTtsApiKey()
        if (apiKey == null) {
            Log.w(TAG, "Chưa có Gemini API key nào — bỏ qua đọc to (chế độ trò chuyện)")
            onDone()
            return
        }
        val stylePrompt = prefs.getString(MainActivity.KEY_TTS_STYLE_PROMPT, null)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_STYLE_PROMPT
        val voiceName = prefs.getString(MainActivity.KEY_TTS_VOICE_NAME, null)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_VOICE

        Thread {
            try {
                val wavBytes = fetchWavWithFallback(apiKey, stylePrompt, text, voiceName)
                if (wavBytes == null) {
                    Log.e(TAG, "Cả ${MODEL_FALLBACK_CHAIN.size} model TTS đều lỗi — bỏ qua đọc to")
                    mainHandler.post(onDone)
                    return@Thread
                }
                val wavFile = File(appContext.cacheDir, "milky_tts_${System.currentTimeMillis()}.wav")
                FileOutputStream(wavFile).use { it.write(wavBytes) }
                mainHandler.post { playWav(wavFile, onDone) }
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi Gemini TTS: ${e.message}", e)
                mainHandler.post(onDone)
            }
        }.start()
    }

    // ── Fallback chain ─────────────────────────────────────────────────────────

    /** Thử lần lượt từng model trong [MODEL_FALLBACK_CHAIN]; trả về WAV bytes đầu tiên thành công. */
    private fun fetchWavWithFallback(
        apiKey: String, stylePrompt: String, text: String, voiceName: String
    ): ByteArray? {
        for ((index, config) in MODEL_FALLBACK_CHAIN.withIndex()) {
            val result = try {
                when (config.shape) {
                    ApiShape.INTERACTIONS      -> fetchViaInteractions(config.modelId, apiKey, stylePrompt, text, voiceName)
                    ApiShape.GENERATE_CONTENT  -> fetchViaGenerateContent(config.modelId, apiKey, stylePrompt, text, voiceName)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Model '${config.modelId}' lỗi exception: ${e.message}")
                null
            }
            if (result != null) {
                Log.d(TAG, "TTS thành công với model '${config.modelId}'" +
                    if (index > 0) " (đã fallback qua ${index} model trước đó)" else "")
                return result
            }
        }
        return null
    }

    // ── Thế hệ MỚI — Interactions API (gemini-3.8-*) ───────────────────────────

    private fun fetchViaInteractions(
        modelId: String, apiKey: String, stylePrompt: String, text: String, voiceName: String
    ): ByteArray? {
        val requestBody = JSONObject().apply {
            put("model", modelId)
            put("input", JSONArray().put(
                JSONObject().apply {
                    put("type", "user_input")
                    put("content", JSONArray().put(
                        JSONObject().apply {
                            put("type", "text")
                            put("text", text) // verbatim
                            put("annotations", JSONArray().put(
                                JSONObject().apply {
                                    put("type", "speech_metadata")
                                    put("style", stylePrompt)
                                }
                            ))
                        }
                    ))
                }
            ))
            put("response_format", JSONObject().put("type", "audio")) // mặc định trả WAV có header
            put("generation_config", JSONObject().put(
                "speech_config", JSONArray().put(JSONObject().put("voice", voiceName))
            ))
        }.toString()

        val conn = URL(INTERACTIONS_ENDPOINT).openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey) // Interactions API dùng header, không phải ?key=
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        conn.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        if (code != 200) {
            val err = conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            Log.w(TAG, "HTTP $code ('$modelId', Interactions API): $err")
            return null
        }
        val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }

        // audio nằm ở steps[] có type == "model_output", content[] có type == "audio" → field "data"
        // (base64 của file WAV đầy đủ, đã có RIFF header — không cần tự bọc).
        val steps = JSONObject(body).optJSONArray("steps") ?: return null
        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            if (step.optString("type") != "model_output") continue
            val contentArr = step.optJSONArray("content") ?: continue
            for (j in 0 until contentArr.length()) {
                val part = contentArr.getJSONObject(j)
                if (part.optString("type") == "audio" && part.has("data")) {
                    return Base64.decode(part.getString("data"), Base64.DEFAULT)
                }
            }
        }
        Log.w(TAG, "Không tìm thấy audio trong response ('$modelId', Interactions API)")
        return null
    }

    // ── Thế hệ CŨ — generateContent (gemini-3.1-flash-tts-preview / gemini-2.5-flash-preview-tts) ──

    private fun fetchViaGenerateContent(
        modelId: String, apiKey: String, stylePrompt: String, text: String, voiceName: String
    ): ByteArray? {
        val requestBody = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().put(
                    // Thế hệ cũ: không có "annotations" riêng, phải gộp style + text vào 1 chuỗi.
                    JSONObject().put("text", "$stylePrompt: $text")
                ))
            ))
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().put("AUDIO"))
                put("speechConfig", JSONObject().apply {
                    put("voiceConfig", JSONObject().apply {
                        put("prebuiltVoiceConfig", JSONObject().put("voiceName", voiceName))
                    })
                })
            })
        }.toString()

        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent"
        val conn = URL("$endpoint?key=$apiKey").openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        conn.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        if (code != 200) {
            val err = conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            Log.w(TAG, "HTTP $code ('$modelId', generateContent): $err")
            return null
        }
        val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }

        val parts = JSONObject(body).optJSONArray("candidates")
            ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return null

        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (part.has("inlineData")) {
                // Thế hệ cũ trả PCM 16-bit mono @24kHz KHÔNG có header → tự bọc WAV trước khi trả về.
                val pcm = Base64.decode(part.getJSONObject("inlineData").getString("data"), Base64.DEFAULT)
                return wrapPcmAsWav(pcm)
            }
        }
        Log.w(TAG, "Không tìm thấy inlineData trong response ('$modelId', generateContent)")
        return null
    }

    private fun wrapPcmAsWav(pcm: ByteArray): ByteArray {
        val byteRate = SAMPLE_RATE * 2 // 16-bit mono
        val header = ByteArray(44)
        "RIFF".toByteArray().copyInto(header, 0)
        writeInt(header, 4, 36 + pcm.size)
        "WAVE".toByteArray().copyInto(header, 8)
        "fmt ".toByteArray().copyInto(header, 12)
        writeInt(header, 16, 16)   // Subchunk1Size (PCM)
        writeShort(header, 20, 1)  // AudioFormat = PCM
        writeShort(header, 22, 1)  // NumChannels = mono
        writeInt(header, 24, SAMPLE_RATE)
        writeInt(header, 28, byteRate)
        writeShort(header, 32, 2)  // BlockAlign
        writeShort(header, 34, 16) // BitsPerSample
        "data".toByteArray().copyInto(header, 36)
        writeInt(header, 40, pcm.size)
        return header + pcm
    }

    private fun writeInt(b: ByteArray, offset: Int, value: Int) {
        b[offset] = (value and 0xff).toByte()
        b[offset + 1] = ((value shr 8) and 0xff).toByte()
        b[offset + 2] = ((value shr 16) and 0xff).toByte()
        b[offset + 3] = ((value shr 24) and 0xff).toByte()
    }

    private fun writeShort(b: ByteArray, offset: Int, value: Int) {
        b[offset] = (value and 0xff).toByte()
        b[offset + 1] = ((value shr 8) and 0xff).toByte()
    }

    // ── Playback ────────────────────────────────────────────────────────────

    private fun playWav(file: File, onDone: () -> Unit) {
        stop() // huỷ player cũ nếu còn đang phát dở (tránh chồng 2 câu nói)
        val player = MediaPlayer()
        currentPlayer = player
        try {
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener { mp ->
                file.delete()
                mp.release()
                if (currentPlayer === mp) currentPlayer = null
                onDone()
            }
            player.setOnErrorListener { mp, _, _ ->
                file.delete()
                mp.release()
                if (currentPlayer === mp) currentPlayer = null
                onDone()
                true
            }
            player.prepare()
            player.start()
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi phát WAV: ${e.message}", e)
            file.delete()
            onDone()
        }
    }

    /** Dừng phát ngay lập tức (user huỷ giữa chừng / service destroy). */
    fun stop() {
        currentPlayer?.let {
            try { if (it.isPlaying) it.stop() } catch (_: Exception) { /* đã dừng rồi */ }
            try { it.release() } catch (_: Exception) { }
        }
        currentPlayer = null
    }

    fun release() = stop()
}

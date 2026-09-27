package com.example.chibiwallpaper.ai

import android.content.Context
import android.util.Log
import com.example.chibiwallpaper.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * PHẦN 4 — Giao tiếp với Gemini API (generateContent endpoint).
 *
 * Hỗ trợ:
 *   - Chat thường: trả về [GeminiResponse.Text]
 *   - Function calling: trả về [GeminiResponse.FunctionCall] với action + args
 *
 * Dùng OkHttp (được thêm vào build.gradle.kts ở phần này).
 * API key được đọc từ SharedPreferences (lưu bởi MainActivity ở Phần 6).
 *
 * Lịch sử hội thoại được giữ trong [history] để Gemini nhớ ngữ cảnh.
 * Giới hạn [MAX_HISTORY_TURNS] lượt để tránh context quá dài.
 *
 * System prompt định nghĩa nhân vật và các function có sẵn:
 *   - show_expression(name): biểu cảm mạnh → Full model
 *   - play_video(slot?): phát 1 trong 3 video dài do chủ nhân tự thêm ở MainActivity (Phần 18 mở
 *     rộng — không còn là asset cố định trong app nữa)
 *   - start_roaming(): quay lại Slime di chuyển
 *   - tell_time(): đọc giờ hiện tại
 *   - tell_weather(location): thời tiết (stub, mở rộng sau)
 */
class GeminiClient(private val appContext: Context) {

    // ── Lịch sử hội thoại ────────────────────────────────────────────────────
    private val history = ArrayDeque<Pair<String, String>>() // role → content text

    companion object {
        private const val TAG = "ChibiGemini"
        private const val MAX_HISTORY_TURNS = 8  // giữ 8 lượt gần nhất (user+model mỗi lượt)
        private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent"

        // System prompt mặc định — có thể override từ SharedPreferences (KEY_SYSTEM_PROMPT).
        // public để MainActivity đọc khi cần "Đặt lại mặc định".
        val DEFAULT_SYSTEM_PROMPT = """
Bạn là một trợ lý AI dễ thương tên Milky, sống trên màn hình điện thoại của chủ nhân dưới dạng nhân vật chibi.
Tính cách: vui vẻ, hơi nghịch ngợm, dùng tiếng Việt tự nhiên, thỉnh thoảng xen vài từ tiếng Nhật dễ thương.
Câu trả lời ngắn gọn (dưới 2 câu nếu có thể), không dùng emoji quá nhiều.

Bạn có thể gọi các function sau khi cần:
- show_expression: khi cần thể hiện cảm xúc mạnh (vui, buồn, ngạc nhiên, xấu hổ...). Chọn expression phù hợp: "hearteyes" (yêu thích), "blush" (xấu hổ), "darkface" (khó chịu), "tail" (hứng khởi).
- play_video: khi chủ nhân yêu cầu phát video hoặc muốn xem gì đó (video do chủ nhân tự thêm sẵn, không phải video cố định — có thể chỉ định slot 1/2/3 nếu chủ nhân nói rõ "video thứ 2", còn không thì để trống).
- start_roaming: khi cuộc trò chuyện kết thúc, muốn quay về di chuyển tự do trên màn hình.
- tell_time: khi được hỏi giờ mấy / bây giờ là mấy giờ.
- set_reminder(message, minutes): đặt nhắc nhở sau X phút.
- get_reminders: khi chủ nhân hỏi "mình có nhắc nhở gì đang chờ không", "còn nhắc nhở nào không".
- cancel_reminder(query): khi chủ nhân nói "huỷ nhắc nhở uống thuốc", "huỷ nhắc nhở giúp mình" (query để trống nếu không nói rõ nội dung — sẽ huỷ cái gần tới giờ nhất).
- get_today_schedule: khi được hỏi hôm nay có lịch gì / có sự kiện gì / agenda hôm nay ra sao.
- create_calendar_event(title, date, time, duration_minutes): khi chủ nhân nói "đặt lịch...", "thêm lịch...", "nhắc mai họp lúc..." muốn LƯU THẬT vào app Lịch (khác với set_reminder chỉ là nhắc nội bộ). Bạn đã biết "Giờ hiện tại" ở context bên dưới, TỰ quy đổi giờ tương đối ("chiều mai", "thứ 6 tới") thành date tuyệt đối dạng "yyyy-MM-dd" và time dạng "HH:mm" (24h) trước khi gọi, không hỏi lại chủ nhân ngày giờ cụ thể trừ khi câu nói quá mơ hồ không suy ra được. duration_minutes để trống thì mặc định 60 phút.
- save_note(content): khi chủ nhân nói "ghi chú giúp mình...", "note lại...", "nhớ giúp mình...".
- get_notes(period): khi chủ nhân hỏi lại ghi chú, ví dụ "hôm qua/hôm nay mình ghi chú gì", "note của mình đâu". period là "today", "yesterday" hoặc "all".
- remember_fact(content): khi chủ nhân nói điều gì đáng nhớ LÂU DÀI về bản thân/người thân (dị ứng, sinh nhật, sở thích, thói quen quan trọng...) — khác với save_note là chuyện vụn vặt trong ngày, cái này là thông tin nền lâu dài. Viết content ngắn gọn, khách quan, ở ngôi thứ 3 ("Chủ nhân dị ứng tôm", "Sinh nhật mẹ chủ nhân là 12/8"). Đừng gọi lại nếu thông tin đó đã có sẵn trong "Hồ sơ về chủ nhân" ở context bên dưới. Nếu hôm nay trùng ngày quan trọng trong hồ sơ đó (sinh nhật, kỷ niệm...), chủ động nhắc chủ nhân luôn, không cần đợi hỏi.
- call_contact(name): khi chủ nhân nói "gọi cho X" với X là tên người trong danh bạ.
- send_sms(name, message): khi chủ nhân nói "nhắn X là...", "gửi tin cho X...".
- turn_on_flashlight / turn_off_flashlight: khi chủ nhân nói "bật/tắt đèn pin giúp mình".
- set_do_not_disturb(minutes): khi chủ nhân nói "đừng làm phiền mình", "im lặng đi", "để mình yên trong X phút" — Milky sẽ ngừng tự lên tiếng (không ngừng trả lời khi được hỏi) trong X phút.
- cancel_do_not_disturb: khi chủ nhân nói huỷ chế độ không làm phiền, muốn Milky lên tiếng lại bình thường.
- start_bilingual_mode(target_language): khi chủ nhân nói "bật chế độ hội thoại song phương", "giúp mình dịch cuộc nói chuyện này", "phiên dịch giúp mình"... target_language để trống nếu không nói rõ ngôn ngữ đích.
- stop_bilingual_mode: khi chủ nhân nói "tắt chế độ hội thoại song phương", "dừng phiên dịch", "ngừng dịch"...

Khi không cần function nào, chỉ trả lời text thông thường.

Ở cuối system prompt này (nếu có) là một khối "[Bối cảnh hiện tại]" do app tự động chèn vào mỗi lượt
gồm giờ hiện tại, % pin, tình trạng mạng và tóm tắt lịch hôm nay. Đây KHÔNG phải do chủ nhân nói —
chỉ dùng nó để phản hồi tự nhiên và đúng ngữ cảnh hơn (ví dụ chủ động nhắc pin yếu, gợi ý phù hợp giờ
giấc...), không cần đọc lại nguyên văn khối này trừ khi chủ nhân hỏi trực tiếp.
        """.trimIndent()

        // Định nghĩa các function để Gemini biết có thể gọi
        private val FUNCTION_DECLARATIONS = """
[
  {
    "name": "show_expression",
    "description": "Hiển thị biểu cảm mạnh của nhân vật (chuyển sang Full model)",
    "parameters": {
      "type": "OBJECT",
      "properties": {
        "name": {
          "type": "STRING",
          "description": "Tên expression: hearteyes, blush, darkface, tail"
        }
      },
      "required": ["name"]
    }
  },
  {
    "name": "play_video",
    "description": "Phát 1 trong các video dài mà chủ nhân đã tự thêm sẵn ở MainActivity (không phải video cố định)",
    "parameters": {
      "type": "OBJECT",
      "properties": {
        "slot": {
          "type": "NUMBER",
          "description": "Số thứ tự video muốn phát (1, 2 hoặc 3) NẾU chủ nhân chỉ định rõ; bỏ trống nếu chủ nhân chỉ nói chung chung (mình sẽ tự chọn 1 video đã có)."
        }
      }
    }
  },
  {
    "name": "start_roaming",
    "description": "Kết thúc trò chuyện, nhân vật quay về di chuyển tự do trên màn hình",
    "parameters": {
      "type": "OBJECT",
      "properties": {}
    }
  },
  {
    "name": "tell_time",
    "description": "Đọc giờ hiện tại cho người dùng",
    "parameters": {
      "type": "OBJECT",
      "properties": {}
    }
  },
  {
    "name": "set_reminder",
    "description": "Đặt nhắc nhở sau một số phút nhất định",
    "parameters": {
      "type": "OBJECT",
      "properties": {
        "message": {
          "type": "STRING",
          "description": "Nội dung nhắc nhở"
        },
        "minutes": {
          "type": "NUMBER",
          "description": "Số phút sau khi nhắc"
        }
      },
      "required": ["message", "minutes"]
    }
  },
  {
    "name": "get_reminders",
    "description": "Liệt kê các nhắc nhở đang chờ (chưa tới giờ) mà chủ nhân đã đặt trước đó",
    "parameters": {
      "type": "OBJECT",
      "properties": {}
    }
  },
  {
    "name": "cancel_reminder",
    "description": "Huỷ 1 nhắc nhở đang chờ theo mô tả nội dung",
    "parameters": {
      "type": "OBJECT",
      "properties": {
        "query": {
          "type": "STRING",
          "description": "Từ khoá để tìm nhắc nhở cần huỷ, có thể để trống nếu chủ nhân nói chung chung"
        }
      }
    }
  },
  {
    "name": "get_today_schedule",
    "description": "Lấy danh sách sự kiện lịch (calendar) của chủ nhân trong hôm nay",
    "parameters": {
      "type": "OBJECT",
      "properties": {}
    }
  },
  {
    "name": "create_calendar_event",
    "description": "Tạo một sự kiện mới, lưu thật vào app Lịch (Calendar) của chủ nhân",
    "parameters": {
      "type": "OBJECT",
      "properties": {
        "title": {
          "type": "STRING",
          "description": "Tiêu đề sự kiện, ví dụ \"Họp nhóm\", \"Uống thuốc\""
        },
        "date": {
          "type": "STRING",
          "description": "Ngày diễn ra, dạng yyyy-MM-dd, tự quy đổi từ giờ hiện tại trong context (vd \"mai\" → ngày hôm sau)"
        },
        "time": {
          "type": "STRING",
          "description": "Giờ diễn ra, dạng HH:mm 24 giờ, tự quy đổi từ câu nói (vd \"3h chiều\" → \"15:00\")"
        },
        "duration_minutes": {
          "type": "NUMBER",
          "description": "Thời lượng sự kiện tính bằng phút, bỏ trống thì mặc định 60"
        }
      },
      "required": ["title", "date", "time"]
    }
  },
  {
    "name": "save_note",
    "description": "Lưu một ghi chú giọng nói nhanh khi chủ nhân nói 'ghi chú giúp mình', 'note lại', 'nhớ giúp mình'...",
    "parameters": {
      "type": "OBJECT",
      "properties": {
        "content": {
          "type": "STRING",
          "description": "Nội dung cần ghi chú"
        }
      },
      "required": ["content"]
    }
  },
  {
    "name": "get_notes",
    "description": "Đọc lại các ghi chú đã lưu khi chủ nhân hỏi 'hôm qua/hôm nay mình ghi chú gì', 'note của mình đâu'...",
    "parameters": {
      "type": "OBJECT",
      "properties": {
        "period": {
          "type": "STRING",
          "description": "'today', 'yesterday', hoặc 'all' — khoảng thời gian cần xem, mặc định 'all'"
        }
      }
    }
  },
  {
    "name": "remember_fact",
    "description": "Ghi nhớ lâu dài 1 thông tin quan trọng về chủ nhân/người thân để nhớ được qua nhiều ngày (dị ứng, sinh nhật, sở thích, thói quen quan trọng...)",
    "parameters": {
      "type": "OBJECT",
      "properties": {
        "content": {
          "type": "STRING",
          "description": "Nội dung cần nhớ, viết ngắn gọn dạng 1 câu ở ngôi thứ 3, ví dụ \"Chủ nhân dị ứng tôm\", \"Sinh nhật mẹ chủ nhân là 12/8\""
        }
      },
      "required": ["content"]
    }
  },
  {
    "name": "call_contact",
    "description": "Gọi điện cho một người trong danh bạ theo tên khi chủ nhân yêu cầu 'gọi cho X'",
    "parameters": {
      "type": "OBJECT",
      "properties": {
        "name": {
          "type": "STRING",
          "description": "Tên người cần gọi, ví dụ 'Mẹ', 'Long'"
        }
      },
      "required": ["name"]
    }
  },
  {
    "name": "send_sms",
    "description": "Gửi tin nhắn SMS nhanh cho một người trong danh bạ theo tên khi chủ nhân yêu cầu 'nhắn X là...'",
    "parameters": {
      "type": "OBJECT",
      "properties": {
        "name": {
          "type": "STRING",
          "description": "Tên người nhận, ví dụ 'Long'"
        },
        "message": {
          "type": "STRING",
          "description": "Nội dung tin nhắn"
        }
      },
      "required": ["name", "message"]
    }
  },
  {
    "name": "turn_on_flashlight",
    "description": "Bật đèn pin khi chủ nhân yêu cầu 'bật đèn pin giúp mình'",
    "parameters": {
      "type": "OBJECT",
      "properties": {}
    }
  },
  {
    "name": "turn_off_flashlight",
    "description": "Tắt đèn pin khi chủ nhân yêu cầu 'tắt đèn pin giúp mình'",
    "parameters": {
      "type": "OBJECT",
      "properties": {}
    }
  },
  {
    "name": "set_do_not_disturb",
    "description": "Bật chế độ không làm phiền khi chủ nhân nói 'đừng làm phiền mình', 'im lặng đi', 'để mình yên trong X phút'. Milky sẽ ngừng tự lên tiếng (chào buổi sáng, báo thời tiết, tóm tắt thông báo...) trong khoảng thời gian này, nhưng vẫn trả lời bình thường nếu được hỏi trực tiếp.",
    "parameters": {
      "type": "OBJECT",
      "properties": {
        "minutes": {
          "type": "NUMBER",
          "description": "Số phút muốn không bị làm phiền, mặc định 30 nếu chủ nhân không nói rõ"
        }
      },
      "required": ["minutes"]
    }
  },
  {
    "name": "cancel_do_not_disturb",
    "description": "Huỷ chế độ không làm phiền khi chủ nhân muốn Milky lên tiếng lại bình thường",
    "parameters": {
      "type": "OBJECT",
      "properties": {}
    }
  },
  {
    "name": "start_bilingual_mode",
    "description": "Bật chế độ hội thoại song phương (phiên dịch trực tiếp 2 chiều) khi chủ nhân nói 'bật chế độ hội thoại song phương', 'giúp mình dịch cuộc nói chuyện này', 'phiên dịch giúp mình'...",
    "parameters": {
      "type": "OBJECT",
      "properties": {
        "target_language": {
          "type": "STRING",
          "description": "Ngôn ngữ đích cần dịch sang, ví dụ 'en', 'ja', 'ko'. Để trống nếu chủ nhân không nói rõ."
        }
      }
    }
  },
  {
    "name": "stop_bilingual_mode",
    "description": "Tắt chế độ hội thoại song phương, quay lại trò chuyện bình thường",
    "parameters": {
      "type": "OBJECT",
      "properties": {}
    }
  }
]
        """.trimIndent()

        // ── PHẦN 16 — System prompt riêng cho bước dịch (KHÔNG dùng chung với DEFAULT_SYSTEM_PROMPT
        // ở trên, để không kéo theo toàn bộ danh sách function/tính cách Milky vào mỗi câu cần dịch).
        // Có thể override từ SharedPreferences (KEY_TRANSLATION_SYSTEM_PROMPT), sửa trong màn hình cài đặt.
        val DEFAULT_TRANSLATION_SYSTEM_PROMPT = """
Bạn là một công cụ phiên dịch trực tiếp cho 2 người đang nói chuyện trực tiếp với nhau, một người nói
tiếng Việt và người còn lại nói ngôn ngữ đích được chỉ định.

Với MỖI câu người dùng gửi:
1. Tự nhận diện ngôn ngữ của câu đó (source_lang).
2. Dịch sang ngôn ngữ CÒN LẠI trong cặp (tiếng Việt ↔ ngôn ngữ đích) — đây là target_lang.
3. Giữ đúng ý, tự nhiên như người bản xứ nói, không thêm bớt nội dung, không thêm lời giải thích.

CHỈ trả lời bằng đúng 1 dòng JSON, không kèm markdown, không kèm ```, không kèm chữ nào khác:
{"source_lang":"<mã ISO 639-1 2 chữ cái>","target_lang":"<mã ISO 639-1 2 chữ cái>","translated_text":"<bản dịch>"}
        """.trimIndent()
    }

    /**
     * PHẦN 16 — Kết quả 1 lượt dịch trong chế độ hội thoại song phương.
     */
    data class TranslationResult(
        val sourceLang: String,
        val targetLang: String,
        val translatedText: String
    )

    /**
     * PHẦN 16 — Dịch 1 câu cho chế độ hội thoại song phương. KHÔNG dùng chung history/tools với
     * [sendMessage] (không cần function calling ở bước này, không muốn làm loãng ngữ cảnh chat
     * bình thường sau khi thoát chế độ dịch) — dùng system prompt riêng ([getTranslationSystemPrompt]).
     *
     * @param targetLanguage mã ngôn ngữ đích do [start_bilingual_mode] cung cấp; có thể rỗng —
     *   lúc đó prompt vẫn yêu cầu Gemini tự chọn cặp ngôn ngữ hợp lý dựa trên câu vừa nghe được.
     * @return null nếu lỗi mạng/parse — caller tự quyết định cách phục hồi (ví dụ nghe lại câu tiếp theo).
     */
    suspend fun translate(text: String, targetLanguage: String): TranslationResult? = withContext(Dispatchers.IO) {
        val apiKey = getNextApiKey()
        if (apiKey.isNullOrBlank()) {
            Log.e(TAG, "API key chưa được đặt (translate)")
            return@withContext null
        }

        val langHint = if (targetLanguage.isBlank()) "" else "\n\nNgôn ngữ đích lần này: $targetLanguage."
        val systemInstruction = JSONObject().apply {
            put("parts", JSONArray().put(JSONObject().put("text", getTranslationSystemPrompt() + langHint)))
        }
        val contentsArray = JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", text)))
        })
        val requestBody = JSONObject().apply {
            put("system_instruction", systemInstruction)
            put("contents", contentsArray)
            put("generation_config", JSONObject().apply {
                put("temperature", 0.3)
                put("max_output_tokens", 256)
            })
        }.toString()

        Log.d(TAG, "→ Gemini (dịch): \"$text\"")

        try {
            val url = java.net.URL("$ENDPOINT?key=$apiKey")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 20_000
            }
            conn.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }

            val responseCode = conn.responseCode
            val responseText = if (responseCode == 200) {
                conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } else {
                val errBody = conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                Log.e(TAG, "HTTP $responseCode (dịch): $errBody")
                return@withContext null
            }

            parseTranslationResponse(responseText)
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi gọi Gemini (dịch): ${e.message}", e)
            null
        }
    }

    private fun parseTranslationResponse(json: String): TranslationResult? {
        return try {
            val root = JSONObject(json)
            val candidates = root.getJSONArray("candidates")
            if (candidates.length() == 0) return null
            val parts = candidates.getJSONObject(0).getJSONObject("content").getJSONArray("parts")
            val raw = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.has("text")) raw.append(part.getString("text"))
            }
            // Gemini đôi khi vẫn bọc ```json ... ``` dù đã dặn không làm vậy — dọn sạch trước khi parse.
            val cleaned = raw.toString().trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val obj = JSONObject(cleaned)
            TranslationResult(
                sourceLang = obj.optString("source_lang", ""),
                targetLang = obj.optString("target_lang", ""),
                translatedText = obj.optString("translated_text", "")
            ).takeIf { it.translatedText.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "Parse translation thất bại: ${e.message}\nJSON: $json")
            null
        }
    }

    /** PHẦN 16 — Đọc system prompt dịch thuật từ SharedPreferences, mặc định [DEFAULT_TRANSLATION_SYSTEM_PROMPT]. */
    private fun getTranslationSystemPrompt(): String {
        val p = appContext.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        return p.getString(MainActivity.KEY_TRANSLATION_SYSTEM_PROMPT, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_TRANSLATION_SYSTEM_PROMPT
    }

    /**
     * Gửi tin nhắn tới Gemini và nhận phản hồi.
     * Chạy trên Dispatchers.IO (đã bọc bên trong).
     * @return [GeminiResponse] hoặc null nếu lỗi mạng / key chưa đặt.
     */
    suspend fun sendMessage(userText: String): GeminiResponse? = withContext(Dispatchers.IO) {
        val apiKey = getNextApiKey()
        if (apiKey.isNullOrBlank()) {
            Log.e(TAG, "API key chưa được đặt")
            return@withContext GeminiResponse.Text("Bạn ơi, mình chưa có API key nè~ Vào app đặt thử nhé!")
        }

        // Thêm tin nhắn user vào history
        addToHistory("user", userText)

        val requestBody = buildRequestBody()
        Log.d(TAG, "→ Gemini: \"$userText\"")

        try {
            val url = java.net.URL("$ENDPOINT?key=$apiKey")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 30_000
            }

            conn.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }

            val responseCode = conn.responseCode
            val responseText = if (responseCode == 200) {
                conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } else {
                val errBody = conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                Log.e(TAG, "HTTP $responseCode: $errBody")
                return@withContext GeminiResponse.Text("Mình bị lỗi mạng rồi... thử lại sau nha~ (HTTP $responseCode)")
            }

            parseResponse(responseText)
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi gọi Gemini: ${e.message}", e)
            GeminiResponse.Text("Ủa mình bị mất mạng hay sao ấy~ Thử lại sau nhé!")
        }
    }

    /**
     * PHẦN 12 — "Hỏi bằng ảnh": gửi 1 câu hỏi kèm 1 ảnh (base64) cho Gemini multimodal.
     * Dùng inline_data thay vì chỉ text — xem https://ai.google.dev/gemini-api/docs/vision.
     *
     * Không kèm [FUNCTION_DECLARATIONS] (tools) ở lượt này vì đây là câu hỏi 1-lần độc lập
     * (mô tả/dịch ảnh) — không cần Gemini gọi các function điều khiển nhân vật.
     * Vẫn giữ lịch sử hội thoại text trước đó để trả lời có ngữ cảnh liền mạch.
     *
     * @param imageBase64 dữ liệu ảnh đã encode Base64 (KHÔNG kèm tiền tố "data:image/...;base64,").
     */
    suspend fun sendImageMessage(
        userText: String,
        imageBase64: String,
        mimeType: String = "image/jpeg"
    ): GeminiResponse? = withContext(Dispatchers.IO) {
        val apiKey = getNextApiKey()
        if (apiKey.isNullOrBlank()) {
            Log.e(TAG, "API key chưa được đặt")
            return@withContext GeminiResponse.Text("Bạn ơi, mình chưa có API key nè~ Vào app đặt thử nhé!")
        }

        val question = userText.ifBlank { "Đây là gì vậy? Mô tả và dịch giúp mình nếu có chữ trong ảnh." }
        addToHistory("user", "[đã gửi 1 ảnh] $question")

        val contentsArray = JSONArray()
        // Lịch sử trước đó (chỉ text) để giữ ngữ cảnh — bỏ entry cuối vì ta tự dựng lại kèm ảnh bên dưới.
        for ((role, text) in history.dropLast(1)) {
            contentsArray.put(JSONObject().apply {
                put("role", role)
                put("parts", JSONArray().put(JSONObject().put("text", text)))
            })
        }
        // Lượt hiện tại: text + ảnh trong cùng 1 content.
        val currentParts = JSONArray()
            .put(JSONObject().put("text", question))
            .put(
                JSONObject().put(
                    "inline_data",
                    JSONObject().apply {
                        put("mime_type", mimeType)
                        put("data", imageBase64)
                    }
                )
            )
        contentsArray.put(JSONObject().apply {
            put("role", "user")
            put("parts", currentParts)
        })

        val fullSystemPrompt = getSystemPrompt() +
            "\n\n[Bối cảnh hiện tại]\n" + ContextSnapshotProvider.buildSnapshot(appContext)
        val systemInstruction = JSONObject().apply {
            put("parts", JSONArray().put(JSONObject().put("text", fullSystemPrompt)))
        }

        val requestBody = JSONObject().apply {
            put("system_instruction", systemInstruction)
            put("contents", contentsArray)
            put("generation_config", JSONObject().apply {
                put("temperature", 0.7)
                put("max_output_tokens", 512)
            })
        }.toString()

        Log.d(TAG, "→ Gemini (ảnh): \"$question\"")

        try {
            val url = java.net.URL("$ENDPOINT?key=$apiKey")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 30_000
            }

            conn.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }

            val responseCode = conn.responseCode
            val responseText = if (responseCode == 200) {
                conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } else {
                val errBody = conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                Log.e(TAG, "HTTP $responseCode (ảnh): $errBody")
                return@withContext GeminiResponse.Text("Mình xem ảnh bị lỗi mạng rồi... thử lại sau nha~ (HTTP $responseCode)")
            }

            parseResponse(responseText)
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi gọi Gemini (ảnh): ${e.message}", e)
            GeminiResponse.Text("Ủa mình xem ảnh bị lỗi rồi~ Thử lại sau nhé!")
        }
    }

    /** Xoá lịch sử hội thoại (dùng khi bắt đầu cuộc trò chuyện mới). */
    fun clearHistory() {
        history.clear()
        Log.d(TAG, "History đã xoá")
    }

    // ─────────────────────────────────────────────────────────────────────────

    // ── Round-Robin 2 API key (10-C) ────────────────────────────────────────
    @Volatile private var keyIndex = 0

    /**
     * Xoay vòng giữa Gemini key và Robin Round key.
     * Nếu chỉ có 1 key hợp lệ thì dùng key đó mãi (không lỗi).
     * Nếu cả 2 đều trống → trả về null để sendMessage() báo lỗi rõ ràng.
     */
    private fun getNextApiKey(): String? {
        val p    = appContext.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val keys = listOfNotNull(
            p.getString(MainActivity.KEY_GEMINI_API_KEY,     null)?.takeIf { it.isNotBlank() },
            p.getString(MainActivity.KEY_ROBINROUND_API_KEY, null)?.takeIf { it.isNotBlank() },
            p.getString(MainActivity.KEY_ALT_API_KEY_2,      null)?.takeIf { it.isNotBlank() },
            p.getString(MainActivity.KEY_ALT_API_KEY_3,      null)?.takeIf { it.isNotBlank() },
            p.getString(MainActivity.KEY_ALT_API_KEY_4,      null)?.takeIf { it.isNotBlank() },
            p.getString(MainActivity.KEY_ALT_API_KEY_5,      null)?.takeIf { it.isNotBlank() },
        )
        if (keys.isEmpty()) return null
        val key = keys[keyIndex % keys.size]
        Log.d(TAG, "Round-robin Chat: dùng key #${keyIndex % keys.size + 1}/${keys.size}")
        keyIndex++
        return key
    }

    // ── System Prompt (10-D) ─────────────────────────────────────────────────

    /**
     * Đọc system prompt từ SharedPreferences.
     * Nếu người dùng chưa lưu (hoặc để trống) → dùng [DEFAULT_SYSTEM_PROMPT].
     */
    private fun getSystemPrompt(): String {
        val p = appContext.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        return p.getString(MainActivity.KEY_SYSTEM_PROMPT, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_SYSTEM_PROMPT
    }

    private fun addToHistory(role: String, content: String) {
        history.addLast(role to content)
        // Giữ tối đa MAX_HISTORY_TURNS * 2 entries (user + model mỗi lượt)
        while (history.size > MAX_HISTORY_TURNS * 2) history.removeFirst()
    }

    private fun buildRequestBody(): String {
        val contentsArray = JSONArray()

        // Thêm lịch sử hội thoại vào contents
        for ((role, text) in history) {
            val msgObj = JSONObject().apply {
                put("role", role)
                put("parts", JSONArray().put(JSONObject().put("text", text)))
            }
            contentsArray.put(msgObj)
        }

        // System instruction — nối thêm khối bối cảnh hiện tại (giờ/pin/mạng/lịch) mỗi lượt
        // gọi để Milky "nhận thức" được tình huống thật của chủ nhân (Phần 11).
        val fullSystemPrompt = getSystemPrompt() +
            "\n\n[Bối cảnh hiện tại]\n" + ContextSnapshotProvider.buildSnapshot(appContext)
        val systemInstruction = JSONObject().apply {
            put("parts", JSONArray().put(JSONObject().put("text", fullSystemPrompt)))
        }

        // Tool declarations
        val toolsArray = JSONArray().put(
            JSONObject().put("function_declarations", JSONArray(FUNCTION_DECLARATIONS))
        )

        return JSONObject().apply {
            put("system_instruction", systemInstruction)
            put("contents", contentsArray)
            put("tools", toolsArray)
            put("tool_config", JSONObject().put(
                "function_calling_config", JSONObject().put("mode", "AUTO")
            ))
            put("generation_config", JSONObject().apply {
                put("temperature", 0.9)
                put("top_p", 0.95)
                put("max_output_tokens", 512)
            })
        }.toString()
    }

    private fun parseResponse(json: String): GeminiResponse {
        return try {
            val root = JSONObject(json)
            val candidates = root.getJSONArray("candidates")
            if (candidates.length() == 0) return GeminiResponse.Text("...")

            val content = candidates.getJSONObject(0).getJSONObject("content")
            val parts = content.getJSONArray("parts")

            // Kiểm tra xem có function call không
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.has("functionCall")) {
                    val fc = part.getJSONObject("functionCall")
                    val name = fc.getString("name")
                    val args = if (fc.has("args")) fc.getJSONObject("args") else JSONObject()
                    Log.d(TAG, "← Function call: $name($args)")
                    // Không thêm function call vào history text (chỉ thêm response text)
                    return GeminiResponse.FunctionCall(name, args)
                }
            }

            // Text response
            val textBuilder = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.has("text")) textBuilder.append(part.getString("text"))
            }
            val responseText = textBuilder.toString().trim()
            Log.d(TAG, "← Text: \"$responseText\"")

            // Thêm response vào history
            addToHistory("model", responseText)

            GeminiResponse.Text(responseText)
        } catch (e: Exception) {
            Log.e(TAG, "Parse response thất bại: ${e.message}\nJSON: $json")
            GeminiResponse.Text("Mình trả lời rồi nhưng bị lỗi parse... Gemini hơi lạ hôm nay~")
        }
    }
}

/** Kết quả trả về từ Gemini. */
sealed class GeminiResponse {
    /** Câu trả lời text thông thường. */
    data class Text(val content: String) : GeminiResponse()

    /** Gemini muốn gọi một function/action. */
    data class FunctionCall(
        val name: String,
        val args: org.json.JSONObject
    ) : GeminiResponse()
}

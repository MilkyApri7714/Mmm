package com.example.chibiwallpaper.ai

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * PHẦN 11 — Lấy thời tiết hiện tại qua Open-Meteo (miễn phí, KHÔNG cần API key,
 * khác với Gemini key phải xin ở Section 2). Toạ độ (lat/lon) do người dùng nhập tay trong
 * Section "Trợ lý chủ động" (đơn giản hơn nhiều so với xin quyền vị trí nền).
 *
 * weather_code là mã WMO chuẩn — [describeCode] dịch một số mã phổ biến sang tiếng Việt.
 * Dùng chung style HttpURLConnection như [GeminiClient] để khỏi thêm dependency mới (OkHttp/Retrofit).
 */
object WeatherHelper {
    private const val TAG = "ChibiWeather"

    data class WeatherNow(val code: Int, val tempC: Double)

    fun fetchCurrent(lat: Double, lon: Double): WeatherNow? {
        return try {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=weather_code,temperature_2m"
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            if (conn.responseCode != 200) {
                Log.w(TAG, "HTTP ${conn.responseCode} khi gọi Open-Meteo")
                return null
            }
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val current = JSONObject(body).getJSONObject("current")
            WeatherNow(
                code = current.optInt("weather_code", -1),
                tempC = current.optDouble("temperature_2m", Double.NaN)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi lấy thời tiết: ${e.message}")
            null
        }
    }

    /** Dịch mã WMO phổ biến sang mô tả ngắn tiếng Việt (rút gọn, không cần đủ 100 mã). */
    fun describeCode(code: Int): String = when (code) {
        0 -> "trời quang"
        1, 2, 3 -> "có mây"
        45, 48 -> "sương mù"
        51, 53, 55, 56, 57 -> "mưa phùn"
        61, 63, 65, 66, 67 -> "mưa"
        71, 73, 75, 77 -> "tuyết"
        80, 81, 82 -> "mưa rào"
        95, 96, 99 -> "giông"
        else -> "thời tiết thay đổi"
    }
}

package com.example.chibiwallpaper.ui

import android.Manifest
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.chibiwallpaper.ChibiWallpaperService
import com.example.chibiwallpaper.R
import com.example.chibiwallpaper.ai.CalendarHelper
import com.example.chibiwallpaper.ai.ContactsHelper
import com.example.chibiwallpaper.ai.DoNotDisturbHelper
import com.example.chibiwallpaper.ai.MemoryProfileHelper
import com.example.chibiwallpaper.ai.MilkyNotificationListenerService
import com.example.chibiwallpaper.ai.NotesHelper
import com.example.chibiwallpaper.ai.ProactiveManager
import com.example.chibiwallpaper.floating.FloatingPetService
import com.example.chibiwallpaper.floating.ForegroundAppWatcher
import kotlinx.coroutines.launch

/**
 * PHẦN 10-A + 10-B + 10-C + 10-D — MainActivity hoàn chỉnh (Compose).
 *
 * 10-A : migrate ComponentActivity + scaffold + Section 4 (Mic pill).
 * 10-B : Section 1 (Wallpaper toggle) + Section 3 (Scale sliders).
 * 10-C : Section 2 – Part A: 2 ô API key ẩn/hiện + round-robin (logic ở GeminiClient).
 * 10-D : Section 2 – Part B: TextArea system prompt + reset mặc định + lưu.
 */
class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        setContent {
            MaterialTheme {
                SettingsScreen(
                    onSetWallpaper    = { openLiveWallpaperPicker() },
                    onClearWallpaper  = { clearWallpaperInternal() },
                    onOpenFallback    = { openWallpaperFallback() },
                    onOpenAppSettings = { openAppSettings() },
                    prefs             = prefs
                )
            }
        }
    }

    // ── Wallpaper ─────────────────────────────────────────────────────────────

    private fun openLiveWallpaperPicker() {
        val component = ComponentName(this, ChibiWallpaperService::class.java)
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_CHANGE_LIVE_WALLPAPER thất bại: ${e.message}")
            openWallpaperFallback()
        }
    }

    private fun openWallpaperFallback() {
        try {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        } catch (e: Exception) {
            Log.e(TAG, "Cả 2 cách đều thất bại: ${e.message}")
            Toast.makeText(this, R.string.error_set_wallpaper, Toast.LENGTH_LONG).show()
        }
    }

    private fun clearWallpaperInternal(): Boolean {
        return try {
            WallpaperManager.getInstance(this).clear()
            true
        } catch (e: SecurityException) {
            // PHẦN 23 (fix) — Trước đây thiếu <uses-permission SET_WALLPAPER> trong manifest nên
            // LUÔN rơi vào đây (âm thầm, chỉ log) mỗi lần bấm tắt switch — hình nền không hề bị
            // xoá, switch tự bật lại ngay vì isActive không đổi. Đã thêm quyền vào manifest; log
            // rõ ràng thêm ở đây phòng khi máy nào đó vẫn còn thiếu (ví dụ cài đè bản cũ chưa gỡ).
            Log.e(TAG, "clear() thiếu quyền SET_WALLPAPER: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "clear() thất bại: ${e.message}")
            false
        }
    }

    // ── App Settings ──────────────────────────────────────────────────────────

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", packageName, null)
            }
        )
    }

    companion object {
        private const val TAG = "ChibiMainActivity"

        const val PREFS_NAME               = "chibi_wallpaper_prefs"

        // API keys (10-C) — 1 key chính + 5 key Alt, tất cả xoay vòng round-robin
        const val KEY_GEMINI_API_KEY       = "gemini_api_key"
        const val KEY_ROBINROUND_API_KEY   = "robinround_api_key"  // tương đương Alt 1 (giữ tương thích cũ)
        const val KEY_ALT_API_KEY_2        = "alt_api_key_2"
        const val KEY_ALT_API_KEY_3        = "alt_api_key_3"
        const val KEY_ALT_API_KEY_4        = "alt_api_key_4"
        const val KEY_ALT_API_KEY_5        = "alt_api_key_5"

        // System prompt (10-D)
        const val KEY_SYSTEM_PROMPT        = "system_prompt"

        // PHẦN 16 — System prompt riêng cho bước dịch (hội thoại song phương)
        const val KEY_TRANSLATION_SYSTEM_PROMPT = "translation_system_prompt"

        // PHẦN 21 — Chế độ trò chuyện (đọc to chat thường bằng Gemini TTS, khác Android TTS ở PHẦN 16)
        const val KEY_CONVERSATION_MODE_ENABLED = "conversation_mode_enabled"
        const val KEY_TTS_STYLE_PROMPT          = "tts_style_prompt"
        const val KEY_TTS_VOICE_NAME            = "tts_voice_name"

        // Scale sliders (10-B)
        const val KEY_SCALE_SLIME          = "scale_slime"
        const val KEY_SCALE_CHIBI          = "scale_chibi"
        const val KEY_SCALE_FULL           = "scale_full"
        const val DEFAULT_SCALE            = 1.0f

        // Floating pet model (PHẦN 14)
        const val KEY_FLOATING_MODEL       = "floating_model_type"

        // PHẦN 18 — Ảnh nền tĩnh cho Live Wallpaper (Layer 1, xem BackgroundImageLayer)
        const val KEY_BACKGROUND_IMAGE_URI = "background_image_uri"

        // PHẦN 18 (mở rộng video UI) — 1 video ngắn lặp làm nền + 3 video dài cho function calling
        // "play_video" (thay thế asset cố định cũ, xem ActionRouter + LiveBackgroundMediaSubSection).
        const val KEY_BG_VIDEO_URI         = "bg_video_short_uri"
        const val KEY_LONG_VIDEO_1_URI     = "long_video_1_uri"
        const val KEY_LONG_VIDEO_2_URI     = "long_video_2_uri"
        const val KEY_LONG_VIDEO_3_URI     = "long_video_3_uri"
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// ROOT COMPOSABLE
// ═══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onSetWallpaper    : () -> Unit,
    onClearWallpaper  : () -> Boolean,
    onOpenFallback    : () -> Unit,
    onOpenAppSettings : () -> Unit,
    prefs             : SharedPreferences
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cài đặt Chibi Wallpaper", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // Section 1: Hình nền
            item {
                WallpaperSection(
                    onSetWallpaper   = onSetWallpaper,
                    onClearWallpaper = onClearWallpaper,
                    onOpenFallback   = onOpenFallback,
                    prefs            = prefs
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp)) }

            // Section 2: AI & API Keys (10-C + 10-D)
            item {
                AiSection(
                    prefs = prefs,
                    onSaved = { msg ->
                        scope.launch { snackbarHostState.showSnackbar(msg) }
                    }
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp)) }

            // Section 3: Kích thước model
            item { ScaleSection(prefs = prefs) }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp)) }

            // Section 4: Quyền mic
            item { MicSection(onOpenAppSettings = onOpenAppSettings) }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp)) }

            // Section 5: Trợ lý chủ động (Phần 11)
            item { ProactiveSection(prefs = prefs) }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp)) }

            // Section 6: Tính năng mở rộng (Phần 12)
            item { AdvancedFeaturesSection(prefs = prefs) }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp)) }

            // Section 7: Nhân vật nổi đè lên app khác (Phần 14)
            item { FloatingPetSection() }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp)) }

            // Section 8: Hồ sơ chủ nhân — trí nhớ lâu dài (Phần 20)
            item {
                MemoryProfileSection(
                    onSaved = { msg ->
                        scope.launch { snackbarHostState.showSnackbar(msg) }
                    }
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SECTION 1 — HÌNH NỀN
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun WallpaperSection(
    onSetWallpaper   : () -> Unit,
    onClearWallpaper : () -> Boolean,
    onOpenFallback   : () -> Unit,
    prefs            : SharedPreferences
) {
    val context = LocalContext.current
    var isActive by remember { mutableStateOf(isChibiWallpaperActive(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) isActive = isChibiWallpaperActive(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Wallpaper, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("1. Hình nền", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            Switch(
                checked = isActive,
                onCheckedChange = { turnOn ->
                    if (turnOn) {
                        onSetWallpaper()
                        // KHÔNG set isActive=true ở đây: ACTION_CHANGE_LIVE_WALLPAPER mở màn hình
                        // hệ thống, còn cần user bấm xác nhận ở đó — kết quả THẬT chỉ biết được
                        // lúc quay lại app (ON_RESUME ở trên đã tự gọi isChibiWallpaperActive lại).
                    } else {
                        // PHẦN 23 (fix) — TRƯỚC ĐÂY: gọi onClearWallpaper() (Unit) rồi KHÔNG cập
                        // nhật isActive gì cả → vì clear() chạy đồng bộ (không mở activity nào
                        // khác) nên KHÔNG có ON_RESUME nào bắn ra để tự refresh → switch bị Compose
                        // vẽ lại ngay với isActive cũ (true) → nhìn như vừa bấm tắt xong tự bật lại.
                        // Giờ dùng thẳng kết quả true/false của onClearWallpaper() để cập nhật state
                        // ngay lập tức, không phụ thuộc lifecycle nữa.
                        isActive = !onClearWallpaper()
                    }
                }
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (isActive) "Đang dùng làm hình nền động."
            else "Chưa đặt — bật switch hoặc dùng nút dự phòng bên dưới.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onOpenFallback, contentPadding = PaddingValues(0.dp)) {
            Text("Mở cài đặt hình nền (dự phòng)", fontSize = 13.sp)
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        LiveBackgroundMediaSubSection(prefs = prefs)
    }
}

/**
 * PHẦN 18 — Chọn ảnh nền tĩnh + 1 video ngắn (lặp làm nền, ưu tiên hơn ảnh) + 3 video dài (function
 * calling "play_video" — xem ActionRouter.pickLongVideoUri). Mọi lựa chọn dùng SAF (GetContent) +
 * takePersistableUriPermission để đọc lại được sau khi reboot máy.
 */
@Composable
private fun LiveBackgroundMediaSubSection(prefs: SharedPreferences) {
    val context = LocalContext.current

    var bgImageUri by remember { mutableStateOf(prefs.getString(MainActivity.KEY_BACKGROUND_IMAGE_URI, null)) }
    var bgVideoUri by remember { mutableStateOf(prefs.getString(MainActivity.KEY_BG_VIDEO_URI, null)) }
    var longVideo1 by remember { mutableStateOf(prefs.getString(MainActivity.KEY_LONG_VIDEO_1_URI, null)) }
    var longVideo2 by remember { mutableStateOf(prefs.getString(MainActivity.KEY_LONG_VIDEO_2_URI, null)) }
    var longVideo3 by remember { mutableStateOf(prefs.getString(MainActivity.KEY_LONG_VIDEO_3_URI, null)) }

    fun persist(uri: android.net.Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            Log.w("ChibiMedia", "takePersistableUriPermission lỗi: ${e.message}")
        }
    }

    Text("Ảnh/video nền cho hình nền động", fontWeight = FontWeight.Bold, fontSize = 14.sp)
    Spacer(Modifier.height(4.dp))
    Text(
        "Ảnh nền tĩnh vẽ dưới cùng; video ngắn (nếu chọn) sẽ LẶP LIÊN TỤC làm nền thay cho ảnh. " +
            "3 video dài dùng khi Milky phát video theo yêu cầu (function calling).",
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(10.dp))

    val pickImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        persist(uri)
        prefs.edit().putString(MainActivity.KEY_BACKGROUND_IMAGE_URI, uri.toString()).apply()
        bgImageUri = uri.toString()
        ChibiWallpaperService.tryReloadBackground()
    }
    MediaPickerRow(
        label = "Ảnh nền tĩnh",
        currentUri = bgImageUri,
        onPick = { pickImageLauncher.launch("image/*") },
        onClear = {
            prefs.edit().remove(MainActivity.KEY_BACKGROUND_IMAGE_URI).apply()
            bgImageUri = null
            ChibiWallpaperService.tryReloadBackground()
        }
    )

    val pickBgVideoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        persist(uri)
        prefs.edit().putString(MainActivity.KEY_BG_VIDEO_URI, uri.toString()).apply()
        bgVideoUri = uri.toString()
        ChibiWallpaperService.tryReloadBackgroundVideo()
    }
    MediaPickerRow(
        label = "Video ngắn (nền lặp)",
        currentUri = bgVideoUri,
        onPick = { pickBgVideoLauncher.launch("video/*") },
        onClear = {
            prefs.edit().remove(MainActivity.KEY_BG_VIDEO_URI).apply()
            bgVideoUri = null
            ChibiWallpaperService.tryReloadBackgroundVideo()
        }
    )

    Spacer(Modifier.height(8.dp))
    Text("Video dài (phát khi Milky gọi function play_video)", fontSize = 13.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(4.dp))

    val longVideoKeys = listOf(
        MainActivity.KEY_LONG_VIDEO_1_URI, MainActivity.KEY_LONG_VIDEO_2_URI, MainActivity.KEY_LONG_VIDEO_3_URI
    )
    val longVideoStates = listOf(
        longVideo1 to { v: String? -> longVideo1 = v },
        longVideo2 to { v: String? -> longVideo2 = v },
        longVideo3 to { v: String? -> longVideo3 = v }
    )
    longVideoStates.forEachIndexed { i, (currentUri, setUri) ->
        val key = longVideoKeys[i]
        val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            persist(uri)
            prefs.edit().putString(key, uri.toString()).apply()
            setUri(uri.toString())
            // Video dài đọc thẳng từ prefs mỗi lần Gemini gọi play_video — không cần hot-reload GL.
        }
        MediaPickerRow(
            label = "Video dài ${i + 1}",
            currentUri = currentUri,
            onPick = { pickLauncher.launch("video/*") },
            onClear = {
                prefs.edit().remove(key).apply()
                setUri(null)
            }
        )
    }
}

@Composable
private fun MediaPickerRow(
    label: String,
    currentUri: String?,
    onPick: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp)
            Text(
                if (currentUri != null) "Đã chọn" else "Chưa chọn",
                fontSize = 11.sp,
                color = if (currentUri != null) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row {
            if (currentUri != null) {
                TextButton(onClick = onClear) { Text("Xoá", fontSize = 12.sp) }
            }
            OutlinedButton(onClick = onPick) { Text(if (currentUri != null) "Đổi" else "Chọn", fontSize = 12.sp) }
        }
    }
}

private fun isChibiWallpaperActive(context: Context): Boolean {
    val info = try { WallpaperManager.getInstance(context).wallpaperInfo } catch (e: Exception) { null }
    return info != null &&
        info.packageName == context.packageName &&
        info.serviceName == ChibiWallpaperService::class.java.name
}

// ═══════════════════════════════════════════════════════════════════════════════
// SECTION 2 — AI & API KEYS + SYSTEM PROMPT  (10-C + 10-D)
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun AiSection(
    prefs   : SharedPreferences,
    onSaved : (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("2. AI & API Keys", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(12.dp))

        // ── 2A: API Keys ─────────────────────────────────────────────────────
        ApiKeysSubSection(prefs = prefs, onSaved = onSaved)

        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(20.dp))

        // ── 2B: System Prompt ─────────────────────────────────────────────────
        SystemPromptSubSection(prefs = prefs, onSaved = onSaved)

        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(20.dp))

        // ── 2C: System Prompt dịch thuật (PHẦN 16) ─────────────────────────────
        TranslationPromptSubSection(prefs = prefs, onSaved = onSaved)

        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(20.dp))

        // ── 2D: Chế độ trò chuyện — đọc to bằng Gemini TTS (PHẦN 21) ────────────
        ConversationModeSubSection(prefs = prefs, onSaved = onSaved)
    }
}

// ── 2D: Chế độ trò chuyện (Gemini TTS) — PHẦN 21 ───────────────────────────────
// Khác với 2C (chỉ dùng cho lúc dịch song phương, đọc bằng Android TTS hệ thống): mục này bật/tắt
// việc đọc to MỌI câu trả lời chat thường của Milky bằng Gemini TTS (giọng + style prompt tự chọn),
// xem GeminiTtsHelper.speak() được gọi từ ChibiWallpaperService.maybeSpeakConversation().

@Composable
fun ConversationModeSubSection(
    prefs   : SharedPreferences,
    onSaved : (String) -> Unit
) {
    val defaultPrompt = com.example.chibiwallpaper.ai.GeminiTtsHelper.DEFAULT_STYLE_PROMPT
    var enabled by remember {
        mutableStateOf(prefs.getBoolean(MainActivity.KEY_CONVERSATION_MODE_ENABLED, false))
    }
    var voiceName by remember {
        mutableStateOf(prefs.getString(MainActivity.KEY_TTS_VOICE_NAME, "") ?: "")
    }
    var stylePrompt by remember {
        mutableStateOf(
            prefs.getString(MainActivity.KEY_TTS_STYLE_PROMPT, "")
                ?.takeIf { it.isNotBlank() }
                ?: defaultPrompt
        )
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Chế độ trò chuyện (đọc to bằng Gemini TTS)",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Khi bật: MỌI câu trả lời chat thường của Milky sẽ được đọc to bằng Gemini TTS " +
                        "(giọng + style prompt bên dưới) — khác Hội thoại song phương ở trên (chỉ " +
                        "đọc câu đã dịch, dùng giọng Android mặc định).",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    prefs.edit().putBoolean(MainActivity.KEY_CONVERSATION_MODE_ENABLED, it).apply()
                    onSaved(if (it) "Đã bật chế độ trò chuyện" else "Đã tắt chế độ trò chuyện")
                }
            )
        }
        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value         = voiceName,
            onValueChange = { voiceName = it },
            label         = { Text("Tên giọng (voice name)") },
            placeholder   = { Text("Zephyr (bỏ trống = mặc định Zephyr)") },
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value         = stylePrompt,
            onValueChange = { stylePrompt = it },
            label         = { Text("Style prompt (Audio Profile + Director's note)") },
            placeholder   = { Text("Mô tả chất giọng, phong cách, nhịp độ...") },
            minLines      = 6,
            maxLines      = 14,
            modifier      = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick  = { stylePrompt = defaultPrompt },
                modifier = Modifier.weight(1f)
            ) {
                Text("Đặt lại mặc định")
            }

            Button(
                onClick = {
                    prefs.edit()
                        .putString(MainActivity.KEY_TTS_VOICE_NAME, voiceName.trim())
                        .putString(MainActivity.KEY_TTS_STYLE_PROMPT, stylePrompt.trim())
                        .apply()
                    onSaved("Đã lưu giọng + style prompt")
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Lưu")
            }
        }
    }
}

// ── 2A: 6 ô API key (1 chính + 5 Alt) — round-robin cho cả Chat và TTS ──────

@Composable
fun ApiKeysSubSection(
    prefs   : SharedPreferences,
    onSaved : (String) -> Unit
) {
    var geminiKey  by remember { mutableStateOf(prefs.getString(MainActivity.KEY_GEMINI_API_KEY,     "") ?: "") }
    var altKey1    by remember { mutableStateOf(prefs.getString(MainActivity.KEY_ROBINROUND_API_KEY, "") ?: "") }
    var altKey2    by remember { mutableStateOf(prefs.getString(MainActivity.KEY_ALT_API_KEY_2,      "") ?: "") }
    var altKey3    by remember { mutableStateOf(prefs.getString(MainActivity.KEY_ALT_API_KEY_3,      "") ?: "") }
    var altKey4    by remember { mutableStateOf(prefs.getString(MainActivity.KEY_ALT_API_KEY_4,      "") ?: "") }
    var altKey5    by remember { mutableStateOf(prefs.getString(MainActivity.KEY_ALT_API_KEY_5,      "") ?: "") }

    Column {
        Text(
            "API Keys (round-robin)",
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "1 key chính + tối đa 5 key Alt xoay vòng — Chat và TTS đều dùng chung pool này. Bỏ trống Alt nào không dùng.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(12.dp))

        PasswordTextField(value = geminiKey, onValueChange = { geminiKey = it }, label = "Gemini API Key (chính)")
        Spacer(Modifier.height(8.dp))
        PasswordTextField(value = altKey1,   onValueChange = { altKey1   = it }, label = "Alt Key 1")
        Spacer(Modifier.height(8.dp))
        PasswordTextField(value = altKey2,   onValueChange = { altKey2   = it }, label = "Alt Key 2")
        Spacer(Modifier.height(8.dp))
        PasswordTextField(value = altKey3,   onValueChange = { altKey3   = it }, label = "Alt Key 3")
        Spacer(Modifier.height(8.dp))
        PasswordTextField(value = altKey4,   onValueChange = { altKey4   = it }, label = "Alt Key 4")
        Spacer(Modifier.height(8.dp))
        PasswordTextField(value = altKey5,   onValueChange = { altKey5   = it }, label = "Alt Key 5")
        Spacer(Modifier.height(14.dp))

        Button(
            onClick = {
                prefs.edit()
                    .putString(MainActivity.KEY_GEMINI_API_KEY,     geminiKey.trim())
                    .putString(MainActivity.KEY_ROBINROUND_API_KEY, altKey1.trim())
                    .putString(MainActivity.KEY_ALT_API_KEY_2,      altKey2.trim())
                    .putString(MainActivity.KEY_ALT_API_KEY_3,      altKey3.trim())
                    .putString(MainActivity.KEY_ALT_API_KEY_4,      altKey4.trim())
                    .putString(MainActivity.KEY_ALT_API_KEY_5,      altKey5.trim())
                    .apply()
                onSaved("Đã lưu API keys")
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Lưu API Keys")
        }
    }
}

@Composable
fun PasswordTextField(
    value         : String,
    onValueChange : (String) -> Unit,
    label         : String
) {
    var visible by remember { mutableStateOf(false) }

    OutlinedTextField(
        value               = value,
        onValueChange       = onValueChange,
        label               = { Text(label) },
        singleLine          = true,
        visualTransformation = if (visible) VisualTransformation.None
                               else PasswordVisualTransformation(),
        keyboardOptions     = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Default.VisibilityOff
                                  else Icons.Default.Visibility,
                    contentDescription = if (visible) "Ẩn key" else "Hiện key"
                )
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

// ── 2B: System Prompt ─────────────────────────────────────────────────────────

@Composable
fun SystemPromptSubSection(
    prefs   : SharedPreferences,
    onSaved : (String) -> Unit
) {
    // Load từ prefs; nếu trống thì hiện DEFAULT_SYSTEM_PROMPT
    val defaultPrompt = com.example.chibiwallpaper.ai.GeminiClient.DEFAULT_SYSTEM_PROMPT
    var promptText by remember {
        mutableStateOf(
            prefs.getString(MainActivity.KEY_SYSTEM_PROMPT, "")
                ?.takeIf { it.isNotBlank() }
                ?: defaultPrompt
        )
    }

    Column {
        Text(
            "System Prompt",
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Định nghĩa tính cách và hành vi của trợ lý. Có hiệu lực từ lần hỏi tiếp theo.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value         = promptText,
            onValueChange = { promptText = it },
            label         = { Text("System Prompt") },
            minLines      = 6,
            maxLines      = 14,
            modifier      = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Nút reset — chỉ đặt lại state local, chưa lưu
            OutlinedButton(
                onClick  = { promptText = defaultPrompt },
                modifier = Modifier.weight(1f)
            ) {
                Text("Đặt lại mặc định")
            }

            // Nút lưu
            Button(
                onClick = {
                    prefs.edit()
                        .putString(MainActivity.KEY_SYSTEM_PROMPT, promptText.trim())
                        .apply()
                    onSaved("Đã lưu system prompt")
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Lưu")
            }
        }
    }
}

// ── 2C: System Prompt dịch thuật — PHẦN 16 — Hội thoại song phương ─────────────
// Tách riêng khỏi SystemPromptSubSection ở trên: đây là prompt dùng cho MỖI câu cần dịch trong
// lúc hội thoại song phương (GeminiClient.translate()), KHÔNG liên quan tới tính cách/12 function
// của Milky trong chat bình thường — sửa ô này không ảnh hưởng ô "System Prompt" phía trên.

@Composable
fun TranslationPromptSubSection(
    prefs   : SharedPreferences,
    onSaved : (String) -> Unit
) {
    val defaultPrompt = com.example.chibiwallpaper.ai.GeminiClient.DEFAULT_TRANSLATION_SYSTEM_PROMPT
    var promptText by remember {
        mutableStateOf(
            prefs.getString(MainActivity.KEY_TRANSLATION_SYSTEM_PROMPT, "")
                ?.takeIf { it.isNotBlank() }
                ?: defaultPrompt
        )
    }

    Column {
        Text(
            "System Prompt dịch thuật (song phương)",
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Dùng riêng cho chế độ Hội thoại song phương (\"bật chế độ hội thoại song phương\") — " +
                "không ảnh hưởng System Prompt của Milky ở trên. Có hiệu lực từ câu dịch tiếp theo.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value         = promptText,
            onValueChange = { promptText = it },
            label         = { Text("System Prompt dịch thuật") },
            minLines      = 6,
            maxLines      = 14,
            modifier      = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick  = { promptText = defaultPrompt },
                modifier = Modifier.weight(1f)
            ) {
                Text("Đặt lại mặc định")
            }

            Button(
                onClick = {
                    prefs.edit()
                        .putString(MainActivity.KEY_TRANSLATION_SYSTEM_PROMPT, promptText.trim())
                        .apply()
                    onSaved("Đã lưu system prompt dịch thuật")
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Lưu")
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SECTION 3 — KÍCH THƯỚC MODEL
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun ScaleSection(prefs: SharedPreferences) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("3. Kích thước model", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "Thay đổi có hiệu lực lần khởi động hình nền tiếp theo.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        ScaleSliderRow("Milky-slime", MainActivity.KEY_SCALE_SLIME, prefs)
        Spacer(Modifier.height(8.dp))
        ScaleSliderRow("Milky-chibi", MainActivity.KEY_SCALE_CHIBI, prefs)
        Spacer(Modifier.height(8.dp))
        ScaleSliderRow("Milky-full",  MainActivity.KEY_SCALE_FULL,  prefs)
    }
}

@Composable
fun ScaleSliderRow(label: String, prefKey: String, prefs: SharedPreferences) {
    var value by remember(prefKey) {
        mutableStateOf(prefs.getFloat(prefKey, MainActivity.DEFAULT_SCALE))
    }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("%.2f×".format(value), fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        Slider(
            value                = value,
            onValueChange        = { value = it },
            valueRange           = 0.2f..2.0f,
            onValueChangeFinished = { prefs.edit().putFloat(prefKey, value).apply() },
            modifier             = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0.2×", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
            Text("2.0×", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SECTION 4 — QUYỀN MIC
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun MicSection(onOpenAppSettings: () -> Unit) {
    val context = LocalContext.current
    var hasMic by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    var permanentlyDenied by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMic = granted
        if (!granted) {
            permanentlyDenied = (context as? ComponentActivity)
                ?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Mic, null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("4. Quyền Micro", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            StatusPill(
                label   = if (hasMic) "Đã cấp" else "Chưa cấp",
                bgColor = if (hasMic) Color(0xFF2E7D32) else Color(0xFFB71C1C)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (hasMic) "Đã cấp — tính năng nói chuyện với trợ lý sẵn sàng."
            else "Cần cấp để dùng tính năng nói chuyện với trợ lý.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!hasMic) {
            Spacer(Modifier.height(12.dp))
            if (permanentlyDenied) {
                OutlinedButton(onClick = onOpenAppSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Mở Cài đặt app để bật quyền tay")
                }
                Spacer(Modifier.height(4.dp))
                Text("Đã từ chối vĩnh viễn. Vào Cài đặt > App > Chibi Wallpaper > Quyền.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            } else {
                Button(
                    onClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Cấp quyền Micro") }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SECTION 5 — TRỢ LÝ CHỦ ĐỘNG (PHẦN 11)
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun ProactiveSection(prefs: SharedPreferences) {
    val context = LocalContext.current

    var hasCalendar by remember {
        mutableStateOf(CalendarHelper.hasPermission(context))
    }
    var hasCalendarWrite by remember {
        mutableStateOf(CalendarHelper.hasWritePermission(context))
    }
    val calendarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasCalendar = result[Manifest.permission.READ_CALENDAR] ?: hasCalendar
        hasCalendarWrite = result[Manifest.permission.WRITE_CALENDAR] ?: hasCalendarWrite
    }

    var morningEnabled by remember {
        mutableStateOf(prefs.getBoolean(ProactiveManager.KEY_MORNING_ENABLED, true))
    }
    var morningHour by remember {
        mutableStateOf(prefs.getInt(ProactiveManager.KEY_MORNING_HOUR, ProactiveManager.DEFAULT_MORNING_HOUR))
    }
    var weatherEnabled by remember {
        mutableStateOf(prefs.getBoolean(ProactiveManager.KEY_WEATHER_ENABLED, true))
    }
    var lat by remember {
        mutableStateOf(prefs.getFloat(ProactiveManager.KEY_WEATHER_LAT, ProactiveManager.DEFAULT_LAT.toFloat()).toString())
    }
    var lon by remember {
        mutableStateOf(prefs.getFloat(ProactiveManager.KEY_WEATHER_LON, ProactiveManager.DEFAULT_LON.toFloat()).toString())
    }
    var inactivityEnabled by remember {
        mutableStateOf(prefs.getBoolean(ProactiveManager.KEY_INACTIVITY_ENABLED, true))
    }
    var inactivityHours by remember {
        mutableStateOf(prefs.getInt(ProactiveManager.KEY_INACTIVITY_HOURS, ProactiveManager.DEFAULT_INACTIVITY_HOURS))
    }

    fun persistAndReschedule() {
        prefs.edit()
            .putBoolean(ProactiveManager.KEY_MORNING_ENABLED, morningEnabled)
            .putInt(ProactiveManager.KEY_MORNING_HOUR, morningHour)
            .putBoolean(ProactiveManager.KEY_WEATHER_ENABLED, weatherEnabled)
            .putFloat(ProactiveManager.KEY_WEATHER_LAT, lat.toFloatOrNull() ?: ProactiveManager.DEFAULT_LAT.toFloat())
            .putFloat(ProactiveManager.KEY_WEATHER_LON, lon.toFloatOrNull() ?: ProactiveManager.DEFAULT_LON.toFloat())
            .putBoolean(ProactiveManager.KEY_INACTIVITY_ENABLED, inactivityEnabled)
            .putInt(ProactiveManager.KEY_INACTIVITY_HOURS, inactivityHours)
            .apply()
        ProactiveManager.scheduleMorning(context)
        ProactiveManager.scheduleWeatherCheck(context)
        ProactiveManager.scheduleInactivityCheck(context)
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("5. Trợ lý chủ động", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "Milky sẽ tự lên tiếng vào những lúc dưới đây, không cần bạn hỏi trước.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        // ── Quyền Lịch (dùng chung cho chào buổi sáng + get_today_schedule) ────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Quyền xem & thêm Lịch", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            StatusPill(
                label   = if (hasCalendar && hasCalendarWrite) "Đã cấp" else "Chưa cấp",
                bgColor = if (hasCalendar && hasCalendarWrite) Color(0xFF2E7D32) else Color(0xFFB71C1C)
            )
        }
        if (!hasCalendar || !hasCalendarWrite) {
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = {
                    calendarLauncher.launch(
                        arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Cấp quyền Lịch") }
        }

        Spacer(Modifier.height(16.dp))

        // ── Chào buổi sáng ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Chào buổi sáng", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Switch(checked = morningEnabled, onCheckedChange = {
                morningEnabled = it; persistAndReschedule()
            })
        }
        if (morningEnabled) {
            Text("Lúc ${morningHour}:00 mỗi ngày (kèm lịch hôm nay nếu đã cấp quyền)",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = morningHour.toFloat(),
                onValueChange = { morningHour = it.toInt() },
                onValueChangeFinished = { persistAndReschedule() },
                valueRange = 0f..23f, steps = 22
            )
        }

        Spacer(Modifier.height(8.dp))

        // ── Báo thời tiết thay đổi ──────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Báo khi thời tiết đổi", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Switch(checked = weatherEnabled, onCheckedChange = {
                weatherEnabled = it; persistAndReschedule()
            })
        }
        if (weatherEnabled) {
            Text("Toạ độ dùng để tra thời tiết (kiểm tra mỗi 3 tiếng):",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = lat, onValueChange = { lat = it },
                    label = { Text("Vĩ độ") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = lon, onValueChange = { lon = it },
                    label = { Text("Kinh độ") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { persistAndReschedule() }, contentPadding = PaddingValues(0.dp)) {
                Text("Lưu toạ độ", fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Nhắc khi lâu không dùng ─────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Nhắc khi lâu không dùng", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Switch(checked = inactivityEnabled, onCheckedChange = {
                inactivityEnabled = it; persistAndReschedule()
            })
        }
        if (inactivityEnabled) {
            Text("Sau $inactivityHours giờ không tương tác",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = inactivityHours.toFloat(),
                onValueChange = { inactivityHours = it.toInt() },
                onValueChangeFinished = { persistAndReschedule() },
                valueRange = 1f..24f, steps = 22
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SECTION 6 — TÍNH NĂNG MỞ RỘNG (PHẦN 12)
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun AdvancedFeaturesSection(prefs: SharedPreferences) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // ── Trạng thái quyền — refresh mỗi khi quay lại màn hình (giống WallpaperSection) ──────
    var hasNotifAccess by remember { mutableStateOf(MilkyNotificationListenerService.hasAccess(context)) }
    var hasContacts by remember { mutableStateOf(ContactsHelper.hasPermission(context)) }
    var hasCallPhone by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var hasSendSms by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasNotifAccess = MilkyNotificationListenerService.hasAccess(context)
                hasContacts = ContactsHelper.hasPermission(context)
                hasCallPhone = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
                    PackageManager.PERMISSION_GRANTED
                hasSendSms = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
                    PackageManager.PERMISSION_GRANTED
                hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val contactsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasContacts = result[Manifest.permission.READ_CONTACTS] ?: hasContacts
        hasCallPhone = result[Manifest.permission.CALL_PHONE] ?: hasCallPhone
        hasSendSms = result[Manifest.permission.SEND_SMS] ?: hasSendSms
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCamera = granted }

    // ── Không làm phiền: đọc trực tiếp mỗi recomposition để hiện đúng trạng thái còn hạn ────
    var dndTick by remember { mutableStateOf(0) } // bump để force recheck sau khi bấm nút
    val dndUntil = remember(dndTick) { DoNotDisturbHelper.activeUntilMillis(context) }
    val dndActive = dndUntil > 0L
    var dndMinutes by remember { mutableStateOf(30) }

    // ── Ghi chú gần đây (đọc lại mỗi khi tick đổi) ──────────────────────────────────────────
    var notesTick by remember { mutableStateOf(0) }
    val recentNotes = remember(notesTick) { NotesHelper.loadAll(context).takeLast(5).reversed() }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("6. Tính năng mở rộng", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "Đọc thông báo, hỏi bằng ảnh, ghi chú nhanh, gọi/nhắn qua danh bạ, đèn pin và chế độ không làm phiền.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))

        // ── Đọc thông báo quan trọng ────────────────────────────────────────────────────
        Text("Đọc thông báo quan trọng", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(
            "Milky tóm tắt \"bạn có tin nhắn mới từ X\" khi có thông báo — cần bật tay ở Cài đặt hệ thống (Special app access), không phải quyền thường.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(
                label = if (hasNotifAccess) "Đã bật" else "Chưa bật",
                bgColor = if (hasNotifAccess) Color(0xFF2E7D32) else Color(0xFFB71C1C)
            )
            Spacer(Modifier.width(8.dp))
            if (!hasNotifAccess) {
                TextButton(
                    onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                    contentPadding = PaddingValues(0.dp)
                ) { Text("Mở Cài đặt", fontSize = 13.sp) }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Hỏi bằng ảnh ────────────────────────────────────────────────────────────────
        Text("Hỏi bằng ảnh", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(
            "Chụp 1 tấm rồi hỏi \"cái này là gì / dịch giúp mình\" — trên hình nền, chạm nhanh 4 lần vào Slime.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(
                label = if (hasCamera) "Đã cấp quyền Camera" else "Chưa cấp quyền Camera",
                bgColor = if (hasCamera) Color(0xFF2E7D32) else Color(0xFFB71C1C)
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!hasCamera) {
                OutlinedButton(onClick = { cameraLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Cấp quyền Camera", fontSize = 13.sp)
                }
            }
            OutlinedButton(onClick = {
                context.startActivity(Intent(context, PhotoAskActivity::class.java))
            }) { Text("Thử ngay", fontSize = 13.sp) }
        }

        Spacer(Modifier.height(16.dp))

        // ── Ghi chú giọng nói nhanh ─────────────────────────────────────────────────────
        Text("Ghi chú giọng nói nhanh", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(
            "Nói \"ghi chú giúp mình...\" để lưu, hỏi lại \"hôm qua mình ghi chú gì\" để nghe lại.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        if (recentNotes.isEmpty()) {
            Text("Chưa có ghi chú nào.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val fmt = remember { java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()) }
            recentNotes.forEach { note ->
                Text(
                    "• ${note.content}  (${fmt.format(java.util.Date(note.timestampMillis))})",
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = {
                    NotesHelper.clearAll(context)
                    notesTick++
                },
                contentPadding = PaddingValues(0.dp)
            ) { Text("Xoá tất cả ghi chú", fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
        }

        Spacer(Modifier.height(16.dp))

        // ── Gọi / nhắn nhanh qua danh bạ ────────────────────────────────────────────────
        Text("Gọi / nhắn nhanh qua danh bạ", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(
            "Nói \"gọi cho Mẹ\" hoặc \"nhắn Long là mình trễ 10 phút\".",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(
                label = if (hasContacts) "Danh bạ: Đã cấp" else "Danh bạ: Chưa cấp",
                bgColor = if (hasContacts) Color(0xFF2E7D32) else Color(0xFFB71C1C)
            )
            StatusPill(
                label = if (hasCallPhone) "Gọi: Đã cấp" else "Gọi: Chưa cấp",
                bgColor = if (hasCallPhone) Color(0xFF2E7D32) else Color(0xFFB71C1C)
            )
            StatusPill(
                label = if (hasSendSms) "SMS: Đã cấp" else "SMS: Chưa cấp",
                bgColor = if (hasSendSms) Color(0xFF2E7D32) else Color(0xFFB71C1C)
            )
        }
        if (!hasContacts || !hasCallPhone || !hasSendSms) {
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = {
                    contactsLauncher.launch(
                        arrayOf(
                            Manifest.permission.READ_CONTACTS,
                            Manifest.permission.CALL_PHONE,
                            Manifest.permission.SEND_SMS
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Cấp quyền Danh bạ / Gọi / SMS") }
        }

        Spacer(Modifier.height(16.dp))

        // ── Đèn pin ─────────────────────────────────────────────────────────────────────
        Text("Đèn pin", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(
            "Nói \"bật đèn pin giúp mình\" / \"tắt đèn pin giúp mình\" — không cần cấp quyền gì thêm.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        // ── Quick Settings Tile ─────────────────────────────────────────────────────────
        Text("Hỏi Milky từ Quick Settings", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(
            "Kéo thanh trạng thái xuống, bấm bút chì \"Chỉnh sửa\" rồi kéo tile \"Hỏi Milky\" vào — bấm tile là vào STT ngay, không cần mở hình nền chính.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        // ── Không làm phiền ─────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Không làm phiền", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            StatusPill(
                label = if (dndActive) "Đang bật" else "Đang tắt",
                bgColor = if (dndActive) Color(0xFFB71C1C) else Color(0xFF2E7D32)
            )
        }
        Text(
            "Khi bật, Milky ngừng tự lên tiếng (chào sáng, báo thời tiết, tóm tắt thông báo...) — vẫn trả lời bình thường nếu bạn chủ động hỏi. Cũng có thể nói \"đừng làm phiền mình trong X phút\".",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        if (dndActive) {
            val fmt = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }
            Text("Đang im lặng đến ${fmt.format(java.util.Date(dndUntil))}", fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Button(onClick = {
                DoNotDisturbHelper.disable(context)
                dndTick++
            }) { Text("Tắt Không làm phiền ngay") }
        } else {
            Text("Im lặng trong $dndMinutes phút", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = dndMinutes.toFloat(),
                onValueChange = { dndMinutes = it.toInt() },
                valueRange = 15f..240f, steps = 14
            )
            Button(onClick = {
                DoNotDisturbHelper.enableForMinutes(context, dndMinutes.toLong())
                dndTick++
            }) { Text("Bật Không làm phiền") }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SECTION 7 — NHÂN VẬT NỔI ĐÈ LÊN APP KHÁC (PHẦN 14)
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun FloatingPetSection() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE) }

    var hasOverlayPermission by remember {
        mutableStateOf(FloatingPetService.hasOverlayPermission(context))
    }
    // PHẦN 16 — Quyền Usage Access, cần thêm bên cạnh overlay để tự ẩn/hiện theo app đang mở.
    var hasUsageAccess by remember {
        mutableStateOf(ForegroundAppWatcher.hasUsageAccess(context))
    }
    var isRunning by remember {
        mutableStateOf(isFloatingPetServiceRunning(context))
    }
    // Model đang được chọn để nổi
    var selectedModel by remember {
        mutableStateOf(prefs.getString(MainActivity.KEY_FLOATING_MODEL, FloatingPetService.MODEL_SLIME)
            ?: FloatingPetService.MODEL_SLIME)
    }
    var modelDropdownExpanded by remember { mutableStateOf(false) }

    val modelOptions = listOf(
        FloatingPetService.MODEL_SLIME to "Milky-slime",
        FloatingPetService.MODEL_CHIBI to "Milky-chibi",
        FloatingPetService.MODEL_FULL  to "Milky-full"
    )
    val selectedLabel = modelOptions.firstOrNull { it.first == selectedModel }?.second ?: "Milky-slime"

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = FloatingPetService.hasOverlayPermission(context)
                hasUsageAccess = ForegroundAppWatcher.hasUsageAccess(context)
                isRunning = isFloatingPetServiceRunning(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val overlaySettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        hasOverlayPermission = FloatingPetService.hasOverlayPermission(context)
    }
    val usageAccessSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        hasUsageAccess = ForegroundAppWatcher.hasUsageAccess(context)
    }

    // PHẦN 16 — Chỉ cho bật switch khi có ĐỦ cả 2 quyền (overlay + usage access).
    val hasAllPermissions = hasOverlayPermission && hasUsageAccess

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("7. Nhân vật nổi trên app khác", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (hasAllPermissions) {
                Switch(
                    checked = isRunning,
                    onCheckedChange = { turnOn ->
                        if (turnOn) {
                            FloatingPetService.start(context, selectedModel)
                        } else {
                            FloatingPetService.stop(context)
                        }
                        isRunning = turnOn
                    }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Tách biệt với hình nền động ở Mục 1 — nhân vật nhỏ nổi ĐÈ LÊN mọi app khác, kéo để " +
                "di chuyển, chạm để phản ứng. Có thể bật cùng lúc với hình nền hoặc chỉ dùng riêng.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        // ── Chọn nhân vật nổi ──────────────────────────────────────────────
        if (hasAllPermissions) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Nhân vật nổi:", fontSize = 14.sp)
                Box {
                    OutlinedButton(onClick = { modelDropdownExpanded = true }) {
                        Text(selectedLabel)
                    }
                    DropdownMenu(
                        expanded = modelDropdownExpanded,
                        onDismissRequest = { modelDropdownExpanded = false }
                    ) {
                        modelOptions.forEach { (key, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    selectedModel = key
                                    prefs.edit().putString(MainActivity.KEY_FLOATING_MODEL, key).apply()
                                    modelDropdownExpanded = false
                                    // Nếu đang chạy, restart với model mới ngay
                                    if (isRunning) {
                                        FloatingPetService.start(context, key)
                                    }
                                }
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        // ───────────────────────────────────────────────────────────────────

        if (!hasOverlayPermission) {
            StatusPill(label = "Chưa cấp quyền hiển thị đè lên app khác", bgColor = Color(0xFFB71C1C))
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${context.packageName}")
                    )
                    overlaySettingsLauncher.launch(intent)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Cấp quyền hiển thị đè lên app khác") }
        } else if (!hasUsageAccess) {
            // PHẦN 16 — Đã có overlay nhưng thiếu Usage Access (cần để tự ẩn/hiện theo app đang mở).
            StatusPill(label = "Chưa cấp quyền truy cập dữ liệu sử dụng (Usage Access)", bgColor = Color(0xFFB71C1C))
            Spacer(Modifier.height(6.dp))
            Text(
                "Cần quyền này để nhân vật nổi tự ẩn ở Home/màn hình khoá và tự hiện lại khi vào app khác.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    usageAccessSettingsLauncher.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Cấp quyền Usage Access") }
        } else {
            StatusPill(
                label = if (isRunning) "Đang nổi ($selectedLabel)" else "Đã cấp quyền — đang tắt",
                bgColor = if (isRunning) Color(0xFF2E7D32) else Color(0xFF616161)
            )
        }
    }
}

/** Kiểm tra [FloatingPetService] hiện có đang chạy không (chỉ thấy service của chính app mình). */
private fun isFloatingPetServiceRunning(context: Context): Boolean {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    @Suppress("DEPRECATION")
    return am.getRunningServices(Int.MAX_VALUE).any {
        it.service.className == FloatingPetService::class.java.name
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SECTION 8 — HỒ SƠ CHỦ NHÂN: TRÍ NHỚ LÂU DÀI (PHẦN 20)
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun MemoryProfileSection(onSaved: (String) -> Unit) {
    val context = LocalContext.current
    var profileText by remember { mutableStateOf(MemoryProfileHelper.get(context)) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("8. Hồ sơ chủ nhân (trí nhớ lâu dài)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "Milky đọc hồ sơ này ở MỌI câu chat để nhớ những chuyện quan trọng qua nhiều ngày " +
                "(dị ứng, sinh nhật người thân, sở thích...) — Milky cũng tự thêm vào đây khi bạn " +
                "kể chuyện gì đáng nhớ. Mỗi dòng là 1 điều cần nhớ, bạn có thể tự sửa/xoá trực tiếp.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value         = profileText,
            onValueChange = { profileText = it },
            label         = { Text("Hồ sơ về bạn") },
            placeholder   = { Text("Ví dụ:\nChủ nhân dị ứng tôm\nSinh nhật mẹ chủ nhân là 12/8") },
            minLines      = 6,
            maxLines      = 14,
            modifier      = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick  = {
                    profileText = ""
                    MemoryProfileHelper.set(context, "")
                    onSaved("Đã xoá hồ sơ")
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Xoá hết")
            }

            Button(
                onClick = {
                    MemoryProfileHelper.set(context, profileText)
                    onSaved("Đã lưu hồ sơ")
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Lưu")
            }
        }
    }
}

// ── Shared: Pill ──────────────────────────────────────────────────────────────

@Composable
fun StatusPill(label: String, bgColor: Color) {
    Surface(color = bgColor, shape = MaterialTheme.shapes.extraLarge) {
        Text(
            label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

package com.example.chibiwallpaper.ui

import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.chibiwallpaper.ai.GeminiClient
import com.example.chibiwallpaper.ai.GeminiResponse
import com.example.chibiwallpaper.ai.SpeechToTextManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * PHẦN 12 — "Hỏi bằng ảnh": chụp 1 tấm rồi hỏi bằng giọng nói ("cái này là gì?", "dịch giúp
 * mình"), gửi cả ảnh + câu hỏi cho Gemini multimodal (xem [GeminiClient.sendImageMessage]) và
 * hiện câu trả lời ngay trong dialog.
 *
 * Luồng: xin quyền Camera (nếu chưa có) → mở camera chụp ảnh (FileProvider) → xin quyền Micro
 * (nếu chưa có) → STT hỏi câu hỏi (mặc định "Cái này là gì vậy?" nếu không nói/không có quyền)
 * → gửi Gemini → hiện AlertDialog.
 *
 * Activity trong suốt (theme Translucent.NoTitleBar trong Manifest), có thể được mở từ
 * MainActivity (Context bình thường) hoặc từ ChibiWallpaperService (Context của Service, cần
 * FLAG_ACTIVITY_NEW_TASK — caller tự thêm flag đó).
 */
class PhotoAskActivity : ComponentActivity() {

    private lateinit var geminiClient: GeminiClient
    private lateinit var sttManager: SpeechToTextManager
    private var photoUri: Uri? = null

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) onPhotoTaken() else finishWithToast("Không chụp được ảnh~")
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera() else finishWithToast("Cần quyền Camera để chụp ảnh~")
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) sttManager.startListening() else askQuestionByDefault()
    }

    companion object {
        private const val TAG = "ChibiPhotoAsk"
        private const val MAX_IMAGE_DIMENSION = 1024 // giữ payload gọn khi gửi Gemini
        private const val JPEG_QUALITY = 80
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        geminiClient = GeminiClient(applicationContext)
        sttManager = SpeechToTextManager(
            context = this,
            onResult = { text -> onQuestionReady(text) },
            onError = { askQuestionByDefault() }
        )
        sttManager.init()

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            launchCamera()
        } else {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        try {
            val file = File.createTempFile("milky_photo_", ".jpg", cacheDir)
            // Dùng local val rồi mới gán vào property — Kotlin không smart-cast được property
            // var (photoUri: Uri?) sang non-null dù vừa gán ngay phía trên.
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            photoUri = uri
            cameraLauncher.launch(uri)
        } catch (e: Exception) {
            Log.e(TAG, "Không tạo được file ảnh tạm: ${e.message}")
            finishWithToast("Mình không mở được camera~")
        }
    }

    private fun onPhotoTaken() {
        Toast.makeText(this, "Chụp xong rồi! Hỏi Milky xem đây là gì nè~", Toast.LENGTH_SHORT).show()
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            sttManager.startListening()
        } else {
            micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    /** Không có quyền mic hoặc STT lỗi/im lặng → dùng câu hỏi mặc định thay vì kẹt luồng. */
    private fun askQuestionByDefault() {
        onQuestionReady("Cái này là gì vậy? Nếu có chữ thì dịch giúp mình.")
    }

    private fun onQuestionReady(question: String) {
        val uri = photoUri ?: run { finishWithToast("Không tìm thấy ảnh vừa chụp~"); return }
        Toast.makeText(this, "Milky đang xem ảnh...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.Main).launch {
            val base64 = withContext(Dispatchers.IO) { encodeImageForGemini(uri) }
            if (base64 == null) {
                finishWithToast("Mình không đọc được ảnh vừa chụp~")
                return@launch
            }

            val response = geminiClient.sendImageMessage(question, base64)
            val answer = when (response) {
                is GeminiResponse.Text -> response.content
                is GeminiResponse.FunctionCall -> "Milky muốn làm gì đó (${response.name}) nhưng ở đây mình chỉ trả lời text thôi~"
                null -> "Mình bị mất mạng rồi~ Thử lại sau nhé!"
            }
            showAnswerDialog(question, answer)
        }
    }

    /** Đọc bitmap từ URI, resize + nén JPEG để giữ payload gọn trước khi Base64 encode. */
    private fun encodeImageForGemini(uri: Uri): String? {
        return try {
            val original = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                ?: return null
            val scaled = scaleDown(original, MAX_IMAGE_DIMENSION)
            val outputStream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
            Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi encode ảnh: ${e.message}", e)
            null
        }
    }

    private fun scaleDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val largestSide = maxOf(bitmap.width, bitmap.height)
        if (largestSide <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / largestSide
        val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun showAnswerDialog(question: String, answer: String) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("Milky trả lời")
            .setMessage(answer)
            .setPositiveButton("Đóng") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun finishWithToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        sttManager.release()
        super.onDestroy()
    }
}

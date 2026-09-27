package com.example.chibiwallpaper.ai

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log

/**
 * PHẦN 12 — Bật/tắt đèn pin (torch) qua [CameraManager.setTorchMode].
 *
 * Lưu ý: [CameraManager.setTorchMode] KHÔNG cần quyền CAMERA để bật/tắt torch trên API 23+
 * (đây là API công khai, độc lập với việc mở camera preview). Quyền
 * android.permission.FLASHLIGHT khai trong Manifest chỉ mang tính khai báo tương thích cho các
 * máy/ROM cũ, không phải quyền dangerous cần xin runtime.
 */
object FlashlightHelper {
    private const val TAG = "ChibiFlashlight"

    fun hasFlash(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)

    /**
     * Bật ([on]=true) hoặc tắt ([on]=false) đèn pin của camera sau.
     * @return true nếu thành công, false nếu máy không có đèn pin hoặc có lỗi.
     */
    fun setTorch(context: Context, on: Boolean): Boolean {
        if (!hasFlash(context)) return false
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val camId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false
            cameraManager.setTorchMode(camId, on)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Không đổi được torch mode: ${e.message}")
            false
        }
    }
}

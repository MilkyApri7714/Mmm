package com.example.chibiwallpaper.ai

import android.service.quicksettings.TileService
import android.widget.Toast
import com.example.chibiwallpaper.ChibiWallpaperService
import com.example.chibiwallpaper.floating.FloatingPetService

/**
 * PHẦN 12 — Quick Settings Tile "Hỏi Milky": kéo thanh trạng thái xuống, bấm 1 cái là kích
 * hoạt STT ngay lập tức, không cần chạm vào nhân vật.
 *
 * Ưu tiên: thử floating trước (đang nổi trên app khác), nếu không có thì thử live wallpaper,
 * nếu cả hai đều không active → Toast hướng dẫn user.
 */
class QuickListenTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val handled = FloatingPetService.tryTriggerFloatingVoice()
                   || ChibiWallpaperService.tryTriggerVoiceInput()
        if (!handled) {
            Toast.makeText(
                this,
                "Bật nhân vật nổi hoặc đặt Milky làm hình nền trước đã nhé~",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}

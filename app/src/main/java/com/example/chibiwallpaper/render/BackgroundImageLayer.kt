package com.example.chibiwallpaper.render

import android.graphics.Bitmap

/**
 * PHẦN 18 — Layer 1 (nền dưới cùng) cho Live Wallpaper: ảnh tĩnh vẽ full màn hình, TRƯỚC khi vẽ
 * model (Layer 2) đè lên. Dùng lại [OverlayTextureRenderer.createTextureFromBitmap]/[OverlayTextureRenderer.drawTexture]
 * có sẵn (Phần 5) — không cần SurfaceTexture/OES như [VideoLayer] vì đây chỉ là ảnh tĩnh.
 *
 * Bitmap truyền vào [setImage] PHẢI đã được center-crop theo đúng tỉ lệ màn hình thật từ trước
 * (xem [BackgroundImageLoader.decodeCenterCropped]) — layer này chỉ upload thẳng lên texture rồi
 * vẽ full quad, không tự crop/scale gì thêm (tái dùng drawTexture 100% như plan yêu cầu, quad sẽ
 * khớp khít vì bitmap đã đúng tỉ lệ từ khâu decode).
 *
 * Không có ảnh (chưa chọn / lỗi decode) → [draw] không làm gì — MultiModelScene fallback về
 * glClearColor màu đặc hiện có, không breaking change.
 */
class BackgroundImageLayer {

    private var textureId = 0

    val hasImage: Boolean get() = textureId != 0

    /** GL thread. Upload bitmap mới lên texture, xoá texture cũ (nếu có) trước. Không giữ bitmap lại. */
    fun setImage(overlayRenderer: OverlayTextureRenderer, bitmap: Bitmap) {
        deleteCurrent(overlayRenderer)
        textureId = overlayRenderer.createTextureFromBitmap(bitmap)
    }

    /** GL thread. Xoá ảnh nền hiện tại (nếu có) — quay về fallback màu đặc. */
    fun clearImage(overlayRenderer: OverlayTextureRenderer) {
        deleteCurrent(overlayRenderer)
    }

    /** GL thread. Vẽ full màn hình — gọi NGAY ĐẦU onDrawFrame (Layer 1), trước khi vẽ model. */
    fun draw(overlayRenderer: OverlayTextureRenderer, screenW: Int, screenH: Int) {
        if (textureId == 0) return
        overlayRenderer.drawTexture(
            textureId = textureId,
            screenW = screenW, screenH = screenH,
            centerXPx = screenW / 2f, centerYPx = screenH / 2f,
            widthPx = screenW.toFloat(), heightPx = screenH.toFloat()
        )
    }

    /** Context GL bị huỷ — texture cũ tự mất theo context, chỉ cần reset id nội bộ. */
    fun onContextDestroyed() {
        textureId = 0
    }

    private fun deleteCurrent(overlayRenderer: OverlayTextureRenderer) {
        if (textureId != 0) {
            overlayRenderer.deleteTexture(textureId)
            textureId = 0
        }
    }
}

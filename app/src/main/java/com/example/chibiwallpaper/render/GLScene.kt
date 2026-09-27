package com.example.chibiwallpaper.render

/**
 * Một "cảnh" được GLRenderer vẽ. TẤT CẢ hàm dưới đây đều được gọi trên GL thread
 * ("ChibiGL"), nên có thể gọi GLES20.* thoải mái bên trong.
 *
 * PHẦN 9 — Thêm [onTripleTap] (toggle STRAW) và [wakeIfHit] (chỉ đánh thức khi trúng Slime).
 */
interface GLScene {

    /** EGL context vừa được tạo mới (lần đầu, hoặc sau khi mất context). Tạo shader/texture/model ở đây. */
    fun onContextCreated()

    /** Kích thước surface thay đổi (kể cả lần đầu). GLRenderer đã glViewport(0, 0, width, height). */
    fun onSurfaceChanged(width: Int, height: Int)

    /** Vẽ một frame. GLRenderer đã glClear trước khi gọi. [deltaSeconds] đã bị chặn tối đa 0.1s. */
    fun onDrawFrame(deltaSeconds: Float)

    /** Chạm xuống màn hình, toạ độ pixel (gốc trên-trái). */
    fun onTouch(x: Float, y: Float)

    /**
     * PHẦN 9 — Ba lần tap: toggle STRAW motion trên Slime (hoạt động kể cả khi đang ngủ).
     * Toạ độ [x], [y] dùng để hit-test nếu cần (hiện tại toggle bất kể trúng đâu).
     */
    fun onTripleTap(x: Float, y: Float) {}

    /**
     * PHẦN 13 — Vuốt lên trúng Slime: bật/tắt luân phiên một trong hai hiệu ứng
     * (AD.exp3.json hoặc STRAW.motion3.json, chọn ngẫu nhiên khi bật). Vuốt lần 1 → bật,
     * vuốt lần 2 → tắt lại đúng hiệu ứng đang bật (hoạt động kể cả khi Slime đang ngủ).
     * Toạ độ [x], [y] là điểm bắt đầu vuốt, dùng để hit-test vào Slime.
     */
    fun onSwipeUp(x: Float, y: Float) {}

    /**
     * PHẦN 9 — Double-tap: chỉ đánh thức Slime nếu tap TRÚNG VÀO Slime (theo vị trí hiện tại),
     * không đánh thức khi tap vào chỗ khác.
     * Trả về true nếu Slime được đánh thức (để service biết có nên tiếp tục vào STT không).
     */
    fun wakeIfHit(x: Float, y: Float): Boolean = true

    /**
     * PHẦN 12 — Đánh thức Slime vô điều kiện (không cần tap trúng vị trí), dùng cho trigger
     * bên ngoài không có toạ độ chạm thật (Quick Settings Tile).
     */
    fun wakeSlime() {}

    /**
     * Context sắp bị huỷ (hoặc đã mất). Khi context bị huỷ, mọi tài nguyên GL (program, texture, buffer...)
     * tự được giải phóng — scene chỉ cần bỏ các handle đang giữ, KHÔNG cần gọi glDelete*.
     */
    fun onContextDestroyed()

    /**
     * PHẦN 8 — true khi scene muốn [GLRenderer] hạ khung hình/giây xuống mức tiết kiệm pin.
     */
    fun wantsLowFrameRate(): Boolean = false
}

package com.example.chibiwallpaper.character

import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * PHẦN 3.5 — Logic di chuyển Slime.
 * PHẦN 4   — Thêm [nudge]: single-tap làm Slime "giật" nhẹ rồi đổi hướng.
 * PHẦN 8   — Thêm chế độ "ngủ" tiết kiệm pin: [SLEEP_IDLE_THRESHOLD] giây không có tương tác →
 *            Slime đi về góc, đứng yên ([isSleeping] = true). [wake] để đánh thức.
 *
 * FIX: posY không còn bị drift tích lũy bởi bobOffset mỗi frame — bob giờ được tính thuần túy
 * từ sin(timeAccum) và gán vào [posY] độc lập với vị trí logic ([logicX]/[logicY]).
 * FIX: pickNewTarget() dùng Random đủ rộng trên toàn vùng MARGIN để Slime thực sự đi khắp màn.
 */
class SlimeController {

    /** Vị trí hiển thị (có bob) — dùng để render. */
    var posX: Float = 0f
        private set
    var posY: Float = -0.3f
        private set

    /** Vị trí logic (không bob) — dùng để tính toán di chuyển bên trong. */
    private var logicX: Float = 0f
    private var logicY: Float = -0.3f

    private var targetX: Float = 0f
    private var targetY: Float = -0.3f
    private var pauseTimer: Float = 0f
    private var isMoving: Boolean = false
    private var timeAccum: Float = 0f

    // Nudge
    private var nudgeVX: Float = 0f
    private var nudgeVY: Float = 0f
    private var nudgeDecay: Float = 0f

    // PHẦN 8 — Ngủ
    @Volatile var isSleeping: Boolean = false
        private set
    @Volatile private var idleTimer: Float = 0f
    @Volatile private var headingToSleep: Boolean = false

    @Volatile var suppressSleep: Boolean = false

    companion object {
        private const val MARGIN_X        = 0.55f
        private const val MARGIN_Y_LOW    = -0.55f
        private const val MARGIN_Y_HIGH   = 0.10f
        private const val MOVE_SPEED      = 0.22f
        private const val PAUSE_MIN       = 1.0f
        private const val PAUSE_MAX       = 3.5f
        private const val BOB_AMPLITUDE   = 0.018f
        private const val BOB_FREQUENCY   = 2.2f
        private const val ARRIVE_THRESHOLD = 0.015f
        private const val NUDGE_SPEED     = 0.4f
        private const val NUDGE_DECAY     = 4.0f

        // PHẦN 8
        private const val SLEEP_IDLE_THRESHOLD = 25f
        private const val SLEEP_MARGIN_X       = 0.50f
        private const val SLEEP_Y              = -0.50f
        private const val SLEEP_BOB_AMPLITUDE  = 0.007f
    }

    fun update(dt: Float) {
        timeAccum += dt

        if (isSleeping) {
            if (suppressSleep) {
                wake()
            } else {
                // Ngủ: chỉ dao động nhẹ theo Y, không di chuyển logic.
                posX = logicX
                posY = logicY + sin(timeAccum * BOB_FREQUENCY * Math.PI.toFloat() * 2f) * SLEEP_BOB_AMPLITUDE
                return
            }
        }

        if (suppressSleep) {
            idleTimer = 0f
            headingToSleep = false
        } else {
            idleTimer += dt
            if (!headingToSleep && nudgeDecay <= 0f && idleTimer >= SLEEP_IDLE_THRESHOLD) {
                headingToSleep = true
                isMoving = true
                targetX = if (Random.nextBoolean()) SLEEP_MARGIN_X else -SLEEP_MARGIN_X
                targetY = SLEEP_Y
            }
        }

        // Nudge decay
        if (nudgeDecay > 0f) {
            logicX += nudgeVX * dt
            logicY += nudgeVY * dt
            // Clamp để nudge không bay ra ngoài vùng roam
            logicX = logicX.coerceIn(-MARGIN_X, MARGIN_X)
            logicY = logicY.coerceIn(MARGIN_Y_LOW, MARGIN_Y_HIGH)
            nudgeDecay -= dt
            if (nudgeDecay <= 0f) {
                nudgeVX = 0f; nudgeVY = 0f; nudgeDecay = 0f
                pickNewTarget()
                isMoving = true
            }
        } else if (!isMoving) {
            pauseTimer -= dt
            if (pauseTimer <= 0f) { pickNewTarget(); isMoving = true }
        } else {
            moveTowardTarget(dt)
        }

        // Bob: tính từ logicY thuần túy, không cộng dồn qua các frame
        val bobOffset = sin(timeAccum * BOB_FREQUENCY * Math.PI.toFloat() * 2f) * BOB_AMPLITUDE
        posX = logicX
        posY = logicY + bobOffset
    }

    fun reset() {
        logicX = 0f; logicY = -0.3f
        posX = 0f; posY = -0.3f
        targetX = 0f; targetY = -0.3f
        isMoving = false; pauseTimer = 0.5f; timeAccum = 0f
        nudgeVX = 0f; nudgeVY = 0f; nudgeDecay = 0f
        isSleeping = false; idleTimer = 0f; headingToSleep = false
        suppressSleep = false
    }

    fun nudge() {
        wake()
        val angle = Random.nextFloat() * Math.PI.toFloat() * 2f
        nudgeVX = NUDGE_SPEED * kotlin.math.cos(angle)
        nudgeVY = NUDGE_SPEED * kotlin.math.sin(angle) * 0.5f
        nudgeDecay = 1f / NUDGE_DECAY
        isMoving = false
    }

    fun wake() {
        idleTimer = 0f
        headingToSleep = false
        if (isSleeping) {
            isSleeping = false
            isMoving = false
            pauseTimer = 0.4f + Random.nextFloat() * 0.3f
        }
    }

    private fun pickNewTarget() {
        // Đảm bảo target luôn cách vị trí hiện tại ít nhất 0.25 để không đứng một chỗ
        var tries = 0
        do {
            targetX = Random.nextFloat() * 2f * MARGIN_X - MARGIN_X
            targetY = Random.nextFloat() * (MARGIN_Y_HIGH - MARGIN_Y_LOW) + MARGIN_Y_LOW
            val dx = targetX - logicX
            val dy = targetY - logicY
            tries++
        } while (sqrt(dx * dx + dy * dy) < 0.25f && tries < 8)
    }

    private fun moveTowardTarget(dt: Float) {
        val dx = targetX - logicX
        val dy = targetY - logicY
        val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        if (dist < ARRIVE_THRESHOLD) {
            logicX = targetX; logicY = targetY
            isMoving = false
            if (headingToSleep) {
                headingToSleep = false
                isSleeping = true
            } else {
                pauseTimer = PAUSE_MIN + Random.nextFloat() * (PAUSE_MAX - PAUSE_MIN)
            }
        } else {
            val step = MOVE_SPEED * dt
            val ratio = minOf(step / dist, 1f)
            logicX += dx * ratio
            logicY += dy * ratio
        }
    }
}

package com.example.chibiwallpaper.character

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import com.live2d.sdk.cubism.framework.CubismDefaultParameterId
import com.live2d.sdk.cubism.framework.CubismFramework
import com.live2d.sdk.cubism.framework.CubismModelSettingJson
import com.live2d.sdk.cubism.framework.ICubismModelSetting
import com.live2d.sdk.cubism.framework.effect.CubismBreath
import com.live2d.sdk.cubism.framework.effect.CubismEyeBlink
import com.live2d.sdk.cubism.framework.math.CubismMatrix44
import com.live2d.sdk.cubism.framework.math.CubismModelMatrix
import com.live2d.sdk.cubism.framework.model.CubismUserModel
import com.live2d.sdk.cubism.framework.motion.CubismExpressionMotion
import com.live2d.sdk.cubism.framework.motion.CubismMotion
import com.live2d.sdk.cubism.framework.motion.CubismMotionManager
import com.live2d.sdk.cubism.framework.physics.CubismPhysics
import com.live2d.sdk.cubism.framework.rendering.android.CubismRendererAndroid
import java.io.IOException
import kotlin.random.Random

/**
 * PHẦN 3.3–3.5 — Load + render 1 Cubism model.
 * PHẦN 4 — Thêm [setExpression] và [triggerRandomMotion].
 * PHẦN 9 — Thêm toggle: [toggleMotion] (bật/tắt 1 motion loop theo tên nhóm),
 *           [toggleExpression] (bật/tắt 1 expression theo tên).
 *           Motions được lưu vào [motionsByGroupName] để gọi theo tên nhóm.
 *           Expressions khi "tắt" dùng expression off tự sinh (reset param về 0).
 */
class CubismModelWrapper(private val appContext: Context) : CubismUserModel() {

    private val textureIdsByIndex = mutableMapOf<Int, Int>()

    var posX: Float = 0f
    var posY: Float = 0f
    var scaleFactor: Float = 1f

    // ── Motion / Expression / Physics ─────────────────────────────────────
    private val motionManager     = CubismMotionManager()
    private val expressionManager = CubismMotionManager()

    private val idleMotions     = mutableListOf<CubismMotion>()
    private val allMotions      = mutableListOf<CubismMotion>()  // tất cả motions (cho triggerRandom)

    /** PHẦN 9 — Map tên nhóm (lowercase) → danh sách motion trong nhóm đó. */
    private val motionsByGroupName = mutableMapOf<String, MutableList<CubismMotion>>()

    /** Map tên file motion (đúng như trong model3.json, ví dụ "motion/05.motion3.json") → motion. */
    private val motionsByFileName = mutableMapOf<String, CubismMotion>()

    /**
     * Tên (hậu tố) file của motion "khóc" cho model này, ví dụ "05.motion3.json".
     * Set từ ngoài (sau khi [loadFromAssets] xong) cho model nào có biểu cảm khóc — hiện tại
     * chỉ Chibi. Khi != null:
     *  - Motion này bị LOẠI khỏi pool ngẫu nhiên của [triggerRandomMotion] (không tự chạy khi tap).
     *  - Motion này CHỈ chạy khi gọi [playCryMotion] (dùng cho lúc lỗi / Gemini báo lỗi).
     * Không set (null) thì model chạy như cũ — không có xử lý đặc biệt gì.
     */
    var cryMotionFileName: String? = null

    private fun findCryMotion(): CubismMotion? {
        val suffix = cryMotionFileName ?: return null
        return motionsByFileName.entries.firstOrNull { it.key.endsWith(suffix) }?.value
    }

    private val expressions     = mutableMapOf<String, CubismExpressionMotion>()

    /** PHẦN 9 — Map tên nhóm motion đang toggle-on (lowercase) → true nếu đang chạy. */
    private val motionToggleState = mutableMapOf<String, Boolean>()

    /** PHẦN 9 — Map tên expression đang toggle-on (lowercase) → true nếu đang bật. */
    private val expressionToggleState = mutableMapOf<String, Boolean>()

    /** PHẦN 9 — Expression "tắt" tự sinh — reset tất cả param về 0 để dứt khoát tắt. */
    private var offExpression: CubismExpressionMotion? = null

    private var idleIndex = 0

    private var physics:  CubismPhysics?  = null
    private var breath:   CubismBreath?   = null
    private var eyeBlink: CubismEyeBlink? = null

    // ─────────────────────────────────────────────────────────────────────────

    fun loadFromAssets(modelDir: String, modelJsonFileName: String): Boolean {
        val settingJson = readAssetBytes(modelDir + modelJsonFileName) ?: return false
        val setting: ICubismModelSetting = CubismModelSettingJson(settingJson)
        if (setting.json == null) { Log.e(TAG, "model3.json không đọc được: $modelDir$modelJsonFileName"); return false }

        // moc3
        val mocFileName = setting.modelFileName
        if (mocFileName.isNullOrEmpty()) { Log.e(TAG, "Thiếu FileReferences.Moc"); return false }
        val mocBytes = readAssetBytes(modelDir + mocFileName) ?: return false
        loadModel(mocBytes)
        val loadedModel = model ?: return false

        // Textures
        for (i in 0 until setting.textureCount) {
            val texFileName = setting.getTextureFileName(i)
            if (texFileName.isNullOrEmpty()) continue
            loadGlTexture(modelDir + texFileName)?.let { textureIdsByIndex[i] = it }
        }

        // Renderer
        setupRenderer(CubismRendererAndroid.create(1, 1))
        val cubismRenderer = getRenderer<CubismRendererAndroid>() ?: return false
        cubismRenderer.isPremultipliedAlpha(true)
        for ((idx, glId) in textureIdsByIndex) cubismRenderer.bindTexture(idx, glId)

        // Model matrix
        modelMatrix = CubismModelMatrix.create(loadedModel.canvasWidth, loadedModel.canvasHeight)
        val layout = mutableMapOf<String, Float>()
        if (setting.getLayoutMap(layout)) modelMatrix?.setupFromLayout(layout)

        // Motions — load tất cả nhóm, lưu vào cả allMotions và motionsByGroupName
        for (g in 0 until setting.motionGroupCount) {
            val groupName = setting.getMotionGroupName(g) ?: continue
            val groupKey = groupName.lowercase()
            val groupList = motionsByGroupName.getOrPut(groupKey) { mutableListOf() }
            for (i in 0 until setting.getMotionCount(groupName)) {
                val motionFile = setting.getMotionFileName(groupName, i)
                if (motionFile.isNullOrEmpty()) continue
                val bytes = readAssetBytes(modelDir + motionFile) ?: continue
                val motion = CubismMotion.create(bytes)
                val fadeIn  = setting.getMotionFadeInTimeValue(groupName, i).takeIf { it >= 0f } ?: 0.5f
                val fadeOut = setting.getMotionFadeOutTimeValue(groupName, i).takeIf { it >= 0f } ?: 0.5f
                motion.setFadeInTime(fadeIn)
                motion.setFadeOutTime(fadeOut)
                allMotions.add(motion)
                groupList.add(motion)
                motionsByFileName[motionFile] = motion
                if (groupKey == "idle") idleMotions.add(motion)
            }
        }
        Log.d(TAG, "Motions: total=${allMotions.size} idle=${idleMotions.size} groups=${motionsByGroupName.keys}")

        // Expressions (exp3.json)
        for (i in 0 until setting.expressionCount) {
            val expName = setting.getExpressionName(i) ?: continue
            val expFile = setting.getExpressionFileName(i) ?: continue
            val bytes   = readAssetBytes(modelDir + expFile) ?: continue
            val expr    = CubismExpressionMotion.create(bytes)
            val key = expName.substringBefore(".exp3").substringBefore(".").lowercase()
            expressions[key] = expr
            expressions[expName.lowercase()] = expr
        }

        // PHẦN 9 — Tự sinh expression "tắt" (reset tất cả param về 0, Blend=Overwrite)
        offExpression = buildOffExpression()

        Log.d(TAG, "Expressions: ${expressions.keys}")

        // Physics
        val physicsFile = setting.physicsFileName
        if (!physicsFile.isNullOrEmpty()) {
            readAssetBytes(modelDir + physicsFile)?.let { physics = CubismPhysics.create(it) }
        }

        // Breath
        breath = CubismBreath.create().apply {
            val breathId = CubismFramework.getIdManager().getId(CubismDefaultParameterId.ParameterId.BREATH.id)
            setParameters(listOf(CubismBreath.BreathParameterData(breathId, 0f, 1f, 3.2f, 1f)))
        }

        // Eye Blink
        eyeBlink = CubismEyeBlink.create(setting).also { blink ->
            if (blink.parameterIds.isEmpty()) {
                val idMgr = CubismFramework.getIdManager()
                blink.setParameterIds(listOf(
                    idMgr.getId(CubismDefaultParameterId.ParameterId.EYE_L_OPEN.id),
                    idMgr.getId(CubismDefaultParameterId.ParameterId.EYE_R_OPEN.id)
                ))
            }
        }

        loadedModel.update()
        Log.d(TAG, "Load OK '$modelJsonFileName': params=${loadedModel.parameterCount}")
        return true
    }

    fun draw(screenWidth: Int, screenHeight: Int, dt: Float = 0f) {
        if (screenWidth <= 0 || screenHeight <= 0) return
        val loadedModel = model ?: return
        val cubismRenderer = getRenderer<CubismRendererAndroid>() ?: return

        // Idle motion loop — CHỈ chạy khi KHÔNG có toggle motion nào đang bật
        val anyToggleMotionOn = motionToggleState.values.any { it }
        if (!anyToggleMotionOn && idleMotions.isNotEmpty() && motionManager.isFinished()) {
            val motion = idleMotions[idleIndex % idleMotions.size]
            idleIndex++
            motionManager.startMotionPriority(motion, PRIORITY_IDLE)
        }
        tickToggleMotionLoops()
        motionManager.updateMotion(loadedModel, dt)
        expressionManager.updateMotion(loadedModel, dt)

        breath?.updateParameters(loadedModel, dt)
        eyeBlink?.updateParameters(loadedModel, dt)
        physics?.evaluate(loadedModel, dt)
        loadedModel.update()

        val projection = CubismMatrix44.create()
        projection.loadIdentity()
        val screenAspect = screenWidth.toFloat() / screenHeight.toFloat()
        if (screenAspect > 1f) projection.scale(scaleFactor / screenAspect, scaleFactor)
        else projection.scale(scaleFactor, scaleFactor * screenAspect)
        modelMatrix?.let { projection.multiplyByMatrix(it) }
        projection.translateRelative(posX, posY)

        cubismRenderer.setMvpMatrix(projection)
        cubismRenderer.drawModel()
    }

    /**
     * PHẦN 4 — Kích hoạt expression theo tên (1 lần, không toggle).
     */
    fun setExpression(name: String) {
        val key = name.substringBefore(".exp3").substringBefore(".").lowercase()
        val expr = expressions[key] ?: expressions[name.lowercase()]
        if (expr != null) {
            expressionManager.startMotionPriority(expr, PRIORITY_EXPRESSION)
            Log.d(TAG, "Expression: $key")
        } else {
            Log.w(TAG, "Expression '$name' không tìm thấy (có: ${expressions.keys})")
        }
    }

    /**
     * PHẦN 4 — Trigger ngẫu nhiên 1 motion ngoài idle (single-tap reaction).
     * Motion "khóc" ([cryMotionFileName], nếu có set) bị loại khỏi pool ngẫu nhiên này —
     * biểu cảm khóc chỉ được phép chạy qua [playCryMotion] (lúc lỗi/Gemini báo lỗi).
     */
    fun triggerRandomMotion() {
        val cry = findCryMotion()
        val nonIdle = allMotions.filter { it !in idleMotions && it !== cry }
        val pool = if (nonIdle.isNotEmpty()) nonIdle else allMotions.filter { it !== cry }
        val motion = pool.randomOrNull() ?: return
        motionManager.startMotionPriority(motion, PRIORITY_NORMAL)
        Log.d(TAG, "Random motion triggered")
    }

    /**
     * Kích hoạt motion "khóc" ([cryMotionFileName]) — dùng khi lỗi (STT lỗi, Gemini báo lỗi,
     * mất kết nối...). Không làm gì (chỉ log warning) nếu model này chưa set [cryMotionFileName]
     * hoặc không tìm thấy motion tương ứng.
     */
    fun playCryMotion() {
        val motion = findCryMotion()
        if (motion != null) {
            motionManager.startMotionPriority(motion, PRIORITY_FORCE)
            Log.d(TAG, "Cry motion triggered (error)")
        } else {
            Log.w(TAG, "playCryMotion: chưa set cryMotionFileName hoặc không tìm thấy motion khóc")
        }
    }

    /**
     * PHẦN 9 — Bật/tắt (toggle) một motion loop theo tên nhóm (case-insensitive).
     * - Bật: lấy motion đầu tiên trong nhóm, chạy loop ở PRIORITY_FORCE (cao hơn idle).
     *   Loop được thực hiện bằng cách tái-schedule trong [draw] thay vì loop flag của SDK,
     *   vì SDK không expose loop flag công khai — thay vào đó ta dùng pattern "re-start khi finished".
     * - Tắt: gọi stopAllMotions() để dừng motion đang chạy, idle sẽ tự chạy lại trong [draw].
     * Trả về trạng thái mới (true = đang bật).
     */
    fun toggleMotion(groupName: String): Boolean {
        val key = groupName.lowercase()
        val currentOn = motionToggleState[key] ?: false
        return if (currentOn) {
            // Tắt
            motionManager.stopAllMotions()
            motionToggleState[key] = false
            Log.d(TAG, "Toggle motion OFF: $key")
            false
        } else {
            // Bật — chạy motion đầu của nhóm ở priority cao
            val groupMotions = motionsByGroupName[key]
            if (groupMotions.isNullOrEmpty()) {
                Log.w(TAG, "Không tìm thấy motion nhóm '$groupName' (có: ${motionsByGroupName.keys})")
                return false
            }
            motionToggleState[key] = true
            startToggleMotionLoop(key, groupMotions)
            Log.d(TAG, "Toggle motion ON: $key")
            true
        }
    }

    /**
     * PHẦN 9 — Bật/tắt (toggle) một expression theo tên (case-insensitive).
     * - Bật: startMotionPriority expression đó.
     * - Tắt: startMotionPriority expression "off" tự sinh để reset param dứt khoát.
     * Trả về trạng thái mới (true = đang bật).
     */
    fun toggleExpression(expressionName: String): Boolean {
        val key = expressionName.substringBefore(".exp3").substringBefore(".").lowercase()
        val currentOn = expressionToggleState[key] ?: false
        return if (currentOn) {
            // Tắt — dùng off expression để reset param
            val off = offExpression
            if (off != null) {
                expressionManager.startMotionPriority(off, PRIORITY_FORCE)
            }
            expressionToggleState[key] = false
            Log.d(TAG, "Toggle expression OFF: $key")
            false
        } else {
            // Bật
            val expr = expressions[key] ?: expressions[expressionName.lowercase()]
            if (expr == null) {
                Log.w(TAG, "Toggle expression '$expressionName' không tìm thấy (có: ${expressions.keys})")
                return false
            }
            expressionManager.startMotionPriority(expr, PRIORITY_FORCE)
            expressionToggleState[key] = true
            Log.d(TAG, "Toggle expression ON: $key")
            true
        }
    }

    /** PHẦN 9 — Lấy trạng thái toggle của motion nhóm (true = đang bật). */
    fun isMotionToggleOn(groupName: String): Boolean =
        motionToggleState[groupName.lowercase()] ?: false

    /** PHẦN 9 — Lấy trạng thái toggle của expression (true = đang bật). */
    fun isExpressionToggleOn(expressionName: String): Boolean {
        val key = expressionName.substringBefore(".exp3").substringBefore(".").lowercase()
        return expressionToggleState[key] ?: false
    }

    /**
     * PHẦN 9 — Gọi từ [draw] khi toggle motion đang ON và motionManager.isFinished() == true
     * để loop lại motion. Tách ra hàm riêng để dễ đọc.
     */
    fun tickToggleMotionLoops() {
        for ((key, on) in motionToggleState) {
            if (!on) continue
            if (motionManager.isFinished()) {
                val groupMotions = motionsByGroupName[key] ?: continue
                startToggleMotionLoop(key, groupMotions)
            }
        }
    }

    private fun startToggleMotionLoop(key: String, groupMotions: List<CubismMotion>) {
        val motion = groupMotions.first()
        motionManager.startMotionPriority(motion, PRIORITY_FORCE)
    }

    fun release() {
        idleMotions.clear()
        allMotions.clear()
        motionsByGroupName.clear()
        motionsByFileName.clear()
        expressions.clear()
        motionToggleState.clear()
        expressionToggleState.clear()
        physics = null; breath = null; eyeBlink = null; offExpression = null
        if (textureIdsByIndex.isNotEmpty()) {
            GLES20.glDeleteTextures(textureIdsByIndex.size, textureIdsByIndex.values.toIntArray(), 0)
            textureIdsByIndex.clear()
        }
        delete()
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * PHẦN 9 — Tạo expression "tắt" từ byte array JSON inline, reset tất cả param về 0.
     * Không cần file asset vì chỉ dùng nội bộ.
     */
    private fun buildOffExpression(): CubismExpressionMotion {
        val json = """{"Type":"Live2D Expression","Parameters":[]}""".toByteArray(Charsets.UTF_8)
        return CubismExpressionMotion.create(json)
    }

    private fun loadGlTexture(assetPath: String): Int? {
        val bitmap = try {
            appContext.assets.open(assetPath).use { stream ->
                val opts = BitmapFactory.Options().apply { inPremultiplied = true }
                BitmapFactory.decodeStream(stream, null, opts)
            }
        } catch (e: IOException) { Log.e(TAG, "Không mở được texture '$assetPath': ${e.message}"); null }
        ?: run { Log.e(TAG, "Decode PNG thất bại: $assetPath"); return null }

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        bitmap.recycle()
        return ids[0]
    }

    private fun readAssetBytes(assetPath: String): ByteArray? =
        try { appContext.assets.open(assetPath).use { it.readBytes() } }
        catch (e: IOException) { Log.e(TAG, "Không đọc được '$assetPath': ${e.message}"); null }

    companion object {
        private const val TAG = "ChibiCubism"
        // Motion priority constants (Cubism SDK dùng int, không có enum public)
        const val PRIORITY_IDLE       = 1
        const val PRIORITY_NORMAL     = 2
        const val PRIORITY_EXPRESSION = 2
        const val PRIORITY_FORCE      = 3
    }
}

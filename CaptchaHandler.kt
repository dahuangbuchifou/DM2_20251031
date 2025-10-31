package com.damaihelper.utils

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

/**
 * 验证码处理工具类
 * 负责检测、截取和处理验证码
 */
object CaptchaHandler {

    private const val TAG = "CaptchaHandler"

    // 常见验证码相关的文本标识
    private val CAPTCHA_KEYWORDS = listOf(
        "验证码", "captcha", "verification", "code",
        "请输入验证码", "请完成验证", "安全验证",
        "滑动验证", "点击验证", "拖动滑块"
    )

    // 验证码输入框的可能类名
    private val CAPTCHA_INPUT_CLASSES = listOf(
        "android.widget.EditText",
        "android.widget.AutoCompleteTextView"
    )

    /**
     * 检测当前页面是否存在验证码
     */
    fun detectCaptcha(rootNode: AccessibilityNodeInfo): CaptchaInfo? {
        // 检测文本验证码
        val textCaptcha = detectTextCaptcha(rootNode)
        if (textCaptcha != null) return textCaptcha

        // 检测滑动验证码
        val slideCaptcha = detectSlideCaptcha(rootNode)
        if (slideCaptcha != null) return slideCaptcha

        // 检测点选验证码
        val clickCaptcha = detectClickCaptcha(rootNode)
        if (clickCaptcha != null) return clickCaptcha

        return null
    }

    /**
     * 检测文本验证码
     */
    private fun detectTextCaptcha(rootNode: AccessibilityNodeInfo): CaptchaInfo? {
        // 查找验证码图片
        val imageNodes = findNodesByClassName(rootNode, "android.widget.ImageView")

        // 查找验证码输入框
        val inputNodes = CAPTCHA_INPUT_CLASSES.flatMap { className ->
            findNodesByClassName(rootNode, className)
        }

        // 检查是否有验证码相关的文本提示
        val hasKeyword = CAPTCHA_KEYWORDS.any { keyword ->
            findNodeByText(rootNode, keyword) != null
        }

        if (hasKeyword && imageNodes.isNotEmpty() && inputNodes.isNotEmpty()) {
            val imageNode = imageNodes.first()
            val inputNode = inputNodes.first()

            return CaptchaInfo(
                type = CaptchaType.TEXT,
                imageNode = imageNode,
                inputNode = inputNode,
                bounds = getBounds(imageNode)
            )
        }

        return null
    }

    /**
     * 检测滑动验证码
     */
    private fun detectSlideCaptcha(rootNode: AccessibilityNodeInfo): CaptchaInfo? {
        val slideKeywords = listOf("滑动", "slide", "拖动", "drag")

        val hasSlideKeyword = slideKeywords.any { keyword ->
            findNodeByText(rootNode, keyword) != null
        }

        if (hasSlideKeyword) {
            // 查找可能的滑动区域
            val imageNodes = findNodesByClassName(rootNode, "android.widget.ImageView")
            if (imageNodes.isNotEmpty()) {
                return CaptchaInfo(
                    type = CaptchaType.SLIDE,
                    imageNode = imageNodes.first(),
                    inputNode = null,
                    bounds = getBounds(imageNodes.first())
                )
            }
        }

        return null
    }

    /**
     * 检测点选验证码
     */
    private fun detectClickCaptcha(rootNode: AccessibilityNodeInfo): CaptchaInfo? {
        val clickKeywords = listOf("点击", "click", "选择", "select", "按顺序点击")

        val hasClickKeyword = clickKeywords.any { keyword ->
            findNodeByText(rootNode, keyword) != null
        }

        if (hasClickKeyword) {
            val imageNodes = findNodesByClassName(rootNode, "android.widget.ImageView")
            if (imageNodes.isNotEmpty()) {
                return CaptchaInfo(
                    type = CaptchaType.CLICK,
                    imageNode = imageNodes.first(),
                    inputNode = null,
                    bounds = getBounds(imageNodes.first())
                )
            }
        }

        return null
    }

    /**
     * 处理验证码（简化版本）
     */
    suspend fun handleCaptcha(
        service: AccessibilityService,
        captchaInfo: CaptchaInfo
    ): Boolean {
        return when (captchaInfo.type) {
            CaptchaType.TEXT -> handleTextCaptcha(service, captchaInfo)
            CaptchaType.SLIDE -> handleSlideCaptcha(service, captchaInfo)
            CaptchaType.CLICK -> handleClickCaptcha(service, captchaInfo)
        }
    }

    /**
     * 处理文本验证码
     */
    private suspend fun handleTextCaptcha(
        service: AccessibilityService,
        captchaInfo: CaptchaInfo
    ): Boolean {
        try {
            // 截取验证码图片
            val captchaBitmap = captureNodeImage(service, captchaInfo.imageNode)
            if (captchaBitmap == null) {
                Log.e(TAG, "无法截取验证码图片")
                return false
            }

            // 识别验证码（需要用户实现 OCR 功能）
            val recognizedText = recognizeImageWithOCR(captchaBitmap)

            if (!recognizedText.isNullOrEmpty()) {
                // 输入识别结果
                return inputCaptchaText(captchaInfo.inputNode, recognizedText)
            } else {
                Log.e(TAG, "验证码识别失败")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理文本验证码异常", e)
            return false
        }
    }

    /**
     * 处理滑动验证码
     * **注意：此方法需要 AccessibilityService.dispatchGesture 实现**
     */
    private suspend fun handleSlideCaptcha(
        service: AccessibilityService,
        captchaInfo: CaptchaInfo
    ): Boolean {
        try {
            // 1. 识别滑动距离 (需要图像识别)
            val targetOffsetX = 500 // 占位值，实际应由识别服务返回
            val startXOffset = 50 // 滑块起始位置的偏移

            val bounds = captchaInfo.bounds
            val startX = bounds.left + startXOffset
            val endX = startX + targetOffsetX
            val y = bounds.centerY()

            Log.d(TAG, "滑动验证码：从 ($startX, $y) 滑动到 ($endX, $y)")
            Log.d(TAG, "注意：需要用户集成 dispatchGesture 实现实际滑动")

            // TODO: 用户需要在此处实现 dispatchGesture 手势滑动
            // 简化处理：等待时间，模拟滑动过程
            delay(HumanBehaviorSimulator.generateRandomDelay(1000, 2000))

            return true
        } catch (e: Exception) {
            Log.e(TAG, "处理滑动验证码异常", e)
            return false
        }
    }

    /**
     * 处理点选验证码
     * **注意：此方法需要 AccessibilityService.dispatchGesture 实现**
     */
    private suspend fun handleClickCaptcha(
        service: AccessibilityService,
        captchaInfo: CaptchaInfo
    ): Boolean {
        try {
            // 截取验证码图片
            val captchaBitmap = captureNodeImage(service, captchaInfo.imageNode)
            if (captchaBitmap == null) {
                Log.e(TAG, "无法截取点选验证码图片")
                return false
            }

            // 识别点选验证码（需要用户实现图像识别）
            val coordinatesString = recognizeClickCaptcha(captchaBitmap)

            if (!coordinatesString.isNullOrEmpty()) {
                // 解析点击坐标（格式通常为 "x1,y1|x2,y2|..."）
                val coordinates = parseClickCoordinates(coordinatesString)
                val imageBounds = captchaInfo.bounds

                Log.d(TAG, "点选验证码：需要点击 ${coordinates.size} 个位置")

                // 依次点击指定位置
                for (coordinate in coordinates) {
                    // 计算屏幕上的绝对坐标
                    val absoluteX = imageBounds.left + coordinate.first
                    val absoluteY = imageBounds.top + coordinate.second

                    Log.d(TAG, "点击坐标: ($absoluteX, $absoluteY)")

                    // TODO: 用户需要在此处实现 dispatchGesture 点击手势
                    delay(HumanBehaviorSimulator.generateRandomDelay(500, 1000))
                }

                return true
            } else {
                Log.e(TAG, "点选验证码识别失败")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理点选验证码异常", e)
            return false
        }
    }

    /**
     * 输入验证码文本
     */
    private suspend fun inputCaptchaText(inputNode: AccessibilityNodeInfo?, text: String): Boolean {
        if (inputNode == null) return false

        try {
            // 点击输入框
            inputNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            delay(500)

            // 清空现有内容
            inputNode.performAction(AccessibilityNodeInfo.ACTION_SELECT_ALL)
            delay(200)

            // 输入验证码
            val arguments = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

            delay(500)
            Log.d(TAG, "验证码输入完成: $text")

            return true
        } catch (e: Exception) {
            Log.e(TAG, "输入验证码文本异常", e)
            return false
        }
    }

    /**
     * 截取节点图片（简化实现）
     */
    private fun captureNodeImage(service: AccessibilityService, node: AccessibilityNodeInfo?): Bitmap? {
        // TODO: 在实际实现中，这里需要使用屏幕截图API
        // 由于无障碍服务的限制，需要用户手动实现
        Log.w(TAG, "截图功能需要用户实现")
        return null
    }

    /**
     * 获取节点边界
     */
    private fun getBounds(node: AccessibilityNodeInfo): Rect {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return bounds
    }

    /**
     * 解析点击坐标
     */
    private fun parseClickCoordinates(coordinateString: String): List<Pair<Int, Int>> {
        return try {
            coordinateString.split("|").mapNotNull { coord ->
                val parts = coord.split(",")
                if (parts.size == 2) {
                    Pair(parts[0].toInt(), parts[1].toInt())
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析点击坐标失败", e)
            emptyList()
        }
    }

    // ========== 辅助工具方法 ==========

    /**
     * 根据类名查找节点
     */
    private fun findNodesByClassName(rootNode: AccessibilityNodeInfo, className: String): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        traverseNodeForClassName(rootNode, className, result)
        return result
    }

    /**
     * 递归遍历查找指定类名的节点
     */
    private fun traverseNodeForClassName(
        node: AccessibilityNodeInfo,
        targetClassName: String,
        result: MutableList<AccessibilityNodeInfo>
    ) {
        if (node.className?.toString() == targetClassName) {
            result.add(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                traverseNodeForClassName(child, targetClassName, result)
            }
        }
    }

    /**
     * 根据文本查找节点
     */
    private fun findNodeByText(rootNode: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val lowerText = text.lowercase()
        return findNodeByTextRecursive(rootNode, lowerText)
    }

    /**
     * 递归查找包含指定文本的节点
     */
    private fun findNodeByTextRecursive(node: AccessibilityNodeInfo, lowerText: String): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString()?.lowercase()
        if (nodeText != null && nodeText.contains(lowerText)) {
            return node
        }

        val contentDesc = node.contentDescription?.toString()?.lowercase()
        if (contentDesc != null && contentDesc.contains(lowerText)) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val found = findNodeByTextRecursive(child, lowerText)
                if (found != null) return found
            }
        }

        return null
    }

    /**
     * OCR 识别的占位函数，用于指导用户集成图像识别库。
     * @param bitmap 待识别的验证码图片
     * @return 识别结果文本，如果失败返回 null
     */
    private fun recognizeImageWithOCR(bitmap: Bitmap): String? {
        // TODO: 用户需在此处集成 OCR 库（如 ML Kit, Tesseract-OCR 或自定义模型）
        // 1. 图像预处理（去噪、二值化、裁剪）
        // 2. 调用 OCR 引擎进行识别
        Log.w(TAG, "OCR 识别功能未实现，请集成图像识别库")
        return null
    }

    /**
     * 识别点选验证码
     */
    private fun recognizeClickCaptcha(bitmap: Bitmap): String? {
        // TODO: 用户需要实现点选验证码的图像识别
        Log.w(TAG, "点选验证码识别功能未实现")
        return null
    }
}

/**
 * 验证码信息数据类
 */
data class CaptchaInfo(
    val type: CaptchaType,
    val imageNode: AccessibilityNodeInfo?,
    val inputNode: AccessibilityNodeInfo?,
    val bounds: Rect
)

/**
 * 验证码类型枚举
 */
enum class CaptchaType {
    TEXT,   // 文本验证码
    SLIDE,  // 滑动验证码
    CLICK   // 点选验证码
}
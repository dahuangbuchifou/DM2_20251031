package com.damaihelper.utils

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.damaihelper.service.CaptchaRecognitionService
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
        val imageNodes = NodeUtils.findNodesByClassName(rootNode, "android.widget.ImageView")
        
        // 查找验证码输入框
        val inputNodes = CAPTCHA_INPUT_CLASSES.flatMap { className ->
            NodeUtils.findNodesByClassName(rootNode, className)
        }
        
        // 检查是否有验证码相关的文本提示
        val hasKeyword = CAPTCHA_KEYWORDS.any { keyword ->
            NodeUtils.findNodeByText(rootNode, keyword) != null
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
            NodeUtils.findNodeByText(rootNode, keyword) != null
        }
        
        if (hasSlideKeyword) {
            // 查找可能的滑动区域
            val imageNodes = NodeUtils.findNodesByClassName(rootNode, "android.widget.ImageView")
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
            NodeUtils.findNodeByText(rootNode, keyword) != null
        }
        
        if (hasClickKeyword) {
            val imageNodes = NodeUtils.findNodesByClassName(rootNode, "android.widget.ImageView")
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
     * 处理验证码
     */
    suspend fun handleCaptcha(
        service: AccessibilityService,
        captchaInfo: CaptchaInfo,
        recognitionService: CaptchaRecognitionService
    ): Boolean {
        return when (captchaInfo.type) {
            CaptchaType.TEXT -> handleTextCaptcha(service, captchaInfo, recognitionService)
            CaptchaType.SLIDE -> handleSlideCaptcha(service, captchaInfo)
            CaptchaType.CLICK -> handleClickCaptcha(service, captchaInfo, recognitionService)
        }
    }
    
    /**
     * 处理文本验证码
     */
    private suspend fun handleTextCaptcha(
        service: AccessibilityService,
        captchaInfo: CaptchaInfo,
        recognitionService: CaptchaRecognitionService
    ): Boolean {
        try {
            // 截取验证码图片
            val captchaBitmap = captureNodeImage(service, captchaInfo.imageNode)
            if (captchaBitmap == null) {
                Log.e(TAG, "无法截取验证码图片")
                return false
            }
            
            // 识别验证码
            val result = recognitionService.recognizeCaptcha(
                captchaBitmap, 
                CaptchaRecognitionService.TYPE_NORMAL
            )
            
            if (result.success && result.text.isNotEmpty()) {
                // 输入识别结果
                return inputCaptchaText(captchaInfo.inputNode, result.text)
            } else {
                Log.e(TAG, "验证码识别失败: ${result.message}")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理文本验证码异常", e)
            return false
        }
    }
    
    /**
     * 处理滑动验证码
     */
	    /**
	     * 处理滑动验证码
	     * **注意：此方法需要 AccessibilityService.dispatchGesture 实现**
	     */
	    private suspend fun handleSlideCaptcha(
	        service: AccessibilityService,
	        captchaInfo: CaptchaInfo
	    ): Boolean {
	        try {
	            // 1. 识别滑动距离 (需要 CaptchaRecognitionService.recognizeCaptcha 返回滑动距离)
	            // 由于 CaptchaRecognitionService 默认返回文本，这里假设我们已经通过某种方式获取了目标滑块的 X 坐标偏移量
	            val targetOffsetX = 500 // 占位值，实际应由识别服务返回
	            val startXOffset = 50 // 滑块起始位置的偏移
	            val endXOffset = 50 // 目标滑块的右侧边界预留
	            
	            val bounds = captchaInfo.bounds
	            val startX = bounds.left + startXOffset
	            val endX = startX + targetOffsetX 
	            val y = bounds.centerY() + HumanBehaviorSimulator.generateClickOffset(5).second // 模拟Y轴轻微偏移
	            
	            // 2. 使用人类行为模拟器生成贝塞尔曲线滑动路径
	            val pathPoints = HumanBehaviorSimulator.generateBezierPath(
	                startX.toFloat(), y.toFloat(),
	                endX.toFloat(), y.toFloat(),
	                curvature = 0.4f // 增加弯曲度，模拟手抖
	            )
	            
	            // 3. 将路径转换为 GestureDescription
	            // **注意：以下代码是伪代码，用于指导用户集成 dispatchGesture**
	            /*
	            val gestureBuilder = GestureDescription.Builder()
	            val strokeBuilder = GestureDescription.StrokeDescription(
	                Path().apply {
	                    moveTo(pathPoints.first().x, pathPoints.first().y)
	                    pathPoints.drop(1).forEach { point ->
	                        lineTo(point.x, point.y)
	                    }
	                },
	                0, // start time
	                HumanBehaviorSimulator.generateRandomDelay(800, 1500, HumanBehaviorSimulator.DelayType.NORMAL) // duration
	            )
	            gestureBuilder.addStroke(strokeBuilder)
	            
	            // 4. 执行手势
	            service.dispatchGesture(
	                gestureBuilder.build(),
	                null, // callback
	                null // handler
	            )
	            */
	            
	            Log.d(TAG, "已生成贝塞尔曲线滑动路径，等待用户集成 dispatchGesture")
	            
	            // 简化处理：等待时间，模拟滑动过程
	            delay(HumanBehaviorSimulator.generateRandomDelay(1000, 2000, HumanBehaviorSimulator.DelayType.NORMAL))
	            
	            return true
	        } catch (e: Exception) {
	            Log.e(TAG, "处理滑动验证码异常", e)
	            return false
	        }
	    }
    
    /**
     * 处理点选验证码
     */
	    /**
	     * 处理点选验证码
	     * **注意：此方法需要 AccessibilityService.dispatchGesture 实现**
	     */
	    private suspend fun handleClickCaptcha(
	        service: AccessibilityService,
	        captchaInfo: CaptchaInfo,
	        recognitionService: CaptchaRecognitionService
	    ): Boolean {
	        try {
	            // 截取验证码图片
	            val captchaBitmap = captureNodeImage(service, captchaInfo.imageNode)
	            if (captchaBitmap == null) {
	                Log.e(TAG, "无法截取点选验证码图片")
	                return false
	            }
	            
	            // 识别点选验证码
	            val result = recognitionService.recognizeCaptcha(
	                captchaBitmap,
	                CaptchaRecognitionService.TYPE_CLICK
	            )
	            
	            if (result.success && result.text.isNotEmpty()) {
	                // 解析点击坐标（格式通常为 "x1,y1|x2,y2|..."）
	                val coordinates = parseClickCoordinates(result.text)
	                val imageBounds = captchaInfo.bounds
	                
	                // 依次点击指定位置
	                for (coordinate in coordinates) {
	                    // 计算屏幕上的绝对坐标
	                    val absoluteX = imageBounds.left + coordinate.first
	                    val absoluteY = imageBounds.top + coordinate.second
	                    
	                    // 应用人类行为模拟的点击偏移
	                    val offset = HumanBehaviorSimulator.generateClickOffset(5)
	                    val finalX = absoluteX + offset.first
	                    val finalY = absoluteY + offset.second
	                    
	                    // 这里需要实现精确的坐标点击，使用 dispatchGesture
	                    // **注意：以下代码是伪代码，用于指导用户集成 dispatchGesture**
	                    /*
	                    val clickPath = Path().apply {
	                        moveTo(finalX.toFloat(), finalY.toFloat())
	                    }
	                    val gestureBuilder = GestureDescription.Builder()
	                    val clickStroke = GestureDescription.StrokeDescription(
	                        clickPath,
	                        0, // start time
	                        100 // duration (模拟点击的短暂时间)
	                    )
	                    gestureBuilder.addStroke(clickStroke)
	                    service.dispatchGesture(
	                        gestureBuilder.build(),
	                        null, // callback
	                        null // handler
	                    )
	                    */
	                    
	                    Log.d(TAG, "已生成点选坐标: ($finalX, $finalY)，等待用户集成 dispatchGesture")
	                    HumanBehaviorSimulator.simulateHesitation() // 模拟点击后的短暂思考
	                }
	                
	                return true
	            } else {
	                Log.e(TAG, "点选验证码识别失败: ${result.message}")
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
            
            // 模拟用户检查输入的时间
            HumanBehaviorSimulator.delayedAction(HumanBehaviorSimulator.DelayType.QUICK) {
                Log.d(TAG, "验证码输入完成: $text")
            }
            
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
        // 在实际实现中，这里需要使用屏幕截图API
        // 由于无障碍服务的限制，这里返回null，表示需要用户手动处理
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
	 * OCR 识别的占位函数，用于指导用户集成图像识别库。
	 * @param bitmap 待识别的验证码图片
	 * @return 识别结果文本，如果失败返回 null
	 */
	fun recognizeImageWithOCR(bitmap: Bitmap): String? {
	    // TODO: 用户需在此处集成 OCR 库（如 ML Kit, Tesseract-OCR 或自定义模型）
	    // 1. 图像预处理（去噪、二值化、裁剪）
	    // 2. 调用 OCR 引擎进行识别
	    Log.w(TAG, "OCR 识别功能未实现，请集成图像识别库")
	    return null
	}
	
	/**
	 * 验证码类型枚举
	 */
	enum class CaptchaType {
	    TEXT,   // 文本验证码
	    SLIDE,  // 滑动验证码
	    CLICK   // 点选验证码
	}

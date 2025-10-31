package com.damaihelper.utils

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * "人数太多"场景专用处理器
 * 针对大麦网抢票时出现的"人数太多，请稍后再试"场景进行优化处理
 */
object TooManyPeopleHandler {
    
    private const val TAG = "TooManyPeopleHandler"
    
    // 常见的"人数太多"相关文本
    private val TOO_MANY_PEOPLE_KEYWORDS = listOf(
        "人数太多", "请稍后再试", "服务器繁忙", "网络拥堵",
        "排队中", "请耐心等待", "系统繁忙", "稍后重试",
        "too many", "busy", "queue", "wait"
    )
    
    // 常见的重试按钮文本
    private val RETRY_BUTTON_KEYWORDS = listOf(
        "重试", "继续", "再试一次", "刷新", "重新加载",
        "retry", "continue", "refresh", "reload", "try again"
    )
    
    // 高频点击配置
    data class HighFrequencyConfig(
        val maxAttempts: Int = 150,           // 最大尝试次数
        val baseIntervalMs: Long = 60L,       // 基础点击间隔（毫秒）
        val intervalVariation: Long = 30L,    // 间隔变化范围
        val burstSize: Int = 5,               // 突发点击次数
        val burstIntervalMs: Long = 20L,      // 突发点击间隔
        val restIntervalMs: Long = 200L,      // 突发后休息时间
        val progressCheckInterval: Int = 10   // 每N次点击检查一次进度
    )
    
    /**
     * 检测是否出现"人数太多"场景
     */
    fun detectTooManyPeopleScenario(rootNode: AccessibilityNodeInfo): TooManyPeopleInfo? {
        // 检查是否存在相关关键词
        val foundKeywords = mutableListOf<String>()
        var retryButton: AccessibilityNodeInfo? = null
        
        // 遍历所有文本节点查找关键词
        val allTextNodes = NodeUtils.getAllTextNodes(rootNode)
        
        for (textNode in allTextNodes) {
            val text = textNode.text?.toString()?.lowercase() ?: continue
            
            // 检查"人数太多"关键词
            for (keyword in TOO_MANY_PEOPLE_KEYWORDS) {
                if (text.contains(keyword.lowercase())) {
                    foundKeywords.add(keyword)
                    break
                }
            }
            
            // 检查重试按钮
            if (retryButton == null) {
                for (buttonKeyword in RETRY_BUTTON_KEYWORDS) {
                    if (text.contains(buttonKeyword.lowercase()) && textNode.isClickable) {
                        retryButton = textNode
                        break
                    }
                }
            }
        }
        
        // 如果没有找到重试按钮，尝试查找其他可点击元素
        if (retryButton == null && foundKeywords.isNotEmpty()) {
            retryButton = findAlternativeRetryButton(rootNode)
        }
        
        return if (foundKeywords.isNotEmpty()) {
            TooManyPeopleInfo(
                detected = true,
                keywords = foundKeywords,
                retryButton = retryButton,
                severity = calculateSeverity(foundKeywords)
            )
        } else {
            null
        }
    }
    
    /**
     * 查找备用的重试按钮
     */
    private fun findAlternativeRetryButton(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 查找所有可点击的按钮
        val clickableNodes = NodeUtils.findAllClickableNodes(rootNode)
        
        // 优先选择包含特定类名的按钮
        val buttonClassNames = listOf("Button", "ImageButton", "TextView")
        
        for (className in buttonClassNames) {
            val button = clickableNodes.find { 
                it.className?.toString()?.contains(className) == true 
            }
            if (button != null) return button
        }
        
        // 如果没找到特定类型的按钮，返回第一个可点击元素
        return clickableNodes.firstOrNull()
    }
    
    /**
     * 计算场景严重程度
     */
    private fun calculateSeverity(keywords: List<String>): TooManyPeopleSeverity {
        return when {
            keywords.any { it.contains("服务器") || it.contains("系统") } -> 
                TooManyPeopleSeverity.SEVERE
            keywords.any { it.contains("排队") || it.contains("等待") } -> 
                TooManyPeopleSeverity.MODERATE
            else -> TooManyPeopleSeverity.MILD
        }
    }
    
    /**
     * 执行高频轮询点击处理
     */
    suspend fun executeHighFrequencyClicking(
        service: AccessibilityService,
        info: TooManyPeopleInfo,
        config: HighFrequencyConfig = HighFrequencyConfig()
    ): ClickingResult {
        Log.d(TAG, "开始高频轮询点击处理，严重程度: ${info.severity}")
        
        val startTime = System.currentTimeMillis()
        var totalClicks = 0
        var successfulClicks = 0
        var lastProgressCheck = System.currentTimeMillis()
        
        // 根据严重程度调整配置
        val adjustedConfig = adjustConfigBySeverity(config, info.severity)
        
        repeat(adjustedConfig.maxAttempts) { attempt ->
            try {
                // 检查是否还在"人数太多"页面
                if (attempt % adjustedConfig.progressCheckInterval == 0) {
                    val currentRootNode = service.rootInActiveWindow
                    if (currentRootNode != null) {
                        val stillInTooManyPeople = detectTooManyPeopleScenario(currentRootNode) != null
                        currentRootNode.recycle()
                        
                        if (!stillInTooManyPeople) {
                            Log.d(TAG, "成功跳出人数太多页面，总点击次数: $totalClicks")
                            return ClickingResult(
                                success = true,
                                totalClicks = totalClicks,
                                successfulClicks = successfulClicks,
                                duration = System.currentTimeMillis() - startTime,
                                exitReason = "页面跳转成功"
                            )
                        }
                        
                        // 记录进度
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastProgressCheck > 5000) { // 每5秒记录一次
                            Log.d(TAG, "高频点击进行中... 已点击${totalClicks}次，用时${(currentTime - startTime) / 1000}秒")
                            lastProgressCheck = currentTime
                        }
                    }
                }
                
                // 执行点击操作
                val clickSuccess = performSmartClick(service, info, attempt)
                totalClicks++
                if (clickSuccess) successfulClicks++
                
                // 智能延迟策略
                val delay = calculateSmartDelay(adjustedConfig, attempt, info.severity)
                delay(delay)
                
            } catch (e: Exception) {
                Log.e(TAG, "高频点击过程中出现异常", e)
            }
        }
        
        Log.w(TAG, "高频点击达到最大次数限制，总点击: $totalClicks, 成功: $successfulClicks")
        return ClickingResult(
            success = false,
            totalClicks = totalClicks,
            successfulClicks = successfulClicks,
            duration = System.currentTimeMillis() - startTime,
            exitReason = "达到最大尝试次数"
        )
    }
    
    /**
     * 根据严重程度调整配置
     */
    private fun adjustConfigBySeverity(
        config: HighFrequencyConfig,
        severity: TooManyPeopleSeverity
    ): HighFrequencyConfig {
        return when (severity) {
            TooManyPeopleSeverity.MILD -> config.copy(
                baseIntervalMs = config.baseIntervalMs + 20L,
                maxAttempts = config.maxAttempts - 30
            )
            TooManyPeopleSeverity.MODERATE -> config
            TooManyPeopleSeverity.SEVERE -> config.copy(
                baseIntervalMs = config.baseIntervalMs - 10L,
                maxAttempts = config.maxAttempts + 50,
                burstSize = config.burstSize + 2
            )
        }
    }
    
    /**
     * 执行智能点击
     */
    private suspend fun performSmartClick(
        service: AccessibilityService,
        info: TooManyPeopleInfo,
        attempt: Int
    ): Boolean {
        val rootNode = service.rootInActiveWindow ?: return false
        
        try {
            // 重新查找重试按钮（页面可能已更新）
            val currentInfo = detectTooManyPeopleScenario(rootNode)
            val targetButton = currentInfo?.retryButton ?: info.retryButton
            
            if (targetButton != null && targetButton.isClickable) {
                // 使用人类行为模拟器进行点击，但减少延迟
                val success = targetButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                
                if (success) {
                    Log.v(TAG, "第${attempt + 1}次点击成功")
                } else {
                    Log.v(TAG, "第${attempt + 1}次点击失败")
                }
                
                return success
            } else {
                // 如果找不到特定按钮，尝试点击屏幕中央
                Log.v(TAG, "未找到重试按钮，尝试点击屏幕中央")
                return performCenterClick(service)
            }
        } finally {
            rootNode.recycle()
        }
    }
    
    /**
     * 点击屏幕中央
     */
    private fun performCenterClick(service: AccessibilityService): Boolean {
        // 这里需要使用手势API，但在无障碍服务中实现较复杂
        // 简化处理：尝试点击根节点
        val rootNode = service.rootInActiveWindow
        return if (rootNode != null) {
            val success = rootNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            rootNode.recycle()
            success
        } else {
            false
        }
    }
    
    /**
     * 计算智能延迟
     */
    private fun calculateSmartDelay(
        config: HighFrequencyConfig,
        attempt: Int,
        severity: TooManyPeopleSeverity
    ): Long {
        // 基础延迟
        var delay = config.baseIntervalMs
        
        // 添加随机变化
        val variation = Random.nextLong(-config.intervalVariation, config.intervalVariation)
        delay += variation
        
        // 突发模式：每隔一段时间进行快速连击
        val burstCycle = config.burstSize + 5
        if (attempt % burstCycle < config.burstSize) {
            delay = config.burstIntervalMs
        } else if (attempt % burstCycle == config.burstSize) {
            delay = config.restIntervalMs
        }
        
        // 根据严重程度微调
        when (severity) {
            TooManyPeopleSeverity.SEVERE -> delay = (delay * 0.8).toLong()
            TooManyPeopleSeverity.MILD -> delay = (delay * 1.2).toLong()
            else -> {} // 保持不变
        }
        
        // 确保延迟在合理范围内
        return delay.coerceIn(10L, 500L)
    }
}

/**
 * "人数太多"场景信息
 */
data class TooManyPeopleInfo(
    val detected: Boolean,
    val keywords: List<String>,
    val retryButton: AccessibilityNodeInfo?,
    val severity: TooManyPeopleSeverity
)

/**
 * "人数太多"场景严重程度
 */
enum class TooManyPeopleSeverity {
    MILD,       // 轻微：可能很快恢复
    MODERATE,   // 中等：需要持续重试
    SEVERE      // 严重：服务器压力大，需要高频重试
}

/**
 * 高频点击结果
 */
data class ClickingResult(
    val success: Boolean,
    val totalClicks: Int,
    val successfulClicks: Int,
    val duration: Long,
    val exitReason: String
)

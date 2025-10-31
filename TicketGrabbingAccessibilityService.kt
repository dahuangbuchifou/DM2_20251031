package com.damaihelper.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.damaihelper.model.TaskStatus
import com.damaihelper.model.TicketTask
import com.damaihelper.network.TicketGrabbingNetworkManager
import com.damaihelper.network.TicketRequest
import com.damaihelper.utils.*
import kotlinx.coroutines.*

class TicketGrabbingAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TicketGrabbingService"
        private const val DAMAI_PACKAGE = "cn.damai"

        // 大麦App中的关键UI元素文本
        private const val SEARCH_HINT = "搜索演出、场馆"
        private const val BUY_NOW_TEXT = "立即购买"
        private const val SUBMIT_ORDER_TEXT = "提交订单"
        private const val SELECT_SESSION_TEXT = "选择场次"
        private const val SELECT_PRICE_TEXT = "选择票价"
    }

    private var currentTask: TicketTask? = null
    private var serviceJob: Job? = null
    private val captchaRecognitionService = CaptchaRecognitionService()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var networkManager: TicketGrabbingNetworkManager

    private val grabbingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.damaihelper.START_GRABBING") {
                val task = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra("task", TicketTask::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra("task")
                }
                if (task != null) {
                    Log.d(TAG, "收到前台服务的抢票指令")
                    startGrabbingFromForegroundService(task)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "无障碍服务已连接")

        // 尝试初始化 Hook 框架（长期攻坚方向）
        initializeHookFramework()

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            packageNames = arrayOf(DAMAI_PACKAGE)
        }
        serviceInfo = info

        // 初始化网络管理器
        networkManager = TicketGrabbingNetworkManager(this)
        serviceScope.launch {
            networkManager.initialize()
        }

        val filter = IntentFilter("com.damaihelper.START_GRABBING")
        registerReceiver(grabbingReceiver, filter, RECEIVER_EXPORTED) // ⚠️ 修复点：添加 RECEIVER_EXPORTED 避免 API 33+ 崩溃
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "无障碍服务销毁")
        serviceJob?.cancel()
        serviceScope.cancel()
        try {
            unregisterReceiver(grabbingReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "注销广播接收器失败", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            serviceScope.launch {
                val rootNode = rootInActiveWindow
                if (rootNode != null) {
                    val info = TooManyPeopleHandler.detectTooManyPeopleScenario(rootNode)
                    if (info != null && info.detected) {
                        TooManyPeopleHandler.executeHighFrequencyClicking(this@TicketGrabbingAccessibilityService, info)
                    } else {
                        // 检测其他通用弹窗，如“知道了”、“确定”等
                        handleGenericDialog(rootNode)
                    }
                    rootNode.recycle()
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "无障碍服务被中断")
        serviceJob?.cancel()
    }

    /**
     * 处理通用弹窗，例如包含“确定”、“知道了”等按钮的弹窗。
     */
    private fun handleGenericDialog(rootNode: AccessibilityNodeInfo) {
        val positiveButtonKeywords = listOf("确定", "知道了", "允许", "同意", "OK", "Confirm", "Allow", "Agree")
        // val negativeButtonKeywords = listOf("取消", "以后再说", "拒绝", "Cancel", "Deny") // 移除未使用的变量

        for (keyword in positiveButtonKeywords) {
            val button = NodeUtils.findNodeByText(rootNode, keyword, clickable = true)
            if (button != null) {
                Log.d(TAG, "检测到通用弹窗，点击正面按钮: $keyword")
                button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                button.recycle()
                return // 通常处理一个弹窗后即可返回
            }
        }
    }

    private fun startGrabbingFromForegroundService(task: TicketTask) {
        currentTask = task
        serviceJob = serviceScope.launch {
            try {
                executeGrabbingProcess(task)
            } catch (e: CancellationException) {
                Log.w(TAG, "抢票任务被取消: ${e.message}")
                updateTaskStatus(task, TaskStatus.CANCELED, "抢票任务被取消")
            } catch (e: IllegalStateException) {
                Log.e(TAG, "抢票过程状态异常: ${e.message}", e)
                updateTaskStatus(task, TaskStatus.FAILED, "抢票状态异常: ${e.message}")
            } catch (e: SecurityException) {
                Log.e(TAG, "无障碍服务权限不足或被禁用: ${e.message}", e)
                updateTaskStatus(task, TaskStatus.FAILED, "权限不足: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "抢票过程发生未知错误: ${e.message}", e)
                updateTaskStatus(task, TaskStatus.FAILED, "未知错误: ${e.message}")
            }
        }
    }

    fun startGrabbing(task: TicketTask) {
        Log.d(TAG, "启动抢票任务: ${task.concertName}")
        TicketGrabbingForegroundService.startGrabbingService(this, task)
    }

    /**
     * 深度API逆向与Hook占位逻辑
     * 指导用户如何集成 Xposed/Frida 等框架进行深度 Hook
     */
    private fun initializeHookFramework() {
        // TODO: 用户需在此处集成 Hook 框架的初始化逻辑
        Log.w(TAG, "Hook 框架初始化占位：深度API逆向与Hook功能未实现")
    }

    private suspend fun executeGrabbingProcess(task: TicketTask) {
        updateTaskStatus(task, TaskStatus.RUNNING, "开始抢票")

        Log.d(TAG, "检查时间同步状态...")
        val syncInfo = PreciseTimeManager.checkSyncStatus()
        if (!syncInfo.isAccurate) {
            Log.w(TAG, "时间同步不够精确，尝试强制同步...")
            PreciseTimeManager.forceSyncTime()
        }

        if (!openDamaiApp()) {
            updateTaskStatus(task, TaskStatus.FAILED, "无法打开大麦App")
            return
        }

        if (!retryUiOperation(maxRetries = 3, operationName = "搜索演出") { searchConcert(task.concertName) }) {
            updateTaskStatus(task, TaskStatus.FAILED, "搜索演出失败")
            return
        }

        if (!retryUiOperation(maxRetries = 3, operationName = "进入演出详情页") { enterConcertDetail() }) {
            updateTaskStatus(task, TaskStatus.FAILED, "进入演出详情页失败")
            return
        }

        Log.d(TAG, "预热网络连接...")
        networkManager.warmupConnections()

        val grabTime = task.grabTime
        Log.d(TAG, "精确等待开抢时间...")
        updateTaskStatus(task, TaskStatus.WAITING, "等待开抢...")
        PreciseTimeManager.waitUntilPreciseTime(grabTime, leadTime = 150L)

        Log.d(TAG, "开始抢票操作")
        updateTaskStatus(task, TaskStatus.GRABBING, "正在抢票...")

        // 检测并处理验证码
        if (!handleCaptchaIfExists()) {
            updateTaskStatus(task, TaskStatus.FAILED, "验证码处理失败")
            return
        }

        // 尝试通过API抢票（如果可能）
        val ticketRequest = TicketRequest(itemId = task.itemId, sessionId = task.sessionId, priceId = task.priceId, quantity = task.quantity)
        val apiResult = networkManager.executeConcurrentTicketGrabbing(ticketRequest)

        if (apiResult.success) {
            updateTaskStatus(task, TaskStatus.SUCCESS, "API抢票成功: ${apiResult.message}")
            return
        }
        Log.w(TAG, "API抢票失败或未启用，转为模拟点击抢票")

        // 点击立即购买 (包含重试和人数太多处理)
        if (!clickBuyNowWithRetry()) {
            updateTaskStatus(task, TaskStatus.FAILED, "点击购买失败")
            return
        }

        // 选择场次和票价
        delay(1000)
        if (!retryUiOperation(maxRetries = 3, operationName = "选择场次和票价") { selectSessionAndPrice(task) }) {
            updateTaskStatus(task, TaskStatus.FAILED, "选择场次和票价失败")
            return
        }

        // 提交订单
        if (retryUiOperation(maxRetries = 3, operationName = "提交订单") { submitOrder() }) {
            updateTaskStatus(task, TaskStatus.SUCCESS, "抢票成功，请手动完成支付")
        } else {
            updateTaskStatus(task, TaskStatus.FAILED, "提交订单失败")
        }
    }

    /**
     * 带重试的点击购买按钮
     */
    private suspend fun clickBuyNowWithRetry(maxRetries: Int = 10): Boolean {
        repeat(maxRetries) {
            if (clickBuyNow()) return true
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                val info = TooManyPeopleHandler.detectTooManyPeopleScenario(rootNode)
                if (info != null && info.detected) {
                    Log.d(TAG, "检测到人数太多，启动高频点击策略")
                    val clickResult = TooManyPeopleHandler.executeHighFrequencyClicking(this, info)
                    rootNode.recycle()
                    if (clickResult.success) {
                        Log.d(TAG, "高频点击成功跳出，继续流程")
                        return true // 假设跳出后就是选票页面
                    }
                } else {
                    rootNode.recycle()
                }
            }
            HumanBehaviorSimulator.delayedAction(DelayType.QUICK) {}
        }
        return false
    }

    private fun openDamaiApp(): Boolean {
        val launchIntent: Intent? = packageManager.getLaunchIntentForPackage(DAMAI_PACKAGE)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
            HumanBehaviorSimulator.delayedAction(DelayType.LONG) { /* 等待App启动 */ }
            return true
        }
        Log.e(TAG, "无法找到大麦App")
        return false
    }

    /**
     * 统一的UI操作重试机制
     */
    private suspend fun retryUiOperation(
        maxRetries: Int,
        operationName: String,
        operation: suspend () -> Boolean
    ): Boolean {
        repeat(maxRetries) { attempt ->
            Log.d(TAG, "$operationName - 尝试 $attempt/${maxRetries}")
            if (operation()) {
                Log.d(TAG, "$operationName - 成功")
                return true
            }
            // 模拟人类重试前的短暂犹豫
            HumanBehaviorSimulator.delayedAction(DelayType.SHORT) {}
        }
        Log.e(TAG, "$operationName - 失败，达到最大重试次数")
        return false
    }

    private suspend fun searchConcert(concertName: String): Boolean {
        return withContext(Dispatchers.Main) {
            val rootNode = rootInActiveWindow ?: return@withContext false

            HumanBehaviorSimulator.simulateThinking()

            val searchBox = NodeUtils.findNodeByText(rootNode, SEARCH_HINT)
            if (searchBox != null) {
                HumanBehaviorSimulator.simulateClick(searchBox)
                HumanBehaviorSimulator.delayedAction(DelayType.TYPING) {
                    searchBox.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                        android.os.Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, concertName)
                        })
                }
                searchBox.recycle()
                rootNode.recycle()
                return@withContext true
            }
            // 尝试查找搜索按钮并点击
            val searchButton = NodeUtils.findClickableNodeByText(rootNode, "搜索")
            if (searchButton != null) {
                HumanBehaviorSimulator.simulateClick(searchButton)
                searchButton.recycle()
                rootNode.recycle()
                return@withContext true
            }

            // 尝试查找搜索图标并点击 (需要图像识别或更复杂的定位)

            rootNode.recycle()
            return@withContext false
        }
    } // 修复点 1：添加缺失的右花括号 '}'

    /**
     * 进入演出详情页的UI操作
     */
    private suspend fun enterConcertDetail(): Boolean { // 修复点 2：将粘连的代码块分离并命名
        return withContext(Dispatchers.Main) {
            val rootNode = rootInActiveWindow ?: return@withContext false

            HumanBehaviorSimulator.simulateBrowsing()

            // 查找第一个搜索结果并点击
            val firstResult = NodeUtils.findFirstClickableNode(rootNode)
            if (firstResult != null) {
                HumanBehaviorSimulator.simulateClick(firstResult)
                firstResult.recycle()
                rootNode.recycle()
                return@withContext true
            }

            rootNode.recycle()
            return@withContext false
        }
    }

    /**
     * 检测并处理验证码
     */
    private suspend fun handleCaptchaIfExists(): Boolean {
        return withContext(Dispatchers.Main) {
            val rootNode = rootInActiveWindow ?: return@withContext true

            val captchaInfo = CaptchaHandler.detectCaptcha(rootNode)

            if (captchaInfo != null) {
                Log.d(TAG, "检测到验证码，类型: ${captchaInfo.type}")

                val success = CaptchaHandler.handleCaptcha(
                    this@TicketGrabbingAccessibilityService,
                    captchaInfo,
                    captchaRecognitionService
                )

                if (success) {
                    Log.d(TAG, "验证码处理成功")
                    delay(2000)
                } else {
                    Log.e(TAG, "验证码处理失败，需要用户手动处理")
                    return@withContext false
                }
            }

            rootNode.recycle()
            return@withContext true
        }
    }

    private suspend fun clickBuyNow(): Boolean {
        return withContext(Dispatchers.Main) {
            val rootNode = rootInActiveWindow ?: return@withContext false

            HumanBehaviorSimulator.simulateInformationCheck()

            val buyButton = NodeUtils.findNodeByText(rootNode, BUY_NOW_TEXT)
            if (buyButton != null) {
                HumanBehaviorSimulator.simulateHesitation()

                HumanBehaviorSimulator.simulateClick(buyButton)
                buyButton.recycle()
                rootNode.recycle()
                return@withContext true
            }

            rootNode.recycle()
            return@withContext false
        }
    }

    private suspend fun selectSessionAndPrice(task: TicketTask) {
        withContext(Dispatchers.Main) {
            val rootNode = rootInActiveWindow ?: return@withContext

            HumanBehaviorSimulator.simulateDecisionMaking()

            // 修复点 3：使用 findNodeByTextAndClick 函数简化代码
            NodeUtils.findNodeByTextAndClick(rootNode, task.sessionPreference ?: SELECT_SESSION_TEXT)
            HumanBehaviorSimulator.delayedAction(DelayType.MEDIUM) {}
            NodeUtils.findNodeByTextAndClick(rootNode, task.pricePreference ?: SELECT_PRICE_TEXT)

            // 旧代码：
            /*
            val sessionButton = NodeUtils.findNodeByText(rootNode, task.sessionPreference ?: SELECT_SESSION_TEXT)
            sessionButton?.let {
                HumanBehaviorSimulator.simulateClick(it)
                it.recycle()
            }
            HumanBehaviorSimulator.delayedAction(DelayType.MEDIUM) {}
            val priceButton = NodeUtils.findNodeByText(rootNode, task.pricePreference ?: SELECT_PRICE_TEXT)
            priceButton?.let {
                HumanBehaviorSimulator.simulateClick(it)
                it.recycle()
            }
            */

            rootNode.recycle()
        }
    }

    private suspend fun submitOrder(): Boolean {
        return withContext(Dispatchers.Main) {
            val rootNode = rootInActiveWindow ?: return@withContext false

            if (!handleCaptchaIfExists()) {
                return@withContext false
            }

            HumanBehaviorSimulator.simulateInformationCheck()

            val submitButton = NodeUtils.findNodeByText(rootNode, SUBMIT_ORDER_TEXT)
            if (submitButton != null) {
                HumanBehaviorSimulator.simulateHesitation()

                submitButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                submitButton.recycle()
                rootNode.recycle()
                return@withContext true
            }

            rootNode.recycle()
            return@withContext false
        }
    }

    private fun updateTaskStatus(task: TicketTask, status: TaskStatus, message: String) {
        Log.d(TAG, "任务状态更新: ${task.concertName} - $status - $message")
        val intent = Intent("com.damaihelper.TASK_STATUS_UPDATE").apply {
            putExtra("task_id", task.id)
            putExtra("status", status.name)
            putExtra("message", message)
        }
        sendBroadcast(intent)
    }
}
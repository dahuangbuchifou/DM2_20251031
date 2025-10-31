package com.damaihelper.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.damaihelper.model.TicketTask
import com.damaihelper.utils.PreciseTimeManager
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * 抢票前台服务
 * 确保抢票任务在后台稳定运行，不被系统杀死
 */
class TicketGrabbingForegroundService : Service() {

    companion object {
        private const val TAG = "TicketForegroundService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "ticket_grabbing_channel"
        private const val WAKELOCK_TAG = "DamaiHelper:TicketGrabbing"

        const val ACTION_START_GRABBING = "start_grabbing"
        const val ACTION_STOP_GRABBING = "stop_grabbing"
        const val EXTRA_TASK = "task"

        /**
         * 启动抢票服务
         */
        fun startGrabbingService(context: Context, task: TicketTask) {
            val intent = Intent(context, TicketGrabbingForegroundService::class.java).apply {
                action = ACTION_START_GRABBING
                putExtra(EXTRA_TASK, task)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 停止抢票服务
         */
        fun stopGrabbingService(context: Context) {
            val intent = Intent(context, TicketGrabbingForegroundService::class.java).apply {
                action = ACTION_STOP_GRABBING
            }
            context.startService(intent)
        }
    }

    private var currentTask: TicketTask? = null
    private var serviceJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "前台服务创建")
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_GRABBING -> {
                val task = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_TASK, TicketTask::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_TASK)
                }

                if (task != null) {
                    startGrabbingTask(task)
                } else {
                    Log.e(TAG, "未收到有效的抢票任务")
                    stopSelf()
                }
            }
            ACTION_STOP_GRABBING -> {
                stopGrabbingTask()
                stopSelf()
            }
        }

        return START_STICKY // 服务被杀死后自动重启
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "前台服务销毁")
        stopGrabbingTask()
        releaseWakeLock()
        serviceScope.cancel()
    }

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "抢票服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "大麦抢票助手后台服务"
                setShowBadge(false)
                setSound(null, null)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 获取唤醒锁，防止设备休眠
     */
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKELOCK_TAG
            ).apply {
                acquire(10 * 60 * 1000L) // 10分钟超时
            }
            Log.d(TAG, "获取唤醒锁成功")
        } catch (e: Exception) {
            Log.e(TAG, "获取唤醒锁失败", e)
        }
    }

    /**
     * 释放唤醒锁
     */
    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "释放唤醒锁成功")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "释放唤醒锁失败", e)
        }
    }

    /**
     * 开始抢票任务
     */
    private fun startGrabbingTask(task: TicketTask) {
        currentTask = task
        Log.d(TAG, "开始抢票任务: ${task.concertName}")

        // 启动前台服务
        val notification = createNotification(task, "准备中...")
        startForeground(NOTIFICATION_ID, notification)

        // 启动抢票协程
        serviceJob = serviceScope.launch {
            try {
                executeGrabbingTask(task)
            } catch (e: Exception) {
                Log.e(TAG, "抢票任务执行异常", e)
                updateNotification(task, "任务异常: ${e.message}")
            }
        }
    }

    /**
     * 停止抢票任务
     */
    private fun stopGrabbingTask() {
        serviceJob?.cancel()
        serviceJob = null
        currentTask = null
        Log.d(TAG, "抢票任务已停止")
    }

    /**
     * 执行抢票任务
     */
    private suspend fun executeGrabbingTask(task: TicketTask) {
        // 第一步：时间同步
        updateNotification(task, "正在同步网络时间...")
        val timeSyncStatus = PreciseTimeManager.checkSyncStatus()

        if (!timeSyncStatus.success) {
            updateNotification(task, "时间同步失败，使用本地时间")
            Log.w(TAG, "时间同步失败: ${timeSyncStatus.message}")
        } else {
            updateNotification(task, "时间同步成功，偏差: ${timeSyncStatus.offset}ms")
            Log.d(TAG, "时间同步成功: ${timeSyncStatus.message}")
        }

        // 第二步：等待开抢时间
        val grabTime = task.grabTime
        val waitTime = PreciseTimeManager.calculatePreciseDelay(grabTime)

        if (waitTime > 0) {
            updateNotification(task, "等待开抢，剩余: ${formatWaitTime(waitTime)}")
            Log.d(TAG, "等待开抢，剩余时间: ${waitTime}ms")

            // 使用精确时间管理器等待
            PreciseTimeManager.waitUntilPreciseTime(grabTime, leadTime = 150L)
        }

        // 第三步：开始抢票
        updateNotification(task, "开始抢票...")
        Log.d(TAG, "开始执行抢票操作")

        // 这里需要与无障碍服务通信，触发实际的抢票操作
        val intent = Intent("com.damaihelper.START_GRABBING").apply {
            putExtra("task", task)
        }
        sendBroadcast(intent)

        // 监控抢票进度（这里简化处理，实际应该监听无障碍服务的状态反馈）
        var elapsedTime = 0L
        val maxWaitTime = 5 * 60 * 1000L // 最多等待5分钟

        while (elapsedTime < maxWaitTime) {
            delay(1000)
            elapsedTime += 1000
            updateNotification(task, "抢票中... (${elapsedTime / 1000}s)")
        }

        // 任务完成
        updateNotification(task, "抢票任务完成")
        Log.d(TAG, "抢票任务执行完成")

        // 延迟停止服务
        delay(5000)
        stopSelf()
    }

    /**
     * 创建通知
     */
    private fun createNotification(task: TicketTask, status: String): Notification {
        // 修复：尝试安全地获取 MainActivity 类
        val intent = try {
            Intent(this, Class.forName("com.damaihelper.MainActivity"))
        } catch (e: ClassNotFoundException) {
            // 如果找不到 MainActivity，创建一个空的 Intent
            Log.w(TAG, "MainActivity 未找到，使用默认 Intent")
            Intent()
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("大麦抢票助手")
            .setContentText("${task.concertName} - $status")
            .setSmallIcon(android.R.drawable.ic_dialog_info) // 修复：使用系统默认图标
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /**
     * 更新通知
     */
    private fun updateNotification(task: TicketTask, status: String) {
        val notification = createNotification(task, status)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 格式化等待时间
     */
    private fun formatWaitTime(milliseconds: Long): String {
        val seconds = milliseconds / 1000
        val minutes = seconds / 60
        val hours = minutes / 60

        return when {
            hours > 0 -> "${hours}时${minutes % 60}分${seconds % 60}秒"
            minutes > 0 -> "${minutes}分${seconds % 60}秒"
            else -> "${seconds}秒"
        }
    }
}
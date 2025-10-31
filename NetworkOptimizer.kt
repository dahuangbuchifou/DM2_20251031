package com.damaihelper.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

/**
 * 网络优化器
 * 处理网络延迟、并发请求和重试机制
 */
object NetworkOptimizer {
    
    private const val TAG = "NetworkOptimizer"
    
    // 大麦网相关的域名和IP
    private val DAMAI_DOMAINS = listOf(
        "m.damai.cn",
        "damai.cn",
        "mtop.damai.cn",
        "acs.m.damai.cn"
    )
    
    // 网络质量评估结果
    data class NetworkQuality(
        val latency: Long,          // 延迟（毫秒）
        val isWifi: Boolean,        // 是否为WiFi
        val downloadSpeed: Long,    // 下载速度（KB/s）
        val quality: Quality        // 网络质量等级
    ) {
        enum class Quality {
            EXCELLENT, GOOD, FAIR, POOR
        }
    }
    
    // 重试配置
    data class RetryConfig(
        val maxRetries: Int = 5,
        val baseDelayMs: Long = 100L,
        val maxDelayMs: Long = 5000L,
        val backoffMultiplier: Double = 2.0,
        val jitterFactor: Double = 0.1
    )
    
    // 并发请求结果
    data class ConcurrentResult<T>(
        val success: Boolean,
        val result: T?,
        val latency: Long,
        val error: Throwable?
    )
    
    /**
     * 评估当前网络质量
     */
    suspend fun assessNetworkQuality(context: Context): NetworkQuality {
        return withContext(Dispatchers.IO) {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            
            val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            
            // 测试延迟
            val latency = measureLatency()
            
            // 估算下载速度（简化实现）
            val downloadSpeed = estimateDownloadSpeed(latency, isWifi)
            
            // 评估网络质量
            val quality = when {
                latency < 50 && isWifi -> NetworkQuality.Quality.EXCELLENT
                latency < 100 -> NetworkQuality.Quality.GOOD
                latency < 300 -> NetworkQuality.Quality.FAIR
                else -> NetworkQuality.Quality.POOR
            }
            
            NetworkQuality(latency, isWifi, downloadSpeed, quality)
        }
    }
    
    /**
     * 测量网络延迟
     */
    private suspend fun measureLatency(): Long {
        return withContext(Dispatchers.IO) {
            val results = mutableListOf<Long>()
            
            // 对多个域名进行ping测试
            for (domain in DAMAI_DOMAINS.take(3)) {
                try {
                    val startTime = System.currentTimeMillis()
                    InetAddress.getByName(domain)
                    val endTime = System.currentTimeMillis()
                    results.add(endTime - startTime)
                } catch (e: UnknownHostException) {
                    Log.w(TAG, "无法解析域名: $domain")
                }
            }
            
            // 返回平均延迟，如果没有成功的测试则返回默认值
            if (results.isNotEmpty()) {
                results.average().toLong()
            } else {
                500L // 默认延迟
            }
        }
    }
    
    /**
     * 估算下载速度
     */
    private fun estimateDownloadSpeed(latency: Long, isWifi: Boolean): Long {
        return when {
            isWifi && latency < 50 -> 5000L  // 5MB/s
            isWifi && latency < 100 -> 3000L // 3MB/s
            isWifi -> 1000L                  // 1MB/s
            latency < 100 -> 1500L           // 4G网络
            latency < 300 -> 800L            // 3G网络
            else -> 200L                     // 2G网络
        }
    }
    
    /**
     * 并发执行多个网络请求
     */
    suspend fun <T> executeConcurrentRequests(
        requests: List<suspend () -> T>,
        timeoutMs: Long = 5000L
    ): List<ConcurrentResult<T>> {
        return withContext(Dispatchers.IO) {
            val results = mutableListOf<ConcurrentResult<T>>()
            
            // 使用协程并发执行所有请求
            val jobs = requests.map { request ->
                async {
                    val startTime = System.currentTimeMillis()
                    try {
                        withTimeout(timeoutMs) {
                            val result = request()
                            val latency = System.currentTimeMillis() - startTime
                            ConcurrentResult(true, result, latency, null)
                        }
                    } catch (e: Exception) {
                        val latency = System.currentTimeMillis() - startTime
                        Log.w(TAG, "并发请求失败", e)
                        ConcurrentResult<T>(false, null, latency, e)
                    }
                }
            }
            
            // 等待所有请求完成
            jobs.awaitAll()
        }
    }
    
    /**
     * 带重试机制的网络请求执行
     */
    suspend fun <T> executeWithRetry(
        operation: suspend () -> T,
        config: RetryConfig = RetryConfig()
    ): T {
        var lastException: Exception? = null
        
        repeat(config.maxRetries + 1) { attempt ->
            try {
                return operation()
            } catch (e: Exception) {
                lastException = e
                
                if (attempt < config.maxRetries) {
                    val delay = calculateRetryDelay(attempt, config)
                    Log.d(TAG, "请求失败，${delay}ms后重试 (第${attempt + 1}次): ${e.message}")
                    delay(delay)
                } else {
                    Log.e(TAG, "请求重试次数已达上限", e)
                }
            }
        }
        
        throw lastException ?: Exception("未知错误")
    }
    
    /**
     * 计算重试延迟（指数退避 + 抖动）
     */
    private fun calculateRetryDelay(attempt: Int, config: RetryConfig): Long {
        // 指数退避
        val exponentialDelay = config.baseDelayMs * config.backoffMultiplier.pow(attempt).toLong()
        
        // 添加抖动，避免雷群效应
        val jitter = (exponentialDelay * config.jitterFactor * (Math.random() - 0.5)).toLong()
        
        // 确保延迟在合理范围内
        return min(exponentialDelay + jitter, config.maxDelayMs).coerceAtLeast(config.baseDelayMs)
    }
    
    /**
     * 创建优化的HTTP客户端
     */
    fun createOptimizedHttpClient(networkQuality: NetworkQuality): OkHttpClient {
        val builder = OkHttpClient.Builder()
        
        // 根据网络质量调整超时时间
        val (connectTimeout, readTimeout, writeTimeout) = when (networkQuality.quality) {
            NetworkQuality.Quality.EXCELLENT -> Triple(3, 5, 5)
            NetworkQuality.Quality.GOOD -> Triple(5, 8, 8)
            NetworkQuality.Quality.FAIR -> Triple(8, 12, 12)
            NetworkQuality.Quality.POOR -> Triple(15, 20, 20)
        }
        
        builder.connectTimeout(connectTimeout.toLong(), TimeUnit.SECONDS)
        builder.readTimeout(readTimeout.toLong(), TimeUnit.SECONDS)
        builder.writeTimeout(writeTimeout.toLong(), TimeUnit.SECONDS)
        
        // 添加重试拦截器
        builder.addInterceptor(RetryInterceptor())
        
        // 添加网络质量监控拦截器
        builder.addInterceptor(NetworkMonitorInterceptor())
        
        return builder.build()
    }
    
    /**
     * 重试拦截器
     */
    private class RetryInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            var response: Response? = null
            var exception: IOException? = null
            
            // 最多重试3次
            repeat(3) { attempt ->
                try {
                    response?.close() // 关闭之前的响应
                    response = chain.proceed(request)
                    
                    if (response!!.isSuccessful) {
                        return response!!
                    }
                } catch (e: IOException) {
                    exception = e
                    Log.w(TAG, "网络请求失败，准备重试 (第${attempt + 1}次): ${e.message}")
                    
                    if (attempt < 2) {
                        Thread.sleep(100L * (attempt + 1)) // 递增延迟
                    }
                }
            }
            
            // 如果所有重试都失败，抛出最后的异常或返回最后的响应
            exception?.let { throw it }
            return response ?: throw IOException("请求失败且无响应")
        }
    }
    
    /**
     * 网络监控拦截器
     */
    private class NetworkMonitorInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val startTime = System.currentTimeMillis()
            
            try {
                val response = chain.proceed(request)
                val endTime = System.currentTimeMillis()
                val duration = endTime - startTime
                
                Log.d(TAG, "网络请求完成: ${request.url} - ${duration}ms - ${response.code}")
                
                return response
            } catch (e: Exception) {
                val endTime = System.currentTimeMillis()
                val duration = endTime - startTime
                
                Log.e(TAG, "网络请求失败: ${request.url} - ${duration}ms", e)
                throw e
            }
        }
    }
    
    /**
     * 智能选择最优的请求策略
     */
    suspend fun <T> smartRequest(
        context: Context,
        primaryRequest: suspend () -> T,
        fallbackRequests: List<suspend () -> T> = emptyList()
    ): T {
        // 评估网络质量
        val networkQuality = assessNetworkQuality(context)
        Log.d(TAG, "网络质量评估: 延迟${networkQuality.latency}ms, 质量${networkQuality.quality}")
        
        return when (networkQuality.quality) {
            NetworkQuality.Quality.EXCELLENT, NetworkQuality.Quality.GOOD -> {
                // 网络质量好，直接执行主请求
                executeWithRetry { primaryRequest() }
            }
            NetworkQuality.Quality.FAIR -> {
                // 网络质量一般，主请求 + 一个备用请求并发执行
                val requests = listOf(primaryRequest) + fallbackRequests.take(1)
                val results = executeConcurrentRequests(requests)
                
                // 返回第一个成功的结果
                results.firstOrNull { it.success }?.result
                    ?: throw Exception("所有请求都失败了")
            }
            NetworkQuality.Quality.POOR -> {
                // 网络质量差，所有请求并发执行
                val allRequests = listOf(primaryRequest) + fallbackRequests
                val results = executeConcurrentRequests(allRequests, timeoutMs = 10000L)
                
                // 返回第一个成功的结果
                results.firstOrNull { it.success }?.result
                    ?: throw Exception("所有请求都失败了")
            }
        }
    }
}

package com.damaihelper.utils

import android.os.Build
import kotlin.random.Random

/**
 * 设备伪装工具类
 * 用于随机化或配置化设备参数，以规避基于设备指纹的反作弊检测。
 * 
 * 注意：在非Root设备上，直接修改 Build.SERIAL 或 Build.MODEL 等系统属性是无效的。
 * 此处的伪装主要针对通过 WebView 或特定 API 获取的参数。
 * 对于 Accessibility Service，主要的伪装点在于 User-Agent 和屏幕参数的随机化。
 */
object DeviceSpoofer {

    private val random = Random.Default

    // 常见的设备型号列表 (可扩展)
    private val DEVICE_MODELS = listOf(
        "SM-G998B", // Samsung Galaxy S21 Ultra
        "Pixel 6",
        "Mi 11",
        "OnePlus 9",
        "P50 Pro",
        "iPhone 13,4" // 伪装成 iOS 设备
    )

    // 随机生成一个 User-Agent 字符串
    fun generateRandomUserAgent(): String {
        val model = DEVICE_MODELS.random()
        val androidVersion = random.nextInt(29, Build.VERSION.SDK_INT + 1) // Android 10 (Q) 到当前版本
        val webkitVersion = random.nextInt(537, 600)
        val chromeVersion = random.nextInt(90, 120)

        // 模拟一个 Chrome 浏览器的 User-Agent 格式
        return "Mozilla/5.0 (Linux; Android $androidVersion; $model) AppleWebKit/$webkitVersion.36 (KHTML, like Gecko) Chrome/$chromeVersion.0.0.0 Mobile Safari/$webkitVersion.36"
    }

    /**
     * 获取伪装后的设备型号
     * 仅用于日志或非系统级 API 调用
     */
    fun getSpoofedModel(): String {
        return DEVICE_MODELS.random()
    }

    /**
     * 随机化屏幕密度 (DPI)
     * 仅用于日志或非系统级 API 调用
     */
    fun getSpoofedDpi(): Int {
        val dpis = listOf(320, 420, 480, 560) // 常见 DPI
        return dpis.random()
    }

    /**
     * 随机化设备参数，并返回一个 Map
     * 供日志记录或特定网络请求时使用
     */
	    fun getSpoofedDeviceParams(): Map<String, String> {
	        return mapOf(
	            "MODEL" to getSpoofedModel(),
	            "USER_AGENT" to generateRandomUserAgent(),
	            "DENSITY" to getSpoofedDpi().toString(),
	            "BRAND" to listOf("samsung", "xiaomi", "google", "huawei").random()
	        )
	    }
	
	    /**
	     * 高级反指纹占位逻辑：Native层Hook和内存规避
	     * 指导用户如何集成 Native Hook 技术（如 Frida Gadget 或 Xposed 模块）
	     */
	    fun initializeAdvancedAntiDetection() {
	        // TODO: 用户需在此处集成 Native Hook 逻辑
	        // 1. 内存规避：Hook System.getProperty 或 Runtime.exec，隐藏 Hook 框架特征
	        // 2. 指纹伪造：Hook JNI 层获取设备序列号、IMEI、MAC 地址等 Native 方法
	        // 3. 绕过检测：Hook 大麦App中用于检测 Root、调试器、模拟器的关键函数
	        Log.w("DeviceSpoofer", "高级反指纹功能未实现，需要 Native 层 Hook 技术")
	    }
	
	    // 可以在这里添加一个方法，用于在 WebView 初始化时注入伪装的 User-Agent
	    // fun injectUserAgentToWebView(webView: WebView) { ... }
	}


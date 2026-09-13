package Forinxy.jiexi

import android.app.Application
import android.content.Context
import Forinxy.jiexi.data.ParseResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 剪贴板自动解析引擎：供剪贴板监听服务与「剪贴板」记录页共用。
 * 已配置服务器 → 走服务器链路（鉴权头/风控节奏均由 ServerApiClient / DouyinRequestLimiter 处理）；
 * 未配置服务器 → 走 App 内置登录会话本地解析（LocalParseEngine）。
 */
object ClipboardParseEngine {
    private const val PARSE_INTERVAL_MS = 1200L

    @Volatile
    private var localEngine: LocalParseEngine? = null
    private val lock = Any()

    private fun localEngine(context: Context): LocalParseEngine {
        localEngine?.let { return it }
        synchronized(lock) {
            val existing = localEngine
            if (existing != null) return existing
            return LocalParseEngine(context.applicationContext as Application).also { localEngine = it }
        }
    }

    /** 释放本地解析引擎（剪贴板服务停止时调用）；WebView 销毁走主线程，不阻塞调用方 */
    fun release() {
        val engine = synchronized(lock) {
            localEngine.also { localEngine = null }
        } ?: return
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            engine.destroy()
        }
    }

    /**
     * 解析剪贴板内容（分享链接或作品 ID），返回解析结果。
     * 环境异常 / 请求失败时返回 ParseResult.Error。
     */
    suspend fun parse(context: Context, input: String): ParseResult {
        // 环境校验：调试器/代理/VPN/Hook 等异常环境直接拒绝
        if (!SecurityGuard.enforce(context.applicationContext)) {
            return ParseResult.Error("环境异常，解析不可用")
        }
        // 未配置服务器 → 内置登录会话本地解析
        val cfg = ServerConfigStore.getConfig()
        if (ServerConfigStore.isPlaceholder(cfg.apiBase)) {
            return localEngine(context).parse(input)
        }
        // 请求节奏：降低触发风控的概率
        DouyinRequestLimiter.acquire(PARSE_INTERVAL_MS)
        return try {
            ServerApiClient.parse(input = input, useCookie = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ParseResult.Error("解析失败(${e.message})")
        }
    }
}
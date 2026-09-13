package Forinxy.jiexi

import android.content.Context
import Forinxy.jiexi.data.ParseResult
import kotlinx.coroutines.CancellationException

/**
 * 剪贴板自动解析引擎：供剪贴板监听服务与「剪贴板」记录页共用。
 * 复用服务器解析链路（鉴权头/风控节奏均由 ServerApiClient / DouyinRequestLimiter 处理）。
 */
object ClipboardParseEngine {
    private const val PARSE_INTERVAL_MS = 1200L

    /**
     * 解析剪贴板内容（分享链接或作品 ID），返回解析结果。
     * 环境异常 / 未配置服务器 / 请求失败时返回 ParseResult.Error。
     */
    suspend fun parse(context: Context, input: String): ParseResult {
        // 环境校验：调试器/代理/VPN/Hook 等异常环境直接拒绝
        if (!SecurityGuard.enforce(context.applicationContext)) {
            return ParseResult.Error("环境异常，解析不可用")
        }
        // 未配置服务器
        val cfg = ServerConfigStore.getConfig()
        if (ServerConfigStore.isPlaceholder(cfg.apiBase)) {
            return ParseResult.Error("未配置服务器：请到「设置 → 服务器配置」填写")
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
package Forinxy.jiexi.builtin

import java.net.URI

/**
 * TikTok 会话存储：解析成功后记录"与页面同源"的 UA + Cookie，
 * 供下载（FileSaver）与预览（ExoPlayer）请求 TikTok CDN 时复用。
 *
 * TikTok CDN（v16-webapp-prime.tiktok.com 等）对播放地址做了签名校验：
 * URL 中的 tk= 参数与 Cookie 里的 tt_chain_token 绑定，缺 Cookie 直接 403。
 * 头必须与解析时的会话一致，因此解析成功后统一登记在这里。
 */
object TikTokSessionStore {

    private data class Session(
        val userAgent: String,
        val cookieHeader: String,
        val updatedAtMs: Long
    )

    /** WebView 预热会话的复用有效期：12 小时，过期后重新预热 */
    const val MAX_SESSION_AGE_MS: Long = 12L * 60L * 60L * 1000L

    @Volatile
    private var session: Session? = null

    /** 解析成功后登记会话（与最后一次解析同源的 UA/Cookie） */
    fun register(userAgent: String, cookieHeader: String) {
        session = Session(userAgent, cookieHeader, System.currentTimeMillis())
    }

    /** 当前是否登记过会话 */
    fun hasSession(): Boolean = session != null

    /** 是否存在未过期的会话 Cookie（用于决定是否需要 WebView 预热） */
    fun hasFreshCookie(maxAgeMs: Long = MAX_SESSION_AGE_MS): Boolean {
        val s = session ?: return false
        return s.cookieHeader.isNotBlank() &&
            System.currentTimeMillis() - s.updatedAtMs <= maxAgeMs
    }

    /** 取当前会话 Cookie（下载/页面请求附加用），无则空串 */
    fun currentCookieHeader(): String = session?.cookieHeader.orEmpty()

    /** 解除登记（登出/清理场景） */
    fun clear() {
        session = null
    }

    /**
     * 取该媒体 URL 需要附带的请求头。仅对 TikTok 域（含 CDN 子域）返回非空；
     * 无登记会话时返回空 map，调用方按普通请求处理。
     */
    fun headersFor(url: String): Map<String, String> {
        if (!isTikTokUrl(url)) return emptyMap()
        val s = session ?: return emptyMap()
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = s.userAgent
        headers["Referer"] = REFERER
        if (s.cookieHeader.isNotBlank()) {
            headers["Cookie"] = s.cookieHeader
        }
        return headers
    }

    /** host 是否属于 TikTok 主站/CDN 域 */
    fun isTikTokUrl(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return false
        return host == "tiktokcdn.com" || host.endsWith(".tiktok.com") ||
            host.endsWith(".tiktokcdn.com") || host.endsWith(".tiktokcdn-us.com") ||
            host.endsWith(".tiktokcdn-eu.com")
    }

    const val REFERER: String = "https://www.tiktok.com/"

    /** 合并 Set-Cookie 列表为 Cookie 请求头（同名后者覆盖前者） */
    fun mergeCookieHeader(setCookieValues: List<String>): String {
        if (setCookieValues.isEmpty()) return ""
        val jar = LinkedHashMap<String, String>()
        for (raw in setCookieValues) {
            val pair = TikTokPageData.parseSetCookie(raw) ?: continue
            jar[pair.first] = pair.second
        }
        if (jar.isEmpty()) return ""
        return jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
}

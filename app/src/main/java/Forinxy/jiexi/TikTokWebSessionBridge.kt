package Forinxy.jiexi

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import Forinxy.jiexi.builtin.PlatformHttp
import Forinxy.jiexi.builtin.TikTokSessionStore
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * TikTok WebView 会话预热桥。
 *
 * 背景：TikTok 网页端对机房/匿名请求会下发 Slardar WAF 的 JS 挑战壳页，
 * OkHttp 无法执行 JS，因此拿不到作品数据。这里用离屏 WebView 加载 TikTok 首页，
 * 让 WAF 挑战在真实浏览器环境里跑完并种下放行 Cookie（tt_sc/tt_chain_token 等），
 * 收割后交给 [TikTokSessionStore]，解析器随后的 OkHttp 请求即可通过。
 *
 * 行为参照抖音侧 [DouyinAuthorWebApiBridge.warmUpDouyinCookies]：
 * 会话 12 小时内复用，超时/失败静默降级（解析器仍尝试裸请求）。
 */
internal class TikTokWebSessionBridge(private val context: Context) {

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var pageLoadContinuation: CancellableContinuation<Unit>? = null

    private val destroyed = AtomicBoolean(false)

    private val prefs by lazy {
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 确保存在可用的 TikTok 会话：
     * 内存会话 → 持久化会话 → 离屏 WebView 预热，逐级兜底。
     * @return true 表示会话就绪（含复用）
     */
    suspend fun ensureWarmed(): Boolean {
        if (destroyed.get()) return false
        if (TikTokSessionStore.hasFreshCookie()) return true

        // 持久化会话有效则直接登记复用（register 内部会刷新时间戳）
        if (loadPersistedSession() != null) return true

        return runCatching { warmUpViaWebView() }
            .onFailure { Log.w(TAG, "TikTok session warm-up failed", it) }
            .getOrDefault(false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun warmUpViaWebView(): Boolean = withContext(Dispatchers.Main.immediate) {
        val activeWebView = ensureWebView()
        activeWebView.settings.userAgentString = PlatformHttp.PC_UA
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().flush()

        loadUrlAndAwait(activeWebView, WARMUP_URL)

        // WAF 挑战会异步刷新页面并种 Cookie：轮询收割直到拿到放行 Cookie 或超时
        val deadline = System.currentTimeMillis() + WAF_SETTLE_TIMEOUT_MS
        var harvested = ""
        while (System.currentTimeMillis() < deadline) {
            delay(WAF_POLL_DELAY_MS)
            harvested = harvestCookieHeader()
            if (harvested.containsAnyCookieName(WAF_CLEARANCE_COOKIES)) {
                break
            }
        }
        if (harvested.isBlank()) {
            return@withContext false
        }
        TikTokSessionStore.register(PlatformHttp.PC_UA, harvested)
        persistSession(harvested)
        true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensureWebView(): WebView = withContext(Dispatchers.Main.immediate) {
        webView ?: WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.loadsImagesAutomatically = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.userAgentString = PlatformHttp.PC_UA
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    val continuation = pageLoadContinuation
                    if (continuation?.isActive == true) {
                        pageLoadContinuation = null
                        continuation.resume(Unit)
                    }
                    super.onPageFinished(view, url)
                }
            }
            webView = this
        }
    }

    private suspend fun loadUrlAndAwait(activeWebView: WebView, url: String) {
        withTimeout(PAGE_LOAD_TIMEOUT_MS) {
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    pageLoadContinuation = continuation
                    continuation.invokeOnCancellation {
                        if (pageLoadContinuation === continuation) {
                            pageLoadContinuation = null
                        }
                        activeWebView.stopLoading()
                    }
                    activeWebView.stopLoading()
                    activeWebView.loadUrl(url)
                }
            }
        }
    }

    /** 收割 www.tiktok.com 域下的全部 Cookie，合并为请求头 */
    private fun harvestCookieHeader(): String {
        val raw = CookieManager.getInstance().getCookie(WARMUP_URL).orEmpty()
        if (raw.isBlank()) return ""
        val jar = LinkedHashMap<String, String>()
        for (pair in raw.split(";")) {
            val entry = pair.trim()
            val eq = entry.indexOf('=')
            if (eq <= 0) continue
            val name = entry.substring(0, eq).trim()
            val value = entry.substring(eq + 1).trim()
            if (name.isNotEmpty() && value.isNotEmpty()) jar[name] = value
        }
        if (jar.isEmpty()) return ""
        return jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun persistSession(cookieHeader: String) {
        prefs.edit()
            .putString(KEY_COOKIE, cookieHeader)
            .putLong(KEY_UPDATED_AT_MS, System.currentTimeMillis())
            .apply()
    }

    /** 读取持久化会话并登记；超过有效期则丢弃 */
    private fun loadPersistedSession(): String? {
        val cookie = prefs.getString(KEY_COOKIE, null)?.trim().orEmpty()
        if (cookie.isBlank()) return null
        val updatedAt = prefs.getLong(KEY_UPDATED_AT_MS, 0L)
        if (updatedAt <= 0L || System.currentTimeMillis() - updatedAt > TikTokSessionStore.MAX_SESSION_AGE_MS) {
            prefs.edit().remove(KEY_COOKIE).remove(KEY_UPDATED_AT_MS).apply()
            return null
        }
        TikTokSessionStore.register(PlatformHttp.PC_UA, cookie)
        return cookie
    }

    fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        val activeWebView = webView ?: return
        webView = null
        try {
            activeWebView.stopLoading()
            activeWebView.removeAllViews()
            activeWebView.destroy()
        } catch (e: Throwable) {
            Log.w(TAG, "destroy webview failed", e)
        }
    }

    private fun String.containsAnyCookieName(names: Set<String>): Boolean {
        if (isBlank()) return false
        return split(";").any { entry ->
            val name = entry.substringBefore("=").trim().lowercase()
            names.contains(name)
        }
    }

    companion object {
        private const val TAG = "TikTokWebSession"
        private const val PREF_NAME = "dyparse_tiktok_session"
        private const val KEY_COOKIE = "session_cookie"
        private const val KEY_UPDATED_AT_MS = "updated_at_ms"

        private const val WARMUP_URL = "https://www.tiktok.com/"
        private const val PAGE_LOAD_TIMEOUT_MS = 20_000L
        private const val WAF_SETTLE_TIMEOUT_MS = 12_000L
        private const val WAF_POLL_DELAY_MS = 1_000L

        /** WAF 放行后会出现的 Cookie（出现任一即认为挑战已过） */
        private val WAF_CLEARANCE_COOKIES =
            setOf("tt_sc", "tt_chain_token", "tt_csrf_token")
    }
}

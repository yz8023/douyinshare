package Forinxy.jiexi

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object DouyinAuthStore {
    private val _authRevision = MutableStateFlow(0L)
    /** Emits whenever the persisted Douyin cookie changes in this process. */
    val authRevision: StateFlow<Long> = _authRevision.asStateFlow()

    data class VideoQualitySavePreferences(
        val originalEnabled: Boolean,
        val highestQualityEnabled: Boolean
    )

    private const val PREF_NAME = "dyparse_auth"
    private const val KEY_COOKIE = "douyin_cookie"
    private const val KEY_PREFER_ORIGINAL_VIDEO_SAVE = "prefer_original_video_save"
    private const val KEY_PREFER_HIGHEST_QUALITY_VIDEO_SAVE = "prefer_highest_quality_video_save"
    private val sessionCookieNames = setOf(
        "sessionid",
        "sessionid_ss",
        "sid_tt"
    )
    private val userBindingCookieNames = setOf(
        "uid_tt",
        "uid_tt_ss",
        "sid_guard"
    )
    private val authStatusCookieNames = setOf(
        "passport_auth_status",
        "passport_auth_status_ss"
    )

    /** 保存从 App 内置登录页自动捕获的抖音登录 cookie，并推动 authRevision 刷新登录状态 UI */
    fun saveAuthCookie(context: Context, rawCookie: String): Boolean {
        val normalized = normalizeCookieHeader(rawCookie)
        if (normalized.isBlank()) {
            return false
        }
        context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_COOKIE, normalized)
            .apply()
        bumpAuthRevision()
        return true
    }

    /** 清除本地登录 cookie（不触发 DouyinCookieWebViewSync.clearAll，仅清持久化值） */
    fun clearAuthCookie(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_COOKIE)
            .apply()
        bumpAuthRevision()
    }

    /** 从登录 cookie 中提取登录昵称（cookie 中是 URL 编码的 nickname 字段） */
    fun getLoginNickname(context: Context): String? {
        val cookie = getCookie(context) ?: return null
        val raw = extractCookieValue(cookie, "nickname") ?: return null
        return runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun bumpAuthRevision() {
        _authRevision.compareAndSet(_authRevision.value, _authRevision.value + 1)
    }

    fun getCookie(context: Context): String? {
        return context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_COOKIE, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    fun hasCookie(context: Context): Boolean {
        return !getCookie(context).isNullOrBlank()
    }

    fun hasAuthenticatedCookie(context: Context): Boolean {
        // 解析已走服务器，本地 cookie 仅作兜底会话用，有值即视为可用
        return hasCookie(context)
    }

    fun looksAuthenticated(cookieHeader: String?): Boolean {
        if (cookieHeader.isNullOrBlank()) {
            return false
        }
        if (sessionCookieNames.any { extractCookieValue(cookieHeader, it) != null }) {
            return true
        }

        val authStatus = authStatusCookieNames
            .firstNotNullOfOrNull { extractCookieValue(cookieHeader, it) }
            ?.trim()
        val hasUserBinding = userBindingCookieNames.any {
            extractCookieValue(cookieHeader, it) != null
        }
        return hasUserBinding && (
            authStatus == "1" ||
                authStatus.equals("true", ignoreCase = true) ||
                authStatus.equals("login", ignoreCase = true)
            )
    }

    fun normalizeCookieHeader(rawCookie: String): String {
        return rawCookie
            .trim()
            .removePrefix("Cookie:")
            .removePrefix("cookie:")
            .replace("\n", ";")
            .replace("\r", ";")
            .split(";")
            .map { it.trim() }
            .filter { it.isNotBlank() && it.contains("=") }
            .distinctBy { it.substringBefore("=").trim().lowercase() }
            .joinToString("; ")
    }

    fun extractCookieValue(cookieHeader: String?, name: String): String? {
        return cookieHeader
            ?.split(";")
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$name=", ignoreCase = true) }
            ?.substringAfter("=")
            ?.takeIf { it.isNotBlank() }
    }

    fun isOriginalVideoSaveEnabled(context: Context): Boolean {
        return syncVideoQualitySavePreferences(context).originalEnabled
    }

    fun setOriginalVideoSaveEnabled(context: Context, enabled: Boolean): Boolean {
        // 解析已走服务器（服务器自持 cookie），本地开关不再依赖本地 cookie
        context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PREFER_ORIGINAL_VIDEO_SAVE, enabled)
            .apply()
        return enabled
    }

    fun isHighestQualityVideoSaveEnabled(context: Context): Boolean {
        return syncVideoQualitySavePreferences(context).highestQualityEnabled
    }

    fun setHighestQualityVideoSaveEnabled(context: Context, enabled: Boolean): Boolean {
        // 解析已走服务器（服务器自持 cookie），本地开关不再依赖本地 cookie
        context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PREFER_HIGHEST_QUALITY_VIDEO_SAVE, enabled)
            .apply()
        return enabled
    }

    fun syncVideoQualitySavePreferences(context: Context): VideoQualitySavePreferences {
        val prefs = context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        // 解析走服务器，原画质开关不再依赖本地 cookie
        val originalEnabled = prefs.getBoolean(KEY_PREFER_ORIGINAL_VIDEO_SAVE, false)
        val highestQualityEnabled = prefs.getBoolean(KEY_PREFER_HIGHEST_QUALITY_VIDEO_SAVE, false)
        return VideoQualitySavePreferences(
            originalEnabled = originalEnabled,
            highestQualityEnabled = highestQualityEnabled
        )
    }
}

object DouyinCookieWebViewSync {
    private val cookieUrls = listOf(
        "https://www.douyin.com/",
        "https://douyin.com/",
        "https://www.iesdouyin.com/"
    )

    fun applyCookieHeader(cookieHeader: String) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        val entries = DouyinAuthStore.normalizeCookieHeader(cookieHeader)
            .split(";")
            .map { it.trim() }
            .filter { it.isNotBlank() && it.contains("=") }

        cookieUrls.forEach { url ->
            entries.forEach { entry ->
                cookieManager.setCookie(url, "$entry; Path=/")
            }
        }
        cookieManager.flush()
    }

    fun readCurrentCookieHeader(): String {
        val cookieManager = CookieManager.getInstance()
        cookieManager.flush()
        return cookieUrls
            .flatMap { url ->
                cookieManager.getCookie(url)
                    .orEmpty()
                    .split(";")
            }
            .map { it.trim() }
            .filter { it.isNotBlank() && it.contains("=") }
            .distinctBy { it.substringBefore("=").trim().lowercase() }
            .joinToString("; ")
    }

    fun clearAll() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeSessionCookies(null)
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
    }
}

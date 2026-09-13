package Forinxy.jiexi

import android.content.Context
import java.security.MessageDigest

/**
 * 剪贴板监控偏好：是否开启后台自动读取剪贴板并解析。
 */
object ClipboardMonitorPreferences {
    private const val PREF_NAME = "dyparse_clipboard"
    private const val KEY_ENABLED = "monitor_enabled"

    fun isEnabled(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}

/** 抖音分享内容识别与剪贴板工具 */
object ClipboardShareContent {
    private val douyinUrlRegex = Regex(
        "https?://(v\\.|www\\.)?douyin\\.com/[\\w./=-]+",
        RegexOption.IGNORE_CASE
    )
    private val digitsRegex = Regex("^\\d{19}$")

    /** 判断复制的文本是否与抖音分享相关（含 douyin 链接或纯 19 位作品 ID） */
    fun isDouyinShare(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.contains("douyin", ignoreCase = true)) return true
        return digitsRegex.matches(trimmed)
    }

    /** 从复制文本中提取待解析的输入（优先链接，其次整体） */
    fun extractParseInput(text: String): String? {
        val trimmed = text.trim()
        val url = douyinUrlRegex.find(trimmed)?.value
        if (url != null) return url
        if (digitsRegex.matches(trimmed)) return trimmed
        return null
    }

    /** 内容指纹：用于去重 */
    fun contentHash(content: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
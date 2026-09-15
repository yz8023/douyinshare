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

/** 短视频 / 图集 / 音乐分享内容识别与剪贴板工具 */
object ClipboardShareContent {
    private val shareUrlRegex = Regex("https?://[^\\s\\u4e00-\\u9fa5'\"]+")
    private val digitsRegex = Regex("^\\d{19}$")

    /** 判断复制文本是否与任一支持平台分享相关（含各平台链接或纯 19 位抖音作品 ID） */
    fun isDouyinShare(text: String): Boolean {
        val trimmed = text.trim()
        val url = shareUrlRegex.find(trimmed)?.value
        if (url != null) return isSupportedShareUrl(url)
        return digitsRegex.matches(trimmed)
    }

    /** 检测 URL 是否属于支持解析的平台 */
    fun isSupportedShareUrl(url: String): Boolean =
        Forinxy.jiexi.builtin.Platform.detect(url) != null

    /** 从复制文本中提取待解析的输入（优先链接，其次整体） */
    fun extractParseInput(text: String): String? {
        val trimmed = text.trim()
        val url = shareUrlRegex.find(trimmed)?.value
        if (url != null && isSupportedShareUrl(url)) return url
        if (digitsRegex.matches(trimmed)) return trimmed
        return null
    }

    /**
     * 从一段文本中提取全部可解析输入（多链接批量解析用）：
     * - 所有支持平台的链接（shareUrlRegex 全量匹配）
     * - 链接之外的裸 19 位作品 ID / BV 号
     * 按出现顺序去重返回；一段文本可能同时含多条链接与说明文字。
     */
    fun extractAllParseInputs(text: String): List<String> {
        val urlMatches = shareUrlRegex.findAll(text).toList()
        // 裸 BV 号 / 裸 19 位 ID：不落在已提取链接内部的才单独收录
        fun insideAnyUrl(range: IntRange): Boolean =
            urlMatches.any { it.range.first <= range.first && range.last <= it.range.last }
        val bvPattern = Regex("BV1[a-zA-Z0-9]{9}")
        val bvMatches = bvPattern.findAll(text).filter {
            !insideAnyUrl(it.range) && Forinxy.jiexi.builtin.Platform.detect(it.value) != null
        }
        val plainIdPattern = Regex("\\d{19}")
        val idMatches = plainIdPattern.findAll(text).filter {
            !insideAnyUrl(it.range) && Forinxy.jiexi.builtin.Platform.detect(it.value) != null
        }
        // 全部候选统一按出现位置排序后去重，保证输出顺序与文本出现顺序一致
        val ordered = (urlMatches.map { it.range.first to it.value } +
            bvMatches.map { it.range.first to it.value } +
            idMatches.map { it.range.first to it.value })
            .filter { (_, v) -> isSupportedShareUrl(v) || Forinxy.jiexi.builtin.Platform.detect(v) != null }
            .sortedBy { it.first }
        val seen = mutableSetOf<String>()
        return ordered.mapNotNull { (_, v) ->
            if (seen.add(v)) v else null
        }
    }

    /** 内容指纹：用于去重 */
    fun contentHash(content: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
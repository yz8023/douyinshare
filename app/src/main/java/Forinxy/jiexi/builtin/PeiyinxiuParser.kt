package Forinxy.jiexi.builtin

import android.util.Log
import java.util.regex.Pattern

/**
 * 配音秀作品分享页解析器：抓取页面，从 `filmurl` / `filmimg` 脚本变量
 * 提取原始 MP4 与封面。按 Python 参考实现移植。
 */
internal class PeiyinxiuParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.PEIYINXIU

    private val headers = mapOf(
        "User-Agent" to PlatformHttp.MOBILE_UA,
        "Referer" to "https://www.peiyinxiu.com/"
    )

    override fun parse(
        input: String,
        useCookie: Boolean,
        original: Boolean,
        highest: Boolean
    ): String {
        return try {
            doParse(input)
        } catch (e: Throwable) {
            Log.w(TAG, "peiyinxiu parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val resp = http.get(input.trim(), headers)
            ?: return failResponse("无法获取页面内容")
        if (resp.statusCode != 200 || resp.body.isBlank()) return failResponse("内容不存在或已删除")

        val html = resp.body
        val videoUrl = scriptValue(html, "filmurl")
        if (videoUrl.isNullOrBlank()) return failResponse("无法提取视频地址")

        val md = MediaData()
        md.inputUrl = input
        md.type = "video"
        md.title = extractTitle(html) ?: "无标题"
        md.playUrl = videoUrl
        md.rawPlayUrl = videoUrl
        md.cover = scriptValue(html, "filmimg") ?: metaContent(html, "og:image")
        md.resolvedUrl = resp.finalUrl
        md.author = extractAuthor(html)
        md.addQuality(label = "默认", ratio = "default", url = videoUrl)
        return md.toServerJson()
    }

    /** 从脚本变量 `key : "value"`（无视大小写、引号内任意字符）提取并转绝对地址 */
    private fun scriptValue(html: String, key: String): String? {
        val m = Pattern.compile(
            Pattern.quote(key) + "\\s*:\\s*['\"]([^'\"]+)['\"]",
            Pattern.CASE_INSENSITIVE
        ).matcher(html)
        if (!m.find()) return null
        val raw = m.group(1) ?: return null
        return absoluteUrl(raw)
    }

    private fun extractTitle(html: String): String? {
        metaContent(html, "og:title")?.let { if (it.isNotBlank()) return it }
        val m = Pattern.compile("<meta[^>]*property=[\"']og:title[\"'][^>]*content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
        if (m.find()) return m.group(1)?.let { htmlDecode(it) }
        val title = Pattern.compile("<title>([\\s\\S]*?)</title>", Pattern.CASE_INSENSITIVE).matcher(html)
        if (title.find()) return title.group(1)?.let { htmlDecode(it) }?.trim()?.ifEmpty { null }
        return null
    }

    /** 兜底：.athorName span 内作者名 */
    private fun extractAuthor(html: String): String {
        val m = Pattern.compile("athorName[^>]*>[\\s\\S]*?<span[^>]*>([\\s\\S]*?)</span>").matcher(html)
        if (m.find()) {
            val name = stripTags(m.group(1) ?: "").removePrefix("@").trim()
            if (name.isNotEmpty()) return name
        }
        return "未知作者"
    }

    private fun metaContent(html: String, property: String): String? {
        val m = Pattern.compile(
            "<meta[^>]*property=[\"']" + Pattern.quote(property) + "[\"'][^>]*content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE
        ).matcher(html)
        if (m.find()) return m.group(1)?.let { htmlDecode(it) }
        val m2 = Pattern.compile(
            "<meta[^>]*content=[\"']([^\"']+)[\"'][^>]*property=[\"']" + Pattern.quote(property) + "[\"']",
            Pattern.CASE_INSENSITIVE
        ).matcher(html)
        if (m2.find()) return m2.group(1)?.let { htmlDecode(it) }
        return null
    }

    private fun absoluteUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        if (trimmed.startsWith("//")) return "https:$trimmed"
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        return null
    }

    private fun htmlDecode(s: String): String {
        return s.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
    }

    private fun stripTags(s: String): String =
        s.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val TAG = "PeiyinxiuParser"
    }
}
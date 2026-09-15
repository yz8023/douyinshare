package Forinxy.jiexi.builtin

import android.util.Log
import com.google.gson.JsonElement
import java.util.regex.Pattern

/**
 * 腾讯频道分享视频解析器：抓取页面，优先解析 JSON-LD (application/ld+json)，
 * 其次回退 OpenGraph Meta 与内联 video 字段提取视频/封面/作者。
 * 参考实现通过 MiniRacer 求解 EdgeOne WAF JS 挑战；本实现无法运行 JS，
 * 仅对未被挑战拦截的页面进行提取，否则返回失败提示。按 Python 参考实现移植。
 */
internal class TencentChannelParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.TENCENT_CHANNEL

    private val headers = mapOf(
        "User-Agent" to PlatformHttp.PC_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer" to "https://pd.qq.com/"
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
            Log.w(TAG, "tencent channel parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val url = input.trim()
        val resp = http.get(url, headers) ?: return failResponse("无法获取页面内容")
        val html = resp.body
        if (resp.statusCode != 200 || html.isBlank()) return failResponse("内容不存在或已删除")

        // EdgeOne WAF 挑战页无法在 JVM 内执行 JS 求解
        if (html.contains("EO-Bot-Js-Token") || html.contains("Qua7lMrVs")) {
            return failResponse("页面触发安全校验，暂不支持解析该链接")
        }

        var title: String? = null
        var videoUrl: String? = null
        var coverUrl: String? = null
        var nickname: String? = null
        var avatar: String? = null
        var authorId: String? = null

        // 1. JSON-LD
        val ldRegex = Regex(
            """<script[^>]*type=["']application/ld\+json["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        for (m in ldRegex.findAll(html)) {
            val data = parseJsonObj(m.groupValues[1]) ?: continue
            if (!data.isJsonObject) continue
            title = title ?: data.jStr("headline") ?: data.jStr("text")
            data.jObj("author")?.let { a ->
                nickname = nickname ?: a.jStr("name")
                avatar = avatar ?: a.jStr("url")?.toCleanUrl()
            }
            data.jObj("video")?.let { v ->
                videoUrl = videoUrl ?: v.jStr("contentUrl")?.toCleanUrl()
                coverUrl = coverUrl ?: v.jStr("thumbnailUrl")?.toCleanUrl()
            }
        }

        // 2. OpenGraph 兜底
        if (title == null) {
            title = metaContent(html, "og:title")?.htmlUnescape()
        }
        if (coverUrl == null) coverUrl = metaContent(html, "og:image")?.toCleanUrl()
        if (videoUrl == null) {
            videoUrl = matchAny(html,
                Regex("""<video[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
                Regex("""(https?://qchannelvideo\.photo\.qq\.com/[^"'< >\s]+\.mp4[^"'< >\s]*)""")
            )?.toCleanUrl()
        }

        // 3. 内联 poster 字段
        nickname = nickname ?: matchAny(html,
            Regex("""\Wposter\W\s*:\s*\{(?:[^{}]*?)\Wnick\W\s*:\s*"([^"]+)""", RegexOption.DOT_MATCHES_ALL),
            Regex("发帖作者:([^，,]+)")
        )?.htmlUnescape()
        avatar = avatar ?: matchAny(html,
            Regex("""\Wposter\W\s*:\s*\{(?:[^{}]*?)\Wavatar\W\s*:\s*"([^"]+)""", RegexOption.DOT_MATCHES_ALL)
        )?.toCleanUrl()
        authorId = authorId ?: matchAny(html,
            Regex("""\Wposter\W\s*:\s*\{(?:[^{}]*?)\Wstr_tiny_id\W\s*:\s*"([^"]+)""", RegexOption.DOT_MATCHES_ALL),
            Regex("""\Wposter\W\s*:\s*\{(?:[^{}]*?)\Wtiny_id\W\s*:\s*([0-9]+)""", RegexOption.DOT_MATCHES_ALL)
        )?.htmlUnescape()

        if (videoUrl == null) return failResponse("无法提取视频地址")

        val md = MediaData()
        md.inputUrl = input
        md.type = "video"
        md.title = title ?: "腾讯频道 分享"
        md.playUrl = videoUrl
        md.rawPlayUrl = videoUrl
        md.cover = coverUrl
        md.resolvedUrl = resp.finalUrl
        md.author = nickname ?: "未知作者"
        md.addQuality(label = "默认", ratio = "default", url = videoUrl)
        return md.toServerJson()
    }

    private fun matchAny(html: String, vararg regexes: Regex): String? {
        for (regex in regexes) {
            val m = regex.find(html) ?: continue
            val v = m.groupValues[1]
            if (v.isNotBlank()) return v
        }
        return null
    }

    private fun metaContent(html: String, property: String): String? {
        val m = Pattern.compile(
            """<meta[^>]*property=["']$property["'][^>]*content=["']([^"']+)["']""",
            Pattern.CASE_INSENSITIVE
        ).matcher(html)
        if (m.find()) return m.group(1)
        val m2 = Pattern.compile(
            """<meta[^>]*content=["']([^"']+)["'][^>]*property=["']$property["']""",
            Pattern.CASE_INSENSITIVE
        ).matcher(html)
        return if (m2.find()) m2.group(1) else null
    }

    private fun String.toCleanUrl(): String? {
        val cleaned = htmlUnescape().replace("\\/", "/").replace("&amp;", "&").trim()
        return if (cleaned.isBlank()) null else cleaned
    }

    private fun String.htmlUnescape(): String {
        return this.replace("&quot;", "\"").replace("&#34;", "\"")
            .replace("&#x27;", "'").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&nbsp;", " ").replace("&amp;", "&").trim()
    }

    private companion object {
        const val TAG = "TencentChannelParser"
    }
}